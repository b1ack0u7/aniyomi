package eu.kanade.domain.track.anime.store

import android.content.Context
import eu.kanade.domain.track.store.DelayedTrackingStore
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.domain.track.anime.model.identity
import tachiyomi.domain.track.model.TrackingIdentity

class DelayedAnimeTrackingStore(context: Context) {

    private val store = DelayedTrackingStore(context, DelayedTrackingStore.Stack.ANIME)

    fun addAnime(track: AnimeTrack, lastEpisodeSeen: Double, account: String) =
        store.add(track.identity, account, lastEpisodeSeen)

    fun removeAnimeItem(track: AnimeTrack, acknowledgedProgress: Double, account: String) =
        store.remove(track.identity, account, acknowledgedProgress)

    internal fun acknowledge(track: AnimeTrack, updatedTrack: AnimeTrack, progress: Double, account: String) =
        store.acknowledge(track.identity, updatedTrack.identity, account, progress)

    fun getAnimeItems(): List<DelayedAnimeTrackingItem> = store.items().map { (identity, account, progress) ->
        DelayedAnimeTrackingItem(identity, account, progress)
    }

    internal fun getProgress(track: AnimeTrack, account: String): Float? = store.progress(track.identity, account)

    internal suspend fun <T> withUpdateLock(trackId: Long, block: suspend () -> T): T =
        store.withUpdateLock(trackId, block)

    data class DelayedAnimeTrackingItem(
        val identity: TrackingIdentity,
        val account: String,
        val lastEpisodeSeen: Float,
    ) {
        val trackId: Long get() = identity.trackId
    }
}
