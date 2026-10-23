package eu.kanade.tachiyomi.ui.player

internal enum class PlaybackFailureReason {
    Resolution,
    Opening,
    NoContent,
    Stall,
}

internal enum class RecoveryDecision {
    Allowed,
    Duplicate,
    Stale,
    Exhausted,
    NoCandidate,
}

internal class PlaybackRecoveryPolicy {
    private var episode: Pair<Long, Long>? = null
    private var lastAttempt = 0L
    private var currentAttempt: Long? = null
    private var handled = false
    private var switches = 0
    private val reasonCounts = mutableMapOf<PlaybackFailureReason, Int>()

    val totalSwitches: Int
        @Synchronized get() = switches

    val countsByReason: Map<PlaybackFailureReason, Int>
        @Synchronized get() = reasonCounts.toMap()

    @Synchronized
    fun beginEpisode(animeId: Long, episodeId: Long): Boolean {
        val nextEpisode = animeId to episodeId
        if (episode == nextEpisode) return false
        episode = nextEpisode
        currentAttempt = null
        handled = false
        switches = 0
        reasonCounts.clear()
        return true
    }

    @Synchronized
    fun beginAttempt(): Long {
        lastAttempt += 1
        currentAttempt = lastAttempt
        handled = false
        return lastAttempt
    }

    @Synchronized
    fun reserve(
        attempt: Long,
        reason: PlaybackFailureReason,
        limit: Int,
        hasCandidate: Boolean,
    ): RecoveryDecision {
        if (attempt != currentAttempt) return RecoveryDecision.Stale
        if (handled) return RecoveryDecision.Duplicate
        handled = true
        if (!hasCandidate) return RecoveryDecision.NoCandidate
        if (switches >= limit.coerceAtLeast(0)) return RecoveryDecision.Exhausted
        switches += 1
        reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
        return RecoveryDecision.Allowed
    }

    @Synchronized
    fun remaining(limit: Int): Int = (limit.coerceAtLeast(0) - switches).coerceAtLeast(0)
}
