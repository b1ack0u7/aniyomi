package tachiyomi.data.items.episode

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.mi.data.AnimeDatabase

class EpisodeRepositoryImpl(
    private val handler: AnimeDatabaseHandler,
) : EpisodeRepository {

    override suspend fun addAllEpisodes(episodes: List<Episode>): List<Episode> {
        return handler.await(inTransaction = true) { insertEpisodes(episodes) }
    }

    override suspend fun syncEpisodes(
        episodesToAdd: List<Episode>,
        episodesToUpdate: List<EpisodeUpdate>,
        episodeIdsToDelete: List<Long>,
    ): List<Episode> {
        return handler.await(inTransaction = true) {
            if (episodeIdsToDelete.isNotEmpty()) {
                episodeQueries.removeEpisodesWithIds(episodeIdsToDelete)
            }
            val addedEpisodes = insertEpisodes(episodesToAdd)
            updateEpisodes(episodesToUpdate)
            addedEpisodes
        }
    }

    private fun AnimeDatabase.insertEpisodes(episodes: List<Episode>): List<Episode> {
        return episodes.map { episode ->
            episodeQueries.insert(
                episode.animeId,
                episode.url,
                episode.name,
                episode.scanlator,
                episode.seen,
                episode.bookmark,
                episode.lastSecondSeen,
                episode.totalSeconds,
                episode.episodeNumber,
                episode.sourceOrder,
                episode.dateFetch,
                episode.dateUpload,
                episode.version,
                episode.summary,
                episode.previewUrl,
                episode.fillermark,
            )
            val storedId = episodeQueries.getExistingId(episode.animeId, episode.url).executeAsOne()
            episodeQueries.getEpisodeById(storedId, ::mapEpisode).executeAsOne()
        }
    }

    override suspend fun updateEpisode(episodeUpdate: EpisodeUpdate) {
        partialUpdate(episodeUpdate)
    }

    override suspend fun updateAllEpisodes(episodeUpdates: List<EpisodeUpdate>) {
        partialUpdate(*episodeUpdates.toTypedArray())
    }

    private suspend fun partialUpdate(vararg episodeUpdates: EpisodeUpdate) {
        handler.await(inTransaction = true) { updateEpisodes(episodeUpdates.toList()) }
    }

    private fun AnimeDatabase.updateEpisodes(episodeUpdates: List<EpisodeUpdate>) {
        episodeUpdates.forEach { episodeUpdate ->
            episodeQueries.update(
                animeId = episodeUpdate.animeId,
                url = episodeUpdate.url,
                name = episodeUpdate.name,
                scanlator = episodeUpdate.scanlator,
                seen = episodeUpdate.seen,
                bookmark = episodeUpdate.bookmark,
                lastSecondSeen = episodeUpdate.lastSecondSeen,
                totalSeconds = episodeUpdate.totalSeconds,
                episodeNumber = episodeUpdate.episodeNumber,
                sourceOrder = episodeUpdate.sourceOrder,
                dateFetch = episodeUpdate.dateFetch,
                dateUpload = episodeUpdate.dateUpload,
                episodeId = episodeUpdate.id,
                version = episodeUpdate.version,
                isSyncing = 0,
                summary = episodeUpdate.summary,
                previewUrl = episodeUpdate.previewUrl,
                fillermark = episodeUpdate.fillermark,
            )
        }
    }

    override suspend fun removeEpisodesWithIds(episodeIds: List<Long>) {
        handler.await { episodeQueries.removeEpisodesWithIds(episodeIds) }
    }

    override suspend fun getEpisodeByAnimeId(animeId: Long): List<Episode> {
        return handler.awaitList { episodeQueries.getEpisodesByAnimeId(animeId, ::mapEpisode) }
    }

    override suspend fun getBookmarkedEpisodesByAnimeId(animeId: Long): List<Episode> {
        return handler.awaitList {
            episodeQueries.getBookmarkedEpisodesByAnimeId(
                animeId,
                ::mapEpisode,
            )
        }
    }

    override suspend fun getEpisodeById(id: Long): Episode? {
        return handler.awaitOneOrNull { episodeQueries.getEpisodeById(id, ::mapEpisode) }
    }

    override suspend fun getEpisodeByAnimeIdAsFlow(animeId: Long): Flow<List<Episode>> {
        return handler.subscribeToList {
            episodeQueries.getEpisodesByAnimeId(
                animeId,
                ::mapEpisode,
            )
        }
    }

    override suspend fun getEpisodeByUrlAndAnimeId(url: String, animeId: Long): Episode? {
        return handler.awaitOneOrNull {
            episodeQueries.getEpisodeByUrlAndAnimeId(
                url,
                animeId,
                ::mapEpisode,
            )
        }
    }

    private fun mapEpisode(
        id: Long,
        animeId: Long,
        url: String,
        name: String,
        scanlator: String?,
        seen: Boolean,
        bookmark: Boolean,
        lastSecondSeen: Long,
        totalSeconds: Long,
        episodeNumber: Double,
        sourceOrder: Long,
        dateFetch: Long,
        dateUpload: Long,
        lastModifiedAt: Long,
        version: Long,
        @Suppress("UNUSED_PARAMETER")
        isSyncing: Long,
        summary: String?,
        previewUrl: String?,
        fillermark: Boolean,
    ): Episode = Episode(
        id = id,
        animeId = animeId,
        seen = seen,
        bookmark = bookmark,
        fillermark = fillermark,
        lastSecondSeen = lastSecondSeen,
        totalSeconds = totalSeconds,
        dateFetch = dateFetch,
        sourceOrder = sourceOrder,
        url = url,
        name = name,
        dateUpload = dateUpload,
        episodeNumber = episodeNumber,
        scanlator = scanlator,
        summary = summary,
        previewUrl = previewUrl,
        lastModifiedAt = lastModifiedAt,
        version = version,
    )
}
