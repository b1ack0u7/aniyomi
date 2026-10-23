package eu.kanade.tachiyomi.data.track

import eu.kanade.domain.track.service.TrackPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class BaseTrackerRefreshTest {

    private val originalInjekt = Injekt
    private val preferences = mockk<TrackPreferences>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(preferences)
    }

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
    }

    @Test
    fun `display names stay private and do not overwrite authentication credentials`() {
        val values = mutableMapOf<String, String>()
        val store = mockk<PreferenceStore>(relaxed = true)
        every { store.getString(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<String>()
            mockk<Preference<String>> {
                every { get() } answers { values[key] ?: default }
                every { set(any()) } answers { values[key] = firstArg() }
            }
        }
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(TrackPreferences(store))
        val tracker = TestTracker {}

        tracker.saveCredentials("Account ID", "Secret")
        tracker.saveDisplayUsername("Profile nickname")

        tracker.getUsername() shouldBe "Account ID"
        tracker.getPassword() shouldBe "Secret"
        tracker.getDisplayUsername() shouldBe "Profile nickname"
        values.size shouldBe 3
        values.keys.all { Preference.isPrivate(it) } shouldBe true
    }

    @Test
    fun `successful refresh exposes loading until configuration has been saved`() = runTest {
        val release = CompletableDeferred<Unit>()
        val tracker = TestTracker { release.await() }
        var result: RefreshResult? = null
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            result = tracker.refreshResultFlow.first()
        }

        val refresh = launch { tracker.refreshUser() }
        runCurrent()
        tracker.isRefreshingFlow.value shouldBe true
        result shouldBe null

        release.complete(Unit)
        refresh.join()

        tracker.isRefreshingFlow.value shouldBe false
        runCurrent()
        result shouldBe RefreshResult.Success
    }

    @Test
    fun `overlapping refresh requests do not fetch the same profile twice`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val tracker = TestTracker {
            calls++
            release.await()
        }
        val first = launch { tracker.refreshUser() }
        runCurrent()

        tracker.refreshUser()

        calls shouldBe 1
        tracker.isRefreshingFlow.value shouldBe true
        release.complete(Unit)
        first.join()
        tracker.isRefreshingFlow.value shouldBe false
    }

    @Test
    fun `profile failure is reported and can be retried`() = runTest {
        var fail = true
        val tracker = TestTracker { if (fail) error("Remote service unavailable") }
        val results = mutableListOf<RefreshResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            tracker.refreshResultFlow.collect { results.add(it) }
        }

        tracker.refreshUser()
        runCurrent()
        tracker.isRefreshingFlow.value shouldBe false
        results shouldBe listOf(RefreshResult.Error("Remote service unavailable"))

        fail = false
        tracker.refreshUser()
        runCurrent()
        results shouldBe listOf(RefreshResult.Error("Remote service unavailable"), RefreshResult.Success)
    }

    @Test
    fun `cancellation is propagated without an error result and clears loading`() = runTest {
        val tracker = TestTracker { awaitCancellation() }
        val results = mutableListOf<RefreshResult>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            tracker.refreshResultFlow.collect { results.add(it) }
        }
        val refresh = launch { tracker.refreshUser() }
        runCurrent()
        tracker.isRefreshingFlow.value shouldBe true

        refresh.cancel()
        refresh.join()
        runCurrent()

        refresh.isCancelled shouldBe true
        tracker.isRefreshingFlow.value shouldBe false
        results shouldBe emptyList()
    }

    @Test
    fun `logout cancels remote work without cancelling the caller scope`() = runTest {
        val tracker = TestTracker { awaitCancellation() }
        every { preferences.setCredentials(tracker, "", "") } answers { tracker.loggedIn = false }
        val refresh = launch { tracker.refreshUser() }
        runCurrent()

        tracker.logout()
        refresh.join()

        refresh.isCancelled shouldBe true
        tracker.isRefreshingFlow.value shouldBe false
        tracker.isLoggedIn shouldBe false
    }

    @Test
    fun `late response after logout cannot write old profile data`() = runTest {
        val response = CompletableDeferred<Unit>()
        var stored = false
        val tracker = TestTracker {
            withContext(NonCancellable) { response.await() }
        }
        tracker.persist = { stored = true }
        every { preferences.setCredentials(tracker, "", "") } answers { tracker.loggedIn = false }
        val refresh = launch { tracker.refreshUser() }
        runCurrent()

        tracker.logout()
        response.complete(Unit)
        refresh.join()

        stored shouldBe false
        tracker.isLoggedIn shouldBe false
        tracker.isRefreshingFlow.value shouldBe false
    }

    @Test
    fun `new login cancels old profile before a late response can be stored`() = runTest {
        val response = CompletableDeferred<Unit>()
        var stored = false
        val tracker = TestTracker { withContext(NonCancellable) { response.await() } }
        tracker.persist = { stored = true }
        val refresh = launch { tracker.refreshUser() }
        runCurrent()

        tracker.saveCredentials("New user", "New secret")
        response.complete(Unit)
        refresh.join()

        stored shouldBe false
        tracker.isRefreshingFlow.value shouldBe false
    }

    @Test
    fun `logged out trackers do not request a profile`() = runTest {
        var calls = 0
        val tracker = TestTracker { calls++ }.apply { loggedIn = false }

        tracker.refreshUser()

        calls shouldBe 0
        tracker.isRefreshingFlow.value shouldBe false
    }

    @Test
    fun `hook cancellation does not become a user facing error`() = runTest {
        val tracker = TestTracker { throw CancellationException("Screen left") }
        var cancelled = false

        try {
            tracker.refreshUser()
        } catch (_: CancellationException) {
            cancelled = true
        }

        cancelled shouldBe true
        tracker.isRefreshingFlow.value shouldBe false
    }

    private class TestTracker(
        private val update: suspend () -> Unit,
    ) : BaseTracker(123, "Test") {

        var loggedIn = true
        override val isLoggedIn: Boolean get() = loggedIn

        var persist: () -> Unit = {}

        override suspend fun updateUserConfig() {
            update()
            saveUserConfig(persist)
        }
        override fun getLogo() = 0
        override fun getLogoColor() = 0
        override fun getCompletionStatus() = 0L
        override fun getScoreList() = persistentListOf<String>()
        override suspend fun login(username: String, password: String) = Unit
    }
}
