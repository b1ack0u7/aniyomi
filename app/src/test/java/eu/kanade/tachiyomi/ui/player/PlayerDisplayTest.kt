package eu.kanade.tachiyomi.ui.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlayerDisplayTest {

    @Test
    fun `PiP requires enabled preference automatic exit and playing state`() {
        shouldAutoEnterPip(enabled = true, onExit = true, paused = false) shouldBe true
        shouldAutoEnterPip(enabled = false, onExit = true, paused = false) shouldBe false
        shouldAutoEnterPip(enabled = true, onExit = false, paused = false) shouldBe false
        listOf(true, null).forEach {
            shouldAutoEnterPip(enabled = true, onExit = true, paused = it) shouldBe false
        }
    }

    @Test
    fun `PiP ratio follows video dimensions in both orientations`() {
        pipVideoSize(1080, 16.0 / 9.0) shouldBe PipVideoSize(1920, 1080)
        pipVideoSize(1920, 9.0 / 16.0) shouldBe PipVideoSize(1080, 1920)
        pipVideoSize(100, 2.39) shouldBe PipVideoSize(239, 100)
        pipVideoSize(239, 100.0 / 239) shouldBe PipVideoSize(100, 239)
    }

    @Test
    fun `unavailable invalid or unsupported video dimensions omit the PiP ratio`() {
        listOf(null, 0, -1).forEach { pipVideoSize(it, 16.0 / 9.0) shouldBe null }
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.1, 10.0)
            .forEach { pipVideoSize(1080, it) shouldBe null }
        pipVideoSize(Int.MAX_VALUE, 2.0) shouldBe null
        pipVideoSize(1, 0.5) shouldBe null
    }

    @Test
    fun `stretch uses the video surface including inset and window size changes`() {
        videoSurfaceAspect(2400, 1080) shouldBe 2400.0 / 1080
        videoSurfaceAspect(2180, 1080) shouldBe 2180.0 / 1080
        videoSurfaceAspect(1080, 2180) shouldBe 1080.0 / 2180
        videoSurfaceAspect(1000, 1000) shouldBe 1.0
        videoSurfaceAspect(0, 1080) shouldBe -1.0
        videoSurfaceAspect(1080, 0) shouldBe -1.0
    }
}
