package eu.kanade.domain.track.anime.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.model.identity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class DelayedAnimeTrackingUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val getTracks = Injekt.get<GetAnimeTracks>()
        val trackEpisode = Injekt.get<TrackEpisode>()

        val delayedTrackingStore = Injekt.get<DelayedAnimeTrackingStore>()

        withIOContext {
            delayedTrackingStore.getAnimeItems().forEach { item ->
                val track = getTracks.awaitOne(item.trackId) ?: return@forEach
                if (track.identity != item.identity) return@forEach
                logcat(LogPriority.DEBUG) { "Updating delayed anime track item: ${item.trackId}" }
                trackEpisode.await(
                    context,
                    track.animeId,
                    item.lastEpisodeSeen.toDouble(),
                    setupJobOnFailure = false,
                    expectedIdentity = item.identity,
                    expectedAccount = item.account,
                )
            }
        }

        return if (delayedTrackingStore.getAnimeItems().isEmpty()) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "DelayedAnimeTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<DelayedAnimeTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5.minutes.toJavaDuration())
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
