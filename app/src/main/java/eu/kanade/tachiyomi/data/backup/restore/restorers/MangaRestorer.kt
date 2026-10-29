package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import kotlinx.serialization.json.JsonObject
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.entries.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.entries.manga.interactor.MangaFetchInterval
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.interactor.InsertMangaTrack
import tachiyomi.domain.track.manga.model.MangaTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.max

class MangaRestorer(
    private val handler: MangaDatabaseHandler = Injekt.get(),
    private val getCategories: GetMangaCategories = Injekt.get(),
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getTracks: GetMangaTracks = Injekt.get(),
    private val insertTrack: InsertMangaTrack = Injekt.get(),
    fetchInterval: MangaFetchInterval = Injekt.get(),
) {

    private var now = ZonedDateTime.now()
    private var currentFetchWindow = fetchInterval.getWindow(now)

    init {
        now = ZonedDateTime.now()
        currentFetchWindow = fetchInterval.getWindow(now)
    }

    suspend fun getExistingMatcher(): (Long, String) -> Boolean {
        val urlsBySource = handler.awaitList { mangaQueries.getAllMangaSourceAndUrl() }
            .groupBy({ it.source }, { it.url })
            .mapValues { (_, urls) -> urls.toHashSet() }
        return { source, url -> urlsBySource[source]?.contains(url) == true }
    }

    suspend fun restore(
        backupManga: BackupManga,
        backupCategories: List<BackupCategory>,
        onWarning: (String) -> Unit = {},
    ) {
        val skippedHistory = handler.await(inTransaction = true) {
            val dbManga = findExistingManga(backupManga)
            val manga = backupManga.getMangaImpl()
            val restoredManga = if (dbManga == null) {
                restoreNewManga(manga)
            } else {
                restoreExistingManga(manga, dbManga)
            }

            restoreMangaDetails(
                manga = restoredManga,
                chapters = backupManga.chapters,
                categories = backupManga.categories,
                backupCategories = backupCategories,
                history = backupManga.history,
                tracks = backupManga.tracking,
                excludedScanlators = backupManga.excludedScanlators,
            )
        }
        if (skippedHistory > 0) {
            onWarning("$skippedHistory history entries could not be restored because their chapter metadata is missing")
        }
    }

    private suspend fun findExistingManga(backupManga: BackupManga): Manga? {
        return getMangaByUrlAndSourceId.await(backupManga.url, backupManga.source)
    }

    private suspend fun restoreExistingManga(manga: Manga, dbManga: Manga): Manga {
        return if (manga.version > dbManga.version) {
            updateManga(dbManga.copyFrom(manga).copy(id = dbManga.id))
        } else {
            updateManga(manga.copyFrom(dbManga).copy(id = dbManga.id))
        }
    }

    private fun Manga.copyFrom(newer: Manga): Manga {
        return this.copy(
            favorite = this.favorite || newer.favorite,
            author = newer.author,
            artist = newer.artist,
            description = newer.description,
            genre = newer.genre,
            thumbnailUrl = newer.thumbnailUrl,
            status = newer.status,
            initialized = this.initialized || newer.initialized,
            version = newer.version,
        )
    }

    private suspend fun updateManga(manga: Manga): Manga {
        handler.await(true) {
            mangaQueries.update(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre?.joinToString(separator = ", "),
                title = manga.title,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                favorite = manga.favorite,
                lastUpdate = manga.lastUpdate,
                nextUpdate = null,
                calculateInterval = null,
                initialized = manga.initialized,
                viewer = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                mangaId = manga.id,
                updateStrategy = manga.updateStrategy.let(MangaUpdateStrategyColumnAdapter::encode),
                version = manga.version,
                isSyncing = 1,
                memo = manga.memo.let(MemoColumnAdapter::encode),
            )
        }
        return manga
    }

    private suspend fun restoreNewManga(
        manga: Manga,
    ): Manga {
        return manga.copy(
            initialized = manga.description != null,
            id = insertManga(manga),
            version = manga.version,
        )
    }

    private suspend fun restoreChapters(manga: Manga, backupChapters: List<BackupChapter>) {
        val dbChaptersByUrl = getChaptersByMangaId.await(manga.id)
            .associateBy { it.url }

        val (existingChapters, newChapters) = backupChapters
            .map { it.toChapterImpl() }
            .groupingBy { it.url }
            .reduce { _, kept, other ->
                kept.copy(
                    read = kept.read || other.read,
                    bookmark = kept.bookmark || other.bookmark,
                    lastPageRead = max(kept.lastPageRead, other.lastPageRead),
                    scanlator = kept.scanlator?.takeUnless { it.isBlank() } ?: other.scanlator,
                    dateFetch = listOf(kept.dateFetch, other.dateFetch).filter { it > 0 }.minOrNull() ?: 0,
                    dateUpload = max(kept.dateUpload, other.dateUpload),
                    lastModifiedAt = max(kept.lastModifiedAt, other.lastModifiedAt),
                    memo = JsonObject(other.memo + kept.memo),
                )
            }
            .values
            .mapNotNull {
                val chapter = it.copy(mangaId = manga.id)

                val dbChapter = dbChaptersByUrl[chapter.url]
                    ?: // New chapter
                    return@mapNotNull chapter

                if (chapter.forComparison() == dbChapter.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing chapter
                chapter
                    .copyFrom(dbChapter)
                    .copy(
                        id = dbChapter.id,
                        scanlator = dbChapter.scanlator?.takeUnless { it.isBlank() } ?: chapter.scanlator,
                        read = chapter.read || dbChapter.read,
                        bookmark = chapter.bookmark || dbChapter.bookmark,
                        lastPageRead = max(chapter.lastPageRead, dbChapter.lastPageRead),
                        memo = JsonObject(chapter.memo + dbChapter.memo),
                    )
            }
            .partition { it.id > 0 }

        insertNewChapters(newChapters)
        updateExistingChapters(existingChapters)
    }

    private fun Chapter.forComparison() =
        this.copy(id = 0L, mangaId = 0L, dateFetch = 0L, dateUpload = 0L, lastModifiedAt = 0L, version = 0L)

    private suspend fun insertNewChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chapterQueries.insert(
                    chapter.mangaId,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read,
                    chapter.bookmark,
                    chapter.lastPageRead,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.dateFetch,
                    chapter.dateUpload,
                    chapter.version,
                    chapter.memo,
                )
            }
        }
    }

    private suspend fun updateExistingChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chapterQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = chapter.scanlator,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id,
                    version = chapter.version,
                    isSyncing = 0,
                    memo = chapter.memo.let(MemoColumnAdapter::encode),
                )
            }
        }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return handler.awaitOneExecutable(true) {
            mangaQueries.insert(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre,
                title = manga.title,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                favorite = manga.favorite,
                lastUpdate = manga.lastUpdate,
                nextUpdate = 0L,
                calculateInterval = 0L,
                initialized = manga.initialized,
                viewerFlags = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                updateStrategy = manga.updateStrategy,
                version = manga.version,
                memo = manga.memo,
            )
            mangaQueries.getExistingId(manga.source, manga.url)
        }
    }

    private suspend fun restoreMangaDetails(
        manga: Manga,
        chapters: List<BackupChapter>,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
        history: List<BackupHistory>,
        tracks: List<BackupTracking>,
        excludedScanlators: List<String>,
    ): Int {
        restoreCategories(manga, categories, backupCategories)
        restoreChapters(manga, chapters)
        restoreTracking(manga, tracks)
        val skippedHistory = restoreHistory(manga.id, history)
        restoreExcludedScanlators(manga, excludedScanlators)
        updateManga.awaitUpdateFetchInterval(manga, now, currentFetchWindow)
        return skippedHistory
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(
        manga: Manga,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategories = getCategories.await()
        val dbCategoriesByName = dbCategories.associateBy { it.name }

        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val mangaCategoriesToUpdate = categories.mapNotNull { backupCategoryOrder ->
            backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                dbCategoriesByName[backupCategory.name]?.let { dbCategory ->
                    Pair(manga.id, dbCategory.id)
                }
            }
        }

        if (mangaCategoriesToUpdate.isNotEmpty()) {
            handler.await(true) {
                manga_categoryQueries.deleteMangaCategoryByMangaId(manga.id)
                mangaCategoriesToUpdate.forEach { (mangaId, categoryId) ->
                    manga_categoryQueries.insert(mangaId, categoryId)
                }
            }
        }
    }

    private suspend fun restoreHistory(mangaId: Long, backupHistory: List<BackupHistory>): Int {
        var skippedHistory = 0
        val mergedHistory = backupHistory.groupingBy { it.url }.reduce { _, kept, other ->
            kept.copy(
                lastRead = max(kept.lastRead, other.lastRead),
                chapter = kept.chapter?.takeIf { it.url == kept.url }
                    ?: other.chapter?.takeIf { it.url == other.url },
                readDuration = kept.readDuration + other.readDuration,
            )
        }
        val toUpdate = mergedHistory.values.mapNotNull { history ->
            val dbHistory = handler.awaitOneOrNull { historyQueries.getHistoryByChapterUrl(mangaId, history.url) }
            val item = history.getHistoryImpl()

            if (dbHistory == null) {
                var chapter = handler.awaitOneOrNull { chapterQueries.getChapterByUrlAndMangaId(history.url, mangaId) }
                if (chapter == null) {
                    history.chapter?.takeIf { it.url == history.url }?.let { metadata ->
                        insertNewChapters(
                            listOf(metadata.historyMetadata().toChapterImpl().copy(mangaId = mangaId)),
                        )
                        chapter = handler.awaitOneOrNull {
                            chapterQueries.getChapterByUrlAndMangaId(history.url, mangaId)
                        }
                    }
                }
                return@mapNotNull if (chapter == null) {
                    skippedHistory++
                    null
                } else {
                    // New history entry
                    item.copy(chapterId = chapter!!.id)
                }
            }

            // Update history entry
            item.copy(
                id = dbHistory.id,
                chapterId = dbHistory.chapter_id,
                readAt = max(item.readAt?.time ?: 0L, dbHistory.last_read?.time ?: 0L)
                    .takeIf { it > 0L }
                    ?.let { Date(it) },
                readDuration = max(item.readDuration, dbHistory.time_read) - dbHistory.time_read,
            )
        }

        if (toUpdate.isNotEmpty()) {
            handler.await(true) {
                toUpdate.forEach {
                    historyQueries.upsert(
                        it.chapterId,
                        it.readAt,
                        it.readDuration,
                    )
                }
            }
        }
        return skippedHistory
    }

    private suspend fun restoreTracking(manga: Manga, backupTracks: List<BackupTracking>) {
        val dbTrackByTrackerId = getTracks.await(manga.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = backupTracks
            .mapNotNull {
                val track = it.getTrackImpl()
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        mangaId = manga.id,
                    )

                if (track.forComparison() == dbTrack.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing track
                dbTrack.copy(
                    remoteId = track.remoteId,
                    libraryId = track.libraryId,
                    lastChapterRead = max(dbTrack.lastChapterRead, track.lastChapterRead),
                )
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            insertTrack.awaitAll(newTracks)
        }
        if (existingTracks.isNotEmpty()) {
            handler.await(true) {
                existingTracks.forEach { track ->
                    manga_trackQueries.update(
                        track.mangaId,
                        track.trackerId,
                        track.remoteId,
                        track.libraryId,
                        track.title,
                        track.lastChapterRead,
                        track.totalChapters,
                        track.status,
                        track.score,
                        track.remoteUrl,
                        track.startDate,
                        track.finishDate,
                        track.private,
                        track.id,
                    )
                }
            }
        }
    }

    private fun MangaTrack.forComparison() = this.copy(id = 0L, mangaId = 0L)

    /**
     * Restores the excluded scanlators for the manga.
     *
     * @param manga the manga whose excluded scanlators have to be restored.
     * @param excludedScanlators the excluded scanlators to restore.
     */
    private suspend fun restoreExcludedScanlators(manga: Manga, excludedScanlators: List<String>) {
        if (excludedScanlators.isEmpty()) return
        val existingExcludedScanlators = handler.awaitList {
            excluded_scanlatorQueries.getExcludedScanlatorsByMangaId(manga.id)
        }
        val toInsert = excludedScanlators.filter { it !in existingExcludedScanlators }
        if (toInsert.isNotEmpty()) {
            handler.await {
                toInsert.forEach {
                    excluded_scanlatorQueries.insert(manga.id, it)
                }
            }
        }
    }
}
