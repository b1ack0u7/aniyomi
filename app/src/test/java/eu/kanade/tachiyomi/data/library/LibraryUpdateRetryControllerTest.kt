package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.SharedPreferences
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceRequestCoordinator
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.workManager
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.source.anime.model.AnimeSourceNotInstalledException
import tachiyomi.domain.source.manga.model.SourceNotInstalledException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class LibraryUpdateRetryControllerTest {

    @BeforeEach
    fun setUp() {
        mockkObject(LibraryUpdateRetryJob)
        every { LibraryUpdateRetryJob.schedule(any(), any(), any()) } just Runs
        every { LibraryUpdateRetryJob.cancel(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(LibraryUpdateRetryJob)
    }

    @Test
    fun `Cloudflare failures including wrapped pauses remove all retries for their source`() {
        val failures = listOf(
            CloudflareChallengeException("challenge"),
            IOException("wrapped", CloudflareChallengeException("challenge")),
            SourceRequestPausedException(),
            IllegalStateException("wrapped", SourceRequestPausedException()),
        )
        LibraryUpdateType.entries.forEach { type ->
            failures.forEach { failure ->
                val fixture = Fixture(type)
                val otherType = LibraryUpdateType.entries.single { it != type }
                fixture.store.recordFailure(type, 1, 10, 100)
                fixture.store.deferEntry(type, 2, 10, 100)
                fixture.store.recordFailure(type, 3, 20, 200)
                fixture.store.recordFailure(otherType, 4, 10, 300)

                fixture.controller.failed(1, 10, failure)
                fixture.controller.schedule()

                fixture.store.entries(type).map { it.entryId } shouldBe listOf(3L)
                fixture.store.entries(otherType).map { it.entryId } shouldBe listOf(4L)
                verify(exactly = 1) { LibraryUpdateRetryJob.schedule(fixture.context, type, 200) }
                verify(exactly = 0) { LibraryUpdateRetryJob.schedule(fixture.context, type, 100) }
            }
        }
    }

    @Test
    fun `timed source pauses defer requests without spending retries or shortening deadlines`() {
        LibraryUpdateType.entries.forEach { type ->
            val fixture = Fixture(type)
            fixture.store.recordFailure(type, 1, 10, 900)

            fixture.controller.failed(1, 10, SourceRequestPausedException(600))
            fixture.controller.failed(2, 10, SourceRequestPausedException(700))

            fixture.store.entries(type) shouldBe listOf(
                LibraryUpdateRetryStore.Entry(2, 10, 0, 700),
                LibraryUpdateRetryStore.Entry(1, 10, 1, 900),
            )
        }
    }

    @Test
    fun `transient failures retain the source deadline and stop after two retries`() {
        LibraryUpdateType.entries.forEach { type ->
            val fixture = Fixture(type)
            val deadline = System.currentTimeMillis() + 3_600_000L
            every { fixture.sourceRequests.deadline(type, 10) } returns deadline

            fixture.controller.failed(1, 10, UnknownHostException("DNS"))
            fixture.store.entries(type).single() shouldBe LibraryUpdateRetryStore.Entry(1, 10, 1, deadline)
            fixture.controller.failed(1, 10, SocketTimeoutException("timeout"))
            fixture.store.entries(type).single() shouldBe LibraryUpdateRetryStore.Entry(1, 10, 2, deadline)
            fixture.controller.failed(1, 10, HttpException(503))
            fixture.controller.schedule()

            fixture.store.entries(type) shouldBe emptyList()
            verify(exactly = 1) { LibraryUpdateRetryJob.cancel(fixture.context, type) }
            verify(exactly = 0) { LibraryUpdateRetryJob.schedule(fixture.context, type, any()) }
        }
    }

    @Test
    fun `local IO parsing missing extensions and permanent HTTP failures are not retried`() {
        val failures = listOf(
            IOException("local disk"),
            FileNotFoundException("local file"),
            IllegalArgumentException("invalid document"),
            HttpException(400),
            HttpException(404),
            SSLHandshakeException("certificate"),
            SourceNotInstalledException(),
            AnimeSourceNotInstalledException(),
        )
        LibraryUpdateType.entries.forEach { type ->
            failures.forEach { failure ->
                val fixture = Fixture(type)
                fixture.store.recordFailure(type, 1, 10, 100)
                fixture.store.recordFailure(type, 2, 10, 200)

                fixture.controller.failed(1, 10, failure)

                fixture.store.entries(type).map { it.entryId } shouldBe listOf(2L)
            }
        }
    }

    @Test
    fun `stopped generations cannot register retries or remove and rearm a newer run`() {
        LibraryUpdateType.entries.forEach { type ->
            val fixture = Fixture(type)
            fixture.store.recordFailure(type, 1, 10, 100)

            LibraryUpdateRetryController.cancel(fixture.context, type)
            fixture.store.entries(type) shouldBe emptyList()
            val current = fixture.newController()
            current.failed(2, 20, UnknownHostException("DNS"))
            val currentEntry = fixture.store.entries(type).single()
            fixture.controller.failed(3, 10, UnknownHostException("late DNS result"))
            fixture.controller.completed(2)
            fixture.controller.schedule()

            fixture.store.entries(type) shouldBe listOf(currentEntry)
            verify(exactly = 0) { LibraryUpdateRetryJob.schedule(fixture.context, type, any()) }
            current.schedule()
            verify(exactly = 1) { LibraryUpdateRetryJob.schedule(fixture.context, type, currentEntry.dueAt) }
        }
    }

    @Test
    fun `only expired backlog ids are selected and successes remove the matching title`() {
        LibraryUpdateType.entries.forEach { type ->
            val fixture = Fixture(type)
            fixture.store.recordFailure(type, 1, 10, 0)
            fixture.store.recordFailure(type, 2, 10, Long.MAX_VALUE)

            fixture.controller.dueIds() shouldBe setOf(1L)
            fixture.controller.completed(1)

            fixture.store.entries(type).map { it.entryId } shouldBe listOf(2L)
        }
    }

    @Test
    fun `real retry requests preserve device restrictions and use a marker for arbitrarily large backlogs`() {
        mockkStatic("eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt")
        try {
            val context = mockk<Context>()
            val manager = mockk<WorkManager>()
            val requests = mutableListOf<OneTimeWorkRequest>()
            every { context.workManager } returns manager
            every { manager.isRunning(any()) } returns false
            every { manager.enqueueUniqueWork(any(), any(), capture(requests)) } returns mockk()
            val manyIds = (1L..10_000L).toList()

            val restrictions = setOf(DEVICE_CHARGING, DEVICE_NETWORK_NOT_METERED)

            MangaLibraryUpdateJob.startRetry(context, manyIds, restrictions) shouldBe true
            AnimeLibraryUpdateJob.startRetry(context, manyIds, restrictions) shouldBe true

            requests.size shouldBe 2
            requests.forEach { request ->
                request.workSpec.constraints.requiredNetworkType shouldBe NetworkType.UNMETERED
                request.workSpec.constraints.requiresCharging() shouldBe true
                request.workSpec.constraints.requiresBatteryNotLow() shouldBe true
                request.workSpec.input.keyValueMap shouldBe mapOf("retry" to true)
            }
        } finally {
            unmockkStatic("eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt")
        }
    }

    private class Fixture(val type: LibraryUpdateType) {
        val context = mockk<Context>()
        val sourceRequests = mockk<SourceRequestCoordinator>()
        private val values = mutableMapOf<String, Set<String>>()
        private val preferences = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>()
        val store = LibraryUpdateRetryStore(preferences)
        val controller: LibraryUpdateRetryController

        init {
            every { context.getSharedPreferences("library_update_retries", Context.MODE_PRIVATE) } returns preferences
            every { preferences.getStringSet(any(), any()) } answers {
                (values[firstArg()] ?: secondArg<Set<String>>()).toMutableSet()
            }
            every { preferences.edit() } returns editor
            every { editor.putStringSet(any(), any()) } answers {
                values[firstArg()] = secondArg<Set<String>>().toSet()
                editor
            }
            every { editor.remove(any()) } answers {
                values.remove(firstArg<String>())
                editor
            }
            every { editor.commit() } returns true
            every { sourceRequests.deadline(any(), any()) } returns 0
            controller = newController()
        }

        fun newController() = LibraryUpdateRetryController(context, type, sourceRequests, store)
    }
}
