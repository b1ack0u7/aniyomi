package eu.kanade.tachiyomi.ui.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VideoFilterChainTest {

    @Test
    fun `software debanding and format conversion coexist in order`() {
        buildVideoFilterChain(Debanding.CPU, useYuv420p = true, tryHWDecoding = false) shouldBe
            "gradfun=radius=12,format=yuv420p"
    }

    @Test
    fun `software filter combinations retain each requested operation`() {
        for (debanding in Debanding.entries) {
            for (useYuv420p in listOf(false, true)) {
                val expected = when (debanding) {
                    Debanding.CPU -> if (useYuv420p) "gradfun=radius=12,format=yuv420p" else "gradfun=radius=12"
                    Debanding.None, Debanding.GPU -> if (useYuv420p) "format=yuv420p" else null
                }
                buildVideoFilterChain(debanding, useYuv420p, tryHWDecoding = false) shouldBe expected
                buildVideoFilterHwdec(debanding, tryHWDecoding = false) shouldBe "no"
            }
        }
    }

    @Test
    fun `hardware decoding never enables yuv420p regardless of debanding`() {
        for (debanding in Debanding.entries) {
            for (useYuv420p in listOf(false, true)) {
                buildVideoFilterChain(debanding, useYuv420p, tryHWDecoding = true) shouldBe
                    if (debanding == Debanding.CPU) "gradfun=radius=12" else null
            }
        }
    }

    @Test
    fun `only explicit cpu debanding requests hardware copies`() {
        buildVideoFilterHwdec(Debanding.CPU, tryHWDecoding = true) shouldBe "auto-copy"
        buildVideoFilterHwdec(Debanding.None, tryHWDecoding = true) shouldBe "auto"
        buildVideoFilterHwdec(Debanding.GPU, tryHWDecoding = true) shouldBe "auto"
    }
}
