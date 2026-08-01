package eu.kanade.tachiyomi.data.suggestions.sources

import eu.kanade.tachiyomi.data.suggestions.SuggestionCache
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionMediaType
import eu.kanade.tachiyomi.data.suggestions.SuggestionReason
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.data.suggestions.SuggestionTitleResolver
import eu.kanade.tachiyomi.data.suggestions.relevanceForRank
import eu.kanade.tachiyomi.data.suggestions.sources.dto.MuSearchResponse
import eu.kanade.tachiyomi.data.suggestions.sources.dto.MuSearchResult
import eu.kanade.tachiyomi.data.suggestions.sources.dto.MuSeriesDetail
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaUpdatesSimilarSource(
    override val mediaType: SuggestionMediaType,
) : RecommendationSource() {

    override val name: String = "MangaUpdates"

    private val client by lazy { Injekt.get<NetworkHelper>().client }
    private val json by lazy { Injekt.get<Json>() }

    private val allowedTypes = setOf("Manga", "Manhwa", "Manhua", "Comic", "Webtoon")

    override suspend fun fetchSuggestions(seed: SuggestionSeed): List<SuggestionItem> = coroutineScope {
        if (mediaType != SuggestionMediaType.MANGA) return@coroutineScope emptyList()

        val cacheKey = SuggestionCache.makeKey(
            name,
            seed.primaryTitle,
            mediaType.name,
            seed.candidateTitles,
            seed.description,
            seed.author,
        )
        SuggestionCache.get(cacheKey)?.let {
            logcat { "[MangaUpdates] CACHE HIT for '${seed.primaryTitle}' (${it.size} items)" }
            matchedBase = true
            return@coroutineScope it
        }
        logcat { "[MangaUpdates] START for '${seed.primaryTitle}', candidates=${seed.candidateTitles}" }

        val suggestions = try {
            val searchResults = mutableListOf<MuSearchResult>()
            for (candidate in seed.candidateTitles.take(MAX_CANDIDATES)) {
                searchResults += search(candidate)
            }

            val bestMatch = searchResults
                .distinctBy { it.record.seriesId }
                .mapNotNull { result ->
                    val type = result.record.type
                    if (type != null && type !in allowedTypes) return@mapNotNull null

                    val score = listOfNotNull(result.record.title, result.hitTitle).maxOfOrNull { muTitle ->
                        seed.candidateTitles.maxOfOrNull { SuggestionTitleResolver.scoreMatch(muTitle, it) } ?: 0
                    } ?: 0
                    if (score <= 0) null else result to score
                }
                .maxByOrNull { it.second }
                ?.first

            if (bestMatch == null) {
                logcat { "[MangaUpdates] No base series matched for '${seed.primaryTitle}'" }
                return@coroutineScope emptyList()
            }
            matchedBase = true
            logcat { "[MangaUpdates] Base series: '${bestMatch.record.title}' (id=${bestMatch.record.seriesId})" }

            val detail = fetchSeries(bestMatch.record.seriesId) ?: return@coroutineScope emptyList()

            val mergedRecs = (
                detail.recommendations.sortedByDescending { it.weight } +
                    detail.categoryRecommendations.sortedByDescending { it.weight }
                )
                .distinctBy { it.seriesId }
                .take(MAX_RECOMMENDATIONS)

            // MangaUpdates only exposes the type on the series endpoint, so each
            // recommendation needs its own lookup to filter out novels.
            mergedRecs
                .mapIndexed { index, rec ->
                    async {
                        val recType = fetchSeries(rec.seriesId)?.type
                        if (recType == null || recType !in allowedTypes) {
                            null
                        } else {
                            SuggestionItem(
                                title = rec.seriesName,
                                searchQueries = listOf(rec.seriesName),
                                thumbnailUrl = rec.seriesImage?.url?.thumb ?: rec.seriesImage?.url?.original,
                                providerName = name,
                                providerUrl = rec.seriesUrl,
                                providerId = rec.seriesId.toString(),
                                mediaType = mediaType,
                                reason = SuggestionReason.EXTERNAL_MU,
                                relevance = relevanceForRank(index, mergedRecs.size),
                            )
                        }
                    }
                }
                .mapNotNull { it.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat { "[MangaUpdates] ERROR for '${seed.primaryTitle}': ${e.message}" }
            emptyList()
        }

        logcat { "[MangaUpdates] END for '${seed.primaryTitle}': ${suggestions.size} suggestions" }
        if (suggestions.isNotEmpty()) {
            SuggestionCache.put(cacheKey, suggestions)
        }
        suggestions
    }

    private suspend fun search(candidate: String): List<MuSearchResult> {
        return try {
            val payload = buildJsonObject {
                put("search", candidate)
                put("page", 1)
                put("perpage", SEARCH_LIMIT)
            }
            val body = payload.toString().toRequestBody(jsonMime)
            with(json) {
                client.newCall(POST("$API_URL/series/search", body = body))
                    .awaitSuccess()
                    .parseAs<MuSearchResponse>()
            }.results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat { "[MangaUpdates] search failed for candidate '$candidate': ${e.message}" }
            emptyList()
        }
    }

    private suspend fun fetchSeries(seriesId: Long): MuSeriesDetail? {
        return try {
            with(json) {
                client.newCall(GET("$API_URL/series/$seriesId"))
                    .awaitSuccess()
                    .parseAs<MuSeriesDetail>()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat { "[MangaUpdates] series fetch failed for id=$seriesId: ${e.message}" }
            null
        }
    }

    private companion object {
        const val API_URL = "https://api.mangaupdates.com/v1"
        const val MAX_CANDIDATES = 3
        const val SEARCH_LIMIT = 5
        const val MAX_RECOMMENDATIONS = 8
    }
}
