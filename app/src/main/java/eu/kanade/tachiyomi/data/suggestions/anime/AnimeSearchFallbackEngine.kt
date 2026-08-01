package eu.kanade.tachiyomi.data.suggestions.anime

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.suggestions.SourceSearchFallback
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import tachiyomi.domain.entries.anime.model.Anime

class AnimeSearchFallbackEngine {

    suspend fun fetchSearchFallback(
        anime: Anime,
        source: AnimeCatalogueSource,
        seed: SuggestionSeed,
        maxResults: Int = 40,
        onProgress: ((List<SuggestionItem>) -> Unit)? = null,
    ): List<SuggestionItem> {
        val filterList = source.getFilterList()
        return SourceSearchFallback.run(
            seed = seed,
            entryTitle = anime.title,
            entryUrl = anime.url,
            author = anime.author,
            artist = anime.artist,
            genres = anime.genre,
            sourceId = source.id,
            sourceName = source.name,
            maxResults = maxResults,
            search = { query ->
                source.getSearchAnime(1, query, filterList).animes.map {
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
