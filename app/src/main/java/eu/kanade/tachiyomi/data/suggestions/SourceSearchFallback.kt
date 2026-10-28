package eu.kanade.tachiyomi.data.suggestions

import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.util.system.logcat

// External databases only help when they know the work; searching the entry's own source is
// what keeps the row populated for niche or region-specific catalogues.
internal class SourceSearchFallback(
    private val seed: SuggestionSeed,
    private val entryTitle: String,
    private val entryUrl: String,
    author: String?,
    artist: String?,
    genres: List<String>?,
    private val sourceId: Long,
    private val sourceName: String,
    private val maxResults: Int,
    private val totalLimit: Int = maxResults,
    private val search: suspend (query: String, page: Int) -> SearchPage,
) {

    // A search result normalized away from SAnime/SManga, so both sides share the scoring.
    data class Candidate(
        val title: String,
        val url: String,
        val thumbnailUrl: String?,
    )

    data class SearchPage(
        val candidates: List<Candidate>,
        val hasNextPage: Boolean,
    )

    private data class Cursor(
        val query: String,
        val reason: SuggestionReason,
        var nextPage: Int,
        var hasNextPage: Boolean,
    )

    internal data class CursorState(
        val query: String,
        val reason: SuggestionReason,
        val nextPage: Int,
        val hasNextPage: Boolean,
    )

    internal data class CachedSearch(val items: List<SuggestionItem>, val cursors: List<CursorState>)

    private val boundedMaxResults = maxResults.coerceIn(1, 100)
    private val boundedTotalLimit = totalLimit.coerceIn(boundedMaxResults, ABSOLUTE_LIMIT)
    private val tiers = buildTiers(seed, splitCreators(author, artist), cleanGenres(genres))
    private val cacheKey = SuggestionCache.makeKey(
        "search:$sourceId:limit:$boundedMaxResults",
        entryUrl,
        seed.mediaType.name,
        seed.candidateTitles + tiers.flatMap { tier -> tier.queries.map { "${tier.reason}:$it" } },
        description = seed.description,
        author = listOfNotNull(author, artist).joinToString("|"),
    )

    private val lock = Mutex()
    private val searchPermits = Semaphore(MAX_CONCURRENT_SEARCHES)
    private val results = LinkedHashMap<String, SuggestionItem>()
    private val cursors = tiers.flatMap { tier ->
        tier.queries.map { Cursor(it, tier.reason, nextPage = 1, hasNextPage = true) }
    }.toMutableList()

    private var authorAdded = 0
    private var genreAdded = 0

    @Volatile
    private var sourcePaused = false

    // Each pass gets its own quota so paginating doesn't stay stuck behind the caps the first
    // pass already spent.
    private fun resetPassQuotas() {
        authorAdded = 0
        genreAdded = 0
    }

    @Volatile
    var hasNextPage: Boolean = false
        private set

    private fun refreshHasNextPage() {
        hasNextPage = !sourcePaused && results.size < boundedTotalLimit && cursors.any { it.hasNextPage }
    }

    suspend fun loadInitial(onProgress: ((List<SuggestionItem>) -> Unit)? = null): List<SuggestionItem> {
        if (sourcePaused) return lock.withLock { results.values.toList() }
        SuggestionCache.getSourceSearch(cacheKey)?.let { cached ->
            logcat { "[SearchFallback] CACHE HIT for '$entryTitle' (${cached.items.size} items)" }
            lock.withLock {
                cached.items.forEach { results.putIfAbsent(it.providerUrl, it) }
                cursors.clear()
                cached.cursors.mapTo(cursors) { Cursor(it.query, it.reason, it.nextPage, it.hasNextPage) }
                refreshHasNextPage()
            }
            return cached.items
        }

        logcat {
            "[SearchFallback] START for '$entryTitle' on '$sourceName' | " +
                "candidates=${seed.candidateTitles}, tiers=${tiers.map { it.queries.size }}"
        }
        resetPassQuotas()
        var hadFailure = false

        for (tier in tiers) {
            if (sourcePaused) break
            if (lock.withLock { results.size } >= boundedMaxResults) {
                logcat { "[SearchFallback] Reached $boundedMaxResults results, skipping remaining tiers" }
                break
            }
            if (tier.queries.isEmpty()) continue
            logcat { "[SearchFallback] ${tier.name}: ${tier.queries}" }

            coroutineScope {
                cursors.filter { it.reason == tier.reason && it.query in tier.queries }.map { cursor ->
                    async {
                        searchPermits.withPermit {
                            if (sourcePaused || lock.withLock { results.size } >= boundedMaxResults) return@withPermit
                            val page = fetchPage(cursor.query, cursor.nextPage)
                            if (page == null) {
                                lock.withLock { hadFailure = true }
                                return@withPermit
                            }
                            lock.withLock { advanceCursor(cursor, page) }
                            if (page.candidates.isEmpty()) return@withPermit

                            val snapshot = absorb(page.candidates, tier.reason, boundedMaxResults)
                            if (snapshot != null) onProgress?.invoke(snapshot)
                        }
                    }
                }.awaitAll()
            }
        }

        val items = lock.withLock {
            refreshHasNextPage()
            results.values.toList()
        }
        logcat { "[SearchFallback] END for '$entryTitle': ${items.size} items" }
        if (!hadFailure) {
            val snapshot = lock.withLock {
                CachedSearch(
                    items,
                    cursors.map { CursorState(it.query, it.reason, it.nextPage, it.hasNextPage) },
                )
            }
            SuggestionCache.putSourceSearch(cacheKey, snapshot)
        }
        return items
    }

    // Walks the queries that reported a further page, most relevant tier first, and returns the
    // accumulated list so callers can re-rank the whole set. Title queries usually run dry on
    // page 2, so a pass that adds nothing retries with the next batch instead of handing the
    // user an unchanged grid.
    suspend fun loadNextPage(): List<SuggestionItem> {
        var attempts = 0
        while (attempts < MAX_PAGE_ATTEMPTS) {
            attempts++
            if (!fetchNextBatch()) break
        }

        val items = lock.withLock {
            refreshHasNextPage()
            results.values.toList()
        }
        logcat { "[SearchFallback] PAGE END for '$entryTitle': ${items.size} items total" }
        return items
    }

    // Returns whether another attempt is worth making: false once something was added, the
    // total limit is reached, or no cursor has a further page.
    private suspend fun fetchNextBatch(): Boolean {
        val batch = lock.withLock {
            if (sourcePaused || results.size >= boundedTotalLimit) return false
            cursors.filter { it.hasNextPage }.take(MAX_PAGINATED_QUERIES)
        }
        if (batch.isEmpty()) return false

        resetPassQuotas()
        logcat { "[SearchFallback] PAGE for '$entryTitle': ${batch.map { "${it.query}@${it.nextPage}" }}" }
        val before = lock.withLock { results.size }
        val failedCursors = mutableListOf<Cursor>()

        coroutineScope {
            batch.map { cursor ->
                async {
                    searchPermits.withPermit {
                        if (sourcePaused || lock.withLock { results.size } >= boundedTotalLimit) return@withPermit
                        val page = fetchPage(cursor.query, cursor.nextPage)
                        if (page == null) {
                            lock.withLock { failedCursors += cursor }
                            return@withPermit
                        }
                        lock.withLock { advanceCursor(cursor, page) }
                        if (page.candidates.isEmpty()) return@withPermit
                        absorb(page.candidates, cursor.reason, boundedTotalLimit)
                    }
                }
            }.awaitAll()
        }

        return lock.withLock {
            val failed = batch.filter { it in failedCursors }
            cursors.removeAll(failed.toSet())
            cursors.addAll(failed)
            !sourcePaused && failed.isEmpty() && results.size == before && cursors.any { it.hasNextPage }
        }
    }

    private suspend fun fetchPage(query: String, page: Int): SearchPage? = try {
        search(query, page)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val paused = generateSequence<Throwable>(e) { it.cause }.take(16).any {
            it is SourceRequestPausedException ||
                it is CloudflareChallengeException ||
                it is HttpException &&
                it.code in setOf(403, 429)
        }
        if (paused) {
            sourcePaused = true
            hasNextPage = false
        }
        logcat { "[SearchFallback] query '$query' page $page failed: ${e.message}" }
        null
    }

    private fun advanceCursor(cursor: Cursor, page: SearchPage) {
        cursor.nextPage += 1
        cursor.hasNextPage = page.hasNextPage && page.candidates.isNotEmpty()
    }

    // Returns the accumulated list when anything was added, null otherwise.
    private suspend fun absorb(
        candidates: List<Candidate>,
        reason: SuggestionReason,
        limit: Int,
    ): List<SuggestionItem>? {
        val scored = candidates
            .mapNotNull { scoreCandidate(it, reason) }
            .sortedByDescending { it.second }

        return lock.withLock {
            var added = false
            for ((candidate, score) in scored) {
                if (results.size >= limit) break
                if (results.containsKey(candidate.url)) continue
                when (reason) {
                    SuggestionReason.SEARCH_AUTHOR -> {
                        if (authorAdded >= MAX_AUTHOR_RESULTS) continue
                        authorAdded++
                    }
                    SuggestionReason.SEARCH_GENRE -> {
                        if (genreAdded >= MAX_GENRE_RESULTS) continue
                        genreAdded++
                    }
                    else -> Unit
                }
                results[candidate.url] = candidate.toItem(reason, score)
                added = true
            }
            if (added) results.values.toList() else null
        }
    }

    private data class Tier(val name: String, val reason: SuggestionReason, val queries: List<String>)

    private fun buildTiers(
        seed: SuggestionSeed,
        authorParts: List<String>,
        genreParts: List<String>,
    ): List<Tier> {
        val exactQueries = buildList {
            add(seed.primaryTitle)
            SuggestionTitleResolver.parseOriginalTitle(seed.description)?.let { add(it) }
            addAll(seed.candidateTitles)
        }.sanitize()

        val relaxedQueries = buildList {
            // Sources with primitive search engines choke on long decorated titles, so
            // progressively shorter forms of the same title are tried.
            listOf(":", "-", "(", "[", ",", ";").forEach { separator ->
                val part = seed.primaryTitle.substringBefore(separator).trim()
                if (part != seed.primaryTitle && part.length >= 3) add(part)
            }

            val cleaned = SuggestionTitleResolver.cleanTitle(seed.primaryTitle)
            if (cleaned != seed.primaryTitle && cleaned.length >= 3) add(cleaned)

            val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size > 4) {
                add(words.take(4).joinToString(" "))
                add(words.take(3).joinToString(" "))
                add(words.take(5).joinToString(" "))
            }
        }.sanitize() - exactQueries.toSet()

        return listOf(
            Tier("Tier 1 (exact title)", SuggestionReason.SEARCH_TITLE, exactQueries),
            Tier("Tier 2 (relaxed title)", SuggestionReason.SEARCH_TITLE, relaxedQueries),
            Tier("Tier 3 (author)", SuggestionReason.SEARCH_AUTHOR, authorParts),
            Tier("Tier 4 (genre)", SuggestionReason.SEARCH_GENRE, genreParts),
        )
    }

    private fun scoreCandidate(candidate: Candidate, reason: SuggestionReason): Pair<Candidate, Int>? {
        if (candidate.url == entryUrl) return null
        if (SuggestionTitleResolver.isFranchiseDuplicate(candidate.title, entryTitle)) return null

        val bestScore = seed.candidateTitles.maxOfOrNull {
            SuggestionTitleResolver.scoreMatch(it, candidate.title)
        } ?: 0

        val finalScore = when {
            bestScore >= SCORE_THRESHOLD -> bestScore
            // A weak title match is still meaningful when the query was the author or a
            // genre: the result is related by creator/theme, not by name.
            reason == SuggestionReason.SEARCH_AUTHOR -> AUTHOR_BASE_SCORE + minOf(bestScore / 10, 10)
            reason == SuggestionReason.SEARCH_GENRE -> GENRE_SCORE
            else -> 0
        }

        return if (finalScore >= SCORE_THRESHOLD) candidate to finalScore else null
    }

    private fun Candidate.toItem(reason: SuggestionReason, score: Int) = SuggestionItem(
        title = title,
        searchQueries = listOf(title),
        thumbnailUrl = thumbnailUrl,
        providerName = sourceName,
        providerUrl = url,
        providerId = "$sourceId:$url",
        mediaType = seed.mediaType,
        reason = reason,
        relevance = score.coerceIn(0, 100),
    )

    private fun List<String>.sanitize(): List<String> = map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()

    private fun cleanGenres(genres: List<String>?): List<String> = genres.orEmpty()
        .take(3)
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()

    private fun splitCreators(author: String?, artist: String?): List<String> = buildList {
        listOfNotNull(author, artist.takeIf { it != author })
            .filter { it.isNotBlank() }
            .forEach { field ->
                addAll(
                    field.split(Regex("[,;/&]"))
                        .map { it.trim() }
                        .filter { it.length >= 2 && it.lowercase() !in garbageAuthors },
                )
            }
    }.distinct()

    private companion object {
        const val SCORE_THRESHOLD = 30
        const val AUTHOR_BASE_SCORE = 40
        const val GENRE_SCORE = 30
        const val MAX_AUTHOR_RESULTS = 8
        const val MAX_GENRE_RESULTS = 8
        const val ABSOLUTE_LIMIT = 300

        // Sources are third-party sites that commonly rate limit: firing a whole tier at once
        // earns a 429 and loses the results entirely.
        const val MAX_CONCURRENT_SEARCHES = 2

        // Paging every query that ever matched would fan out far wider than the first pass did.
        const val MAX_PAGINATED_QUERIES = 3
        const val MAX_PAGE_ATTEMPTS = 3

        val garbageAuthors = setOf("null", "undefined", "unknown", "none", "no author", "n/a", "-")
    }
}
