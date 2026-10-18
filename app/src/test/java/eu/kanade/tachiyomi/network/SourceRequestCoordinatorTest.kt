package eu.kanade.tachiyomi.network

import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.library.LibraryUpdateType
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRequestCoordinatorTest {

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

    private class Fixture(smartDownloads: Boolean = true, now: () -> Long) {
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
    }

    private class MemoryPreferences {
        val values = mutableMapOf<String, Any>()
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
            every { editor.apply() } returns Unit
            every { editor.commit() } returns true
        }
    }

    companion object {
        private fun intPreference(value: Int): Preference<Int> = mockk { every { get() } returns value }
    }
}
