package eu.kanade.tachiyomi.ui.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExternalPlaybackProgressTest {

    @Test
    fun `returning without playback extras preserves existing progress`() {
        progress(previousDuration = 120L) shouldBe null
        progress("position" to 0, "duration" to 0) shouldBe null
        progress("position" to 0, "duration" to 0, previousDuration = 120L) shouldBe null
        progress("duration" to 120L, previousDuration = 120L) shouldBe null
    }

    @Test
    fun `players can report integer or long times with either extra convention`() {
        listOf(45, 45L).forEach { position ->
            listOf(120, 120L).forEach { duration ->
                listOf("", "extra_").forEach { prefix ->
                    progress("${prefix}position" to position, "${prefix}duration" to duration) shouldBe
                        ExternalPlaybackProgress(45L, 120L)
                }
                progress("end_by" to "user", "position" to position, "duration" to duration) shouldBe
                    ExternalPlaybackProgress(45L, 120L)
            }
        }
    }

    @Test
    fun `leaving playback does not imply completion and keeps a valid resume position`() {
        progress("end_by" to "user", "position" to 45, "duration" to 120) shouldBe
            ExternalPlaybackProgress(45L, 120L)
        progress("end_by" to "user", "position" to 0, "duration" to 0, previousDuration = 120L) shouldBe null
        progress("end_by" to "user", previousDuration = 120L) shouldBe null
    }

    @Test
    fun `explicit completion uses reported duration or the known episode duration`() {
        progress("end_by" to "playback_completion", "duration" to 180L, previousDuration = 120L) shouldBe
            ExternalPlaybackProgress(180L, 180L)
        progress("end_by" to "playback_completion", previousDuration = 120L) shouldBe
            ExternalPlaybackProgress(120L, 120L)
        progress("end_by" to "playback_completion") shouldBe null
    }

    @Test
    fun `missing duration can preserve valid progress with a previously known duration`() {
        progress("position" to 45L, previousDuration = 120L) shouldBe ExternalPlaybackProgress(45L, 120L)
        progress("position" to 45L) shouldBe null
    }

    @Test
    fun `invalid position values do not reset stored progress`() {
        listOf(-1, -1L, "45", 45.0, null).forEach { position ->
            progress("position" to position, "duration" to 120L) shouldBe null
        }
        progress("position" to 0, "duration" to 120L) shouldBe ExternalPlaybackProgress(0L, 120L)
    }

    private fun progress(vararg extras: Pair<String, Any?>, previousDuration: Long = 0L): ExternalPlaybackProgress? {
        val values = mapOf(*extras)
        return externalPlaybackProgress(previousDuration, values::get)
    }
}
