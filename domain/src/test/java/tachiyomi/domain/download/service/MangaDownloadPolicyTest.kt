package tachiyomi.domain.download.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class MangaDownloadPolicyTest {

    @Test
    fun `default backoff increases and saturates without overflow`() {
        val policy = MangaDownloadPolicy()

        listOf(1, 2, 3, 4, 5, Int.MAX_VALUE).map(policy::retryDelayMillis) shouldBe
            listOf(300_000L, 900_000L, 1_800_000L, 3_600_000L, 3_600_000L, 3_600_000L)
        policy.retryDelayMillis(Int.MIN_VALUE) shouldBe 300_000L
    }

    @Test
    fun `invalid stored values are constrained to supported bounds`() {
        val lower = MangaDownloadPolicy(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        lower.requestIntervalMs shouldBe 500
        lower.chapterDelaySeconds shouldBe 1
        lower.batchSize shouldBe 1
        lower.batchDelaySeconds shouldBe 30

        val upper = MangaDownloadPolicy(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        upper.requestIntervalMs shouldBe 10_000
        upper.chapterDelaySeconds shouldBe 120
        upper.batchSize shouldBe 20
        upper.batchDelaySeconds shouldBe 1_800
    }

    @Test
    fun `retry ceiling cannot undercut initial delay`() {
        val policy = MangaDownloadPolicy(retryInitialSeconds = 1_800, retryMaxSeconds = 300)

        policy.retryMaxSeconds shouldBe 1_800
        policy.retryDelayMillis(1) shouldBe 1_800_000L
        policy.retryDelayMillis(2) shouldBe 1_800_000L
        MangaDownloadPolicy(retryInitialSeconds = -1).retryInitialSeconds shouldBe 60
        MangaDownloadPolicy(retryMaxSeconds = Int.MAX_VALUE).retryMaxSeconds shouldBe 86_400
    }

    @Test
    fun `recovery slows defaults while preserving a more conservative user setting`() {
        val normal = MangaDownloadPolicy()
        normal.requestDelayMillis(false) shouldBe 1_500L
        normal.chapterDelayMillis(false) shouldBe 10_000L
        normal.requestDelayMillis(true) shouldBe 3_000L
        normal.chapterDelayMillis(true) shouldBe 30_000L

        val conservative = MangaDownloadPolicy(requestIntervalMs = 5_000, chapterDelaySeconds = 60)
        conservative.requestDelayMillis(true) shouldBe 5_000L
        conservative.chapterDelayMillis(true) shouldBe 60_000L
    }

    @Test
    fun `retry after accepts seconds and saturates oversized server delays`() {
        MangaDownloadPolicy.parseRetryAfter(" 120 ", 0) shouldBe 120_000L
        MangaDownloadPolicy.parseRetryAfter("0", 0) shouldBe 0L
        MangaDownloadPolicy.parseRetryAfter(Long.MAX_VALUE.toString(), 0) shouldBe Long.MAX_VALUE
        MangaDownloadPolicy.parseRetryAfter("99999999999999999999999999999", 0) shouldBe Long.MAX_VALUE
    }

    @Test
    fun `retry after handles HTTP dates and elapsed deadlines`() {
        val now = Instant.parse("2015-10-21T07:27:00Z").toEpochMilli()
        val header = "Wed, 21 Oct 2015 07:28:00 GMT"

        MangaDownloadPolicy.parseRetryAfter(header, now) shouldBe 60_000L
        MangaDownloadPolicy.parseRetryAfter(header, now + 120_000) shouldBe 0L
        MangaDownloadPolicy.parseRetryAfter(header, Long.MIN_VALUE) shouldBe Long.MAX_VALUE
    }

    @Test
    fun `retry after rejects invalid input`() {
        listOf(null, "", " ", "-1", "+1", "1.5", "tomorrow", "Wed, 99 Oct 2015 07:28:00 GMT").forEach {
            MangaDownloadPolicy.parseRetryAfter(it, 0) shouldBe null
        }
    }
}
