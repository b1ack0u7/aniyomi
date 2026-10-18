package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class LibraryUpdateRetryJobTest {

    @BeforeEach
    fun setUp() {
        mockkObject(MangaLibraryUpdateJob, AnimeLibraryUpdateJob)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(MangaLibraryUpdateJob, AnimeLibraryUpdateJob, LibraryUpdateRetryJob)
        unmockkObject(WorkManager.Companion)
    }

    @Test
    fun `retry wakes require connectivity and preserve the requested deadline`() {
        val context = mockk<Context>()
        val manager = mockk<WorkManager>()
        val request = slot<OneTimeWorkRequest>()
        val dueAt = System.currentTimeMillis() + 60_000
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(context) } returns manager
        every { manager.enqueueUniqueWork(any(), any(), capture(request)) } returns mockk()

        LibraryUpdateRetryJob.schedule(context, LibraryUpdateType.MANGA, dueAt)

        request.captured.workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        request.captured.workSpec.input.getString(LibraryUpdateRetryJob.KEY_TYPE) shouldBe LibraryUpdateType.MANGA.name
        (request.captured.workSpec.initialDelay in 1..60_000) shouldBe true
        verify(exactly = 1) {
            manager.enqueueUniqueWork(
                LibraryUpdateRetryJob.workName(LibraryUpdateType.MANGA),
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
        LibraryUpdateRetryJob.workName(LibraryUpdateType.MANGA) shouldNotBe
            LibraryUpdateRetryJob.workName(LibraryUpdateType.ANIME)
    }

    @Test
    fun `extreme retry deadlines cap each wake delay without shortening persisted cooldowns`() {
        val fixture = Fixture(LibraryUpdateType.MANGA)
        val manager = mockk<WorkManager>()
        val request = slot<OneTimeWorkRequest>()
        fixture.store.recordFailure(LibraryUpdateType.MANGA, 1, 10, Long.MAX_VALUE)
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(fixture.context) } returns manager
        every { manager.enqueueUniqueWork(any(), any(), capture(request)) } returns mockk()

        LibraryUpdateRetryJob.schedule(fixture.context, LibraryUpdateType.MANGA, Long.MAX_VALUE)

        request.captured.workSpec.initialDelay shouldBe TimeUnit.DAYS.toMillis(1)
        fixture.store.entries(LibraryUpdateType.MANGA).single().dueAt shouldBe Long.MAX_VALUE
    }

    @Test
    fun `dispatcher only requests due failed titles from its library`() = runTest {
        val fixture = Fixture(LibraryUpdateType.MANGA)
        fixture.store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 0)
        fixture.store.recordFailure(LibraryUpdateType.MANGA, 2, 10, Long.MAX_VALUE)
        fixture.store.recordFailure(LibraryUpdateType.ANIME, 3, 20, 0)
        every { MangaLibraryUpdateJob.startRetry(fixture.context, listOf(1L)) } returns true

        fixture.worker.doWork() shouldBe ListenableWorker.Result.success()

        verify(exactly = 1) { MangaLibraryUpdateJob.startRetry(fixture.context, listOf(1L)) }
        verify(exactly = 0) { AnimeLibraryUpdateJob.startRetry(any(), any()) }
        fixture.store.entries(LibraryUpdateType.MANGA).size shouldBe 2
    }

    @Test
    fun `anime backlog dispatches through the anime worker`() = runTest {
        val fixture = Fixture(LibraryUpdateType.ANIME)
        fixture.store.recordFailure(LibraryUpdateType.ANIME, 5, 20, 0)
        every { AnimeLibraryUpdateJob.startRetry(fixture.context, listOf(5L)) } returns true

        fixture.worker.doWork() shouldBe ListenableWorker.Result.success()

        verify(exactly = 1) { AnimeLibraryUpdateJob.startRetry(fixture.context, listOf(5L)) }
        verify(exactly = 0) { MangaLibraryUpdateJob.startRetry(any(), any()) }
    }

    @Test
    fun `early wake rearms the persisted deadline without starting network work`() = runTest {
        val fixture = Fixture(LibraryUpdateType.MANGA)
        val dueAt = Long.MAX_VALUE
        fixture.store.recordFailure(LibraryUpdateType.MANGA, 1, 10, dueAt)
        mockkObject(LibraryUpdateRetryJob)
        every { LibraryUpdateRetryJob.schedule(fixture.context, LibraryUpdateType.MANGA, dueAt) } just Runs

        fixture.worker.doWork() shouldBe ListenableWorker.Result.success()

        verify(exactly = 1) { LibraryUpdateRetryJob.schedule(fixture.context, LibraryUpdateType.MANGA, dueAt) }
        verify(exactly = 0) { MangaLibraryUpdateJob.startRetry(any(), any()) }
        fixture.store.entries(LibraryUpdateType.MANGA).single().failedAttempts shouldBe 1
    }

    @Test
    fun `busy library leaves backlog intact without polling`() = runTest {
        val fixture = Fixture(LibraryUpdateType.MANGA)
        fixture.store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 0)
        every { MangaLibraryUpdateJob.startRetry(fixture.context, listOf(1L)) } returns false
        mockkObject(LibraryUpdateRetryJob)

        fixture.worker.doWork() shouldBe ListenableWorker.Result.success()

        verify(exactly = 1) { MangaLibraryUpdateJob.startRetry(fixture.context, listOf(1L)) }
        verify(exactly = 0) { LibraryUpdateRetryJob.schedule(any(), any(), any()) }
        fixture.store.entries(LibraryUpdateType.MANGA).single().failedAttempts shouldBe 1
    }

    @Test
    fun `an empty backlog does not schedule or start work`() = runTest {
        val fixture = Fixture(LibraryUpdateType.MANGA)
        mockkObject(LibraryUpdateRetryJob)

        fixture.worker.doWork() shouldBe ListenableWorker.Result.success()

        verify(exactly = 0) { LibraryUpdateRetryJob.schedule(any(), any(), any()) }
        verify(exactly = 0) { MangaLibraryUpdateJob.startRetry(any(), any()) }
        verify(exactly = 0) { AnimeLibraryUpdateJob.startRetry(any(), any()) }
    }

    private class Fixture(type: LibraryUpdateType) {
        val context = mockk<Context>()
        private val parameters = mockk<WorkerParameters>()
        private val preferences = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>()
        private val values = mutableMapOf<String, Set<String>>()
        val store = LibraryUpdateRetryStore(preferences)
        val worker: LibraryUpdateRetryJob

        init {
            every { context.getSharedPreferences("library_update_retries", Context.MODE_PRIVATE) } returns preferences
            every { parameters.inputData } returns workDataOf(LibraryUpdateRetryJob.KEY_TYPE to type.name)
            every { preferences.getStringSet(any(), any()) } answers {
                (values[firstArg()] ?: secondArg<Set<String>>()).toMutableSet()
            }
            every { preferences.edit() } returns editor
            every { editor.putStringSet(any(), any()) } answers {
                values[firstArg()] = secondArg<Set<String>>().toSet()
                editor
            }
            every { editor.commit() } returns true
            worker = LibraryUpdateRetryJob(context, parameters)
        }
    }
}
