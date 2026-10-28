package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceAccessRestrictionTest {
    @Test
    fun `countdown rounds up and retry stays disabled until the actual deadline`() {
        val restriction = SourceAccessRestriction.RateLimited(60_000)

        restriction.remainingSeconds(0) shouldBe 60
        restriction.remainingSeconds(59_001) shouldBe 1
        restriction.canRetry(59_999) shouldBe false
        restriction.remainingSeconds(60_000) shouldBe 0
        restriction.canRetry(60_000) shouldBe true
        restriction.remainingSeconds(61_000) shouldBe 0
    }

    @Test
    fun `oversized server wait does not overflow the countdown`() {
        SourceAccessRestriction.RateLimited(Long.MAX_VALUE).remainingSeconds(0) shouldBe
            Long.MAX_VALUE / 1_000 + 1
    }

    @Test
    fun `combined restriction keeps both causes when the wait ends`() {
        val restriction = SourceAccessRestriction.ForbiddenAndRateLimited(60_000)

        restriction.forbidden shouldBe true
        restriction.rateLimited shouldBe true
        restriction.canRetry(59_999) shouldBe false
        restriction.remainingSeconds(59_999) shouldBe 1
        restriction.canRetry(60_000) shouldBe true
        restriction.remainingSeconds(60_000) shouldBe 0
        restriction.forbidden shouldBe true
    }

    @Test
    fun `access denial distinguishes an untimed pause from a generic error cooldown`() {
        SourceAccessRestriction.Forbidden().canRetry(0) shouldBe true
        SourceAccessRestriction.Forbidden(60_000).canRetry(59_999) shouldBe false
        SourceAccessRestriction.Forbidden(60_000).rateLimited shouldBe false
        SourceAccessRestriction.RateLimited(60_000).forbidden shouldBe false
    }
}
