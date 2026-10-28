package eu.kanade.tachiyomi.network

import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadLimiter
import eu.kanade.tachiyomi.data.library.LibraryUpdateType
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRequestCoordinatorTest {

    @Test
    fun `rate limit warning persists across recreation and retry never bypasses Retry After`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        var requests = 0
        runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {
                requests++
                throw HttpException(429, "600")
            }
        }
        val coordinator = fixture.newCoordinator()
        coordinator.mangaRestriction(1) shouldBe SourceAccessRestriction.RateLimited(600_000)
        fixture.resumeDownloads()
        advanceTimeBy(599_999)
        val denied = runCatching {
            coordinator.withRequest(LibraryUpdateType.MANGA, 1) { requests++ }
        }.exceptionOrNull() as SourceRequestPausedException
        denied.rateLimited shouldBe true
        denied.retryAt shouldBe 600_000
        requests shouldBe 1

        advanceTimeBy(1)
        coordinator.mangaRestriction(1)!!.canRetry(testScheduler.currentTime) shouldBe true
        requests shouldBe 1
        coordinator.withRequest(LibraryUpdateType.MANGA, 1) { requests++ }
        requests shouldBe 2
        coordinator.mangaRestriction(1) shouldBe null
    }

    @Test
    fun `download rate limit is visible to all manga using that source without affecting other sources`() {
        val fixture = Fixture { 0L }

        fixture.downloadFailure(HttpException(429, "600"))

        fixture.coordinator.mangaRestriction(1) shouldBe SourceAccessRestriction.RateLimited(600_000)
        fixture.coordinator.mangaRestriction(2) shouldBe null
    }

    @Test
    fun `rate limit warning honors HTTP date Retry After headers`() = runTest {
        val now = 1_800_000_000_000L
        val fixture = Fixture { now }
        val deadline = now + 600_000
        val retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(deadline).atOffset(ZoneOffset.UTC),
        )

        runCatching { fixture.coordinator.withReaderRequest(1) { throw HttpException(429, retryAfter) } }

        fixture.coordinator.mangaRestriction(1) shouldBe SourceAccessRestriction.RateLimited(deadline)
        fixture.coordinator.mangaRestriction(1)!!.canRetry(deadline - 1) shouldBe false
    }

    @Test
    fun `combined warning waits for Retry After and expiry alone never releases access denial`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        runCatching { fixture.coordinator.withReaderRequest(1) { throw HttpException(403) } }
        fixture.downloadFailure(HttpException(429, "600"))

        fixture.coordinator.mangaRestriction(1) shouldBe SourceAccessRestriction.ForbiddenAndRateLimited(600_000)
        fixture.coordinator.resumeForbidden(LibraryUpdateType.MANGA, 1) shouldBe false
        fixture.coordinator.forbidden(LibraryUpdateType.MANGA, 1) shouldBe true
        val denied = runCatching { fixture.coordinator.withReaderRequest(1) {} }.exceptionOrNull()
        (denied as SourceRequestPausedException).rateLimited shouldBe true
        denied.forbidden shouldBe true
        denied.retryAt shouldBe 600_000

        advanceTimeBy(600_000)
        fixture.coordinator.forbidden(LibraryUpdateType.MANGA, 1) shouldBe true
        fixture.coordinator.mangaRestriction(1)!!.canRetry(testScheduler.currentTime) shouldBe true
        fixture.coordinator.resumeForbidden(LibraryUpdateType.MANGA, 1) shouldBe true
        fixture.coordinator.mangaRestriction(1) shouldBe SourceAccessRestriction.RateLimited(600_000)
        fixture.coordinator.withReaderRequest(1) {}
        fixture.coordinator.mangaRestriction(1) shouldBe null
    }

    @Test
    fun `a new rate limit rejects stale retry authorization without clearing the forbidden source`() = runTest {
        val fixture = Fixture { 0L }
        runCatching { fixture.coordinator.withReaderRequest(1) { throw HttpException(403) } }
        val previouslyDisplayed = fixture.coordinator.mangaRestriction(1)!!
        previouslyDisplayed.canRetry(0) shouldBe true

        fixture.downloadFailure(HttpException(429, "600"))

        fixture.coordinator.resumeForbidden(LibraryUpdateType.MANGA, 1) shouldBe false
        fixture.coordinator.forbidden(LibraryUpdateType.MANGA, 1) shouldBe true
        fixture.coordinator.mangaRestriction(1)!!.canRetry(0) shouldBe false
    }

    @Test
    fun `server and connection errors do not appear as rate limiting after an older rate limit expires`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.downloadFailure(HttpException(503))
        fixture.coordinator.mangaRestriction(1) shouldBe null
        fixture.downloadFailure(HttpException(429, "600"))
        advanceTimeBy(fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1))

        runCatching { fixture.coordinator.withReaderRequest(1) { throw HttpException(503) } }

        fixture.coordinator.mangaRestriction(1) shouldBe null
        val denied = runCatching { fixture.coordinator.withReaderRequest(1) {} }.exceptionOrNull()
        (denied as SourceRequestPausedException).rateLimited shouldBe false
    }

    @Test
    fun `restriction observer follows download pauses and recovery and unregisters when stopped`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        val restrictions = mutableListOf<SourceAccessRestriction?>()
        val observer = backgroundScope.launch {
            fixture.coordinator.observeMangaRestriction(1).toList(restrictions)
        }
        runCurrent()
        restrictions shouldBe listOf(null)

        fixture.downloadFailure(HttpException(429, "600"))
        runCurrent()
        restrictions.last() shouldBe SourceAccessRestriction.RateLimited(600_000)
        fixture.downloadFailure(HttpException(403))
        runCurrent()
        restrictions.last() shouldBe SourceAccessRestriction.ForbiddenAndRateLimited(600_000)
        fixture.coordinator.resumeForbidden(LibraryUpdateType.MANGA, 1) shouldBe false
        runCurrent()
        restrictions.last() shouldBe SourceAccessRestriction.ForbiddenAndRateLimited(600_000)
        advanceTimeBy(600_000)
        fixture.coordinator.resumeForbidden(LibraryUpdateType.MANGA, 1) shouldBe true
        runCurrent()
        restrictions.last() shouldBe SourceAccessRestriction.RateLimited(600_000)
        observer.cancel()
        runCurrent()
        fixture.manga.listenerCount shouldBe 0
    }

    @Test
    fun `disabling smart pacing preserves existing cooldowns and challenge pauses`() = runTest {
        val fixture = Fixture(smartDownloads = false) { testScheduler.currentTime }
        repeat(2) { fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {} }
        testScheduler.currentTime shouldBe 0L
        fixture.manga.values["until_1"] = 8_000L

        val cooldown = runCatching { fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {} }.exceptionOrNull()

        (cooldown as SourceRequestPausedException).retryAt shouldBe 8_000L
        fixture.manga.values["challenge_1"] = true
        val challenge = runCatching { fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {} }.exceptionOrNull()
        (challenge as SourceRequestPausedException).retryAt shouldBe null
        fixture.manga.values["until_1"] shouldBe 8_000L
    }

    @Test
    fun `persisted cloudflare pause never invokes requests or consumes retry state`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.manga.values["challenge_1"] = true
        var requests = 0

        val error = runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { requests++ }
        }.exceptionOrNull()

        (error as SourceRequestPausedException).retryAt shouldBe null
        SourceRequestCoordinator.isRetryable(error) shouldBe false
        requests shouldBe 0
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 0L
        fixture.manga.values["request_failures_1"] shouldBe null
    }

    @Test
    fun `a cloudflare response blocks later requests until explicit resume`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {
                throw IOException("wrapper", CloudflareChallengeException("challenge"))
            }
        }

        fixture.coordinator.blocked(LibraryUpdateType.MANGA, 1) shouldBe true
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 0L
        fixture.manga.values["request_failures_1"] shouldBe null
        fixture.coordinator.resumeChallenges(LibraryUpdateType.MANGA)
        fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { "resumed" } shouldBe "resumed"
    }

    @Test
    fun `persisted source pauses remain isolated between manga and anime`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.manga.values["challenge_1"] = true
        fixture.manga.values["until_2"] = 9_000L

        fixture.coordinator.withRequest(LibraryUpdateType.ANIME, 1) { "anime" } shouldBe "anime"
        fixture.coordinator.withRequest(LibraryUpdateType.ANIME, 2) { "anime" } shouldBe "anime"
        fixture.coordinator.resumeChallenges(LibraryUpdateType.ANIME)

        fixture.coordinator.blocked(LibraryUpdateType.MANGA, 1) shouldBe true
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 2) shouldBe 9_000L
    }

    @Test
    fun `retry after can exceed configured maximum and requests cannot run before its deadline`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { throw HttpException(429, "3600") }
        }
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 3_600_000L
        advanceTimeBy(3_599_999)
        var requests = 0

        val error = runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { requests++ }
        }.exceptionOrNull()

        (error as SourceRequestPausedException).retryAt shouldBe 3_600_000L
        requests shouldBe 0
        advanceTimeBy(1)
        fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { requests++ }
        requests shouldBe 1
    }

    @Test
    fun `a request failure cannot shorten a deadline written by an overlapping download`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }

        runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {
                fixture.manga.values["until_1"] = 900_000L
                throw HttpException(503)
            }
        }

        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 900_000L
    }

    @Test
    fun `same source requests serialize while other sources and library types proceed`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var downloadStarted = false
        var otherSourceStarted = false
        var animeStarted = false
        val library = launch {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val download = launch {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { downloadStarted = true }
        }
        launch { fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 2) { otherSourceStarted = true } }
        launch { fixture.coordinator.withRequest(LibraryUpdateType.ANIME, 1) { animeStarted = true } }
        runCurrent()

        downloadStarted shouldBe false
        otherSourceStarted shouldBe true
        animeStarted shouldBe true
        release.complete(Unit)
        library.join()
        advanceUntilIdle()
        download.join()
        downloadStarted shouldBe true
    }

    @Test
    fun `a cloudflare pause installed during pacing is checked again before the request`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {}
        var requests = 0
        var error: Throwable? = null
        val waiting = launch {
            error = runCatching {
                fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { requests++ }
            }.exceptionOrNull()
        }
        runCurrent()
        fixture.manga.values["challenge_1"] = true
        advanceUntilIdle()
        waiting.join()

        requests shouldBe 0
        (error as SourceRequestPausedException).retryAt shouldBe null
    }

    @Test
    fun `a later cooldown installed during pacing is checked again before the request`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {}
        var requests = 0
        var error: Throwable? = null
        val waiting = launch {
            error = runCatching {
                fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { requests++ }
            }.exceptionOrNull()
        }
        runCurrent()
        fixture.manga.values["until_1"] = 9_000L
        advanceUntilIdle()
        waiting.join()

        requests shouldBe 0
        (error as SourceRequestPausedException).retryAt shouldBe 9_000L
    }

    @Test
    fun `local IO parsing certificate and permanent HTTP failures do not create automatic retries`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        listOf(
            IOException("disk full"),
            IllegalStateException("invalid document"),
            SSLHandshakeException("untrusted certificate"),
            HttpException(400),
            HttpException(401),
            HttpException(403),
            HttpException(404),
        ).forEachIndexed { index, error ->
            val source = index.toLong()
            SourceRequestCoordinator.isRetryable(error) shouldBe false
            runCatching { fixture.coordinator.withRequest(LibraryUpdateType.MANGA, source) { throw error } }
            fixture.coordinator.deadline(LibraryUpdateType.MANGA, source) shouldBe 0L
            fixture.manga.values["request_failures_$source"] shouldBe null
        }
    }

    @Test
    fun `wrapped DNS and timeout failures retain their transient classification`() {
        SourceRequestCoordinator.isRetryable(IOException("wrapper", UnknownHostException("example.com"))) shouldBe true
        SourceRequestCoordinator.isRetryable(SocketTimeoutException("timeout")) shouldBe true
        SourceRequestCoordinator.isRetryable(HttpException(500)) shouldBe true
    }

    @Test
    fun `metadata recovery never changes completed download or batch counters`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.manga.values.putAll(
            mapOf("failures_1" to 2, "successes_1" to 1, "batch_1" to 4, "request_failures_1" to 1),
        )

        repeat(3) { fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {} }

        fixture.manga.values["failures_1"] shouldBe 2
        fixture.manga.values["successes_1"] shouldBe 1
        fixture.manga.values["batch_1"] shouldBe 4
        fixture.manga.values["request_failures_1"] shouldBe 0
        fixture.manga.values["request_successes_1"] shouldBe 3
    }

    @Test
    fun `metadata failure continues download backoff without changing its completed download state`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.manga.values["failures_1"] = 2

        runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { throw UnknownHostException("example.com") }
        }

        fixture.manga.values["request_failures_1"] shouldBe 3
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 300_000L
        fixture.manga.values["failures_1"] shouldBe 2
    }

    @Test
    fun `first forbidden response rejects already queued pages and chapters even without smart pacing`() = runTest {
        listOf(true, false).forEach { smart ->
            val fixture = Fixture(smart) { testScheduler.currentTime }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var networkRequests = 0
            val first = launch {
                runCatching {
                    fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) {
                        networkRequests++
                        entered.complete(Unit)
                        release.await()
                        throw HttpException(403)
                    }
                }
            }
            entered.await()
            val next = async {
                runCatching {
                    fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { networkRequests++ }
                }.exceptionOrNull()
            }
            runCurrent()
            release.complete(Unit)
            first.join()
            val paused = next.await() as SourceRequestPausedException
            paused.forbidden shouldBe true
            paused.message!!.contains("WebView") shouldBe false
            networkRequests shouldBe 1

            val restored = fixture.newCoordinator()
            restored.forbidden(LibraryUpdateType.MANGA, 1) shouldBe true
            restored.resumeChallenges(LibraryUpdateType.MANGA)
            restored.forbidden(LibraryUpdateType.MANGA, 1) shouldBe true
            val chapter = runCatching {
                restored.withReaderRequest(1) { networkRequests++ }
            }.exceptionOrNull() as SourceRequestPausedException
            chapter.forbidden shouldBe true
            networkRequests shouldBe 1
            fixture.resumeDownloads()
            restored.withReaderRequest(1) { networkRequests++ }
            networkRequests shouldBe 2
        }
    }

    @Test
    fun `reader rate limit is shared with queued download and honors retry after without pacing reads`() = runTest {
        val fixture = Fixture(false) { testScheduler.currentTime }
        repeat(3) { fixture.coordinator.withReaderRequest(1) {} }
        testScheduler.currentTime shouldBe 0L
        runCatching {
            fixture.coordinator.withReaderRequest(1) { throw HttpException(429, "600") }
        }
        var networkRequests = 0
        val download = runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { networkRequests++ }
        }.exceptionOrNull() as SourceRequestPausedException
        download.retryAt shouldBe 600_000L
        fixture.resumeDownloads()
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 600_000L
        advanceTimeBy(599_999)
        val reader = runCatching { fixture.coordinator.withReaderRequest(1) { networkRequests++ } }.exceptionOrNull()
        (reader as SourceRequestPausedException).retryAt shouldBe 600_000L
        networkRequests shouldBe 0
        advanceTimeBy(1)
        fixture.coordinator.withReaderRequest(1) { networkRequests++ }
        networkRequests shouldBe 1
    }

    @Test
    fun `late reader success cannot release a forbidden pause or shorten a rate limit`() = runTest {
        listOf(403, 429).forEach { code ->
            val fixture = Fixture { testScheduler.currentTime }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val older = async {
                fixture.coordinator.withReaderRequest(1) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            runCatching {
                fixture.coordinator.withReaderRequest(1) { throw HttpException(code, "600") }
            }
            val deadline = fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1)
            release.complete(Unit)
            older.await()

            fixture.coordinator.forbidden(LibraryUpdateType.MANGA, 1) shouldBe (code == 403)
            fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe deadline
            (
                runCatching { fixture.coordinator.withReaderRequest(1) {} }.exceptionOrNull()
                    is SourceRequestPausedException
                ) shouldBe true
        }
    }

    @Test
    fun `three older reader successes cannot reset recovery after a rate limit`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        val entered = List(3) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()
        val older = entered.map { admission ->
            async {
                fixture.coordinator.withReaderRequest(1) {
                    admission.complete(Unit)
                    release.await()
                }
            }
        }
        entered.forEach { it.await() }
        runCatching {
            fixture.coordinator.withReaderRequest(1) { throw HttpException(429, "120") }
        }
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 120_000L
        release.complete(Unit)
        older.forEach { it.await() }

        fixture.manga.values["request_failures_1"] shouldBe 1
        fixture.manga.values["request_successes_1"] shouldBe 0
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 120_000L
        advanceTimeBy(120_000)
        runCatching {
            fixture.coordinator.withReaderRequest(1) { throw HttpException(429) }
        }
        fixture.manga.values["request_failures_1"] shouldBe 2
        fixture.manga.values["request_successes_1"] shouldBe 0
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 300_000L
    }

    @Test
    fun `cancelled failures do not pause a source or record retry state`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        listOf(403, 429).forEach { code ->
            val cancellation = CancellationException("Cancelled").apply { initCause(HttpException(code)) }
            runCatching { fixture.coordinator.withReaderRequest(1) { throw IOException("Wrapped", cancellation) } }
            fixture.coordinator.blocked(LibraryUpdateType.MANGA, 1) shouldBe false
            fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 0L
            fixture.manga.values["request_failures_1"] shouldBe null
        }
    }

    @Test
    fun `download rate limit protects reader and other sources and anime remain independent`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.MANGA, 1) { throw HttpException(429, "600") }
        }
        var networkRequests = 0
        val error = runCatching {
            fixture.coordinator.withReaderRequest(1) { networkRequests++ }
        }.exceptionOrNull() as SourceRequestPausedException
        error.retryAt shouldBe 600_000L
        networkRequests shouldBe 0
        fixture.coordinator.withReaderRequest(2) { "other source" } shouldBe "other source"
        fixture.coordinator.withRequest(LibraryUpdateType.ANIME, 1) { "anime" } shouldBe "anime"
        runCatching {
            fixture.coordinator.withRequest(LibraryUpdateType.ANIME, 3) { throw HttpException(403) }
        }
        fixture.coordinator.blocked(LibraryUpdateType.ANIME, 3) shouldBe false
    }

    @Test
    fun `normal chapter and batch rests do not delay reader but a download error does`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.manga.values["until_1"] = 900_000L
        fixture.coordinator.withReaderRequest(1) { "cached queue rest" } shouldBe "cached queue rest"

        fixture.downloadFailure(HttpException(429, "600"))

        val error = runCatching { fixture.coordinator.withReaderRequest(1) {} }.exceptionOrNull()
        (error as SourceRequestPausedException).retryAt shouldBe 600_000L
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 900_000L
        advanceTimeBy(600_000)
        fixture.coordinator.withReaderRequest(1) { "reader resumed" } shouldBe "reader resumed"
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 900_000L
    }

    @Test
    fun `legacy error cooldown is still respected by reader until its existing deadline`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.manga.values["until_1"] = 600_000L
        fixture.manga.values["request_failures_1"] = 1

        val error = runCatching { fixture.coordinator.withReaderRequest(1) {} }.exceptionOrNull()
        (error as SourceRequestPausedException).retryAt shouldBe 600_000L
    }

    @Test
    fun `resuming a reader source needs no download and does not release another source`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.manga.values["forbidden_1"] = true
        fixture.manga.values["forbidden_2"] = true
        fixture.manga.values["resume"] = false
        var requests = 0
        runCatching { fixture.coordinator.withReaderRequest(1) { requests++ } }
        requests shouldBe 0

        fixture.coordinator.resumeForbidden(LibraryUpdateType.MANGA, 1)

        fixture.coordinator.withReaderRequest(1) { requests++ }
        requests shouldBe 1
        fixture.coordinator.forbidden(LibraryUpdateType.MANGA, 2) shouldBe true
        fixture.manga.values["resume"] shouldBe false
    }

    @Test
    fun `explicit access recovery waits for cooldown and preserves the cloudflare challenge`() = runTest {
        val fixture = Fixture { testScheduler.currentTime }
        fixture.manga.values.putAll(
            mapOf(
                "forbidden_1" to true,
                "challenge_1" to true,
                "until_1" to 900_000L,
                "error_until_1" to 600_000L,
                "request_failures_1" to 2,
            ),
        )

        fixture.coordinator.resumeForbidden(LibraryUpdateType.MANGA, 1) shouldBe false

        fixture.coordinator.forbidden(LibraryUpdateType.MANGA, 1) shouldBe true
        fixture.coordinator.blocked(LibraryUpdateType.MANGA, 1) shouldBe true
        fixture.manga.values["challenge_1"] shouldBe true
        fixture.coordinator.deadline(LibraryUpdateType.MANGA, 1) shouldBe 900_000L
        fixture.manga.values["error_until_1"] shouldBe 600_000L
        fixture.manga.values["request_failures_1"] shouldBe 2
        advanceTimeBy(600_000)
        fixture.coordinator.resumeForbidden(LibraryUpdateType.MANGA, 1) shouldBe true
        fixture.coordinator.forbidden(LibraryUpdateType.MANGA, 1) shouldBe false
        fixture.manga.values["challenge_1"] shouldBe true
        val error = runCatching { fixture.coordinator.withReaderRequest(1) {} }.exceptionOrNull()
        (error as SourceRequestPausedException).retryAt shouldBe null
    }

    private class Fixture(smartDownloads: Boolean = true, private val now: () -> Long) {
        val manga = MemoryPreferences()
        private val anime = MemoryPreferences()
        private val preferences = mockk<DownloadPreferences> {
            every { smartMangaDownloads() } returns mockk { every { get() } returns smartDownloads }
            every { smartMangaRequestIntervalMs() } returns intPreference(500)
            every { smartMangaChapterDelaySeconds() } returns intPreference(10)
            every { smartMangaBatchSize() } returns intPreference(5)
            every { smartMangaBatchDelaySeconds() } returns intPreference(120)
            every { smartMangaRetryInitialSeconds() } returns intPreference(60)
            every { smartMangaRetryMaxSeconds() } returns intPreference(300)
        }
        val coordinator = SourceRequestCoordinator(manga.preferences, anime.preferences, preferences, now)

        fun newCoordinator() = SourceRequestCoordinator(manga.preferences, anime.preferences, preferences, now)

        fun resumeDownloads() = MangaDownloadLimiter(manga.preferences, preferences, now).resumeChallenges()

        fun downloadFailure(error: Throwable) = MangaDownloadLimiter(
            manga.preferences,
            preferences,
            now,
        ).defer(1, error)
    }

    private class MemoryPreferences {
        val values = mutableMapOf<String, Any>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val listenerCount get() = listeners.size
        val preferences = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>()

        init {
            every { preferences.all } answers { values.toMap() }
            every { preferences.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
            every { preferences.getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { preferences.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
            every { preferences.edit() } returns editor
            every { editor.putBoolean(any(), any()) } answers {
                values[firstArg()] = secondArg<Boolean>()
                editor
            }
            every { editor.putLong(any(), any()) } answers {
                values[firstArg()] = secondArg<Long>()
                editor
            }
            every { editor.putInt(any(), any()) } answers {
                values[firstArg()] = secondArg<Int>()
                editor
            }
            every { editor.remove(any()) } answers {
                values.remove(firstArg<String>())
                editor
            }
            every { preferences.registerOnSharedPreferenceChangeListener(any()) } answers {
                listeners += firstArg<SharedPreferences.OnSharedPreferenceChangeListener>()
            }
            every { preferences.unregisterOnSharedPreferenceChangeListener(any()) } answers {
                listeners -= firstArg<SharedPreferences.OnSharedPreferenceChangeListener>()
            }
            every { editor.apply() } answers {
                listeners.toList().forEach { it.onSharedPreferenceChanged(preferences, null) }
            }
            every { editor.commit() } returns true
        }
    }

    companion object {
        private fun intPreference(value: Int): Preference<Int> = mockk { every { get() } returns value }
    }
}
