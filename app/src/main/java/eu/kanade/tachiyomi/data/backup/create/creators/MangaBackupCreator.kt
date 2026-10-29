package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.backupChapterMapper
import eu.kanade.tachiyomi.data.backup.models.backupMangaTrackMapper
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaBackupCreator(
    private val handler: MangaDatabaseHandler = Injekt.get(),
    private val repository: MangaRepository = Injekt.get(),
) {

    operator fun invoke(options: BackupOptions): Flow<BackupManga> = flow {
        if (!options.libraryEntries) return@flow

        val upToId = repository.getMaxMangaId()
        var afterId = 0L
        while (true) {
            val batch = repository.getBackupMangaBatch(afterId, upToId, CHUNK_SIZE, options.readEntries)
            if (batch.isEmpty()) break
            backupMangas(batch, options).forEach { emit(it) }
            afterId = batch.last().id
        }
    }

    private suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        val ids = mangas.map { it.id }
        val chaptersById = if (options.chapters || options.history) {
            handler.awaitList { chapterQueries.getChaptersByMangaIds(ids) }.groupBy { it.manga_id }
        } else {
            emptyMap()
        }
        val categoriesById = if (options.categories) {
            handler.awaitList { categoryQueries.getCategoryOrdersByMangaIds(ids) }.groupBy { it.manga_id }
        } else {
            emptyMap()
        }
        val tracksById = if (options.tracking) {
            handler.awaitList { manga_trackQueries.getTracksByMangaIds(ids) }.groupBy { it.manga_id }
        } else {
            emptyMap()
        }
        val historyById = if (options.history) {
            handler.awaitList { historyQueries.getBackupHistoryByMangaIds(ids) }.groupBy { it.manga_id }
        } else {
            emptyMap()
        }
        val excludedScanlatorsById = handler.awaitList {
            excluded_scanlatorQueries.getExcludedScanlatorsByMangaIds(ids)
        }.groupBy { it.manga_id }

        return mangas.map { manga ->
            manga.toBackupManga().apply {
                excludedScanlators = excludedScanlatorsById[manga.id].orEmpty().map { it.scanlator }
                val backupChildren = chaptersById[manga.id].orEmpty().map {
                    backupChapterMapper(
                        it.id,
                        it.manga_id,
                        it.remote_url,
                        it.remote_name,
                        it.remote_scanlator,
                        it.user_read,
                        it.user_bookmark,
                        it.user_last_page_read,
                        it.remote_chapter_number,
                        it.remote_source_order,
                        it.state_date_fetch,
                        it.remote_date_upload,
                        it.last_modified_at,
                        it.version,
                        it.is_syncing,
                        it.remote_memo,
                    )
                }
                chapters = if (options.chapters) backupChildren else emptyList()
                val childrenByUrl = backupChildren.associateBy { it.url }
                categories = categoriesById[manga.id].orEmpty().map { it.sort }
                tracking = tracksById[manga.id].orEmpty().map {
                    backupMangaTrackMapper(
                        it.id,
                        it.manga_id,
                        it.sync_id,
                        it.remote_id,
                        it.library_id,
                        it.title,
                        it.last_chapter_read,
                        it.total_chapters,
                        it.status,
                        it.score,
                        it.remote_url,
                        it.start_date,
                        it.finish_date,
                        it.private_,
                    )
                }
                history = historyById[manga.id].orEmpty().map {
                    BackupHistory(
                        it.url,
                        it.last_read?.time ?: 0L,
                        it.time_read,
                        chapter = childrenByUrl[it.url]?.historyMetadata(),
                    )
                }
            }
        }
    }

    private companion object {
        const val CHUNK_SIZE = 100L
    }
}

private fun Manga.toBackupManga() =
    BackupManga(
        url = this.url,
        title = this.title,
        artist = this.artist,
        author = this.author,
        description = this.description,
        genre = this.genre.orEmpty(),
        status = this.status.toInt(),
        thumbnailUrl = this.thumbnailUrl,
        favorite = this.favorite,
        source = this.source,
        dateAdded = this.dateAdded,
        viewer = (this.viewerFlags.toInt() and ReadingMode.MASK),
        viewer_flags = this.viewerFlags.toInt(),
        chapterFlags = this.chapterFlags.toInt(),
        updateStrategy = this.updateStrategy,
        lastModifiedAt = this.lastModifiedAt,
        favoriteModifiedAt = this.favoriteModifiedAt,
        version = this.version,
        memo = MemoColumnAdapter.encode(this.memo),
    )
