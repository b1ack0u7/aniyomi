package eu.kanade.tachiyomi.data.suggestions

import eu.kanade.tachiyomi.data.suggestions.sources.AniListRecommendationSource
import eu.kanade.tachiyomi.data.suggestions.sources.MangaUpdatesSimilarSource
import eu.kanade.tachiyomi.data.suggestions.sources.MyAnimeListRecommendationSource
import eu.kanade.tachiyomi.data.suggestions.sources.RecommendationSource
import eu.kanade.tachiyomi.data.suggestions.util.dedupeByFranchise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.util.system.logcat

class SuggestionCoordinator {

    fun createSources(mediaType: SuggestionMediaType): List<RecommendationSource> = buildList {
        add(AniListRecommendationSource(mediaType))
        add(MyAnimeListRecommendationSource(mediaType))
        if (mediaType == SuggestionMediaType.MANGA) {
            add(MangaUpdatesSimilarSource(mediaType))
        }
    }

    suspend fun fetchSuggestions(
        seed: SuggestionSeed,
        limit: Int = DEFAULT_LIMIT,
    ): SuggestionFetchResult = supervisorScope {
        val boundedLimit = limit.coerceIn(1, MAX_LIMIT)
        val sources = createSources(seed.mediaType)
        if (sources.isEmpty()) {
            logcat { "[Coordinator] No sources for mediaType=${seed.mediaType}" }
            return@supervisorScope SuggestionFetchResult(emptyList(), 0, 0)
        }

        logcat {
            "[Coordinator] Fetching '${seed.primaryTitle}' (${seed.mediaType}) via " +
                "${sources.map { it.name }} | candidates=${seed.candidateTitles}"
        }

        val jobs = sources.map { source ->
            async(Dispatchers.IO) {
                try {
                    val result = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { source.fetchSuggestions(seed) }
                    if (result == null) {
                        logcat { "[Coordinator] ${source.name} TIMEOUT" }
                        emptyList<SuggestionItem>() to true
                    } else {
                        result to false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat { "[Coordinator] ${source.name} FAILED: ${e.message}" }
                    emptyList<SuggestionItem>() to true
                }
            }
        }

        val results = jobs.map { it.await() }
        val failedSources = results.count { it.second }
        val items = results.flatMap { it.first }
            .dedupeByFranchise()
            .sortedByDescending { SuggestionSourceWeight.finalScore(it) }
            .take(boundedLimit)

        val matchedBase = sources.any { it.matchedBase }

        logcat {
            "[Coordinator] Done '${seed.primaryTitle}': ${items.size} items, " +
                "attempted=${sources.size}, failed=$failedSources, matchedBase=$matchedBase"
        }

        SuggestionFetchResult(
            items = items,
            attemptedSources = sources.size,
            failedSources = failedSources,
            matchedBase = matchedBase,
        )
    }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 10_000L
        const val DEFAULT_LIMIT = 40
        const val MAX_LIMIT = 100
    }
}
