package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.anime.AnimeMetadataUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaMetadataUpdateJob
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.core.database.DatabaseInitializer
import mihon.core.database.DatabaseKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class LibraryWorkerDatabaseInitializationTest {

    private val originalInjekt = Injekt

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
    }

    @Test
    fun `pending library and metadata workers retry without constructing dependencies on failed migration`() = runTest {
        for (failedDatabase in DatabaseKind.entries) {
            var fail = true
            val initializer = DatabaseInitializer(
                openManga = { if (fail && failedDatabase == DatabaseKind.Manga) error("migration failed") },
                openAnime = { if (fail && failedDatabase == DatabaseKind.Anime) error("migration failed") },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            Injekt = InjektScope(DefaultRegistrar())
            Injekt.addSingleton(initializer)
            val context = mockk<Context>()
            val parameters = mockk<WorkerParameters>()
            val workers: List<CoroutineWorker> = listOf(
                MangaLibraryUpdateJob(context, parameters),
                AnimeLibraryUpdateJob(context, parameters),
                MangaMetadataUpdateJob(context, parameters),
                AnimeMetadataUpdateJob(context, parameters),
            )
            val pending = workers.map { async { it.doWork() } }
            runCurrent()
            pending.forEach { it.isCompleted shouldBe false }

            initializer.open()
            pending.forEach { it.await() shouldBe ListenableWorker.Result.retry() }

            fail = false
            initializer.open()
            val mangaSources = mockk<MangaSourceManager>()
            val animeSources = mockk<AnimeSourceManager>()
            every { mangaSources.isInitialized } returns MutableStateFlow(false)
            every { animeSources.isInitialized } returns MutableStateFlow(false)
            Injekt.addSingleton(mangaSources)
            Injekt.addSingleton(animeSources)
            val retried = workers.map { async { it.doWork() } }
            runCurrent()
            verify(exactly = 2) { mangaSources.isInitialized }
            verify(exactly = 2) { animeSources.isInitialized }
            retried.forEach {
                it.isCompleted shouldBe false
                it.cancelAndJoin()
            }
        }
    }
}
