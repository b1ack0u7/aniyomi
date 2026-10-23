package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.backupAnimeTrackMapper
import eu.kanade.tachiyomi.data.backup.models.backupEpisodeMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeBackupCreator(
    private val handler: AnimeDatabaseHandler = Injekt.get(),
    private val repository: AnimeRepository = Injekt.get(),
) {

    operator fun invoke(options: BackupOptions): Flow<BackupAnime> = flow {
        if (!options.libraryEntries) return@flow

        val upToId = repository.getMaxAnimeId()
        var afterId = 0L
        while (true) {
            val batch = repository.getBackupAnimeBatch(afterId, upToId, CHUNK_SIZE, options.readEntries)
            if (batch.isEmpty()) break
            backupAnimes(batch, options).forEach { emit(it) }
            afterId = batch.last().id
        }
    }

    private suspend fun backupAnimes(animes: List<Anime>, options: BackupOptions): List<BackupAnime> {
        val ids = animes.map { it.id }
        val episodesById = if (options.chapters) {
            handler.awaitList { episodesQueries.getEpisodesByAnimeIds(ids) }.groupBy { it.anime_id }
        } else {
            emptyMap()
        }
        val categoriesById = if (options.categories) {
            handler.awaitList { categoriesQueries.getCategoryOrdersByAnimeIds(ids) }.groupBy { it.anime_id }
        } else {
            emptyMap()
        }
        val tracksById = if (options.tracking) {
            handler.awaitList { anime_syncQueries.getTracksByAnimeIds(ids) }.groupBy { it.anime_id }
        } else {
            emptyMap()
        }
        val historyById = if (options.history) {
            handler.awaitList { animehistoryQueries.getBackupHistoryByAnimeIds(ids) }.groupBy { it.anime_id }
        } else {
            emptyMap()
        }

        return animes.map { anime ->
            anime.toBackupAnime().apply {
                episodes = episodesById[anime.id].orEmpty().map {
                    backupEpisodeMapper(
                        it._id,
                        it.anime_id,
                        it.url,
                        it.name,
                        it.scanlator,
                        it.seen,
                        it.bookmark,
                        it.last_second_seen,
                        it.total_seconds,
                        it.episode_number,
                        it.source_order,
                        it.date_fetch,
                        it.date_upload,
                        it.last_modified_at,
                        it.version,
                        it.is_syncing,
                        it.summary,
                        it.preview_url,
                        it.fillermark,
                    )
                }
                categories = categoriesById[anime.id].orEmpty().map { it.sort }
                tracking = tracksById[anime.id].orEmpty().map {
                    backupAnimeTrackMapper(
                        it._id,
                        it.anime_id,
                        it.sync_id,
                        it.remote_id,
                        it.library_id,
                        it.title,
                        it.last_episode_seen,
                        it.total_episodes,
                        it.status,
                        it.score,
                        it.remote_url,
                        it.start_date,
                        it.finish_date,
                        it.private_,
                    )
                }
                history = historyById[anime.id].orEmpty().map {
                    BackupAnimeHistory(it.url, it.last_seen?.time ?: 0L)
                }
            }
        }
    }

    private companion object {
        const val CHUNK_SIZE = 100L
    }
}

private fun Anime.toBackupAnime() =
    BackupAnime(
        url = this.url,
        title = this.title,
        artist = this.artist,
        author = this.author,
        description = this.description,
        genre = this.genre.orEmpty(),
        status = this.status.toInt(),
        thumbnailUrl = this.thumbnailUrl,
        backgroundUrl = this.backgroundUrl,
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.dateAdded,
        viewer_flags = this.viewerFlags.toInt(),
        episodeFlags = this.episodeFlags.toInt(),
        updateStrategy = this.updateStrategy,
        lastModifiedAt = this.lastModifiedAt,
        favoriteModifiedAt = this.favoriteModifiedAt,
        version = this.version,
        fetchType = this.fetchType,
        parentId = this.parentId,
        id = this.id,
        seasonFlags = this.seasonFlags,
        seasonNumber = this.seasonNumber,
        seasonSourceOrder = this.seasonSourceOrder,
    )
