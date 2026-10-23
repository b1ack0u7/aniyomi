package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class StripImageOwnerTest {

    @Test
    fun `cancellation releases a decoded result that Coil has not delivered`() {
        val (image, bitmap) = image()
        val owner = StripImageOwner()
        owner.offer(image)

        owner.cancel()
        owner.cancel()

        verify(exactly = 1) { bitmap.recycle() }
        owner.take(image) shouldBe false
    }

    @Test
    fun `a decode completing after cancellation is released immediately`() {
        val (image, bitmap) = image()
        val owner = StripImageOwner()
        owner.cancel()

        owner.offer(image)

        verify(exactly = 1) { bitmap.recycle() }
        owner.take(image) shouldBe false
    }

    @Test
    fun `a delivered result belongs to the view when the request is cancelled`() {
        val (image, bitmap) = image()
        val owner = StripImageOwner()
        owner.offer(image)

        owner.take(image) shouldBe true
        owner.cancel()

        verify(exactly = 0) { bitmap.recycle() }
        owner.take(image) shouldBe false
        image.recycle()
    }

    @Test
    fun `a mismatched delivery cannot take another request's result`() {
        val (image, bitmap) = image()
        val (other, otherBitmap) = image()
        val owner = StripImageOwner()
        owner.offer(image)

        owner.take(other) shouldBe false
        owner.cancel()

        verify(exactly = 1) { bitmap.recycle() }
        verify(exactly = 0) { otherBitmap.recycle() }
        other.recycle()
    }

    private fun image(): Pair<TachiyomiImageDecoder.StripsImage, Bitmap> {
        var recycled = false
        val bitmap = mockk<Bitmap> {
            every { width } returns 1
            every { height } returns 1
            every { allocationByteCount } returns 4
            every { isRecycled } answers { recycled }
            every { recycle() } answers { recycled = true }
        }
        return TachiyomiImageDecoder.StripsImage(listOf(bitmap)) to bitmap
    }
}
