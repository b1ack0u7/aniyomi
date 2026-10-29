package eu.kanade.domain.track.manga.store

import android.content.Context
import eu.kanade.domain.track.store.DelayedTrackingStore
import tachiyomi.domain.track.manga.model.MangaTrack
import tachiyomi.domain.track.manga.model.identity
import tachiyomi.domain.track.model.TrackingIdentity

class DelayedMangaTrackingStore(context: Context) {

    private val store = DelayedTrackingStore(context, DelayedTrackingStore.Stack.MANGA)

    fun addManga(track: MangaTrack, lastChapterRead: Double, account: String) =
        store.add(track.identity, account, lastChapterRead)

    fun removeMangaItem(track: MangaTrack, acknowledgedProgress: Double, account: String) =
        store.remove(track.identity, account, acknowledgedProgress)

    internal fun acknowledge(track: MangaTrack, updatedTrack: MangaTrack, progress: Double, account: String) =
        store.acknowledge(track.identity, updatedTrack.identity, account, progress)

    fun getMangaItems(): List<DelayedTrackingItem> = store.items().map { (identity, account, progress) ->
        DelayedTrackingItem(identity, account, progress)
    }

    internal fun getProgress(track: MangaTrack, account: String): Float? = store.progress(track.identity, account)

    internal suspend fun <T> withUpdateLock(trackId: Long, block: suspend () -> T): T =
        store.withUpdateLock(trackId, block)

    data class DelayedTrackingItem(
        val identity: TrackingIdentity,
        val account: String,
        val lastChapterRead: Float,
    ) {
        val trackId: Long get() = identity.trackId
    }
}
