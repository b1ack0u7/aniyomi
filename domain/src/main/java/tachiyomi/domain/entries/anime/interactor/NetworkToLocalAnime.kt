package tachiyomi.domain.entries.anime.interactor

import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager

class NetworkToLocalAnime(
    private val animeRepository: AnimeRepository,
    private val sourceManager: AnimeSourceManager,
) {

    suspend fun await(anime: Anime): Anime {
        val localAnime = getAnime(anime.url, anime.source) ?: run {
            val id = requireNotNull(insertAnime(anime))
            animeRepository.getAnimeById(id)
        }
        return if (localAnime.favorite) {
            localAnime
        } else {
            localAnime.copy(title = anime.title)
        }
    }

    private suspend fun getAnime(url: String, sourceId: Long): Anime? {
        return animeRepository.getAnimeByUrlAndSourceId(url, sourceId)
    }

    private suspend fun insertAnime(anime: Anime): Long? {
        return animeRepository.insertAnime(anime)
    }
}
