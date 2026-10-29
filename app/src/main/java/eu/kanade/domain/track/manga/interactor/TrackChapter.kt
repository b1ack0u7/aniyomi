package eu.kanade.domain.track.manga.interactor

import android.content.Context
import eu.kanade.domain.track.manga.model.toDbTrack
import eu.kanade.domain.track.manga.model.toDomainTrack
import eu.kanade.domain.track.manga.service.DelayedMangaTrackingUpdateJob
import eu.kanade.domain.track.manga.store.DelayedMangaTrackingStore
import eu.kanade.tachiyomi.data.track.MangaTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.interactor.InsertMangaTrack
import tachiyomi.domain.track.manga.model.identity
import tachiyomi.domain.track.model.TrackingIdentity

class TrackChapter(
    private val getTracks: GetMangaTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertMangaTrack,
    private val delayedTrackingStore: DelayedMangaTrackingStore,
    private val scheduleUpdate: (Context) -> Unit = { DelayedMangaTrackingUpdateJob.setupTask(it) },
) {

    suspend fun await(
        context: Context,
        mangaId: Long,
        chapterNumber: Double,
        setupJobOnFailure: Boolean = true,
        expectedIdentity: TrackingIdentity? = null,
        expectedAccount: String? = null,
    ) {
        if (!chapterNumber.isFinite() || chapterNumber <= 0 || !chapterNumber.toFloat().isFinite()) return
        withNonCancellableContext {
            getTracks.await(mangaId).filter { expectedIdentity == null || it.identity == expectedIdentity }
                .mapNotNull { track ->
                    val service = trackerManager.get(track.trackerId)
                    if (service == null || !service.isLoggedIn || service !is MangaTracker) {
                        return@mapNotNull null
                    }

                    val account = service.getUsername()
                    if (expectedAccount != null && account != expectedAccount) return@mapNotNull null

                    async {
                        runCatching {
                            delayedTrackingStore.addManga(track, chapterNumber, account)
                            if (setupJobOnFailure) scheduleUpdate(context)
                            delayedTrackingStore.withUpdateLock(track.id) {
                                val currentTrack = getTracks.awaitOne(track.id)
                                    ?: error("Tracking entry unavailable; retaining pending update")
                                check(currentTrack.identity == track.identity && service.getUsername() == account) {
                                    "Tracking recipient changed; retaining pending update"
                                }
                                val progress = maxOf(
                                    chapterNumber,
                                    delayedTrackingStore.getProgress(track, account)?.toDouble() ?: chapterNumber,
                                )
                                if (currentTrack.lastChapterRead >= progress) {
                                    delayedTrackingStore.removeMangaItem(track, currentTrack.lastChapterRead, account)
                                    return@withUpdateLock
                                }
                                val refreshedTrack = service.refresh(currentTrack.toDbTrack())
                                    .toDomainTrack(idRequired = true)!!
                                check(track.identity.acceptsLibraryResolution(refreshedTrack.identity)) {
                                    "Tracker refresh changed recipient; retaining pending update"
                                }
                                val updatedTrack = refreshedTrack.copy(
                                    lastChapterRead = maxOf(refreshedTrack.lastChapterRead, progress),
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
                                    remoteTrack.lastChapterRead,
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
