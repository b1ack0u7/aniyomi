package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

class LibraryUpdateRetryJob(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val type = inputData.getString(KEY_TYPE)
            ?.let { value -> LibraryUpdateType.entries.find { it.name == value } }
            ?: return Result.failure()
        val store = LibraryUpdateRetryStore(applicationContext)
        val dueEntries = store.dueEntries(type, System.currentTimeMillis())
        if (dueEntries.isEmpty()) {
            store.nextDueAt(type)?.let { schedule(applicationContext, type, it) }
            return Result.success()
        }

        currentCoroutineContext().ensureActive()
        val entryIds = dueEntries.map { it.entryId }
        when (type) {
            LibraryUpdateType.MANGA -> MangaLibraryUpdateJob.startRetry(applicationContext, entryIds)
            LibraryUpdateType.ANIME -> AnimeLibraryUpdateJob.startRetry(applicationContext, entryIds)
        }
        return Result.success()
    }

    companion object {
        internal const val KEY_TYPE = "library_update_retry_type"

        internal fun workName(type: LibraryUpdateType): String = "LibraryUpdateRetry-${type.name}"

        fun schedule(context: Context, type: LibraryUpdateType, dueAt: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<LibraryUpdateRetryJob>()
                .setInputData(workDataOf(KEY_TYPE to type.name))
                .setConstraints(constraints)
                .setInitialDelay(
                    (dueAt - System.currentTimeMillis()).coerceIn(0, TimeUnit.DAYS.toMillis(1)),
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(type), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, type: LibraryUpdateType) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(type))
        }
    }
}
