package eu.kanade.tachiyomi.data.coil

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class WebtoonStripGeometryTest {

    @Test
    fun `tall page retains its aspect ratio and every source row`() {
        val geometry = WebtoonStripGeometry(1080, 30000, 1080, 1)

        geometry.width shouldBe 1080
        geometry.height shouldBe 30000
        geometry.sampleSize shouldBe 1
        verifyBands(geometry, 1080, 30000)
    }

    @Test
    fun `odd sampled dimensions do not accumulate a rounding gap between strips`() {
        val geometry = WebtoonStripGeometry(3001, 30003, 1000, 2)

        geometry.width shouldBe 1500
        geometry.height shouldBe 14997
        geometry.sampleSize shouldBe 2
        verifyBands(geometry, 3001, 30003)
    }

    @Test
    fun `last source fragment shorter than native sample joins the previous band`() {
        val geometry = WebtoonStripGeometry(8000, 8377, 1000, 8)
        val bands = geometry.bands().toList()

        geometry.sampleSize shouldBe 8
        bands.size shouldBe 1
        bands.single().sourceBottom shouldBe 8377
        bands.single().height shouldBe 1048
        verifyBands(geometry, 8000, 8377)
    }

    @Test
    fun `panoramic image does not sample its source height down to zero`() {
        val geometry = WebtoonStripGeometry(40000, 1, 1000, 32)

        geometry.width shouldBe 1250
        geometry.height shouldBe 1
        geometry.sampleSize shouldBe 1
        verifyBands(geometry, 40000, 1)
    }

    @Test
    fun `large viewport cannot produce a texture beyond older GPU limits`() {
        val geometry = WebtoonStripGeometry(16000, 8000, 6000, 1)

        geometry.width shouldBe 4096
        verifyBands(geometry, 16000, 8000)
    }

    @Test
    fun `memory budget limits zoom detail before reducing viewport resolution`() {
        val geometry = WebtoonStripGeometry(8000, 100000, 1080, 1)

        geometry.width shouldBe 1638
        (geometry.width.toLong() * geometry.height * 4 <= WebtoonStripGeometry.MAX_PAGE_BYTES) shouldBe true
        verifyBands(geometry, 8000, 100000)
    }

    @Test
    fun `extremely tall page keeps viewport sharp when the soft budget cannot be met`() {
        val geometry = WebtoonStripGeometry(8000, 1000000, 1080, 1)

        geometry.width shouldBe 1080
        (geometry.width.toLong() * geometry.height * 4 > WebtoonStripGeometry.MAX_PAGE_BYTES) shouldBe true
        verifyBands(geometry, 8000, 1000000)
    }

    @Test
    fun `native sample is refined when initial sampling would undershoot viewport`() {
        val geometry = WebtoonStripGeometry(8000, 20000, 1080, 8)

        geometry.width shouldBe 1080
        geometry.sampleSize shouldBe 4
        verifyBands(geometry, 8000, 20000)
    }

    @Test
    fun `smaller source is never enlarged to viewport width`() {
        val geometry = WebtoonStripGeometry(800, 12000, 2000, 1)

        geometry.width shouldBe 800
        geometry.sampleSize shouldBe 1
        verifyBands(geometry, 800, 12000)
    }

    @Test
    fun `source coordinates cannot overflow for maximum integer dimensions`() {
        val geometry = WebtoonStripGeometry(Int.MAX_VALUE, Int.MAX_VALUE, 1080, 1)

        geometry.width shouldBe 4096
        geometry.height shouldBe 4096
        verifyBands(geometry, Int.MAX_VALUE, Int.MAX_VALUE)
    }

    @Test
    fun `representative aspect ratios preserve coverage and nonzero bounded bands`() {
        val random = Random(2138)
        repeat(500) {
            val width = random.nextInt(1, 50001)
            val height = random.nextInt(1, 1000001)
            val geometry = WebtoonStripGeometry(width, height, random.nextInt(1, 8001), 1 shl random.nextInt(15))
            verifyBands(geometry, width, height)
        }
        verifyBands(WebtoonStripGeometry(1, 1000000, 1080, 1), 1, 1000000)
    }

    @Test
    fun `invalid source dimensions or non power of two samples are rejected`() {
        assertThrows<IllegalArgumentException> { WebtoonStripGeometry(0, 1000, 1080, 1) }
        assertThrows<IllegalArgumentException> { WebtoonStripGeometry(1000, 0, 1080, 1) }
        assertThrows<IllegalArgumentException> { WebtoonStripGeometry(1000, 1000, 1080, 0) }
        assertThrows<IllegalArgumentException> { WebtoonStripGeometry(1000, 1000, 1080, 3) }
    }

    private fun verifyBands(geometry: WebtoonStripGeometry, sourceWidth: Int, sourceHeight: Int) {
        val bands = geometry.bands().toList()
        var previousBottom = 0
        var height = 0L

        (geometry.width in 1..minOf(sourceWidth, 4096)) shouldBe true
        Integer.bitCount(geometry.sampleSize) shouldBe 1
        (sourceWidth / geometry.sampleSize > 0) shouldBe true

        for (band in bands) {
            band.sourceTop shouldBe previousBottom
            band.sourceTop % geometry.sampleSize shouldBe 0
            (band.sourceBottom in (band.sourceTop + 1)..sourceHeight) shouldBe true
            ((band.sourceBottom - band.sourceTop) / geometry.sampleSize > 0) shouldBe true
            (band.height in 1..4096) shouldBe true
            (band.height.toLong() * geometry.width * 4 <= WebtoonStripGeometry.STRIP_BYTES) shouldBe true
            height += band.height
            previousBottom = band.sourceBottom
        }

        previousBottom shouldBe sourceHeight
        height shouldBe geometry.height.toLong()
        geometry.height shouldBe ((sourceHeight.toLong() * geometry.width + sourceWidth - 1) / sourceWidth).toInt()
    }
}
