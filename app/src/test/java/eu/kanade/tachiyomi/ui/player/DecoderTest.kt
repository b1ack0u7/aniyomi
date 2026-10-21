package eu.kanade.tachiyomi.ui.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DecoderTest {

    @Test
    fun `known configured and active decoder values retain their mapping`() {
        getDecoderFromValue("auto-copy") shouldBe Decoder.AutoCopy
        getDecoderFromValue("auto") shouldBe Decoder.Auto
        getDecoderFromValue("no") shouldBe Decoder.SW
        getDecoderFromValue("mediacodec-copy") shouldBe Decoder.HW
        getDecoderFromValue("mediacodec") shouldBe Decoder.HWPlus
    }

    @Test
    fun `unknown configured or active decoder values fall back without throwing`() {
        listOf("auto-safe", "auto-copy-safe", "vaapi", "videotoolbox", "future-decoder", "", null).forEach {
            getDecoderFromValue(it) shouldBe Decoder.Auto
        }
    }
}
