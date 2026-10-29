package eu.kanade.domain.track.store

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.TrackingIdentity
import java.util.Base64

internal class DelayedTrackingStore(context: Context, private val stack: Stack) {

    private val preferences = context.getSharedPreferences(stack.fileName, Context.MODE_PRIVATE)

    init {
        // Legacy IDs have no stack identity. Keep the original file intact for explicit recovery.
        if (context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE).all.isNotEmpty()) {
            logcat(LogPriority.WARN) { "Legacy tracking_queue retained without replay: its stack is unknown" }
        }
    }

    fun add(identity: TrackingIdentity, account: String, progress: Double) = synchronized(stack.monitor) {
        require(identity.trackId > 0 && progress.isFinite() && progress > 0 && progress.toFloat().isFinite())
        val previous = progress(identity, account) ?: 0f
        if (progress.toFloat() > previous) {
            preferences.edit { putFloat(resolveKey(key(identity, account)), progress.toFloat()) }
        }
    }

    fun remove(
        identity: TrackingIdentity,
        account: String,
        acknowledgedProgress: Double,
    ) = synchronized(stack.monitor) {
        val pending = progress(identity, account) ?: return@synchronized
        if (pending <= acknowledgedProgress.toFloat()) {
            preferences.edit { remove(resolveKey(key(identity, account))) }
        }
    }

    fun items(): List<Item> = synchronized(stack.monitor) {
        preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith("alias:")) return@mapNotNull null
            val recipient = recipient(key)
            val progress = (value as? Float)?.takeIf { it.isFinite() && it > 0 }
            if (recipient == null || progress == null) {
                logcat(LogPriority.WARN) {
                    "Unidentified ${stack.name.lowercase()} tracking entry retained without replay"
                }
                null
            } else {
                Item(recipient.first, recipient.second, progress)
            }
        }
    }

    fun progress(identity: TrackingIdentity, account: String): Float? = synchronized(stack.monitor) {
        (preferences.all[resolveKey(key(identity, account))] as? Float)?.takeIf { it.isFinite() && it > 0 }
    }

    fun acknowledge(
        identity: TrackingIdentity,
        updatedIdentity: TrackingIdentity,
        account: String,
        confirmedProgress: Double,
    ) = synchronized(stack.monitor) {
        require(identity.acceptsLibraryResolution(updatedIdentity))
        if (identity == updatedIdentity) {
            remove(identity, account, confirmedProgress)
            return@synchronized
        }
        val previousKey = resolveKey(key(identity, account))
        val updatedKey = resolveKey(key(updatedIdentity, account))
        if (previousKey == updatedKey) {
            remove(updatedIdentity, account, confirmedProgress)
            return@synchronized
        }
        val pending = progress(identity, account)
        val updatedPending = progress(updatedIdentity, account) ?: 0f
        preferences.edit {
            // The successful conditional write proves this library ID was resolved for the same recipient.
            putString("alias:$previousKey", updatedKey)
            if (pending != null && pending > confirmedProgress.toFloat() && pending > updatedPending) {
                putFloat(updatedKey, pending)
            }
            remove(previousKey)
        }
    }

    private fun resolveKey(original: String): String {
        val values = preferences.all
        val visited = mutableSetOf<String>()
        var current = original
        while (visited.add(current)) {
            val next = values["alias:$current"] as? String ?: return current
            val previousRecipient = recipient(current) ?: return original
            val nextRecipient = recipient(next) ?: return original
            if (previousRecipient.second != nextRecipient.second ||
                !previousRecipient.first.acceptsLibraryResolution(nextRecipient.first)
            ) {
                return original
            }
            current = next
        }
        return original
    }

    suspend fun <T> withUpdateLock(trackId: Long, block: suspend () -> T): T {
        return stack.updateLocks[(trackId.hashCode() and Int.MAX_VALUE) % stack.updateLocks.size].withLock { block() }
    }

    private fun key(identity: TrackingIdentity, account: String): String = with(identity) {
        val url = Base64.getUrlEncoder().withoutPadding().encodeToString(remoteUrl.toByteArray(Charsets.UTF_8))
        "v1:$trackId:$parentId:$trackerId:$remoteId:${libraryId ?: "null"}:$url:" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(account.toByteArray(Charsets.UTF_8))
    }

    private fun recipient(key: String): Pair<TrackingIdentity, String>? = runCatching {
        val fields = key.split(':', limit = 8)
        require(fields.size == 8 && fields[0] == "v1")
        val account = Base64.getUrlDecoder().decode(fields[7]).toString(Charsets.UTF_8)
        val identity = TrackingIdentity(
            trackId = fields[1].toLong(),
            parentId = fields[2].toLong(),
            trackerId = fields[3].toLong(),
            remoteId = fields[4].toLong(),
            libraryId = fields[5].takeUnless { it == "null" }?.toLong(),
            remoteUrl = Base64.getUrlDecoder().decode(fields[6]).toString(Charsets.UTF_8),
        )
        require(identity.trackId > 0 && key(identity, account) == key)
        identity to account
    }.getOrNull()

    data class Item(val identity: TrackingIdentity, val account: String, val progress: Float)

    enum class Stack(val fileName: String) {
        MANGA("tracking_queue_manga"),
        ANIME("tracking_queue_anime"),
        ;

        val monitor = Any()
        val updateLocks = Array(32) { Mutex() }
    }
}
