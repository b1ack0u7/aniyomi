package eu.kanade.tachiyomi.ui.player

import kotlin.math.roundToInt

internal fun shouldAutoEnterPip(enabled: Boolean, onExit: Boolean, paused: Boolean?): Boolean =
    enabled && onExit && paused == false

internal data class PipVideoSize(val width: Int, val height: Int)

internal fun pipVideoSize(height: Int?, aspect: Double?): PipVideoSize? {
    if (height == null || height <= 0 || aspect == null || !aspect.isFinite()) return null
    val width = height * aspect
    if (width < 1.0 || width > Int.MAX_VALUE.toDouble()) return null
    val size = PipVideoSize(width.roundToInt(), height)
    val ratio = size.width.toDouble() / size.height
    return size.takeIf { ratio in (1.0 / 2.39)..2.39 }
}

internal fun videoSurfaceAspect(width: Int, height: Int): Double =
    if (width > 0 && height > 0) width.toDouble() / height else -1.0
