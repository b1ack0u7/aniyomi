package eu.kanade.tachiyomi.ui.player

import android.media.session.PlaybackState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlayerSessionStateTest {

    @Test
    fun `opening reports buffering until file is loaded`() {
        val state = PlayerSessionState()
        state.state shouldBe PlaybackState.STATE_BUFFERING
        state.playbackSpeed shouldBe 0f
        state.loading = false
        state.state shouldBe PlaybackState.STATE_PLAYING
        state.playbackSpeed shouldBe 1f
    }

    @Test
    fun `manual pause freezes progress even during cache wait or seeking`() {
        val state = PlayerSessionState().apply {
            loading = false
            positionSeconds = 43
            speed = 1.5f
            buffering = true
            seeking = true
            paused = true
        }
        state.state shouldBe PlaybackState.STATE_PAUSED
        state.positionMs shouldBe 43000
        state.playbackSpeed shouldBe 0f
        state.paused = false
        state.state shouldBe PlaybackState.STATE_BUFFERING
        state.buffering = false
        state.state shouldBe PlaybackState.STATE_BUFFERING
        state.seeking = false
        state.state shouldBe PlaybackState.STATE_PLAYING
        state.playbackSpeed shouldBe 1.5f
    }

    @Test
    fun `end of file stops extrapolation until a seek clears eof`() {
        val state = PlayerSessionState().apply {
            loading = false
            ended = true
            positionSeconds = 14
        }
        state.state shouldBe PlaybackState.STATE_PAUSED
        state.playbackSpeed shouldBe 0f
        state.ended = false
        state.positionSeconds = 3
        state.state shouldBe PlaybackState.STATE_PLAYING
        state.positionMs shouldBe 3000
    }

    @Test
    fun `new load clears previous timeline and transient flags while retaining playback preferences`() {
        val state = PlayerSessionState().apply {
            loading = false
            ended = true
            seeking = true
            buffering = true
            positionSeconds = 120
            durationSeconds = 300
            paused = true
            speed = 2f
        }
        state.startLoading()
        state.state shouldBe PlaybackState.STATE_BUFFERING
        state.positionMs shouldBe 0
        state.durationMs shouldBe 0
        state.buffering shouldBe false
        state.seeking shouldBe false
        state.ended shouldBe false
        state.paused shouldBe true
        state.speed shouldBe 2f
    }

    @Test
    fun `invalid times and speeds cannot overflow or extrapolate progress`() {
        val state = PlayerSessionState().apply { loading = false }
        state.positionSeconds = -10
        state.durationSeconds = -1
        state.positionMs shouldBe 0
        state.durationMs shouldBe 0
        state.positionSeconds = Long.MAX_VALUE
        state.durationSeconds = Long.MAX_VALUE
        state.positionMs shouldBe Long.MAX_VALUE / 1000 * 1000
        state.durationMs shouldBe Long.MAX_VALUE / 1000 * 1000
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f).forEach {
            state.speed = it
            state.playbackSpeed shouldBe 0f
        }
    }
}
