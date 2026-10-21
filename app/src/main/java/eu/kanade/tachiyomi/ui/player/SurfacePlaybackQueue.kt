package eu.kanade.tachiyomi.ui.player

internal class SurfacePlaybackQueue {
    val isSurfaceReady: Boolean
        get() = surfaceAvailable

    private var surfaceAvailable = false
    private var active = true
    private var generation = 0L
    private var pending: (() -> Unit)? = null

    fun beginLoad(): Long {
        clear()
        return generation
    }

    fun submit(generation: Long, action: () -> Unit) {
        if (generation != this.generation) return
        pending = action
        flush()
    }

    fun setSurfaceReady(ready: Boolean) {
        surfaceAvailable = ready
        flush()
    }

    fun setActive(active: Boolean) {
        this.active = active
        flush()
    }

    fun clear() {
        generation++
        pending = null
    }

    private fun flush() {
        if (!isSurfaceReady || !active) return
        val action = pending ?: return
        pending = null
        action()
    }
}
