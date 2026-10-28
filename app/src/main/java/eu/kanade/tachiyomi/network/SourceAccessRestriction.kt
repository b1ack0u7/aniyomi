package eu.kanade.tachiyomi.network

sealed interface SourceAccessRestriction {
    val retryAt: Long?

    data class Forbidden(override val retryAt: Long? = null) : SourceAccessRestriction

    data class RateLimited(override val retryAt: Long) : SourceAccessRestriction

    data class ForbiddenAndRateLimited(override val retryAt: Long) : SourceAccessRestriction

    val forbidden: Boolean get() = this !is RateLimited

    val rateLimited: Boolean get() = this !is Forbidden

    fun remainingSeconds(now: Long): Long {
        val remaining = ((retryAt ?: return 0) - now).coerceAtLeast(0L)
        return remaining / 1_000 + if (remaining % 1_000 > 0) 1 else 0
    }

    fun canRetry(now: Long): Boolean = retryAt?.let { now >= it } != false
}
