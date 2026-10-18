package tachiyomi.domain.download.service

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class MangaDownloadPolicy(
    requestIntervalMs: Int = 1_500,
    chapterDelaySeconds: Int = 10,
    batchSize: Int = 5,
    batchDelaySeconds: Int = 120,
    retryInitialSeconds: Int = 300,
    retryMaxSeconds: Int = 3_600,
) {
    val requestIntervalMs = requestIntervalMs.coerceIn(500, 10_000)
    val chapterDelaySeconds = chapterDelaySeconds.coerceIn(1, 120)
    val batchSize = batchSize.coerceIn(1, 20)
    val batchDelaySeconds = batchDelaySeconds.coerceIn(30, 1_800)
    val retryInitialSeconds = retryInitialSeconds.coerceIn(60, 1_800)
    val retryMaxSeconds = retryMaxSeconds.coerceIn(300, 86_400).coerceAtLeast(this.retryInitialSeconds)

    fun requestDelayMillis(recovering: Boolean): Long =
        requestIntervalMs.toLong().coerceAtLeast(if (recovering) 3_000L else 0L)

    fun chapterDelayMillis(recovering: Boolean): Long =
        (chapterDelaySeconds * 1_000L).coerceAtLeast(if (recovering) 30_000L else 0L)

    fun retryDelayMillis(failures: Int): Long {
        val multiplier = when {
            failures <= 1 -> 1L
            failures == 2 -> 3L
            else -> 6L shl (failures - 3).coerceAtMost(20)
        }
        return (retryInitialSeconds * multiplier).coerceAtMost(retryMaxSeconds.toLong()) * 1_000L
    }

    companion object {
        fun parseRetryAfter(value: String?, nowMillis: Long): Long? {
            val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (header.all { it in '0'..'9' }) {
                val seconds = header.toLongOrNull() ?: return Long.MAX_VALUE
                return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
            }
            return try {
                val retryAt = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
                if (retryAt <= nowMillis) {
                    0L
                } else if (nowMillis < 0 && retryAt > Long.MAX_VALUE + nowMillis) {
                    Long.MAX_VALUE
                } else {
                    retryAt - nowMillis
                }
            } catch (_: DateTimeParseException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
        }
    }
}
