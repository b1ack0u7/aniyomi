package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import android.graphics.Rect
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    class StripsImage(val strips: List<Bitmap>) : Image {
        override val width: Int = strips.firstOrNull()?.width ?: 0
        override val height: Int = strips.sumOf { it.height }
        override val size: Long = strips.sumOf { it.allocationByteCount.toLong() }
        override val shareable: Boolean = false

        override fun draw(canvas: Canvas) {
            val clip = canvas.clipBounds
            var top = 0
            for (strip in strips) {
                if (top > clip.bottom) break
                if (top + strip.height > clip.top && !strip.isRecycled) {
                    canvas.drawBitmap(strip, 0f, top.toFloat(), null)
                }
                top += strip.height
            }
        }

        fun recycle() {
            strips.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    override suspend fun decode(): DecodeResult {
        if (options.webtoonStrips) return decodeStrips()

        val decoder = resources.sourceOrNull()?.use {
            ImageDecoder.newInstance(it.inputStream(), options.cropBorders, displayProfile)
        }

        check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }

        val srcWidth = decoder.width
        val srcHeight = decoder.height

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )

        var bitmap = try {
            decoder.decode(sampleSize = sampleSize)
        } finally {
            decoder.recycle()
        }

        check(bitmap != null) { "Failed to decode image" }

        if (options.bitmapConfig == Bitmap.Config.HARDWARE && ImageUtil.canUseHardwareBitmap(bitmap)) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    private suspend fun decodeStrips(): DecodeResult = stripDecodes.withPermit {
        currentCoroutineContext().ensureActive()
        val decoder = resources.source().use {
            ImageDecoder.newInstance(it.inputStream(), options.cropBorders, displayProfile)
        }
        checkNotNull(decoder) { "Failed to initialize decoder" }

        val strips = mutableListOf<Bitmap>()
        try {
            currentCoroutineContext().ensureActive()
            check(decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }
            val viewWidth = options.size.widthPx(options.scale) { decoder.width }
            val initialSampleSize = DecodeUtils.calculateInSampleSize(
                srcWidth = decoder.width,
                srcHeight = decoder.height,
                dstWidth = viewWidth,
                dstHeight = options.size.heightPx(options.scale) { decoder.height },
                scale = options.scale,
            )
            val geometry = WebtoonStripGeometry(decoder.width, decoder.height, viewWidth, initialSampleSize)

            for (band in geometry.bands()) {
                currentCoroutineContext().ensureActive()
                val bitmap = checkNotNull(
                    decoder.decode(
                        region = Rect(0, band.sourceTop, decoder.width, band.sourceBottom),
                        sampleSize = geometry.sampleSize,
                    ),
                ) { "Failed to decode image strip" }
                try {
                    val scaled = Bitmap.createScaledBitmap(bitmap, geometry.width, band.height, true)
                    try {
                        // Immutable bitmaps keep offscreen bands from being uploaded together by the renderer.
                        strips += checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false)) {
                            "Failed to copy image strip"
                        }
                    } finally {
                        if (scaled !== bitmap) scaled.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            currentCoroutineContext().ensureActive()
            val image = StripsImage(strips)
            options.webtoonStripsOnDecoded?.invoke(image)
            DecodeResult(image = image, isSampled = geometry.width < decoder.width)
        } catch (error: Throwable) {
            strips.forEach(Bitmap::recycle)
            throw error
        } finally {
            decoder.recycle()
        }
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.customDecoder || options.webtoonStrips || isApplicable(result.source.source())) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null

        // The native decoder can retain a full frame even when only a region is requested.
        private val stripDecodes = Semaphore(1)
    }
}
