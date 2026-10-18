package eu.kanade.tachiyomi.data.library.anime

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
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.domain.items.episode.interactor.SyncEpisodesWithSource
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
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
import mihon.domain.items.episode.interactor.FilterEpisodesForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.anime.interactor.AnimeFetchInterval
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.NoEpisodesException
import tachiyomi.domain.items.season.interactor.GetAnimeSeasonsByParentId
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_HAS_UNVIEWED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_NON_VIEWED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.ENTRY_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.source.anime.model.AnimeSourceNotInstalledException
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.source.local.entries.anime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AnimeLibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: AnimeSourceManager = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val downloadManager: AnimeDownloadManager = Injekt.get()
    private val coverCache: AnimeCoverCache = Injekt.get()
    private val backgroundCache: AnimeBackgroundCache = Injekt.get()
    private val getLibraryAnime: GetLibraryAnime = Injekt.get()
    private val getAnime: GetAnime = Injekt.get()
    private val updateAnime: UpdateAnime = Injekt.get()
    private val syncEpisodesWithSource: SyncEpisodesWithSource = Injekt.get()
    private val animeFetchInterval: AnimeFetchInterval = Injekt.get()
    private val filterEpisodesForDownload: FilterEpisodesForDownload = Injekt.get()
    private val getAnimeSeasonsByParentId: GetAnimeSeasonsByParentId = Injekt.get()

    private val sourceRequests: SourceRequestCoordinator = Injekt.get()
    private val retries = LibraryUpdateRetryController(context, LibraryUpdateType.ANIME, sourceRequests)

    private val notifier = AnimeLibraryUpdateNotifier(context)

    private var animeToUpdate: List<LibraryAnime> = mutableListOf()

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
                    libraryPreferences.animeLastUpdatedTimestamp().set(Instant.now().toEpochMilli())
                }.execute {
                    val categoryId = inputData.getLong(KEY_CATEGORY, -1L)
                    addAnimeToQueue(categoryId)
                    updateEpisodeList(this)
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
        val notifier = AnimeLibraryUpdateNotifier(context)
        return ForegroundInfo(
            Notifications.ID_ANIME_LIBRARY_PROGRESS,
            notifier.progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },

        )
    }

    /**
     * Adds list of anime to be updated.
     *
     * @param categoryId the ID of the category to update, or -1 if no category specified.
     */
    private suspend fun addAnimeToQueue(categoryId: Long) {
        val libraryAnime = getLibraryAnime.await()

        val retryIds = if (inputData.getBoolean(KEY_RETRY, false)) retries.dueIds() else null
        val listToUpdate = if (retryIds != null) {
            libraryAnime
        } else if (categoryId != -1L) {
            libraryAnime.filter { it.category == categoryId }
        } else {
            val categoriesToUpdate = libraryPreferences.animeUpdateCategories().get().map { it.toLong() }
            val includedAnime = if (categoriesToUpdate.isNotEmpty()) {
                libraryAnime.filter { it.category in categoriesToUpdate }
            } else {
                libraryAnime
            }

            val categoriesToExclude = libraryPreferences.animeUpdateCategoriesExclude().get().map { it.toLong() }
            val excludedAnimeIds = if (categoriesToExclude.isNotEmpty()) {
                libraryAnime.filter { it.category in categoriesToExclude }.map { it.anime.id }
            } else {
                emptyList()
            }

            includedAnime
                .filterNot { it.anime.id in excludedAnimeIds }
                .distinctBy { it.anime.id }
        }

        val lastToUpdateWithSeasons = selectAnimeForLibraryUpdate(
            libraryAnime = listToUpdate,
            includeSeasons = libraryPreferences.updateSeasonOnLibraryUpdate().get(),
            retryIds = retryIds,
            getSeasons = getAnimeSeasonsByParentId::await,
        )

        val restrictions = libraryPreferences.autoUpdateItemRestrictions().get()
        val skippedUpdates = mutableListOf<Pair<Anime, String?>>()
        val (_, fetchWindowUpperBound) = animeFetchInterval.getWindow(ZonedDateTime.now())

        animeToUpdate = lastToUpdateWithSeasons
            .filter {
                when {
                    it.anime.updateStrategy != AnimeUpdateStrategy.ALWAYS_UPDATE -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_always_update),
                        )
                        false
                    }

                    ENTRY_NON_COMPLETED in restrictions && it.anime.status.toInt() == SAnime.COMPLETED -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_completed),
                        )
                        false
                    }

                    ENTRY_HAS_UNVIEWED in restrictions && it.unseenCount != 0L -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_caught_up),
                        )
                        false
                    }

                    ENTRY_NON_VIEWED in restrictions && it.totalCount > 0L && !it.hasStarted -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_started),
                        )
                        false
                    }

                    ENTRY_OUTSIDE_RELEASE_PERIOD in restrictions && it.anime.nextUpdate > fetchWindowUpperBound -> {
                        skippedUpdates.add(
                            it.anime to context.stringResource(MR.strings.skipped_reason_not_in_release_period),
                        )
                        false
                    }
                    else -> true
                }
            }
            .sortedBy { it.anime.title }

        if (retryIds != null) {
            val queuedIds = animeToUpdate.mapTo(mutableSetOf()) { it.anime.id }
            (retryIds - queuedIds).forEach(retries::completed)
        }

        notifier.showQueueSizeWarningNotificationIfNeeded(animeToUpdate)

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
     * Method that updates anime in [animeToUpdate]. It's called in a background thread, so it's safe
     * to do heavy operations or network calls here.
     * For each anime it calls [updateAnime] and updates the notification showing the current
     * progress.
     *
     * @return an observable delivering the progress of each update.
     */
    private suspend fun updateEpisodeList(run: LibraryUpdateRun): LibraryUpdateSummary {
        val semaphore = Semaphore(5)
        val progressCount = AtomicInteger(0)
        val currentlyUpdatingAnime = CopyOnWriteArrayList<Anime>()
        val newUpdates = CopyOnWriteArrayList<Pair<Anime, Array<Episode>>>()
        val failedUpdates = CopyOnWriteArrayList<Pair<Anime, String?>>()
        val fetchWindow = animeFetchInterval.getWindow(ZonedDateTime.now())
        progressChannel.value = LibraryUpdateProgress(current = 0, total = animeToUpdate.size)

        coroutineScope {
            animeToUpdate.groupBy { it.anime.source }.values
                .map { animeInSource ->
                    async {
                        semaphore.withPermit {
                            animeInSource.forEach { libraryAnime ->
                                val anime = libraryAnime.anime
                                ensureActive()

                                // Don't continue to update if anime is not in library
                                if (anime.parentId == null && getAnime.await(anime.id)?.favorite != true) {
                                    retries.completed(anime.id)
                                    return@forEach
                                }

                                withUpdateNotification(
                                    currentlyUpdatingAnime,
                                    progressCount,
                                    anime,
                                ) {
                                    try {
                                        val newEpisodes = updateAnime(anime, fetchWindow)
                                            .sortedByDescending { it.sourceOrder }
                                        retries.completed(anime.id)

                                        if (newEpisodes.isNotEmpty()) {
                                            val episodesToDownload = filterEpisodesForDownload.await(anime, newEpisodes)

                                            if (episodesToDownload.isNotEmpty()) {
                                                run.queueDownloads { downloadEpisodes(anime, episodesToDownload) }
                                            }

                                            libraryPreferences.incrementNewAnimeUpdatesCount(newEpisodes.size)

                                            // Convert to the anime that contains new episodes
                                            newUpdates.add(anime to newEpisodes.toTypedArray())
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        ensureActive()
                                        retries.failed(anime.id, anime.source, e)
                                        val errorMessage = when (e) {
                                            is NoEpisodesException -> context.stringResource(
                                                AYMR.strings.no_episodes_error,
                                            )
                                            // failedUpdates will already have the source, don't need to copy it into the message
                                            is AnimeSourceNotInstalledException -> context.stringResource(
                                                MR.strings.loader_not_implemented_error,
                                            )
                                            else -> with(context) { e.formattedMessage }
                                        }
                                        failedUpdates.add(anime to errorMessage)
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

    private fun downloadEpisodes(anime: Anime, episodes: List<Episode>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadEpisodes(anime, episodes, false)
    }

    /**
     * Updates the episodes for the given anime and adds them to the database.
     *
     * @param anime the anime to update.
     * @return a pair of the inserted and removed episodes.
     */
    private suspend fun updateAnime(anime: Anime, fetchWindow: Pair<Long, Long>): List<Episode> {
        val source = sourceManager.getOrStub(anime.source)

        val fetchMetadata = libraryPreferences.autoUpdateMetadata().get()
        val fetchUpdate = suspend {
            source.getAnimeUpdate(
                anime = anime.toSAnime(),
                episodes = emptyList(),
                fetchDetails = fetchMetadata,
                fetchEpisodes = true,
            )
        }
        val animeUpdate = if (anime.isLocal()) {
            fetchUpdate()
        } else {
            sourceRequests.withRequest(LibraryUpdateType.ANIME, anime.source, fetchUpdate)
        }

        // Update anime metadata if needed
        if (fetchMetadata) {
            updateAnime.awaitUpdateFromSource(
                anime,
                animeUpdate.anime,
                manualFetch = false,
                coverCache,
                backgroundCache,
            )
        }

        val episodes = animeUpdate.episodes

        // Get anime from database to account for if it was removed during the update and
        // to get latest data so it doesn't get overwritten later on
        val dbAnime = getAnime.await(anime.id)?.takeIf { it.parentId != null || it.favorite } ?: return emptyList()

        return syncEpisodesWithSource.await(episodes, dbAnime, source, false, fetchWindow)
    }

    private suspend fun withUpdateNotification(
        updatingAnime: CopyOnWriteArrayList<Anime>,
        completed: AtomicInteger,
        anime: Anime,
        block: suspend () -> Unit,
    ) = coroutineScope {
        ensureActive()

        updatingAnime.add(anime)
        progressChannel.value = LibraryUpdateProgress(completed.get(), animeToUpdate.size, anime.title)
        notifier.showProgressNotification(
            updatingAnime,
            completed.get(),
            animeToUpdate.size,
        )

        block()

        ensureActive()

        updatingAnime.remove(anime)
        completed.getAndIncrement()
        progressChannel.value = LibraryUpdateProgress(
            completed.get(),
            animeToUpdate.size,
            updatingAnime.firstOrNull()?.title,
        )
        notifier.showProgressNotification(
            updatingAnime,
            completed.get(),
            animeToUpdate.size,
        )
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(errors: List<Pair<Anime, String?>>): File {
        try {
            if (errors.isNotEmpty()) {
                val file = LibraryUpdateType.ANIME.createErrorFile(
                    context.externalCacheDir ?: context.cacheDir,
                )
                file.bufferedWriter().use { out ->
                    out.write(
                        context.stringResource(MR.strings.library_errors_help, ERROR_LOG_HELP_URL) + "\n\n",
                    )
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Anime
                    errors.groupBy({ it.second }, { it.first }).forEach { (error, animes) ->
                        out.write("\n! ${error}\n")
                        animes.groupBy { it.source }.forEach { (srcId, animes) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            animes.forEach {
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
        private const val TAG = "AnimeLibraryUpdate"
        private const val WORK_NAME_AUTO = "AnimeLibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "AnimeLibraryUpdate-manual"

        private const val ERROR_LOG_HELP_URL = "https://aniyomi.org/docs/guides/troubleshooting/"

        /**
         * Key for category to update.
         */
        private const val KEY_RETRY = "retry"
        private val runLock = Mutex()

        private const val KEY_CATEGORY = "animeCategory"

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
            LibraryUpdateRetryController.cancel(context, LibraryUpdateType.ANIME)
            context.workManager.cancelAllWorkByTag(TAG)
        }

        fun setupTask(
            context: Context,
            prefInterval: Int? = null,
        ) {
            LibraryUpdateRetryStore(context).nextDueAt(LibraryUpdateType.ANIME)?.let {
                LibraryUpdateRetryJob.schedule(context, LibraryUpdateType.ANIME, it)
            }
            val preferences = Injekt.get<LibraryPreferences>()
            val interval = prefInterval ?: preferences.autoUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.autoUpdateDeviceRestrictions().get()
                val constraints = libraryUpdateConstraints(restrictions)

                val request = PeriodicWorkRequestBuilder<AnimeLibraryUpdateJob>(
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

            Injekt.get<SourceRequestCoordinator>().resumeChallenges(LibraryUpdateType.ANIME)

            val inputData = workDataOf(
                KEY_CATEGORY to category?.id,
            )
            val request = OneTimeWorkRequestBuilder<AnimeLibraryUpdateJob>()
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
            val request = OneTimeWorkRequestBuilder<AnimeLibraryUpdateJob>()
                .addTag(TAG)
                .setInputData(workDataOf(KEY_RETRY to true))
                .setConstraints(libraryUpdateConstraints(restrictions))
                .build()
            wm.enqueueUniqueWork("${WORK_NAME_MANUAL}-retry", ExistingWorkPolicy.KEEP, request)
            return true
        }

        fun stop(context: Context) {
            LibraryUpdateRetryController.cancel(context, LibraryUpdateType.ANIME)
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
