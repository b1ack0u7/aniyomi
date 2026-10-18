package eu.kanade.tachiyomi.data.library.anime

import aniyomi.domain.anime.SeasonAnime
import eu.kanade.tachiyomi.animesource.model.FetchType
import tachiyomi.domain.library.anime.LibraryAnime

internal suspend fun selectAnimeForLibraryUpdate(
    libraryAnime: List<LibraryAnime>,
    includeSeasons: Boolean,
    retryIds: Set<Long>?,
    getSeasons: suspend (Long) -> List<SeasonAnime>,
): List<LibraryAnime> = libraryAnime
    .distinctBy { it.anime.id }
    .flatMap { entry ->
        when (entry.anime.fetchType) {
            FetchType.Seasons -> if (includeSeasons) {
                getSeasons(entry.anime.id)
                    .filter { it.anime.fetchType == FetchType.Episodes && !it.anime.favorite }
                    .map { it.toLibraryAnime() }
            } else {
                emptyList()
            }
            FetchType.Episodes -> listOf(entry)
        }
    }
    .filter { retryIds == null || it.anime.id in retryIds }
    .distinctBy { it.anime.id }
