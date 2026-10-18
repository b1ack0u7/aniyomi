package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import tachiyomi.domain.download.service.DownloadPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class MangaDownloadResumeJob(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = applicationContext.getSharedPreferences("smart_manga_downloads", Context.MODE_PRIVATE)
        if (store.getBoolean("resume", false)) {
            val manager = Injekt.get<MangaDownloadManager>()
            manager.awaitRestored()
            currentCoroutineContext().ensureActive()
            if (store.getBoolean("resume", false) && !manager.isRunning && manager.queueState.value.isNotEmpty()) {
                MangaDownloadJob.start(applicationContext, automatic = true)
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MangaDownloaderResume"

        fun schedule(context: Context, wakeAt: Long) {
            val preferences = Injekt.get<DownloadPreferences>()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (preferences.downloadOnlyOverWifi().get()) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()
            val request = OneTimeWorkRequestBuilder<MangaDownloadResumeJob>()
                .setConstraints(constraints)
                .setInitialDelay(
                    (wakeAt - System.currentTimeMillis()).coerceIn(0, TimeUnit.DAYS.toMillis(1)),
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun isScheduledFlow(context: Context) = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(TAG).asFlow()
            .map { work -> work.any { !it.state.isFinished } }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }
    }
}
