package eu.kanade.tachiyomi.data.suggestions.anime

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.suggestions.SUGGESTION_SEARCH_LIMIT
import eu.kanade.tachiyomi.data.suggestions.SourceSearchFallback
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import tachiyomi.domain.entries.anime.model.Anime

class AnimeSearchFallbackEngine {

    internal fun createPager(
        anime: Anime,
        source: AnimeCatalogueSource,
        seed: SuggestionSeed,
        maxResults: Int = SUGGESTION_SEARCH_LIMIT,
        totalLimit: Int = maxResults,
    ): SourceSearchFallback {
        val filterList = source.getFilterList()
        return SourceSearchFallback(
            seed = seed,
            entryTitle = anime.title,
            entryUrl = anime.url,
            author = anime.author,
            artist = anime.artist,
            genres = anime.genre,
            sourceId = source.id,
            sourceName = source.name,
            maxResults = maxResults,
            totalLimit = totalLimit,
            search = { query, page ->
                val result = source.getSearchAnime(page, query, filterList)
                SourceSearchFallback.SearchPage(
                    candidates = result.animes.map {
                        SourceSearchFallback.Candidate(
                            title = it.title,
                            url = it.url,
                            thumbnailUrl = it.thumbnail_url?.takeIf(String::isNotBlank),
                        )
                    },
                    hasNextPage = result.hasNextPage,
                )
            },
        )
    }

    suspend fun fetchSearchFallback(
        anime: Anime,
        source: AnimeCatalogueSource,
        seed: SuggestionSeed,
        maxResults: Int = SUGGESTION_SEARCH_LIMIT,
        onProgress: ((List<SuggestionItem>) -> Unit)? = null,
    ): List<SuggestionItem> = createPager(anime, source, seed, maxResults).loadInitial(onProgress)
}
