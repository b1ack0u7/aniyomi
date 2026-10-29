package tachiyomi.domain.entries.manga.interactor

import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository

class NetworkToLocalManga(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(manga: Manga): Manga {
        val localManga = getManga(manga.url, manga.source) ?: run {
            val id = requireNotNull(insertManga(manga))
            mangaRepository.getMangaById(id)
        }
        return if (localManga.favorite) {
            localManga
        } else {
            localManga.copy(title = manga.title)
        }
    }

    private suspend fun getManga(url: String, sourceId: Long): Manga? {
        return mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
    }

    private suspend fun insertManga(manga: Manga): Long? {
        return mangaRepository.insertManga(manga)
    }
}
