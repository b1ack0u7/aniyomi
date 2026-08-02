package eu.kanade.tachiyomi.data.suggestions.manga

import eu.kanade.tachiyomi.data.suggestions.SUGGESTION_SEARCH_LIMIT
import eu.kanade.tachiyomi.data.suggestions.SourceSearchFallback
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.source.CatalogueSource
import tachiyomi.domain.entries.manga.model.Manga

class MangaSearchFallbackEngine {

    internal fun createPager(
        manga: Manga,
        source: CatalogueSource,
        seed: SuggestionSeed,
        maxResults: Int = SUGGESTION_SEARCH_LIMIT,
        totalLimit: Int = maxResults,
    ): SourceSearchFallback {
        val filterList = source.getFilterList()
        return SourceSearchFallback(
            seed = seed,
            entryTitle = manga.title,
            entryUrl = manga.url,
            author = manga.author,
            artist = manga.artist,
            genres = manga.genre,
            sourceId = source.id,
            sourceName = source.name,
            maxResults = maxResults,
            totalLimit = totalLimit,
            search = { query, page ->
                val result = source.getSearchManga(page, query, filterList)
                SourceSearchFallback.SearchPage(
                    candidates = result.mangas.map {
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
        manga: Manga,
        source: CatalogueSource,
        seed: SuggestionSeed,
        maxResults: Int = SUGGESTION_SEARCH_LIMIT,
        onProgress: ((List<SuggestionItem>) -> Unit)? = null,
    ): List<SuggestionItem> = createPager(manga, source, seed, maxResults).loadInitial(onProgress)
}
