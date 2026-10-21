package eu.kanade.tachiyomi.ui.player

import android.media.session.PlaybackState

internal class PlayerSessionState {
    var loading = true
    var paused = false
    var buffering = false
    var seeking = false
    var ended = false
    var positionSeconds = 0L
    var durationSeconds = 0L
    var speed = 1f

    val state: Int
        get() = when {
            loading -> PlaybackState.STATE_BUFFERING
            ended || paused -> PlaybackState.STATE_PAUSED
            buffering || seeking -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PLAYING
        }

    val positionMs: Long
        get() = positionSeconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000

    val durationMs: Long
        get() = durationSeconds.coerceIn(0, Long.MAX_VALUE / 1000) * 1000

    val playbackSpeed: Float
        get() = if (state == PlaybackState.STATE_PLAYING && speed.isFinite() && speed > 0f) speed else 0f

    fun startLoading() {
        loading = true
        buffering = false
        seeking = false
        ended = false
        positionSeconds = 0
        durationSeconds = 0
    }
}
