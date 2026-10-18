package eu.kanade.tachiyomi.data.library.manga

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.domain.entries.manga.model.toSManga
import eu.kanade.domain.items.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.items.chapter.model.toSChapter
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.cache.MangaCoverCache
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateProgress
import eu.kanade.tachiyomi.data.library.LibraryUpdateRetryController
import eu.kanade.tachiyomi.data.library.LibraryUpdateRetryJob
import eu.kanade.tachiyomi.data.library.LibraryUpdateRetryStore
import eu.kanade.tachiyomi.data.library.LibraryUpdateRun
import eu.kanade.tachiyomi.data.library.LibraryUpdateSummary
import eu.kanade.tachiyomi.data.library.LibraryUpdateType
import eu.kanade.tachiyomi.data.library.libraryUpdateConstraints
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.SourceRequestCoordinator
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.isRunningFlow
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.domain.items.chapter.interactor.FilterChaptersForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.manga.interactor.GetLibraryManga
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.interactor.MangaFetchInterval
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.chapter.model.NoChaptersException
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_HAS_UNVIEWED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_NON_VIEWED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.source.manga.model.SourceNotInstalledException
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.entries.manga.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MangaLibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: MangaSourceManager = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val downloadManager: MangaDownloadManager = Injekt.get()
    private val coverCache: MangaCoverCache = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val getManga: GetManga = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get()
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get()
    private val mangaFetchInterval: MangaFetchInterval = Injekt.get()
    private val filterChaptersForDownload: FilterChaptersForDownload = Injekt.get()

    private val sourceRequests: SourceRequestCoordinator = Injekt.get()
    private val retries = LibraryUpdateRetryController(context, LibraryUpdateType.MANGA, sourceRequests)

    private val notifier = MangaLibraryUpdateNotifier(context)

    private var mangaToUpdate: List<LibraryManga> = mutableListOf()

    override suspend fun doWork(): Result {
        if (!runLock.tryLock()) return Result.retry()
        return try {
            updateLibrary()
        } finally {
            runLock.unlock()
            retries.schedule()
        }
    }

    private suspend fun updateLibrary(): Result {
        if (tags.contains(WORK_NAME_AUTO) || inputData.getBoolean(KEY_RETRY, false)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                val preferences = Injekt.get<LibraryPreferences>()
                val restrictions = preferences.autoUpdateDeviceRestrictions().get()
                if ((DEVICE_ONLY_ON_WIFI in restrictions) && !context.isConnectedToWifi()) {
                    return Result.retry()
                }
            }

            // Find a running manual worker. If exists, try again later
            if (context.workManager.isRunning(WORK_NAME_MANUAL)) {
                return Result.retry()
            }
        }

        try {
            setForeground(getForegroundInfo())
        } catch (e: IllegalStateException) {
            logcat(LogPriority.ERROR, e) { "Not allowed to set foreground job" }
        }

        return withIOContext {
            try {
                LibraryUpdateRun(downloadManager::startDownloadsAutomatically) {
                    libraryPreferences.mangaLastUpdatedTimestamp().set(Instant.now().toEpochMilli())
                }.execute {
                    val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
                    addMangaToQueue(categoryId)
                    updateChapterList(this)
                }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                Result.failure()
            } finally {
                progressChannel.value = null
                notifier.cancelProgressNotification()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notifier = MangaLibraryUpdateNotifier(context)
        return ForegroundInfo(
            Notifications.ID_LIBRARY_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Adds list of manga to be updated.
     *
     * @param categoryId the ID of the category to update, or -1 if no category specified.
     */
    private suspend fun addMangaToQueue(categoryId: Long) {
        val libraryManga = getLibraryManga.await()

        val retryIds = if (inputData.getBoolean(KEY_RETRY, false)) retries.dueIds() else null
        val listToUpdate = if (retryIds != null) {
            libraryManga.filter { it.manga.id in retryIds }.distinctBy { it.manga.id }
        } else if (categoryId != -1L) {
            libraryManga.filter { it.category == categoryId }
        } else {
            val categoriesToUpdate = libraryPreferences.mangaUpdateCategories().get().map { it.toLong() }
            val includedManga = if (categoriesToUpdate.isNotEmpty()) {
                libraryManga.filter { it.category in categoriesToUpdate }
            } else {
                libraryManga
            }

            val categoriesToExclude = libraryPreferences.mangaUpdateCategoriesExclude().get().map { it.toLong() }
            val excludedMangaIds = if (categoriesToExclude.isNotEmpty()) {
                libraryManga.filter { it.category in categoriesToExclude }.map { it.manga.id }
            } else {
                emptyList()
            }

            includedManga
                .filterNot { it.manga.id in excludedMangaIds }
                .distinctBy { it.manga.id }
        }

        val restrictions = libraryPreferences.autoUpdateItemRestrictions().get()
        val skippedUpdates = mutableListOf<Pair<Manga, String?>>()
        val (_, fetchWindowUpperBound) = mangaFetchInterval.getWindow(ZonedDateTime.now())

        mangaToUpdate = listToUpdate
            .filter {
                when {
                    it.manga.updateStrategy != UpdateStrategy.ALWAYS_UPDATE -> {
                        skippedUpdates.add(
                            it.manga to context.stringResource(MR.strings.skipped_reason_not_always_update),
                        )
                        false
                    }

                    ENTRY_NON_COMPLETED in restrictions && it.manga.status.toInt() == SManga.COMPLETED -> {
                        skippedUpdates.add(
                            it.manga to context.stringResource(MR.strings.skipped_reason_completed),
                        )
                        false
                    }

                    ENTRY_HAS_UNVIEWED in restrictions && it.unreadCount != 0L -> {
                        skippedUpdates.add(
                            it.manga to context.stringResource(MR.strings.skipped_reason_not_caught_up),
                        )
                        false
                    }

                    ENTRY_NON_VIEWED in restrictions && it.totalChapters > 0L && !it.hasStarted -> {
                        skippedUpdates.add(
                            it.manga to context.stringResource(MR.strings.skipped_reason_not_started),
                        )
                        false
                    }

                    ENTRY_OUTSIDE_RELEASE_PERIOD in restrictions && it.manga.nextUpdate > fetchWindowUpperBound -> {
                        skippedUpdates.add(
                            it.manga to context.stringResource(MR.strings.skipped_reason_not_in_release_period),
                        )
                        false
                    }
                    else -> true
                }
            }
            .sortedBy { it.manga.title }

        if (retryIds != null) {
            val queuedIds = mangaToUpdate.mapTo(mutableSetOf()) { it.manga.id }
            (retryIds - queuedIds).forEach(retries::completed)
        }

        notifier.showQueueSizeWarningNotificationIfNeeded(mangaToUpdate)

        if (skippedUpdates.isNotEmpty()) {
            // TODO: surface skipped reasons to user?
            logcat {
                skippedUpdates
                    .groupBy { it.second }
                    .map { (reason, entries) -> "$reason: [${entries.map { it.first.title }.sorted().joinToString()}]" }
                    .joinToString()
            }
        }
    }

    /**
     * Method that updates manga in [mangaToUpdate]. It's called in a background thread, so it's safe
     * to do heavy operations or network calls here.
     * For each manga it calls [updateManga] and updates the notification showing the current
     * progress.
     *
     * @return an observable delivering the progress of each update.
     */
    private suspend fun updateChapterList(run: LibraryUpdateRun): LibraryUpdateSummary {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInteger(0)
        val currentlyUpdatingManga = CopyOnWriteArrayList<Manga>()
        val newUpdates = CopyOnWriteArrayList<Pair<Manga, Array<Chapter>>>()
        val failedUpdates = CopyOnWriteArrayList<Pair<Manga, String?>>()
        val fetchWindow = mangaFetchInterval.getWindow(ZonedDateTime.now())
        progressChannel.value = LibraryUpdateProgress(current = 0, total = mangaToUpdate.size)

        coroutineScope {
            mangaToUpdate.groupBy { it.manga.source }.values
                .map { mangaInSource ->
                    async {
                        semaphore.withPermit {
                            mangaInSource.forEach { libraryManga ->
                                val manga = libraryManga.manga
                                ensureActive()

                                // Don't continue to update if manga is not in library
                                if (getManga.await(manga.id)?.favorite != true) {
                                    retries.completed(manga.id)
                                    return@forEach
                                }

                                withUpdateNotification(
                                    currentlyUpdatingManga,
                                    progressCount,
                                    manga,
                                ) {
                                    try {
                                        val newChapters = updateManga(manga, fetchWindow)
                                            .sortedByDescending { it.sourceOrder }
                                        retries.completed(manga.id)

                                        if (newChapters.isNotEmpty()) {
                                            val chaptersToDownload = filterChaptersForDownload.await(manga, newChapters)
                                            if (chaptersToDownload.isNotEmpty()) {
                                                run.queueDownloads { downloadChapters(manga, chaptersToDownload) }
                                            }
                                            libraryPreferences.incrementNewMangaUpdatesCount(newChapters.size)

                                            // Convert to the manga that contains new chapters
                                            newUpdates.add(manga to newChapters.toTypedArray())
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        ensureActive()
                                        retries.failed(manga.id, manga.source, e)
                                        val errorMessage = when (e) {
                                            is NoChaptersException -> context.stringResource(
                                                MR.strings.no_chapters_error,
                                            )
                                            // failedUpdates will already have the source, don't need to copy it into the message
                                            is SourceNotInstalledException -> context.stringResource(
                                                MR.strings.loader_not_implemented_error,
                                            )
                                            else -> with(context) { e.formattedMessage }
                                        }
                                        failedUpdates.add(manga to errorMessage)
                                    }
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

        notifier.cancelProgressNotification()

        if (newUpdates.isNotEmpty()) {
            notifier.showUpdateNotifications(newUpdates)
        }

        if (failedUpdates.isNotEmpty()) {
            val errorFile = writeErrorFile(failedUpdates)
            notifier.showUpdateErrorNotification(
                failedUpdates.size,
                errorFile.getUriCompat(context),
            )
        }

        return LibraryUpdateSummary(
            newItems = newUpdates.sumOf { it.second.size },
            failed = failedUpdates.size,
        ).also { summaryChannel.tryEmit(it) }
    }

    private fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadChapters(manga, chapters, false)
    }

    /**
     * Updates the chapters for the given manga and adds them to the database.
     *
     * @param manga the manga to update.
     * @return a pair of the inserted and removed chapters.
     */
    private suspend fun updateManga(manga: Manga, fetchWindow: Pair<Long, Long>): List<Chapter> {
        val source = sourceManager.getOrStub(manga.source)

        val fetchMetadata = libraryPreferences.autoUpdateMetadata().get()
        val currentChapters = getChaptersByMangaId.await(manga.id)
            .sortedBy { it.sourceOrder }
            .map(Chapter::toSChapter)
        val fetchUpdate = suspend {
            source.getMangaUpdate(
                manga = manga.toSManga(),
                chapters = currentChapters,
                fetchDetails = fetchMetadata,
                fetchChapters = true,
            )
        }
        val mangaUpdate = if (manga.isLocal()) {
            fetchUpdate()
        } else {
            sourceRequests.withRequest(LibraryUpdateType.MANGA, manga.source, fetchUpdate)
        }

        // Update manga metadata if needed
        if (fetchMetadata) {
            updateManga.awaitUpdateFromSource(manga, mangaUpdate.manga, manualFetch = false, coverCache)
        }

        val chapters = mangaUpdate.chapters

        // Get manga from database to account for if it was removed during the update and
        // to get latest data so it doesn't get overwritten later on
        val dbManga = getManga.await(manga.id)?.takeIf { it.favorite } ?: return emptyList()

        return syncChaptersWithSource.await(chapters, dbManga, source, false, fetchWindow)
    }

    private suspend fun withUpdateNotification(
        updatingManga: CopyOnWriteArrayList<Manga>,
        completed: AtomicInteger,
        manga: Manga,
        block: suspend () -> Unit,
    ) = coroutineScope {
        ensureActive()

        updatingManga.add(manga)
        progressChannel.value = LibraryUpdateProgress(completed.get(), mangaToUpdate.size, manga.title)
        notifier.showProgressNotification(
            updatingManga,
            completed.get(),
            mangaToUpdate.size,
        )

        block()

        ensureActive()

        updatingManga.remove(manga)
        completed.getAndIncrement()
        progressChannel.value = LibraryUpdateProgress(
            completed.get(),
            mangaToUpdate.size,
            updatingManga.firstOrNull()?.title,
        )
        notifier.showProgressNotification(
            updatingManga,
            completed.get(),
            mangaToUpdate.size,
        )
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(errors: List<Pair<Manga, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = LibraryUpdateType.MANGA.createErrorFile(
                    context.externalCacheDir ?: context.cacheDir,
                )
                file.bufferedWriter().use { out ->
                    out.write(
                        context.stringResource(MR.strings.library_errors_help, ERROR_LOG_HELP_URL) + "\n\n",
                    )
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Manga
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, mangas) ->
                        out.write("\n! ${error}\n")
                        mangas.groupBy { it.source }.forEach { (srcId, mangas) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            mangas.forEach {
                                out.write("    - ${it.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (_: Exception) {}
        return File("")
    }

    companion object {
        private const val TAG = "LibraryUpdate"
        private const val WORK_NAME_AUTO = "LibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "LibraryUpdate-manual"

        private const val ERROR_LOG_HELP_URL = "https://aniyomi.org/docs/guides/troubleshooting/"

        /**
         * Key for category to update.
         */
        private const val KEY_RETRY = "retry"
        private val runLock = Mutex()

        private const val KEY_CATEGORY = "category"

        private val progressChannel = MutableStateFlow<LibraryUpdateProgress?>(null)

        /**
         * Non-null while a run is in progress, so screens can show how far along it is.
         */
        val progress: StateFlow<LibraryUpdateProgress?> = progressChannel.asStateFlow()

        private val summaryChannel = MutableSharedFlow<LibraryUpdateSummary>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /**
         * Emits once per finished run, so screens can report the outcome without depending on
         * notifications being permitted.
         */
        val summaries: SharedFlow<LibraryUpdateSummary> = summaryChannel.asSharedFlow()

        fun isRunningFlow(context: Context): Flow<Boolean> = context.workManager.isRunningFlow(TAG)

        fun cancelAllWorks(context: Context) {
            LibraryUpdateRetryController.cancel(context, LibraryUpdateType.MANGA)
            context.workManager.cancelAllWorkByTag(TAG)
        }

        fun setupTask(
            context: Context,
            prefInterval: Int? = null,
        ) {
            LibraryUpdateRetryStore(context).nextDueAt(LibraryUpdateType.MANGA)?.let {
                LibraryUpdateRetryJob.schedule(context, LibraryUpdateType.MANGA, it)
            }
            val preferences = Injekt.get<LibraryPreferences>()
            val interval = prefInterval ?: preferences.autoUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.autoUpdateDeviceRestrictions().get()
                val constraints = libraryUpdateConstraints(restrictions)

                val request = PeriodicWorkRequestBuilder<MangaLibraryUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            }
        }

        fun startNow(
            context: Context,
            category: Category? = null,
        ): Boolean {
            val wm = context.workManager
            if (wm.isRunning(TAG)) {
                // Already running either as a scheduled or manual job
                return false
            }

            Injekt.get<SourceRequestCoordinator>().resumeChallenges(LibraryUpdateType.MANGA)

            val inputData = workDataOf(
                KEY_CATEGORY to category?.id,
            )
            val request = OneTimeWorkRequestBuilder<MangaLibraryUpdateJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setInputData(inputData)
                .build()
            wm.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        fun startRetry(context: Context, entryIds: List<Long>): Boolean = startRetry(
            context,
            entryIds,
            Injekt.get<LibraryPreferences>().autoUpdateDeviceRestrictions().get(),
        )

        internal fun startRetry(context: Context, entryIds: List<Long>, restrictions: Set<String>): Boolean {
            val wm = context.workManager
            if (entryIds.isEmpty() || wm.isRunning(TAG)) return false
            val request = OneTimeWorkRequestBuilder<MangaLibraryUpdateJob>()
                .addTag(TAG)
                .setInputData(workDataOf(KEY_RETRY to true))
                .setConstraints(libraryUpdateConstraints(restrictions))
                .build()
            wm.enqueueUniqueWork("${WORK_NAME_MANUAL}-retry", ExistingWorkPolicy.KEEP, request)
            return true
        }

        fun stop(context: Context) {
            LibraryUpdateRetryController.cancel(context, LibraryUpdateType.MANGA)
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                // Should only return one work but just in case
                .forEach {
                    wm.cancelWorkById(it.id)

                    // Re-enqueue cancelled scheduled work
                    if (it.tags.contains(WORK_NAME_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
