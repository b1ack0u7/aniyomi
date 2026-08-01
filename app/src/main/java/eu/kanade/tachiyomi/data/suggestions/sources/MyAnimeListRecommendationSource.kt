package eu.kanade.tachiyomi.data.suggestions.sources

import eu.kanade.tachiyomi.data.suggestions.SuggestionCache
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionMediaType
import eu.kanade.tachiyomi.data.suggestions.SuggestionReason
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.data.suggestions.SuggestionTitleResolver
import eu.kanade.tachiyomi.data.suggestions.relevanceForRank
import eu.kanade.tachiyomi.data.suggestions.sources.dto.JikanRecommendationResponse
import eu.kanade.tachiyomi.data.suggestions.sources.dto.JikanSearchEntry
import eu.kanade.tachiyomi.data.suggestions.sources.dto.JikanSearchResponse
import eu.kanade.tachiyomi.data.suggestions.toJikanPath
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

// Jikan is rate limited to roughly three requests per second, so calls are serialized with a
// delay between them instead of fanned out.
class MyAnimeListRecommendationSource(
    override val mediaType: SuggestionMediaType,
) : RecommendationSource() {

    override val name: String = "MyAnimeList"

    private val client by lazy { Injekt.get<NetworkHelper>().client }
    private val json by lazy { Injekt.get<Json>() }
    private val jikanPath = mediaType.toJikanPath()

    override suspend fun fetchSuggestions(seed: SuggestionSeed): List<SuggestionItem> {
        val cacheKey = SuggestionCache.makeKey(name, seed.primaryTitle, mediaType.name, seed.candidateTitles)
        SuggestionCache.get(cacheKey)?.let {
            logcat { "[MAL] CACHE HIT for '${seed.primaryTitle}' (${it.size} items)" }
            matchedBase = true
            return it
        }
        logcat { "[MAL] START for '${seed.primaryTitle}', candidates=${seed.candidateTitles}" }

        val suggestions = try {
            val searchEntries = mutableListOf<JikanSearchEntry>()
            for ((index, candidate) in seed.candidateTitles.take(MAX_CANDIDATES).withIndex()) {
                if (index > 0) delay(RATE_LIMIT_DELAY_MS)
                searchEntries += search(candidate)
            }

            val bestMatch = searchEntries
                .distinctBy { it.malId }
                .mapNotNull { entry ->
                    val score = seed.candidateTitles.maxOfOrNull {
                        SuggestionTitleResolver.scoreMatch(entry.title, it)
                    } ?: 0
                    if (score <= 0) null else entry to score
                }
                .maxByOrNull { it.second }
                ?.first

            if (bestMatch == null) {
                logcat { "[MAL] No base entry matched for '${seed.primaryTitle}'" }
                return emptyList()
            }
            matchedBase = true
            logcat { "[MAL] Base entry: '${bestMatch.title}' (id=${bestMatch.malId})" }

            delay(RATE_LIMIT_DELAY_MS)
            val recUrl = "$API_URL/$jikanPath/${bestMatch.malId}/recommendations"
            val recResponse = with(json) {
                client.newCall(GET(recUrl)).awaitSuccess().parseAs<JikanRecommendationResponse>()
            }

            val ranked = recResponse.data
                // Recommendation lists routinely include the parent series of the entry.
                .filterNot { SuggestionTitleResolver.isFranchiseDuplicate(it.entry.title, bestMatch.title) }
                .sortedByDescending { it.votes }
            ranked.mapIndexed { index, item ->
                SuggestionItem(
                    title = item.entry.title,
                    searchQueries = listOf(item.entry.title),
                    thumbnailUrl = item.entry.images?.jpg?.imageUrl,
                    providerName = name,
                    providerUrl = item.entry.url,
                    providerId = item.entry.malId.toString(),
                    mediaType = mediaType,
                    reason = SuggestionReason.EXTERNAL_MAL,
                    relevance = relevanceForRank(index, ranked.size),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat { "[MAL] ERROR for '${seed.primaryTitle}': ${e.message}" }
            emptyList()
        }

        logcat { "[MAL] END for '${seed.primaryTitle}': ${suggestions.size} suggestions" }
        if (suggestions.isNotEmpty()) {
            SuggestionCache.put(cacheKey, suggestions)
        }
        return suggestions
    }

    private suspend fun search(candidate: String): List<JikanSearchEntry> {
        return try {
            val encoded = URLEncoder.encode(candidate, "UTF-8")
            val searchUrl = "$API_URL/$jikanPath?q=$encoded&limit=$SEARCH_LIMIT"
            with(json) {
                client.newCall(GET(searchUrl)).awaitSuccess().parseAs<JikanSearchResponse>()
            }.data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat { "[MAL] search failed for candidate '$candidate': ${e.message}" }
            emptyList()
        }
    }

    private companion object {
        const val API_URL = "https://api.jikan.moe/v4"
        const val MAX_CANDIDATES = 3
        const val SEARCH_LIMIT = 5
        const val RATE_LIMIT_DELAY_MS = 350L
    }
}
