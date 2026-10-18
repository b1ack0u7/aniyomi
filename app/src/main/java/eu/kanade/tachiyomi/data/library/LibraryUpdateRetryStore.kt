package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LibraryUpdateRetryStore(private val preferences: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences("library_update_retries", Context.MODE_PRIVATE))

    fun entries(type: LibraryUpdateType): List<Entry> = synchronized(lock) {
        read(type).sortedWith(compareBy(Entry::dueAt, Entry::entryId))
    }

    fun dueEntries(type: LibraryUpdateType, now: Long): List<Entry> = entries(type).filter { it.dueAt <= now }

    fun nextDueAt(type: LibraryUpdateType): Long? = entries(type).firstOrNull()?.dueAt

    fun recordFailure(
        type: LibraryUpdateType,
        entryId: Long,
        sourceId: Long,
        dueAt: Long,
    ): Entry? = synchronized(lock) {
        val entries = read(type).associateByTo(mutableMapOf(), Entry::entryId)
        val previous = entries[entryId]?.takeIf { it.sourceId == sourceId }
        val failedAttempts = (previous?.failedAttempts ?: 0) + 1
        val entry = if (failedAttempts <= MAX_RETRIES) {
            Entry(entryId, sourceId, failedAttempts, maxOf(previous?.dueAt ?: 0, dueAt))
        } else {
            null
        }
        if (entry == null) {
            entries.remove(entryId)
        } else {
            entries[entryId] = entry
        }
        write(type, entries.values)
        entry
    }

    fun deferEntry(
        type: LibraryUpdateType,
        entryId: Long,
        sourceId: Long,
        dueAt: Long,
    ) = synchronized(lock) {
        val entries = read(type).associateByTo(mutableMapOf(), Entry::entryId)
        val previous = entries[entryId]?.takeIf { it.sourceId == sourceId }
        entries[entryId] = Entry(
            entryId,
            sourceId,
            previous?.failedAttempts ?: 0,
            maxOf(previous?.dueAt ?: 0, dueAt),
        )
        write(type, entries.values)
    }

    fun removeSource(type: LibraryUpdateType, sourceId: Long) = synchronized(lock) {
        write(type, read(type).filterNot { it.sourceId == sourceId })
    }

    fun postponeSource(type: LibraryUpdateType, sourceId: Long, dueAt: Long) = synchronized(lock) {
        write(
            type,
            read(type).map {
                if (it.sourceId == sourceId) it.copy(dueAt = maxOf(it.dueAt, dueAt)) else it
            },
        )
    }

    fun remove(type: LibraryUpdateType, entryId: Long) = synchronized(lock) {
        write(type, read(type).filterNot { it.entryId == entryId })
    }

    fun clear(type: LibraryUpdateType) = synchronized(lock) {
        check(preferences.edit().remove(type.name).commit()) { "Failed to persist library update retries" }
    }

    private fun read(type: LibraryUpdateType): List<Entry> = preferences.getStringSet(type.name, emptySet())
        .orEmpty()
        .mapNotNull { value -> runCatching { json.decodeFromString<Entry>(value) }.getOrNull() }
        .filter { it.entryId >= 0 && it.failedAttempts in 0..MAX_RETRIES && it.dueAt >= 0 }

    private fun write(type: LibraryUpdateType, entries: Collection<Entry>) {
        val values = entries.mapTo(mutableSetOf()) { json.encodeToString(it) }
        check(preferences.edit().putStringSet(type.name, values).commit()) {
            "Failed to persist library update retries"
        }
    }

    @Serializable
    data class Entry(
        val entryId: Long,
        val sourceId: Long,
        val failedAttempts: Int,
        val dueAt: Long,
    )

    companion object {
        const val MAX_RETRIES = 2
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
