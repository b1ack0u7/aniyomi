package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import kotlin.math.roundToInt

/** Draws only visible strips, avoiding a single oversized GPU texture for tall webtoon pages. */
class WebtoonStripImageView(context: Context) : View(context) {

    private var image: TachiyomiImageDecoder.StripsImage? = null
    private var imageWidth = 0
    private var imageHeight = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clipRect = Rect()
    private val destination = Rect()

    var onReload: (() -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    fun setImage(image: TachiyomiImageDecoder.StripsImage) {
        releaseImage()
        this.image = image
        imageWidth = image.width
        imageHeight = image.height
        requestLayout()
        invalidate()
    }

    fun recycle() {
        releaseImage()
        onReload = null
        onRelease = null
        imageWidth = 0
        imageHeight = 0
        requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        onRelease?.invoke()
        if (image?.strips?.sumOf { it.allocationByteCount.toLong() }?.let { it > LARGE_PAGE_BYTES } == true) {
            // Keep the geometry while detached so decoding again cannot move the reader's scroll position.
            releaseImage()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (image == null) onReload?.invoke()
    }

    private fun releaseImage() {
        image?.recycle()
        image = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (imageWidth > 0) {
            (width.toDouble() * imageHeight / imageWidth).roundToInt().coerceAtLeast(1)
        } else {
            0
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val image = image ?: return
        if (!canvas.getClipBounds(clipRect)) return

        val scale = width.toDouble() / imageWidth
        var sourceTop = 0
        for (strip in image.strips) {
            val top = (sourceTop * scale).roundToInt()
            sourceTop += strip.height
            val bottom = (sourceTop * scale).roundToInt()
            if (bottom <= clipRect.top) continue
            if (top >= clipRect.bottom) break
            destination.set(0, top, width, bottom)
            canvas.drawBitmap(strip, null, destination, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

private const val LARGE_PAGE_BYTES = 64L * 1024 * 1024
