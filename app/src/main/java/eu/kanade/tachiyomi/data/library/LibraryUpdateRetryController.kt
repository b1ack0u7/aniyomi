package eu.kanade.tachiyomi.data.library

import android.content.Context
import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.SourceRequestCoordinator
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import tachiyomi.core.common.util.lang.causeChain

internal class LibraryUpdateRetryController(
    private val context: Context,
    private val type: LibraryUpdateType,
    private val sourceRequests: SourceRequestCoordinator,
    private val store: LibraryUpdateRetryStore = LibraryUpdateRetryStore(context),
) {
    private val state = states.getValue(type)
    private val generation = synchronized(state) { state.generation }

    fun dueIds(): Set<Long> = store.dueEntries(type, System.currentTimeMillis()).mapTo(mutableSetOf()) { it.entryId }

    fun completed(entryId: Long) = synchronized(state) {
        if (generation == state.generation) store.remove(type, entryId)
    }

    fun failed(entryId: Long, sourceId: Long, error: Throwable) = synchronized(state) {
        if (generation != state.generation) return@synchronized
        val causes = error.causeChain()
        val pause = causes.filterIsInstance<SourceRequestPausedException>().firstOrNull()
        when {
            causes.any { it is CloudflareChallengeException } || (pause != null && pause.retryAt == null) -> {
                store.removeSource(type, sourceId)
            }
            pause?.retryAt != null -> {
                store.deferEntry(type, entryId, sourceId, pause.retryAt)
                store.postponeSource(type, sourceId, pause.retryAt)
            }
            SourceRequestCoordinator.isRetryable(error) -> {
                val now = System.currentTimeMillis()
                val deadline = sourceRequests.deadline(type, sourceId)
                val dueAt = if (deadline > now) deadline else now + 300_000L
                store.recordFailure(type, entryId, sourceId, dueAt)
                store.postponeSource(type, sourceId, dueAt)
            }
            else -> store.remove(type, entryId)
        }
    }

    fun schedule() = synchronized(state) {
        if (generation != state.generation) return@synchronized
        val next = store.nextDueAt(type)
        if (next == null) {
            LibraryUpdateRetryJob.cancel(context, type)
        } else {
            LibraryUpdateRetryJob.schedule(context, type, next)
        }
    }

    private class State(var generation: Long = 0L)

    companion object {
        private val states = LibraryUpdateType.entries.associateWith { State() }

        fun cancel(context: Context, type: LibraryUpdateType) {
            val state = states.getValue(type)
            synchronized(state) {
                state.generation++
                LibraryUpdateRetryStore(context).clear(type)
                LibraryUpdateRetryJob.cancel(context, type)
            }
        }
    }
}
