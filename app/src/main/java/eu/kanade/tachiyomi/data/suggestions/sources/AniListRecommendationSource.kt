package eu.kanade.tachiyomi.data.suggestions.sources

import eu.kanade.tachiyomi.data.suggestions.SuggestionCache
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionMediaType
import eu.kanade.tachiyomi.data.suggestions.SuggestionReason
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.data.suggestions.SuggestionTitleResolver
import eu.kanade.tachiyomi.data.suggestions.relevanceForRank
import eu.kanade.tachiyomi.data.suggestions.toAniListType
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AniListRecommendationSource(
    override val mediaType: SuggestionMediaType,
) : RecommendationSource() {

    override val name: String = "AniList"

    private val client by lazy { Injekt.get<NetworkHelper>().client }
    private val json by lazy { Injekt.get<Json>() }
    private val aniListType = mediaType.toAniListType()

    // AniList files light novels under type MANGA with format NOVEL, and the manga side only
    // wants comics.
    private fun isValidMedia(type: String?, format: String?): Boolean = when (mediaType) {
        SuggestionMediaType.ANIME -> type?.uppercase() == "ANIME"
        SuggestionMediaType.MANGA -> type?.uppercase() == "MANGA" && format?.uppercase() != "NOVEL"
    }

    override suspend fun fetchSuggestions(seed: SuggestionSeed): List<SuggestionItem> = coroutineScope {
        val cacheKey = SuggestionCache.makeKey(
            name,
            seed.primaryTitle,
            mediaType.name,
            seed.candidateTitles,
            seed.description,
            seed.author,
        )
        SuggestionCache.get(cacheKey)?.let {
            logcat { "[AniList] CACHE HIT for '${seed.primaryTitle}' (${it.size} items)" }
            matchedBase = true
            return@coroutineScope it
        }
        logcat { "[AniList] START for '${seed.primaryTitle}', candidates=${seed.candidateTitles}" }

        // Each candidate is a separate rate-limited request; three covers the raw title
        // plus its most relevant aliases without burning the public API quota.
        val jobs = seed.candidateTitles.take(MAX_CANDIDATES).map { candidate ->
            async { searchMedia(candidate) }
        }
        val allResults = jobs.awaitAll().flatten()
            .distinctBy { it["id"]?.jsonPrimitive?.contentOrNull }

        val bestBaseMedia = allResults
            .mapNotNull { media ->
                val type = media["type"]?.jsonPrimitive?.contentOrNull
                val format = media["format"]?.jsonPrimitive?.contentOrNull
                if (!isValidMedia(type, format)) return@mapNotNull null

                val score = media.allTitles().maxOfOrNull { mediaTitle ->
                    seed.candidateTitles.maxOfOrNull { SuggestionTitleResolver.scoreMatch(mediaTitle, it) } ?: 0
                } ?: 0
                if (score <= 0) null else media to score
            }
            .maxByOrNull { it.second }

        if (bestBaseMedia == null) {
            logcat { "[AniList] No base media matched for '${seed.primaryTitle}' among ${allResults.size} results" }
            return@coroutineScope emptyList()
        }
        matchedBase = true

        // Edges routinely point back at the parent series, and the caller can only filter
        // those against the title its own source used — only AniList knows every alias, so
        // the filtering has to happen here.
        val baseTitles = bestBaseMedia.first.allTitles()

        val edges = bestBaseMedia.first["recommendations"]?.jsonObject
            ?.get("edges")?.jsonArray
            .orEmpty()
            // AniList returns the edges unsorted; its `rating` is the community vote
            // count for "these two go together", which is exactly the ordering we want.
            .sortedByDescending { it.jsonObject["node"]?.jsonObject?.get("rating")?.jsonPrimitive?.intOrNull ?: 0 }

        val suggestions = edges
            .mapIndexedNotNull { index, edge ->
                val rec = edge.jsonObject["node"]?.jsonObject
                    ?.get("mediaRecommendation")
                    ?.takeIf { it is JsonObject }
                    ?.jsonObject
                    ?: return@mapIndexedNotNull null

                val recType = rec["type"]?.jsonPrimitive?.contentOrNull
                val recFormat = rec["format"]?.jsonPrimitive?.contentOrNull
                if (!isValidMedia(recType, recFormat)) return@mapIndexedNotNull null

                val siteUrl = rec["siteUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
                val titleObj = rec["title"]?.jsonObject
                val recTitle = titleObj?.get("english")?.jsonPrimitive?.contentOrNull
                    ?: titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull
                    ?: titleObj?.get("native")?.jsonPrimitive?.contentOrNull
                    ?: return@mapIndexedNotNull null

                if (baseTitles.any { SuggestionTitleResolver.isFranchiseDuplicate(recTitle, it) }) {
                    return@mapIndexedNotNull null
                }

                SuggestionItem(
                    title = recTitle,
                    searchQueries = listOfNotNull(
                        recTitle,
                        titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull,
                    ).distinct(),
                    thumbnailUrl = rec["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull,
                    providerName = name,
                    providerUrl = siteUrl,
                    providerId = rec["id"]?.jsonPrimitive?.contentOrNull,
                    mediaType = mediaType,
                    reason = SuggestionReason.EXTERNAL_ANILIST,
                    relevance = relevanceForRank(index, edges.size),
                )
            }

        logcat { "[AniList] END for '${seed.primaryTitle}': ${suggestions.size} suggestions" }
        if (suggestions.isNotEmpty()) {
            SuggestionCache.put(cacheKey, suggestions)
        }
        suggestions
    }

    private suspend fun searchMedia(candidate: String): List<JsonObject> {
        return try {
            val payload = buildJsonObject {
                put("query", RECOMMENDATIONS_QUERY)
                put(
                    "variables",
                    buildJsonObject {
                        put("search", candidate)
                        put("type", aniListType)
                    },
                )
            }
            val body = payload.toString().toRequestBody(jsonMime)
            val data = with(json) {
                client.newCall(POST(API_URL, body = body))
                    .awaitSuccess()
                    .parseAs<JsonObject>()
            }

            data["data"]?.jsonObject
                ?.get("Page")?.jsonObject
                ?.get("media")?.jsonArray
                ?.map { it.jsonObject }
                .orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat { "[AniList] query failed for candidate '$candidate': ${e.message}" }
            emptyList()
        }
    }

    private fun JsonObject.allTitles(): List<String> {
        val titleObj = this["title"]?.jsonObject
        val primary = listOfNotNull(
            titleObj?.get("romaji")?.jsonPrimitive?.contentOrNull,
            titleObj?.get("english")?.jsonPrimitive?.contentOrNull,
            titleObj?.get("native")?.jsonPrimitive?.contentOrNull,
        )
        val synonyms = this["synonyms"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return primary + synonyms
    }

    private companion object {
        const val API_URL = "https://graphql.anilist.co/"
        const val MAX_CANDIDATES = 3

        val RECOMMENDATIONS_QUERY = """
            query Recommendations(${'$'}search: String!, ${'$'}type: MediaType!) {
                Page {
                    media(search: ${'$'}search, type: ${'$'}type) {
                        id
                        type
                        format
                        title { romaji english native }
                        synonyms
                        recommendations {
                            edges {
                                node {
                                    rating
                                    mediaRecommendation {
                                        id
                                        type
                                        format
                                        siteUrl
                                        title { romaji english native }
                                        coverImage { large }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
