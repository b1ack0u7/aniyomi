package mihon.core.database

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DatabaseInitializerTest {

    @Test
    fun `both databases must open before consumers are released`() = runTest {
        val opened = mutableListOf<DatabaseKind>()
        val initializer = DatabaseInitializer(
            openManga = { opened += DatabaseKind.Manga },
            openAnime = { opened += DatabaseKind.Anime },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val consumer = async { initializer.awaitReady() }
        runCurrent()
        consumer.isCompleted shouldBe false

        initializer.open()
        consumer.await()

        opened shouldBe DatabaseKind.entries
        initializer.state.value.isReady shouldBe true
    }

    @Test
    fun `failed anime remains blocked and retry does not reopen manga`() = runTest {
        var mangaOpens = 0
        var animeOpens = 0
        val failure = IllegalStateException("anime_parent_conflict")
        val initializer = DatabaseInitializer(
            openManga = { mangaOpens++ },
            openAnime = {
                animeOpens++
                if (animeOpens == 1) throw failure
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        initializer.open()
        initializer.state.value.isReady shouldBe false
        initializer.state.value.databases[DatabaseKind.Manga] shouldBe DatabaseOpenState.Ready
        initializer.state.value.databases[DatabaseKind.Anime] shouldBe DatabaseOpenState.Failed(failure)

        initializer.open()

        mangaOpens shouldBe 1
        animeOpens shouldBe 2
        initializer.state.value.isReady shouldBe true
    }

    @Test
    fun `failed manga does not start anime`() = runTest {
        var animeOpens = 0
        val failure = IllegalStateException("migration failed")
        val initializer = DatabaseInitializer(
            openManga = { throw failure },
            openAnime = { animeOpens++ },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        initializer.open()

        animeOpens shouldBe 0
        initializer.state.value.databases[DatabaseKind.Manga] shouldBe DatabaseOpenState.Failed(failure)
    }

    @Test
    fun `concurrent open requests perform each opening once`() = runTest {
        var mangaOpens = 0
        var animeOpens = 0
        val initializer = DatabaseInitializer(
            openManga = { mangaOpens++ },
            openAnime = { animeOpens++ },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        List(8) { launch { initializer.open() } }.joinAll()

        mangaOpens shouldBe 1
        animeOpens shouldBe 1
    }

    @Test
    fun `upgrade callbacks expose migration state before ready`() = runTest {
        lateinit var initializer: DatabaseInitializer
        initializer = DatabaseInitializer(
            openManga = { initializer.migrating(DatabaseKind.Manga) },
            openAnime = {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val seen = mutableListOf<DatabaseOpenState?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            initializer.state
                .map { it.databases[DatabaseKind.Manga] }
                .distinctUntilChanged()
                .collect { seen += it }
        }

        initializer.open()

        seen shouldBe listOf(DatabaseOpenState.Opening, DatabaseOpenState.Migrating, DatabaseOpenState.Ready)
    }

    @Test
    fun `background subscribers survive a failed migration and resume after retry`() = runTest {
        var attempts = 0
        val initializer = DatabaseInitializer(
            openManga = {},
            openAnime = { if (attempts++ == 0) error("migration failed") },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        var subscribed = false
        val subscriber = launch {
            initializer.awaitReady()
            subscribed = true
        }
        val worker = async { initializer.awaitReadyOrFailure() }
        runCurrent()

        initializer.open()
        worker.await() shouldBe false
        subscriber.isCompleted shouldBe false
        subscribed shouldBe false

        initializer.open()
        subscriber.join()
        initializer.awaitReadyOrFailure() shouldBe true
        subscribed shouldBe true
        attempts shouldBe 2
    }

    @Test
    fun `cancellation propagates without being presented as a database failure`() = runTest {
        val initializer = DatabaseInitializer(
            openManga = { throw CancellationException("cancelled") },
            openAnime = {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val opening = async { initializer.open() }
        runCatching { opening.await() }.exceptionOrNull()!!::class shouldBe CancellationException::class
        initializer.state.value.databases[DatabaseKind.Manga] shouldBe DatabaseOpenState.Opening
    }
}
