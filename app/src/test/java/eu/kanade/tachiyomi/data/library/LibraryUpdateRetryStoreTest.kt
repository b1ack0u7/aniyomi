package eu.kanade.tachiyomi.data.library

import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LibraryUpdateRetryStoreTest {

    @Test
    fun `pending retries retain attempts and deadlines after store recreation`() {
        val fixture = Fixture()
        fixture.store().recordFailure(LibraryUpdateType.MANGA, 1, -10, 500)

        val restored = fixture.store()

        restored.entries(LibraryUpdateType.MANGA) shouldBe listOf(LibraryUpdateRetryStore.Entry(1, -10, 1, 500))
        restored.dueEntries(LibraryUpdateType.MANGA, 499) shouldBe emptyList()
        restored.dueEntries(LibraryUpdateType.MANGA, 500).map { it.entryId } shouldBe listOf(1L)
    }

    @Test
    fun `a failed original request receives at most two retries`() {
        val store = Fixture().store()

        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 100)?.failedAttempts shouldBe 1
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 200)?.failedAttempts shouldBe 2
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 300) shouldBe null
        store.entries(LibraryUpdateType.MANGA) shouldBe emptyList()
        store.nextDueAt(LibraryUpdateType.MANGA) shouldBe null
    }

    @Test
    fun `a repeated failure cannot shorten an already persisted cooldown`() {
        val store = Fixture().store()
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 900)

        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 200)?.dueAt shouldBe 900
    }

    @Test
    fun `same title ids from separate libraries do not share retry state`() {
        val store = Fixture().store()
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 500)
        store.recordFailure(LibraryUpdateType.ANIME, 1, 20, 600)

        store.remove(LibraryUpdateType.MANGA, 1)

        store.entries(LibraryUpdateType.MANGA) shouldBe emptyList()
        store.entries(LibraryUpdateType.ANIME) shouldBe listOf(LibraryUpdateRetryStore.Entry(1, 20, 1, 600))
    }

    @Test
    fun `source cooldown extends pending deadlines without consuming attempts`() {
        val store = Fixture().store()
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 100)
        store.recordFailure(LibraryUpdateType.MANGA, 2, 10, 900)
        store.recordFailure(LibraryUpdateType.MANGA, 3, 20, 200)

        store.postponeSource(LibraryUpdateType.MANGA, 10, 600)

        store.entries(LibraryUpdateType.MANGA) shouldBe listOf(
            LibraryUpdateRetryStore.Entry(3, 20, 1, 200),
            LibraryUpdateRetryStore.Entry(1, 10, 1, 600),
            LibraryUpdateRetryStore.Entry(2, 10, 1, 900),
        )
        store.nextDueAt(LibraryUpdateType.MANGA) shouldBe 200
    }

    @Test
    fun `deferring unrequested titles does not consume their retry budget`() {
        val fixture = Fixture()
        fixture.store().deferEntry(LibraryUpdateType.MANGA, 1, 10, 500)

        val restored = fixture.store()
        restored.entries(LibraryUpdateType.MANGA) shouldBe listOf(LibraryUpdateRetryStore.Entry(1, 10, 0, 500))
        restored.recordFailure(LibraryUpdateType.MANGA, 1, 10, 600)?.failedAttempts shouldBe 1
        restored.recordFailure(LibraryUpdateType.MANGA, 1, 10, 700)?.failedAttempts shouldBe 2
        restored.recordFailure(LibraryUpdateType.MANGA, 1, 10, 800) shouldBe null
    }

    @Test
    fun `deferring existing retries preserves their budget and later deadline`() {
        val store = Fixture().store()
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 900)

        store.deferEntry(LibraryUpdateType.MANGA, 1, 10, 500)

        store.entries(LibraryUpdateType.MANGA) shouldBe listOf(LibraryUpdateRetryStore.Entry(1, 10, 1, 900))
        store.deferEntry(LibraryUpdateType.MANGA, 1, 10, 1_200)
        store.entries(LibraryUpdateType.MANGA) shouldBe listOf(LibraryUpdateRetryStore.Entry(1, 10, 1, 1_200))
    }

    @Test
    fun `blocking a source removes its backlog without touching other sources or libraries`() {
        val store = Fixture().store()
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 100)
        store.deferEntry(LibraryUpdateType.MANGA, 2, 10, 100)
        store.recordFailure(LibraryUpdateType.MANGA, 3, 20, 200)
        store.recordFailure(LibraryUpdateType.ANIME, 4, 10, 300)

        store.removeSource(LibraryUpdateType.MANGA, 10)

        store.entries(LibraryUpdateType.MANGA).map { it.entryId } shouldBe listOf(3L)
        store.entries(LibraryUpdateType.ANIME).map { it.entryId } shouldBe listOf(4L)
    }

    @Test
    fun `successful entries are removed without removing other failed titles`() {
        val store = Fixture().store()
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 100)
        store.recordFailure(LibraryUpdateType.MANGA, 2, 10, 200)

        store.remove(LibraryUpdateType.MANGA, 1)

        store.entries(LibraryUpdateType.MANGA).map { it.entryId } shouldBe listOf(2L)
    }

    @Test
    fun `moving an entry to another source resets its retry budget`() {
        val store = Fixture().store()
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 100)
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 200)

        store.recordFailure(LibraryUpdateType.MANGA, 1, 20, 300)?.failedAttempts shouldBe 1
    }

    @Test
    fun `clearing a library preserves retries for the other library`() {
        val store = Fixture().store()
        store.recordFailure(LibraryUpdateType.MANGA, 1, 10, 100)
        store.recordFailure(LibraryUpdateType.ANIME, 2, 20, 200)

        store.clear(LibraryUpdateType.MANGA)

        store.entries(LibraryUpdateType.MANGA) shouldBe emptyList()
        store.entries(LibraryUpdateType.ANIME).map { it.entryId } shouldBe listOf(2L)
    }

    @Test
    fun `corrupt rows cannot discard valid pending retries`() {
        val fixture = Fixture()
        fixture.store().recordFailure(LibraryUpdateType.MANGA, 1, 10, 100)
        fixture.values[LibraryUpdateType.MANGA.name] = fixture.values.getValue(LibraryUpdateType.MANGA.name) + setOf(
            "invalid JSON",
            """{"entryId":2,"sourceId":10,"failedAttempts":99,"dueAt":100}""",
            """{"entryId":3,"sourceId":10,"failedAttempts":1,"dueAt":-1}""",
        )

        fixture.store().entries(LibraryUpdateType.MANGA).map { it.entryId } shouldBe listOf(1L)
    }

    @Test
    fun `separate store instances preserve concurrent failures from different sources`() {
        val fixture = Fixture()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1L..100L).map { entryId ->
                Callable { fixture.store().recordFailure(LibraryUpdateType.MANGA, entryId, entryId % 5, 100) }
            }

            executor.invokeAll(tasks).forEach { it.get() }

            fixture.store().entries(LibraryUpdateType.MANGA).size shouldBe 100
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private class Fixture {
        val values = mutableMapOf<String, Set<String>>()
        private val preferences = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>()

        init {
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
        }

        fun store() = LibraryUpdateRetryStore(preferences)
    }
}
