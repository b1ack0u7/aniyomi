package eu.kanade.tachiyomi.data.download.manga

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.IOException

class MangaDownloadLimiterTest {

    @Test
    fun `request pacing is cancellable and does not hold another source`() = runTest {
        val fixture = Fixture()
        val limiter = fixture.limiter()
        limiter.beforeRequest(1)

        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { limiter.beforeRequest(1) }
        waiting.isCompleted shouldBe false
        limiter.beforeRequest(2)
        waiting.cancelAndJoin()
        waiting.isCancelled shouldBe true
    }

    @Test
    fun `chapter pacing and batch counters are shared by source and survive recreation`() {
        val fixture = Fixture()
        var limiter = fixture.limiter()

        repeat(4) {
            limiter.completed(1)
            limiter.deadline(1) shouldBe fixture.now + 10_000
            fixture.now = limiter.deadline(1)
            limiter = fixture.limiter()
        }
        limiter.completed(1)
        limiter.deadline(1) shouldBe fixture.now + 120_000
        limiter.ready(1) shouldBe false
        limiter.ready(2) shouldBe true

        fixture.now = limiter.deadline(1)
        limiter.ready(1) shouldBe true
        limiter.completed(1)
        limiter.deadline(1) shouldBe fixture.now + 10_000
    }

    @Test
    fun `rate limit honors server delay and successive failures increase the backoff`() {
        val fixture = Fixture()
        val limiter = fixture.limiter()

        limiter.defer(1, HttpException(429, "600")) shouldBe true
        limiter.deadline(1) shouldBe fixture.now + 600_000
        limiter.defer(1, HttpException(429)) shouldBe true
        limiter.deadline(1) shouldBe fixture.now + 900_000
        limiter.defer(1, IOException("Network error"), networkFailure = true) shouldBe true
        limiter.deadline(1) shouldBe fixture.now + 1_800_000
        limiter.defer(1, HttpException(503)) shouldBe true
        limiter.deadline(1) shouldBe fixture.now + 3_600_000
        limiter.deadline(2) shouldBe 0L
    }

    @Test
    fun `three successful chapters restore normal pacing and reset the backoff`() {
        val fixture = Fixture()
        val limiter = fixture.limiter()
        limiter.defer(1, HttpException(429))

        repeat(3) {
            fixture.now = limiter.deadline(1)
            limiter.completed(1)
            limiter.deadline(1) shouldBe fixture.now + 30_000
        }
        fixture.now = limiter.deadline(1)
        limiter.completed(1)
        limiter.deadline(1) shouldBe fixture.now + 10_000
        limiter.defer(1, HttpException(429)) shouldBe true
        limiter.deadline(1) shouldBe fixture.now + 300_000
    }

    @Test
    fun `cloudflare challenge persists until explicitly resumed`() {
        val fixture = Fixture()
        val limiter = fixture.limiter()

        limiter.defer(1, IOException("Wrapped", CloudflareChallengeException("Challenge"))) shouldBe true
        fixture.now += 86_400_000
        val restored = fixture.limiter()
        restored.blocked(1) shouldBe true
        restored.ready(1) shouldBe false
        restored.ready(2) shouldBe true

        restored.resumeChallenges()
        restored.blocked(1) shouldBe false
        restored.ready(1) shouldBe true
    }

    @Test
    fun `permanent errors are not converted into retry loops`() {
        val fixture = Fixture()
        val limiter = fixture.limiter()

        listOf(
            HttpException(403),
            HttpException(404),
            IOException("Disk full"),
            IllegalArgumentException("Invalid page"),
        ).forEach {
            limiter.defer(1, it) shouldBe false
            limiter.deadline(1) shouldBe 0L
        }
        limiter.defer(1, SourceDownloadWaiting()) shouldBe true
        limiter.deadline(1) shouldBe 0L
    }

    @Test
    fun `oversized retry after does not overflow deadline`() {
        val fixture = Fixture()
        val limiter = fixture.limiter()

        limiter.defer(1, HttpException(429, Long.MAX_VALUE.toString())) shouldBe true
        limiter.deadline(1) shouldBe Long.MAX_VALUE
        limiter.ready(1) shouldBe false
    }

    @Test
    fun `disabled smart downloads ignore stored cooldowns and challenges`() {
        val fixture = Fixture()
        val limiter = fixture.limiter()
        limiter.defer(1, HttpException(429))
        limiter.defer(1, CloudflareChallengeException("Challenge"))

        fixture.enabled = false
        limiter.ready(1) shouldBe true
        limiter.defer(1, HttpException(429)) shouldBe false
        val persisted = fixture.values.toMap()
        limiter.completed(1)
        fixture.values shouldBe persisted
    }

    @Test
    fun `chapter success and download retry preserve a longer library cooldown`() {
        val fixture = Fixture()
        val limiter = fixture.limiter()
        val libraryDeadline = fixture.now + 3_600_000
        fixture.values["until_1"] = libraryDeadline

        limiter.completed(1)
        limiter.deadline(1) shouldBe libraryDeadline

        limiter.defer(1, HttpException(503)) shouldBe true
        limiter.deadline(1) shouldBe libraryDeadline
    }

    @Test
    fun `request pacing rechecks a challenge imposed while waiting`() = runTest {
        val fixture = Fixture()
        val limiter = fixture.limiter()
        limiter.beforeRequest(1)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { limiter.beforeRequest(1) }.exceptionOrNull()
        }
        waiting.isCompleted shouldBe false

        fixture.values["challenge_1"] = true

        (waiting.await() is SourceDownloadWaiting) shouldBe true
    }

    private class Fixture {
        var now = 100_000L
        var enabled = true
        val values = mutableMapOf<String, Any>()
        private val store = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        private val preferenceStore = mockk<PreferenceStore>()
        private val preferences = DownloadPreferences(preferenceStore)

        init {
            every { store.all } answers { values.toMap() }
            every { store.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
            every { store.getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { store.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
            every { store.edit() } returns editor
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
            val enabledPreference = mockk<Preference<Boolean>>()
            every { enabledPreference.get() } answers { enabled }
            every { preferenceStore.getBoolean("smart_manga_downloads", true) } returns enabledPreference
            every { preferenceStore.getInt(any(), any()) } answers {
                val default = secondArg<Int>()
                mockk<Preference<Int>> { every { get() } returns default }
            }
        }

        fun limiter() = MangaDownloadLimiter(store, preferences) { now }
    }
}
