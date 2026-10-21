package eu.kanade.tachiyomi.ui.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlaybackPauseStateTest {

    @Test
    fun `first file starts playing and restoration is consumed once`() {
        val state = PlaybackPauseState()
        state.takeAfterLoad() shouldBe false
        state.takeAfterLoad() shouldBe null
    }

    @Test
    fun `keep-open pause at native EOF does not pause the fallback`() {
        val state = PlaybackPauseState()
        state.takeAfterLoad()
        state.observe(paused = true, eofReached = true)
        state.beginLoad()
        state.observe(paused = true, eofReached = true)
        state.takeAfterLoad() shouldBe false
    }

    @Test
    fun `manual pause before EOF is retained by the fallback`() {
        val state = PlaybackPauseState()
        state.takeAfterLoad()
        state.request(true)
        state.observe(paused = true, eofReached = true)
        state.beginLoad()
        state.takeAfterLoad() shouldBe true
    }

    @Test
    fun `temporary loading pauses and recursive candidate failures preserve intent`() {
        val state = PlaybackPauseState()
        state.takeAfterLoad()
        repeat(3) {
            state.beginLoad()
            state.observe(paused = true, eofReached = false)
        }
        state.takeAfterLoad() shouldBe false
    }

    @Test
    fun `manual pause or play during loading overrides the pending restoration`() {
        listOf(true, false).forEach { paused ->
            val state = PlaybackPauseState()
            state.request(!paused)
            state.beginLoad()
            state.request(paused)
            state.observe(paused = true, eofReached = false)
            state.takeAfterLoad() shouldBe paused
        }
    }

    @Test
    fun `direct mpv pause and play outside loading are remembered`() {
        val state = PlaybackPauseState()
        state.takeAfterLoad()
        state.observe(paused = true, eofReached = false)
        state.beginLoad()
        state.takeAfterLoad() shouldBe true
        state.observe(paused = false, eofReached = false)
        state.beginLoad()
        state.takeAfterLoad() shouldBe false
    }

    @Test
    fun `direct mpv play at EOF replaces a previous pause request`() {
        val state = PlaybackPauseState()
        state.takeAfterLoad()
        state.request(true)
        state.observe(paused = false, eofReached = true)
        state.beginLoad()
        state.takeAfterLoad() shouldBe false
    }

    @Test
    fun `a new episode starts playing after a paused previous episode`() {
        val state = PlaybackPauseState()
        state.takeAfterLoad()
        state.request(true)
        state.reset()
        state.observe(paused = true, eofReached = false)
        state.takeAfterLoad() shouldBe false
    }
}
