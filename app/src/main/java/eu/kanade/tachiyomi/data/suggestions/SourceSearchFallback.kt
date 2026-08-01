package eu.kanade.tachiyomi.data.suggestions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.util.system.logcat

// External databases only help when they know the work; searching the entry's own source is
// what keeps the row populated for niche or region-specific catalogues.
internal object SourceSearchFallback {

    private const val SCORE_THRESHOLD = 30
    private const val AUTHOR_BASE_SCORE = 40
    private const val GENRE_SCORE = 30
    private const val MAX_AUTHOR_RESULTS = 8
    private const val MAX_GENRE_RESULTS = 8

    // Sources are third-party sites that commonly rate limit: firing a whole tier at once
    // earns a 429 and loses the results entirely.
    private const val MAX_CONCURRENT_SEARCHES = 2

    private val garbageAuthors = setOf("null", "undefined", "unknown", "none", "no author", "n/a", "-")

    // A search result normalized away from SAnime/SManga, so both sides share the scoring.
    data class Candidate(
        val title: String,
        val url: String,
        val thumbnailUrl: String?,
    )

    suspend fun run(
        seed: SuggestionSeed,
        entryTitle: String,
        entryUrl: String,
        author: String?,
        artist: String?,
        genres: List<String>?,
        sourceId: Long,
        sourceName: String,
        maxResults: Int,
        search: suspend (query: String) -> List<Candidate>,
        onProgress: ((List<SuggestionItem>) -> Unit)? = null,
    ): List<SuggestionItem> {
        val boundedMaxResults = maxResults.coerceIn(1, 100)
        val cacheKey = SuggestionCache.makeKey(
            "search:$sourceId:limit:$boundedMaxResults",
            entryUrl,
            seed.mediaType.name,
            seed.candidateTitles,
        )
        SuggestionCache.get(cacheKey)?.let {
            logcat { "[SearchFallback] CACHE HIT for '$entryTitle' (${it.size} items)" }
            return it
        }

        val authorParts = splitCreators(author, artist)
        val genreParts = genres.orEmpty().take(3).map { it.trim() }.filter { it.length >= 2 }.distinct()
        val tiers = buildTiers(seed, authorParts, genreParts)

        logcat {
            "[SearchFallback] START for '$entryTitle' on '$sourceName' | " +
                "candidates=${seed.candidateTitles}, author=$authorParts, genres=$genreParts"
        }

        val lock = Mutex()
        val searchPermits = Semaphore(MAX_CONCURRENT_SEARCHES)
        val results = LinkedHashMap<String, SuggestionItem>()
        var authorAdded = 0
        var genreAdded = 0

        for (tier in tiers) {
            if (lock.withLock { results.size } >= boundedMaxResults) {
                logcat { "[SearchFallback] Reached $boundedMaxResults results, skipping remaining tiers" }
                break
            }
            if (tier.queries.isEmpty()) continue
            logcat { "[SearchFallback] ${tier.name}: ${tier.queries}" }

            coroutineScope {
                tier.queries.forEach { query ->
                    launch {
                        if (lock.withLock { results.size } >= boundedMaxResults) return@launch
                        val page = try {
                            searchPermits.withPermit { search(query) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat { "[SearchFallback] query '$query' failed: ${e.message}" }
                            return@launch
                        }
                        if (page.isEmpty()) return@launch

                        val scored = page.mapNotNull { candidate ->
                            scoreCandidate(candidate, seed, entryTitle, entryUrl, tier.reason)
                        }.sortedByDescending { it.second }

                        val snapshot = lock.withLock {
                            var added = false
                            for ((candidate, score) in scored) {
                                if (results.size >= boundedMaxResults) break
                                if (results.containsKey(candidate.url)) continue
                                when (tier.reason) {
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
                                results[candidate.url] = candidate.toItem(
                                    sourceId,
                                    sourceName,
                                    seed.mediaType,
                                    tier.reason,
                                    score,
                                )
                                added = true
                            }
                            if (added) results.values.toList() else null
                        }
                        if (snapshot != null) {
                            onProgress?.invoke(snapshot)
                        }
                    }
                }
            }
        }

        val items = lock.withLock { results.values.toList() }
        logcat { "[SearchFallback] END for '$entryTitle': ${items.size} items" }
        SuggestionCache.put(cacheKey, items)
        return items
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

    private fun scoreCandidate(
        candidate: Candidate,
        seed: SuggestionSeed,
        entryTitle: String,
        entryUrl: String,
        reason: SuggestionReason,
    ): Pair<Candidate, Int>? {
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

    private fun Candidate.toItem(
        sourceId: Long,
        sourceName: String,
        mediaType: SuggestionMediaType,
        reason: SuggestionReason,
        score: Int,
    ) = SuggestionItem(
        title = title,
        searchQueries = listOf(title),
        thumbnailUrl = thumbnailUrl,
        providerName = sourceName,
        providerUrl = url,
        providerId = "$sourceId:$url",
        mediaType = mediaType,
        reason = reason,
        relevance = score.coerceIn(0, 100),
    )

    private fun List<String>.sanitize(): List<String> = map { it.trim() }
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
}
