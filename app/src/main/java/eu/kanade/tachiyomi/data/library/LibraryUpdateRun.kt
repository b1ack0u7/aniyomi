package eu.kanade.tachiyomi.data.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

internal class LibraryUpdateRun(
    private val startDownloads: () -> Unit,
    private val onSuccess: () -> Unit,
) {
    private val hasDownloads = AtomicBoolean(false)

    fun queueDownloads(enqueue: () -> Unit) {
        enqueue()
        hasDownloads.set(true)
    }

    suspend fun execute(block: suspend LibraryUpdateRun.() -> LibraryUpdateSummary): LibraryUpdateSummary {
        return try {
            block().also {
                currentCoroutineContext().ensureActive()
                if (it.failed == 0) onSuccess()
            }
        } finally {
            // Cancellation must not strand downloads already saved by a completed source.
            if (hasDownloads.get()) startDownloads()
        }
    }
}
