package eu.kanade.tachiyomi.ui.player.utils

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlaybackHealthTest {

    @Test
    fun `a stream jumping straight to its end remains a failure candidate`() {
        val health = PlaybackHealth()
        health.onLoadFile()
        health.onPosition(0)
        health.onPosition(1440)

        health.hasPlayedContent() shouldBe false
        health.consumeFailure() shouldBe true
        health.consumeFailure() shouldBe false
    }

    @Test
    fun `real playback before EOF counts as content even after a seek`() {
        val health = PlaybackHealth()
        health.onLoadFile()
        health.onPosition(100)
        health.onPosition(101)
        health.onPosition(1440)

        health.hasPlayedContent() shouldBe true
    }

    @Test
    fun `changing servers resets content and allows one new failure`() {
        val health = PlaybackHealth()
        health.onPosition(0)
        health.onPosition(1)
        health.consumeFailure()
        health.onLoadFile()
        health.onPosition(1440)

        health.hasPlayedContent() shouldBe false
        health.consumeFailure() shouldBe true
        health.consumeFailure() shouldBe false
    }

    @Test
    fun `stationary and backward positions do not count as playback`() {
        val health = PlaybackHealth()
        listOf(100L, 100L, 0L).forEach { health.onPosition(it) }

        health.hasPlayedContent() shouldBe false
    }
}
