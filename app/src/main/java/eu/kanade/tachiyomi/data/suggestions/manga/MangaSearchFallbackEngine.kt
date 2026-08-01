package eu.kanade.tachiyomi.data.suggestions.manga

import eu.kanade.tachiyomi.data.suggestions.SourceSearchFallback
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.source.CatalogueSource
import tachiyomi.domain.entries.manga.model.Manga

class MangaSearchFallbackEngine {

    suspend fun fetchSearchFallback(
        manga: Manga,
        source: CatalogueSource,
        seed: SuggestionSeed,
        maxResults: Int = 40,
        onProgress: ((List<SuggestionItem>) -> Unit)? = null,
    ): List<SuggestionItem> {
        val filterList = source.getFilterList()
        return SourceSearchFallback.run(
            seed = seed,
            entryTitle = manga.title,
            entryUrl = manga.url,
            author = manga.author,
            artist = manga.artist,
            genres = manga.genre,
            sourceId = source.id,
            sourceName = source.name,
            maxResults = maxResults,
            search = { query ->
                source.getSearchManga(1, query, filterList).mangas.map {
                    SourceSearchFallback.Candidate(
                        title = it.title,
                        url = it.url,
                        thumbnailUrl = it.thumbnail_url?.takeIf(String::isNotBlank),
                    )
                }
            },
            onProgress = onProgress,
        )
    }
}
