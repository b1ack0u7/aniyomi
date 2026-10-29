package tachiyomi.data.track.manga

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.track.manga.model.MangaTrack
import tachiyomi.domain.track.manga.model.identity
import tachiyomi.domain.track.manga.repository.MangaTrackRepository

class MangaTrackRepositoryImpl(
    private val handler: MangaDatabaseHandler,
) : MangaTrackRepository {

    override suspend fun getTrackByMangaId(id: Long): MangaTrack? {
        return handler.awaitOneOrNull { manga_trackQueries.getTrackById(id, MangaTrackMapper::mapTrack) }
    }

    override suspend fun getTracksByMangaId(mangaId: Long): List<MangaTrack> {
        return handler.awaitList {
            manga_trackQueries.getTracksByMangaId(mangaId, MangaTrackMapper::mapTrack)
        }
    }

    override fun getMangaTracksAsFlow(): Flow<List<MangaTrack>> {
        return handler.subscribeToList {
            manga_trackQueries.getTracks(MangaTrackMapper::mapTrack)
        }
    }

    override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<MangaTrack>> {
        return handler.subscribeToList {
            manga_trackQueries.getTracksByMangaId(mangaId, MangaTrackMapper::mapTrack)
        }
    }

    override suspend fun delete(mangaId: Long, trackerId: Long) {
        handler.await {
            manga_trackQueries.delete(
                mangaId = mangaId,
                syncId = trackerId,
            )
        }
    }

    override suspend fun updateIfSameIdentity(track: MangaTrack, expected: MangaTrack): Boolean {
        return handler.await(inTransaction = true) {
            if (!expected.identity.acceptsLibraryResolution(track.identity)) return@await false
            val current = manga_trackQueries.getTrackById(expected.id, MangaTrackMapper::mapTrack)
                .executeAsOneOrNull()
            if (current == null || current.identity != expected.identity) return@await false
            insertTrack(track.copy(lastChapterRead = maxOf(current.lastChapterRead, track.lastChapterRead)))
            true
        }
    }

    override suspend fun insertManga(track: MangaTrack) {
        insertValues(track)
    }

    override suspend fun insertAllManga(tracks: List<MangaTrack>) {
        insertValues(*tracks.toTypedArray())
    }

    private suspend fun insertValues(vararg tracks: MangaTrack) {
        handler.await(inTransaction = true) {
            tracks.forEach { insertTrack(it) }
        }
    }

    private fun Database.insertTrack(track: MangaTrack) {
        manga_trackQueries.insert(
            mangaId = track.mangaId,
            syncId = track.trackerId,
            remoteId = track.remoteId,
            libraryId = track.libraryId,
            title = track.title,
            lastChapterRead = track.lastChapterRead,
            totalChapters = track.totalChapters,
            status = track.status,
            score = track.score,
            remoteUrl = track.remoteUrl,
            startDate = track.startDate,
            finishDate = track.finishDate,
            private = track.private,
        )
    }
}
