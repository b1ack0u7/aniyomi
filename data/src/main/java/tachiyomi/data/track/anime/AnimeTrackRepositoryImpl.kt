package tachiyomi.data.track.anime

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.domain.track.anime.model.identity
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import tachiyomi.mi.data.AnimeDatabase

class AnimeTrackRepositoryImpl(
    private val handler: AnimeDatabaseHandler,
) : AnimeTrackRepository {

    override suspend fun getTrackByAnimeId(id: Long): AnimeTrack? {
        return handler.awaitOneOrNull { anime_trackQueries.getTrackByAnimeId(id, AnimeTrackMapper::mapTrack) }
    }

    override suspend fun getTracksByAnimeId(animeId: Long): List<AnimeTrack> {
        return handler.awaitList {
            anime_trackQueries.getTracksByAnimeId(animeId, AnimeTrackMapper::mapTrack)
        }
    }

    override fun getAnimeTracksAsFlow(): Flow<List<AnimeTrack>> {
        return handler.subscribeToList {
            anime_trackQueries.getAnimeTracks(AnimeTrackMapper::mapTrack)
        }
    }

    override fun getTracksByAnimeIdAsFlow(animeId: Long): Flow<List<AnimeTrack>> {
        return handler.subscribeToList {
            anime_trackQueries.getTracksByAnimeId(animeId, AnimeTrackMapper::mapTrack)
        }
    }

    override suspend fun delete(animeId: Long, trackerId: Long) {
        handler.await {
            anime_trackQueries.delete(
                animeId = animeId,
                syncId = trackerId,
            )
        }
    }

    override suspend fun updateIfSameIdentity(track: AnimeTrack, expected: AnimeTrack): Boolean {
        return handler.await(inTransaction = true) {
            if (!expected.identity.acceptsLibraryResolution(track.identity)) return@await false
            val current = anime_trackQueries.getTrackByAnimeId(expected.id, AnimeTrackMapper::mapTrack)
                .executeAsOneOrNull()
            if (current == null || current.identity != expected.identity) return@await false
            insertTrack(track.copy(lastEpisodeSeen = maxOf(current.lastEpisodeSeen, track.lastEpisodeSeen)))
            true
        }
    }

    override suspend fun insertAnime(track: AnimeTrack) {
        insertValues(track)
    }

    override suspend fun insertAllAnime(tracks: List<AnimeTrack>) {
        insertValues(*tracks.toTypedArray())
    }

    private suspend fun insertValues(vararg tracks: AnimeTrack) {
        handler.await(inTransaction = true) {
            tracks.forEach { insertTrack(it) }
        }
    }

    private fun AnimeDatabase.insertTrack(track: AnimeTrack) {
        anime_trackQueries.insert(
            animeId = track.animeId,
            syncId = track.trackerId,
            remoteId = track.remoteId,
            libraryId = track.libraryId,
            title = track.title,
            lastEpisodeSeen = track.lastEpisodeSeen,
            totalEpisodes = track.totalEpisodes,
            status = track.status,
            score = track.score,
            remoteUrl = track.remoteUrl,
            startDate = track.startDate,
            finishDate = track.finishDate,
            private = track.private,
        )
    }
}
