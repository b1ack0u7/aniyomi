package eu.kanade.tachiyomi.ui.player.utils

import android.os.SystemClock

/**
 * Tracks whether the stream currently handed to mpv is actually working.
 *
 * Two questions the player cannot answer from mpv's events alone:
 *
 * - **Did this file ever play?** A stream mpv fails to decode raises `eof-reached` with the
 *   position jumping straight to the duration, which is indistinguishable from an episode watched
 *   to the end unless real playback progress is tracked separately.
 * - **Is this server keeping up?** A server can be unusable without ever hanging outright, stalling
 *   repeatedly in bursts too short to look like a hang while playback crawls.
 *
 * State is per file and reset by [onLoadFile].
 */
class PlaybackHealth {

    private var lastPosition: Long? = null
    private var playedSeconds = 0L

    private var bufferingStartedAt = 0L
    private var stalledMs = 0L

    private var failureHandled = false

    /** Resets everything for a newly loaded file. */
    fun onLoadFile() {
        lastPosition = null
        playedSeconds = 0L
        // Must be cleared too: a stall that began on the previous file would otherwise be charged
        // to this one the moment it stops buffering, condemning a server that just got here.
        bufferingStartedAt = 0L
        stalledMs = 0L
        failureHandled = false
    }

    /**
     * Feed every `time-pos` update.
     *
     * Only plausible forward steps count as progress. Playback reports roughly one second at a
     * time, so a sudden leap is a seek or a stream collapsing to its end — never decoded content.
     *
     * @return true when playback actually advanced, i.e. the stream is delivering.
     */
    fun onPosition(position: Long): Boolean {
        val previous = lastPosition
        lastPosition = position
        if (previous == null) return false

        val delta = position - previous
        if (delta <= 0 || delta > MAX_PLAUSIBLE_STEP_SECONDS) return false

        playedSeconds += delta
        return true
    }

    /** Whether this file has played enough to count as genuinely watched rather than failed. */
    fun hasPlayedContent(): Boolean = playedSeconds >= MIN_PROGRESS_SECONDS

    /** Feed `paused-for-cache` changes so stalls can be accumulated. */
    fun onBuffering(active: Boolean) {
        if (active) {
            bufferingStartedAt = SystemClock.elapsedRealtime()
            return
        }
        if (bufferingStartedAt > 0) {
            stalledMs += SystemClock.elapsedRealtime() - bufferingStartedAt
        }
        bufferingStartedAt = 0L
    }

    /** Milliseconds this file has spent buffering, across all stalls since it was loaded. */
    fun totalStalledMs(): Long = stalledMs

    /**
     * Returns true only for the first failure of the current file — mpv can raise `eof-reached`
     * more than once, and each call would otherwise burn another candidate video.
     */
    fun consumeFailure(): Boolean {
        if (failureHandled) return false
        failureHandled = true
        return true
    }

    companion object {
        /** Seconds of content that must play before a file counts as genuinely watched. */
        private const val MIN_PROGRESS_SECONDS = 1L

        /**
         * Largest position jump still attributable to playback. `time-pos` ticks about once a
         * second, so this leaves room for fast-forward while excluding seeks and end-of-stream
         * collapses.
         */
        private const val MAX_PLAUSIBLE_STEP_SECONDS = 10L
    }
}
