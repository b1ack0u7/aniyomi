package eu.kanade.tachiyomi.ui.player

internal class PlaybackPauseState {
    private var requestedPaused = false
    private var restorePending = true

    @Synchronized
    fun request(paused: Boolean) {
        requestedPaused = paused
    }

    @Synchronized
    fun observe(paused: Boolean, eofReached: Boolean) {
        // keep-open pauses at EOF; loading also pauses temporarily, neither is user intent.
        if (!restorePending && (!paused || !eofReached)) requestedPaused = paused
    }

    @Synchronized
    fun beginLoad() {
        restorePending = true
    }

    @Synchronized
    fun takeAfterLoad(): Boolean? {
        if (!restorePending) return null
        restorePending = false
        return requestedPaused
    }

    @Synchronized
    fun reset() {
        requestedPaused = false
        restorePending = true
    }
}
