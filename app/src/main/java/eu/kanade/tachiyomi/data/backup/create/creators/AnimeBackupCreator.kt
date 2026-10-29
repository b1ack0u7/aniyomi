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
        val episodesById = if (options.chapters || options.history) {
            handler.awaitList { episodeQueries.getEpisodesByAnimeIds(ids) }.groupBy { it.anime_id }
        } else {
            emptyMap()
        }
        val categoriesById = if (options.categories) {
            handler.awaitList { categoryQueries.getCategoryOrdersByAnimeIds(ids) }.groupBy { it.anime_id }
        } else {
            emptyMap()
        }
        val tracksById = if (options.tracking) {
            handler.awaitList { anime_trackQueries.getTracksByAnimeIds(ids) }.groupBy { it.anime_id }
        } else {
            emptyMap()
        }
        val historyById = if (options.history) {
            handler.awaitList { anime_historyQueries.getBackupHistoryByAnimeIds(ids) }.groupBy { it.anime_id }
        } else {
            emptyMap()
        }

        return animes.map { anime ->
            anime.toBackupAnime().apply {
                val backupChildren = episodesById[anime.id].orEmpty().map {
                    backupEpisodeMapper(
                        it.id,
                        it.anime_id,
                        it.remote_url,
                        it.remote_name,
                        it.remote_scanlator,
                        it.user_seen,
                        it.user_bookmark,
                        it.user_last_second_seen,
                        it.remote_total_seconds,
                        it.remote_episode_number,
                        it.remote_source_order,
                        it.state_date_fetch,
                        it.remote_date_upload,
                        it.last_modified_at,
                        it.version,
                        it.is_syncing,
                        it.remote_summary,
                        it.remote_preview,
                        it.user_fillermark,
                    )
                }
                episodes = if (options.chapters) backupChildren else emptyList()
                val childrenByUrl = backupChildren.associateBy { it.url }
                categories = categoriesById[anime.id].orEmpty().map { it.sort }
                tracking = tracksById[anime.id].orEmpty().map {
                    backupAnimeTrackMapper(
                        it.id,
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
                    BackupAnimeHistory(
                        it.url,
                        it.last_seen?.time ?: 0L,
                        episode = childrenByUrl[it.url]?.historyMetadata(),
                    )
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
