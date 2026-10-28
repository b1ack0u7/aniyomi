package eu.kanade.tachiyomi.data.suggestions.manga

import eu.kanade.tachiyomi.data.library.LibraryUpdateType
import eu.kanade.tachiyomi.data.suggestions.SUGGESTION_SEARCH_LIMIT
import eu.kanade.tachiyomi.data.suggestions.SourceSearchFallback
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.network.SourceRequestCoordinator
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.source.local.entries.manga.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaSearchFallbackEngine(
    private val sourceRequests: SourceRequestCoordinator = Injekt.get(),
) {

    internal fun createPager(
        manga: Manga,
        source: CatalogueSource,
        seed: SuggestionSeed,
        maxResults: Int = SUGGESTION_SEARCH_LIMIT,
        totalLimit: Int = maxResults,
    ): SourceSearchFallback {
        var filterList: FilterList? = null
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
                val fetch = suspend {
                    val filters = filterList ?: source.getFilterList().also { filterList = it }
                    source.getSearchManga(page, query, filters)
                }
                val result = if (source.isLocal()) {
                    fetch()
                } else {
                    sourceRequests.withRequest(
                        LibraryUpdateType.MANGA,
                        source.id,
                        fetch,
                    )
                }
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
