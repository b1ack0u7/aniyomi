package eu.kanade.tachiyomi.data.suggestions

import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.manga.model.MangaCover

// Items from an installed source go through the app's cover fetchers so the source's
// headers/interceptors apply; external providers serve plain URLs.
fun suggestionCoverModel(item: SuggestionItem): Any? {
    val url = item.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return null
    val sourceId = item.nativeSourceTarget?.sourceId ?: return url

    return when (item.mediaType) {
        SuggestionMediaType.ANIME -> AnimeCover(
            animeId = SUGGESTION_TEMP_ENTRY_ID,
            sourceId = sourceId,
            isAnimeFavorite = false,
            url = url,
            lastModified = 0L,
        )
        SuggestionMediaType.MANGA -> MangaCover(
            mangaId = SUGGESTION_TEMP_ENTRY_ID,
            sourceId = sourceId,
            isMangaFavorite = false,
            url = url,
            lastModified = 0L,
        )
    }
}

private const val SUGGESTION_TEMP_ENTRY_ID = -1L
