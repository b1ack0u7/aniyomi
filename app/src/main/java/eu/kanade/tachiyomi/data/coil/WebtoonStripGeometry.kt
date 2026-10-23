package eu.kanade.tachiyomi.data.coil

import kotlin.math.sqrt

internal class WebtoonStripGeometry(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    viewWidth: Int,
    initialSampleSize: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(initialSampleSize > 0 && Integer.bitCount(initialSampleSize) == 1)
    }

    private val budgetWidth = sqrt(MAX_PAGE_BYTES.toDouble() / BYTES_PER_PIXEL * sourceWidth / sourceHeight).toInt()

    // The budget is soft: an unzoomed page should retain at least the viewport's resolution.
    val width = maxOf(
        viewWidth.coerceIn(1, sourceWidth),
        minOf((sourceWidth / initialSampleSize).coerceAtLeast(1), budgetWidth),
    ).coerceAtMost(MAX_STRIP_DIMENSION)

    val height = scaledRow(sourceHeight)

    val sampleSize = Integer.highestOneBit(minOf(sourceWidth / width, sourceHeight))

    private val maxStripRows = minOf(MAX_STRIP_DIMENSION, STRIP_BYTES / (width * BYTES_PER_PIXEL))

    // Leave a row for a final source fragment shorter than the native sample size.
    private val bandRows = (((maxStripRows - 1L) * sourceWidth / width) / sampleSize * sampleSize)
        .coerceIn(sampleSize.toLong(), sourceHeight.toLong())
        .toInt()

    fun bands(): Sequence<Band> = sequence {
        var top = 0
        while (top < sourceHeight) {
            var bottom = minOf(top.toLong() + bandRows, sourceHeight.toLong()).toInt()
            if (sourceHeight - bottom < sampleSize || scaledRow(bottom) == height) {
                bottom = sourceHeight
            }
            yield(Band(top, bottom, scaledRow(bottom) - scaledRow(top)))
            top = bottom
        }
    }

    private fun scaledRow(row: Int): Int = ((row.toLong() * width + sourceWidth - 1) / sourceWidth).toInt()

    data class Band(val sourceTop: Int, val sourceBottom: Int, val height: Int)

    companion object {
        const val BYTES_PER_PIXEL = 4
        const val STRIP_BYTES = 4 * 1024 * 1024
        const val MAX_PAGE_BYTES = 128 * 1024 * 1024
        const val MAX_STRIP_DIMENSION = 4096
    }
}
