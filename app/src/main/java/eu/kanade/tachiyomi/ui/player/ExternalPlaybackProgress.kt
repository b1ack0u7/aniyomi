package eu.kanade.tachiyomi.ui.player

internal data class ExternalPlaybackProgress(val position: Long, val duration: Long)

internal fun externalPlaybackProgress(
    previousDuration: Long,
    getExtra: (String) -> Any?,
): ExternalPlaybackProgress? {
    fun time(key: String): Long? = when (val value = getExtra(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0L }

    val cause = getExtra("end_by") as? String
    val useExtraPrefix = cause.isNullOrEmpty() && getExtra("extra_position") != null
    val position = time(if (useExtraPrefix) "extra_position" else "position")
    val duration = time(if (useExtraPrefix) "extra_duration" else "duration")
        ?.takeIf { it > 0L }
        ?: previousDuration.takeIf { it > 0L && (cause == "playback_completion" || (position ?: 0L) > 0L) }
        ?: return null

    return when {
        cause == "playback_completion" -> ExternalPlaybackProgress(duration, duration)
        position != null -> ExternalPlaybackProgress(position, duration)
        else -> null
    }
}
