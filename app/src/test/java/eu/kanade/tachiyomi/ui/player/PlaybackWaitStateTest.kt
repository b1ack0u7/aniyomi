package eu.kanade.tachiyomi.ui.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlaybackWaitStateTest {

    @Test
    fun `disabled or nonpositive configured timeout never arms recovery`() {
        listOf(Int.MIN_VALUE, -1, 0).forEach {
            playbackStallTimeoutMs(enabled = true, seconds = it) shouldBe null
        }
        playbackStallTimeoutMs(enabled = false, seconds = 20) shouldBe null
        playbackStallTimeoutMs(enabled = true, seconds = 20) shouldBe 20000L
        playbackStallTimeoutMs(enabled = true, seconds = 1) shouldBe 5000L
        playbackStallTimeoutMs(enabled = true, seconds = Int.MAX_VALUE) shouldBe 60000L
    }

    @Test
    fun `progressive initial filling outlasts timeout without charging stalled time`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(opening = true, seconds = 0.0), 5000, 10.0)
        for (second in 1..20) {
            val result = state.update(second * 1000L, waiting(opening = true, seconds = second * 0.1), 5000, 10.0)
            result.phase shouldBe PlaybackWaitPhase.Filling
            result.continuousStallMs shouldBe 0L
            result.cumulativeStallMs shouldBe 0L
            result.failed shouldBe false
        }
    }

    @Test
    fun `segmented HLS fill does not charge gaps between growing segments`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(seconds = 0.0), 5000, 10.0)
        for (second in 1..24) {
            val result = state.update(second * 1000L, waiting(seconds = (second / 4).toDouble()), 5000, 10.0)
            result.failed shouldBe false
            if (second >= 4) {
                result.phase shouldBe PlaybackWaitPhase.Filling
                result.cumulativeStallMs shouldBe 3000L
            }
        }
    }

    @Test
    fun `cache without growth reaches the continuous timeout`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(seconds = 2.0), 5000, 10.0)
        val before = state.update(4000, waiting(seconds = 2.0), 5000, 10.0)
        before.failed shouldBe false
        before.remainingMs shouldBe 1000L
        val expired = state.update(5000, waiting(seconds = 2.0), 5000, 10.0)
        expired.phase shouldBe PlaybackWaitPhase.Stalled
        expired.failed shouldBe true
    }

    @Test
    fun `fill that stops growing still times out without accumulating deliberate wait`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(seconds = 0.0), 5000, 10.0)
        state.update(1000, waiting(seconds = 1.0), 5000, 10.0)
        val waiting = state.update(5000, waiting(seconds = 1.0), 5000, 10.0)
        waiting.phase shouldBe PlaybackWaitPhase.Filling
        waiting.failed shouldBe false
        val expired = state.update(6000, waiting(seconds = 1.0), 5000, 10.0)
        expired.phase shouldBe PlaybackWaitPhase.Stalled
        expired.cumulativeStallMs shouldBe 0L
        expired.failed shouldBe true
    }

    @Test
    fun `continuous trickle cannot extend filling beyond its absolute deadline`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(bytes = 0), 5000, 2.0)
        for (second in 1..9) {
            val result = state.update(second * 1000L, waiting(bytes = second * 1024L), 5000, 2.0)
            result.failed shouldBe false
        }
        val expired = state.update(10000, waiting(bytes = 10240), 5000, 2.0)
        expired.failed shouldBe true
        expired.phase shouldBe PlaybackWaitPhase.Stalled
    }

    @Test
    fun `unknown or invalid cache metrics cannot grant progress`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(seconds = Double.NaN, bytes = -1), 5000, 10.0)
        val expired = state.update(5000, waiting(), 5000, 10.0)
        expired.failed shouldBe true
        expired.phase shouldBe PlaybackWaitPhase.Stalled
    }

    @Test
    fun `manual pause excludes its duration and restarts continuous observation`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(), 5000, 0.0)
        state.update(2000, waiting(), 5000, 0.0)
        val paused = state.update(3000, waiting().copy(paused = true), 5000, 0.0)
        paused.phase shouldBe PlaybackWaitPhase.Paused
        paused.cumulativeStallMs shouldBe 2000L
        paused.continuousStallMs shouldBe 0L
        state.update(60000, waiting().copy(paused = true), 5000, 0.0).failed shouldBe false
        val resumed = state.update(61000, waiting(), 5000, 0.0)
        resumed.continuousStallMs shouldBe 0L
        resumed.cumulativeStallMs shouldBe 2000L
        resumed.failed shouldBe false
    }

    @Test
    fun `seek starts a fresh cache epoch and never charges cumulative stalls`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(seconds = 100.0), 5000, 0.0)
        state.update(3000, waiting(seconds = 100.0), 5000, 0.0)
        val seek = state.update(4000, waiting(seconds = 1.0).copy(seeking = true), 5000, 10.0)
        seek.phase shouldBe PlaybackWaitPhase.Seeking
        seek.continuousStallMs shouldBe 0L
        seek.cumulativeStallMs shouldBe 0L
        val progress = state.update(5000, waiting(seconds = 2.0).copy(seeking = true), 5000, 10.0)
        progress.continuousStallMs shouldBe 0L
        progress.cumulativeStallMs shouldBe 0L
        val blocked = state.update(10000, waiting(seconds = 2.0).copy(seeking = true), 5000, 10.0)
        blocked.failed shouldBe true
        blocked.cumulativeStallMs shouldBe 0L
    }

    @Test
    fun `several short stalls share a cumulative budget after minimum observation`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(), 5000, 0.0)
        state.update(2000, waiting(), 5000, 0.0)
        state.update(2500, playing(), 5000, 0.0)
        state.update(3000, waiting(), 5000, 0.0)
        state.update(5000, waiting(), 5000, 0.0).failed shouldBe false
        state.update(5500, playing(), 5000, 0.0)
        state.update(6000, waiting(), 5000, 0.0)
        val expired = state.update(7000, waiting(), 5000, 0.0)
        expired.continuousStallMs shouldBe 1000L
        expired.cumulativeStallMs shouldBe 5000L
        expired.failed shouldBe true
    }

    @Test
    fun `healthy playback clears old cumulative debt after thirty seconds`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(), 5000, 0.0)
        state.update(4000, waiting(), 5000, 0.0)
        state.update(5000, playing(position = 0.0), 5000, 0.0)
        for (second in 6..34) {
            state.update(second * 1000L, playing(position = (second - 5).toDouble()), 5000, 0.0)
        }
        state.update(34999, playing(position = 29.999), 5000, 0.0).cumulativeStallMs shouldBe 4000L
        state.update(35000, playing(position = 30.0), 5000, 0.0).cumulativeStallMs shouldBe 0L
        state.update(36000, waiting(), 5000, 0.0)
        state.update(37000, waiting(), 5000, 0.0).failed shouldBe false
    }

    @Test
    fun `playing flags with stationary position cannot clear cumulative debt`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(), 5000, 0.0)
        state.update(4000, waiting(), 5000, 0.0)
        state.update(5000, playing(position = 0.0), 5000, 0.0)
        state.update(6000, playing(position = 1.0), 5000, 0.0)
        for (second in 7..40) {
            val stationary = state.update(second * 1000L, playing(position = 1.0), 5000, 0.0)
            stationary.cumulativeStallMs shouldBe 4000L
        }
    }

    @Test
    fun `pause interrupts the healthy reset window without deleting previous debt`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(), 5000, 0.0)
        state.update(4000, waiting(), 5000, 0.0)
        state.update(5000, playing(), 5000, 0.0)
        state.update(30000, playing().copy(paused = true), 5000, 0.0)
        state.update(60000, playing(position = 0.0), 5000, 0.0).cumulativeStallMs shouldBe 4000L
        for (second in 61..89) {
            state.update(second * 1000L, playing(position = (second - 60).toDouble()), 5000, 0.0)
        }
        state.update(89999, playing(position = 29.999), 5000, 0.0).cumulativeStallMs shouldBe 4000L
        state.update(90000, playing(position = 30.0), 5000, 0.0).cumulativeStallMs shouldBe 0L
    }

    @Test
    fun `native early resume does not wait for the requested cache target`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(seconds = 0.0), 5000, 10.0)
        state.update(1000, waiting(seconds = 2.0), 5000, 10.0)
        val resumed = state.update(2000, playing().copy(cacheSeconds = 2.0), 5000, 10.0)
        resumed.phase shouldBe PlaybackWaitPhase.Playing
        resumed.continuousStallMs shouldBe 0L
        resumed.failed shouldBe false
    }

    @Test
    fun `new files clear timing debt and cache samples`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(seconds = 100.0), 5000, 0.0)
        state.update(4000, waiting(seconds = 100.0), 5000, 0.0)
        state.reset(5000)
        state.update(5000, waiting(seconds = 1.0), 5000, 10.0)
        val fresh = state.update(6000, waiting(seconds = 2.0), 5000, 10.0)
        fresh.phase shouldBe PlaybackWaitPhase.Filling
        fresh.cumulativeStallMs shouldBe 0L
        fresh.continuousStallMs shouldBe 0L
        fresh.failed shouldBe false
    }

    @Test
    fun `opening and buffering have separate observation epochs`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(opening = true), 5000, 0.0)
        state.update(4000, waiting(opening = true), 5000, 0.0).failed shouldBe false
        val opened = state.update(5000, waiting(), 5000, 0.0)
        opened.continuousStallMs shouldBe 0L
        opened.failed shouldBe false
        state.update(10000, waiting(), 5000, 0.0).failed shouldBe true
    }

    @Test
    fun `cache jitter below its high water mark does not invent growth`() {
        val state = PlaybackWaitState().apply { reset(0) }
        state.update(0, waiting(seconds = 2.0, bytes = 2048), 5000, 10.0)
        state.update(1000, waiting(seconds = 1.0, bytes = 1024), 5000, 10.0)
        state.update(2000, waiting(seconds = 2.0, bytes = 2048), 5000, 10.0)
        state.update(5000, waiting(seconds = 2.0, bytes = 2048), 5000, 10.0).failed shouldBe true
    }

    private fun waiting(
        opening: Boolean = false,
        seconds: Double? = null,
        bytes: Long? = null,
    ) = PlaybackWaitSample(
        opening = opening,
        buffering = !opening,
        seeking = false,
        paused = false,
        positionSeconds = 0.0,
        cacheSeconds = seconds,
        cacheBytes = bytes,
    )

    private fun playing(position: Double? = 1.0) = PlaybackWaitSample(
        opening = false,
        buffering = false,
        seeking = false,
        paused = false,
        positionSeconds = position,
        cacheSeconds = null,
        cacheBytes = null,
    )
}
