package eu.kanade.tachiyomi.ui.player

internal enum class PlaybackWaitPhase {
    Opening,
    Filling,
    Seeking,
    Stalled,
    Paused,
    Playing,
}

internal data class PlaybackWaitSample(
    val opening: Boolean,
    val buffering: Boolean,
    val seeking: Boolean,
    val paused: Boolean,
    val positionSeconds: Double?,
    val cacheSeconds: Double?,
    val cacheBytes: Long?,
)

internal data class PlaybackWaitDecision(
    val phase: PlaybackWaitPhase,
    val continuousStallMs: Long,
    val cumulativeStallMs: Long,
    val remainingMs: Long,
    val failed: Boolean,
)

internal class PlaybackWaitState {
    private var startedAtMs = 0L
    private var lastUpdateMs = 0L
    private var waitStartedAtMs = 0L
    private var healthyStartedAtMs: Long? = null
    private var healthyProgressAtMs: Long? = null
    private var healthyPositionSeconds: Double? = null
    private var kind: WaitKind? = null
    private var highCacheSeconds: Double? = null
    private var highCacheBytes: Long? = null
    private var hasCacheGrowth = false
    private var continuousMs = 0L
    private var cumulativeMs = 0L

    fun reset(nowMs: Long) {
        startedAtMs = nowMs.coerceAtLeast(0)
        lastUpdateMs = startedAtMs
        waitStartedAtMs = startedAtMs
        clearHealthyObservation()
        kind = null
        clearCacheObservation()
        continuousMs = 0
        cumulativeMs = 0
    }

    fun update(
        nowMs: Long,
        sample: PlaybackWaitSample,
        timeoutMs: Long,
        bufferTargetSeconds: Double,
    ): PlaybackWaitDecision {
        val now = nowMs.coerceAtLeast(lastUpdateMs)
        val elapsed = now - lastUpdateMs
        lastUpdateMs = now
        val timeout = timeoutMs.coerceAtLeast(1)

        if (sample.paused) {
            kind = null
            clearHealthyObservation()
            continuousMs = 0
            clearCacheObservation()
            return decision(PlaybackWaitPhase.Paused, timeout, cumulativeEligible = false)
        }

        val nextKind = when {
            sample.seeking -> WaitKind.Seeking
            sample.opening -> WaitKind.Opening
            sample.buffering -> WaitKind.Buffering
            else -> null
        }
        if (nextKind == null) {
            observeHealthyProgress(now, elapsed, sample.positionSeconds)
            kind = null
            continuousMs = 0
            clearCacheObservation()
            return decision(PlaybackWaitPhase.Playing, timeout, cumulativeEligible = false)
        }

        clearHealthyObservation()
        val enteringWait = kind != nextKind
        if (enteringWait) {
            if (nextKind == WaitKind.Seeking) cumulativeMs = 0
            kind = nextKind
            waitStartedAtMs = now
            continuousMs = 0
            clearCacheObservation()
        }
        val grew = observeCache(sample)
        if (grew) hasCacheGrowth = true
        val delta = if (enteringWait) 0L else elapsed
        val grace = fillGraceMs(timeout, bufferTargetSeconds)
        val withinFillDeadline = now - waitStartedAtMs < grace
        // Segment gaps belong to the same fill; lack of growth still has a continuous timeout.
        val filling = bufferTargetSeconds.isFinite() &&
            bufferTargetSeconds > 0 &&
            hasCacheGrowth &&
            withinFillDeadline

        if (grew && withinFillDeadline) {
            continuousMs = 0
        } else {
            continuousMs = addSaturated(continuousMs, delta)
            if (nextKind == WaitKind.Buffering && !filling) {
                cumulativeMs = addSaturated(cumulativeMs, delta)
            }
        }

        val phase = when {
            nextKind == WaitKind.Seeking -> PlaybackWaitPhase.Seeking
            filling && continuousMs < timeout -> PlaybackWaitPhase.Filling
            nextKind == WaitKind.Opening -> PlaybackWaitPhase.Opening
            else -> PlaybackWaitPhase.Stalled
        }
        val cumulativeEligible = phase == PlaybackWaitPhase.Stalled && now - startedAtMs >= MIN_OBSERVATION_MS
        return decision(phase, timeout, cumulativeEligible)
    }

    private fun observeHealthyProgress(nowMs: Long, elapsedMs: Long, positionSeconds: Double?) {
        val position = positionSeconds?.takeIf { it.isFinite() && it >= 0 }
        val previous = healthyPositionSeconds
        healthyPositionSeconds = position
        if (position == null || previous == null) {
            healthyStartedAtMs = null
            healthyProgressAtMs = null
            return
        }

        val step = position - previous
        if (step < 0 || step > MAX_HEALTHY_STEP_SECONDS || elapsedMs > MAX_HEALTHY_GAP_MS) {
            healthyStartedAtMs = null
            healthyProgressAtMs = null
            return
        }
        val lastProgress = healthyProgressAtMs
        if (step > 0) {
            if (lastProgress == null || nowMs - lastProgress > MAX_HEALTHY_GAP_MS) {
                healthyStartedAtMs = nowMs - elapsedMs
            }
            healthyProgressAtMs = nowMs
        } else if (lastProgress == null || nowMs - lastProgress > MAX_HEALTHY_GAP_MS) {
            healthyStartedAtMs = null
            healthyProgressAtMs = null
        }

        healthyStartedAtMs?.let { since ->
            if (nowMs - since >= HEALTHY_RESET_MS) cumulativeMs = 0
        }
    }

    private fun clearHealthyObservation() {
        healthyStartedAtMs = null
        healthyProgressAtMs = null
        healthyPositionSeconds = null
    }

    private fun observeCache(sample: PlaybackWaitSample): Boolean {
        var grew = false
        sample.cacheSeconds?.takeIf { it.isFinite() && it >= 0 }?.let { seconds ->
            val high = highCacheSeconds
            if (high == null || seconds - high >= MIN_CACHE_GROWTH_SECONDS) {
                if (high != null) grew = true
                highCacheSeconds = seconds
            }
        }
        sample.cacheBytes?.takeIf { it >= 0 }?.let { bytes ->
            val high = highCacheBytes
            if (high == null || bytes >= high && bytes - high >= MIN_CACHE_GROWTH_BYTES) {
                if (high != null) grew = true
                highCacheBytes = bytes
            }
        }
        return grew
    }

    private fun clearCacheObservation() {
        highCacheSeconds = null
        highCacheBytes = null
        hasCacheGrowth = false
    }

    private fun decision(
        phase: PlaybackWaitPhase,
        timeoutMs: Long,
        cumulativeEligible: Boolean,
    ): PlaybackWaitDecision {
        val charged = maxOf(continuousMs, if (cumulativeEligible) cumulativeMs else 0)
        return PlaybackWaitDecision(
            phase = phase,
            continuousStallMs = continuousMs,
            cumulativeStallMs = cumulativeMs,
            remainingMs = timeoutMs - charged.coerceAtMost(timeoutMs),
            failed = charged >= timeoutMs,
        )
    }

    private fun fillGraceMs(timeoutMs: Long, targetSeconds: Double): Long {
        val targetGrace = if (targetSeconds.isFinite() && targetSeconds > 0) {
            (targetSeconds * 3000).coerceAtMost(MAX_FILL_GRACE_MS.toDouble()).toLong()
        } else {
            DEFAULT_FILL_GRACE_MS
        }
        return maxOf(timeoutMs, targetGrace).coerceAtMost(MAX_FILL_GRACE_MS)
    }

    private fun addSaturated(value: Long, increment: Long): Long =
        if (increment > Long.MAX_VALUE - value) Long.MAX_VALUE else value + increment

    private enum class WaitKind { Opening, Buffering, Seeking }

    private companion object {
        const val MIN_CACHE_GROWTH_SECONDS = 0.05
        const val MIN_CACHE_GROWTH_BYTES = 1024L
        const val MIN_OBSERVATION_MS = 5000L
        const val HEALTHY_RESET_MS = 30000L
        const val MAX_HEALTHY_GAP_MS = 3000L
        const val MAX_HEALTHY_STEP_SECONDS = 10.0
        const val DEFAULT_FILL_GRACE_MS = 30000L
        const val MAX_FILL_GRACE_MS = 120000L
    }
}

internal fun playbackStallTimeoutMs(enabled: Boolean, seconds: Int): Long? =
    if (enabled && seconds > 0) seconds.coerceIn(5, 60) * 1000L else null
