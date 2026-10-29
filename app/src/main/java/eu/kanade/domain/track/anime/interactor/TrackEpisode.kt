package eu.kanade.domain.track.anime.interactor

import android.content.Context
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.domain.track.anime.service.DelayedAnimeTrackingUpdateJob
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.model.identity
import tachiyomi.domain.track.model.TrackingIdentity

class TrackEpisode(
    private val getTracks: GetAnimeTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertAnimeTrack,
    private val delayedTrackingStore: DelayedAnimeTrackingStore,
    private val scheduleUpdate: (Context) -> Unit = { DelayedAnimeTrackingUpdateJob.setupTask(it) },
) {

    suspend fun await(
        context: Context,
        animeId: Long,
        episodeNumber: Double,
        setupJobOnFailure: Boolean = true,
        expectedIdentity: TrackingIdentity? = null,
        expectedAccount: String? = null,
    ) {
        if (!episodeNumber.isFinite() || episodeNumber <= 0 || !episodeNumber.toFloat().isFinite()) return
        withNonCancellableContext {
            getTracks.await(animeId).filter { expectedIdentity == null || it.identity == expectedIdentity }
                .mapNotNull { track ->
                    val service = trackerManager.get(track.trackerId)
                    if (service == null || !service.isLoggedIn || service !is AnimeTracker) {
                        return@mapNotNull null
                    }

                    val account = service.getUsername()
                    if (expectedAccount != null && account != expectedAccount) return@mapNotNull null

                    async {
                        runCatching {
                            delayedTrackingStore.addAnime(track, episodeNumber, account)
                            if (setupJobOnFailure) scheduleUpdate(context)
                            delayedTrackingStore.withUpdateLock(track.id) {
                                val currentTrack = getTracks.awaitOne(track.id)
                                    ?: error("Tracking entry unavailable; retaining pending update")
                                check(currentTrack.identity == track.identity && service.getUsername() == account) {
                                    "Tracking recipient changed; retaining pending update"
                                }
                                val progress = maxOf(
                                    episodeNumber,
                                    delayedTrackingStore.getProgress(track, account)?.toDouble() ?: episodeNumber,
                                )
                                if (currentTrack.lastEpisodeSeen >= progress) {
                                    delayedTrackingStore.removeAnimeItem(track, currentTrack.lastEpisodeSeen, account)
                                    return@withUpdateLock
                                }
                                val refreshedTrack = service.refresh(currentTrack.toDbTrack())
                                    .toDomainTrack(idRequired = true)!!
                                check(track.identity.acceptsLibraryResolution(refreshedTrack.identity)) {
                                    "Tracker refresh changed recipient; retaining pending update"
                                }
                                val updatedTrack = refreshedTrack.copy(
                                    lastEpisodeSeen = maxOf(refreshedTrack.lastEpisodeSeen, progress),
                                )
                                check(
                                    getTracks.awaitOne(track.id)?.identity == track.identity &&
                                        service.getUsername() == account,
                                ) {
                                    "Tracking recipient changed during refresh; retaining pending update"
                                }
                                val sentTrack = updatedTrack.toDbTrack()
                                service.update(sentTrack, true)
                                val remoteTrack = sentTrack.toDomainTrack(idRequired = true)!!
                                check(track.identity.acceptsLibraryResolution(remoteTrack.identity)) {
                                    "Tracker response changed recipient; retaining pending update"
                                }
                                check(service.getUsername() == account) {
                                    "Tracking account changed during update; retaining pending update"
                                }
                                check(insertTrack.awaitIfSameIdentity(remoteTrack, track)) {
                                    "Tracking recipient changed or progress not persisted; retaining pending update"
                                }
                                check(service.getUsername() == account) {
                                    "Tracking account changed before acknowledgement; retaining pending update"
                                }
                                delayedTrackingStore.acknowledge(
                                    track,
                                    remoteTrack,
                                    remoteTrack.lastEpisodeSeen,
                                    account,
                                )
                            }
                        }
                    }
                }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.INFO, it) }
        }
    }
}
