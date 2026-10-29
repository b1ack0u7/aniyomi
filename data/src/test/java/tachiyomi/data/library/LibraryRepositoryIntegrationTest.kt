package tachiyomi.data.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.animesource.model.FetchType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.entries.anime.AnimeRepositoryImpl
import tachiyomi.data.entries.manga.MangaRepositoryImpl
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler
import tachiyomi.data.items.chapter.ChapterRepositoryImpl
import tachiyomi.data.items.episode.EpisodeRepositoryImpl
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.chapter.model.ChapterUpdate
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.mi.data.AnimeDatabase
import java.nio.file.Files
import java.util.Date
import java.util.Properties
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import data.Chapter as DbChapter
import data.History as DbHistory
import data.Manga as DbManga
import dataanime.Anime as DbAnime
import dataanime.Anime_history as DbAnimeHistory

class LibraryRepositoryIntegrationTest {

    @Test
    fun `manga subscription follows progress history categories and scanlator exclusions`() = runBlocking<Unit> {
        MangaFixture().use { f ->
            val titleId = requireNotNull(f.titles.insertManga(manga()))
            val chapter = f.items.addAllChapters(listOf(chapter(titleId).copy(scanlator = "Group"))).single()
            val events = Channel<List<LibraryManga>>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                f.titles.getLibraryMangaAsFlow().collect { events.send(it) }
            }
            try {
                events.awaitMatching { it.single().totalChapters == 1L }.single().categories shouldBe listOf(0L)

                f.items.updateChapter(ChapterUpdate(chapter.id, read = true, bookmark = true))
                events.awaitMatching { it.single().readCount == 1L }.single().bookmarkCount shouldBe 1L

                f.handler.await { historyQueries.upsert(chapter.id, Date(300), 12) }
                events.awaitMatching { it.single().lastRead == 300L }.single().readCount shouldBe 1L

                val categoryId = f.handler.await(inTransaction = true) {
                    categoryQueries.insert("Selected", 0, 0)
                    categoryQueries.selectLastInsertedRowId().executeAsOne()
                }
                f.titles.setMangaCategories(titleId, listOf(categoryId))
                events.awaitMatching { it.single().categories == listOf(categoryId) }.size shouldBe 1

                f.handler.await { excluded_scanlatorQueries.insert(titleId, "Group") }
                val excluded = events.awaitMatching { it.single().totalChapters == 0L }.single()
                excluded.readCount shouldBe 0L
                excluded.bookmarkCount shouldBe 0L
                excluded.lastRead shouldBe 0L

                f.handler.await { excluded_scanlatorQueries.remove(titleId, listOf("Group")) }
                events.awaitMatching { it.single().totalChapters == 1L }.single().lastRead shouldBe 300L
            } finally {
                collector.cancelAndJoin()
                events.close()
            }
        }
    }

    @Test
    fun `anime subscription follows episode changes and history`() = runBlocking<Unit> {
        AnimeFixture().use { f ->
            val titleId = requireNotNull(f.titles.insertAnime(anime()))
            val episode = f.items.addAllEpisodes(listOf(episode(titleId))).single()
            val events = Channel<List<LibraryAnime>>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                f.titles.getLibraryAnimeAsFlow().collect { events.send(it) }
            }
            try {
                events.awaitMatching { it.single().totalCount == 1L }.single().categories shouldBe listOf(0L)

                f.items.updateEpisode(EpisodeUpdate(episode.id, seen = true, bookmark = true, fillermark = true))
                val progress = events.awaitMatching { it.single().seenCount == 1L }.single()
                progress.bookmarkCount shouldBe 1L
                progress.fillermarkCount shouldBe 1L

                f.handler.await { anime_historyQueries.upsert(episode.id, Date(400)) }
                events.awaitMatching { it.single().lastSeen == 400L }.size shouldBe 1

                val categoryId = f.handler.await(inTransaction = true) {
                    categoryQueries.insert("Selected", 0, 0)
                    categoryQueries.selectLastInsertedRowId().executeAsOne()
                }
                f.titles.setAnimeCategories(titleId, listOf(categoryId))
                events.awaitMatching { it.single().categories == listOf(categoryId) }.size shouldBe 1
            } finally {
                collector.cancelAndJoin()
                events.close()
            }
        }
    }

    @Test
    fun `anime subscription updates a favorite parent when its non favorite child changes`() = runBlocking<Unit> {
        AnimeFixture().use { f ->
            val parentId = requireNotNull(f.titles.insertAnime(anime().copy(fetchType = FetchType.Seasons)))
            val childId = requireNotNull(
                f.titles.insertAnime(anime().copy(url = "/child", parentId = parentId, favorite = false)),
            )
            val episode = f.items.addAllEpisodes(listOf(episode(childId))).single()
            val events = Channel<List<LibraryAnime>>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                f.titles.getLibraryAnimeAsFlow().collect { events.send(it) }
            }
            try {
                val initial = events.awaitMatching { it.single().totalCount == 1L }.single()
                initial.id shouldBe parentId
                initial.seenCount shouldBe 0L

                f.items.updateEpisode(EpisodeUpdate(episode.id, seen = true, bookmark = true, fillermark = true))
                val parent = events.awaitMatching { it.single().seenCount == 1L }.single()
                parent.id shouldBe parentId
                parent.bookmarkCount shouldBe 1L
                parent.fillermarkCount shouldBe 1L

                f.handler.await { anime_historyQueries.upsert(episode.id, Date(500)) }
                events.awaitMatching { it.single().lastSeen == 500L }.single().id shouldBe parentId

                f.items.updateEpisode(EpisodeUpdate(episode.id, seen = false))
                events.awaitMatching { it.single().seenCount == 0L }.single().totalCount shouldBe 1L
            } finally {
                collector.cancelAndJoin()
                events.close()
            }
        }
    }

    @Test
    fun `concurrent manga insertions return one canonical title and chapter`() = runBlocking<Unit> {
        MangaFixture(workers = 2).use { f ->
            val titleIds = race { f.titles.insertManga(manga()) }
            titleIds.distinct().size shouldBe 1
            val titleId = requireNotNull(titleIds.first())
            f.titles.getMangaFavorites().map { it.id } shouldBe listOf(titleId)

            val itemIds = race { f.items.addAllChapters(listOf(chapter(titleId))).single().id }
            itemIds.distinct().size shouldBe 1
            f.items.getChapterByMangaId(titleId, applyScanlatorFilter = false).map { it.id } shouldBe
                listOf(itemIds.first())
        }
    }

    @Test
    fun `concurrent anime insertions return one canonical title and episode`() = runBlocking<Unit> {
        AnimeFixture(workers = 2).use { f ->
            val titleIds = race { f.titles.insertAnime(anime()) }
            titleIds.distinct().size shouldBe 1
            val titleId = requireNotNull(titleIds.first())
            f.titles.getAnimeFavorites().map { it.id } shouldBe listOf(titleId)

            val itemIds = race { f.items.addAllEpisodes(listOf(episode(titleId))).single().id }
            itemIds.distinct().size shouldBe 1
            f.items.getEpisodeByAnimeId(titleId).map { it.id } shouldBe listOf(itemIds.first())
        }
    }

    @Test
    fun `manga restore history lookup distinguishes matching URLs on different titles`() = runBlocking<Unit> {
        MangaFixture().use { f ->
            val firstId = requireNotNull(f.titles.insertManga(manga()))
            val secondId = requireNotNull(f.titles.insertManga(manga().copy(url = "/second")))
            val first = f.items.addAllChapters(listOf(chapter(firstId))).single()
            val second = f.items.addAllChapters(listOf(chapter(secondId))).single()
            f.handler.await {
                historyQueries.upsert(first.id, Date(100), 10)
                historyQueries.upsert(second.id, Date(200), 20)
            }

            val firstHistory = f.handler.awaitOne {
                historyQueries.getHistoryByChapterUrl(firstId, "/item")
            }
            val secondHistory = f.handler.awaitOne {
                historyQueries.getHistoryByChapterUrl(secondId, "/item")
            }

            firstHistory.chapter_id shouldBe first.id
            firstHistory.last_read shouldBe Date(100)
            firstHistory.time_read shouldBe 10L
            secondHistory.chapter_id shouldBe second.id
            secondHistory.last_read shouldBe Date(200)
            secondHistory.time_read shouldBe 20L
        }
    }

    @Test
    fun `anime restore history lookup distinguishes matching URLs on different titles`() = runBlocking<Unit> {
        AnimeFixture().use { f ->
            val firstId = requireNotNull(f.titles.insertAnime(anime()))
            val secondId = requireNotNull(f.titles.insertAnime(anime().copy(url = "/second")))
            val first = f.items.addAllEpisodes(listOf(episode(firstId))).single()
            val second = f.items.addAllEpisodes(listOf(episode(secondId))).single()
            f.handler.await {
                anime_historyQueries.upsert(first.id, Date(100))
                anime_historyQueries.upsert(second.id, Date(200))
            }

            val firstHistory = f.handler.awaitOne {
                anime_historyQueries.getHistoryByEpisodeUrl(firstId, "/item")
            }
            val secondHistory = f.handler.awaitOne {
                anime_historyQueries.getHistoryByEpisodeUrl(secondId, "/item")
            }

            firstHistory.episode_id shouldBe first.id
            firstHistory.last_seen shouldBe Date(100)
            secondHistory.episode_id shouldBe second.id
            secondHistory.last_seen shouldBe Date(200)
        }
    }

    private suspend fun <T> Channel<T>.awaitMatching(predicate: (T) -> Boolean): T = withTimeout(10_000) {
        var next = receive()
        while (!predicate(next)) next = receive()
        next
    }

    private suspend fun <T> race(block: suspend () -> T): List<T> = withTimeout(10_000) {
        coroutineScope {
            val gate = CompletableDeferred<Unit>()
            val requests = List(8) {
                async {
                    gate.await()
                    block()
                }
            }
            gate.complete(Unit)
            requests.awaitAll()
        }
    }

    private fun manga() = Manga.create().copy(source = 1, url = "/title", title = "Title", favorite = true)

    private fun anime() = Anime.create().copy(source = 1, url = "/title", title = "Title", favorite = true)

    private fun chapter(titleId: Long) = Chapter.create().copy(mangaId = titleId, url = "/item", name = "Item")

    private fun episode(titleId: Long) = Episode.create().copy(animeId = titleId, url = "/item", name = "Item")

    private class MangaFixture(workers: Int = 1) : SqliteFixture(workers) {
        val db = Database(
            driver = driver,
            chapterAdapter = DbChapter.Adapter(MemoColumnAdapter),
            historyAdapter = DbHistory.Adapter(DateColumnAdapter),
            mangaAdapter = DbManga.Adapter(
                StringListColumnAdapter,
                MangaUpdateStrategyColumnAdapter,
                MemoColumnAdapter,
            ),
        )
        val handler = AndroidMangaDatabaseHandler(db, driver, dispatcher, dispatcher)
        val titles = MangaRepositoryImpl(handler)
        val items = ChapterRepositoryImpl(handler)

        init {
            Database.Schema.create(driver)
        }
    }

    private class AnimeFixture(workers: Int = 1) : SqliteFixture(workers) {
        val db = AnimeDatabase(
            driver = driver,
            animeAdapter = DbAnime.Adapter(
                StringListColumnAdapter,
                AnimeUpdateStrategyColumnAdapter,
                FetchTypeColumnAdapter,
            ),
            anime_historyAdapter = DbAnimeHistory.Adapter(DateColumnAdapter),
        )
        val handler = AndroidAnimeDatabaseHandler(db, driver, dispatcher, dispatcher)
        val titles = AnimeRepositoryImpl(handler)
        val items = EpisodeRepositoryImpl(handler)

        init {
            AnimeDatabase.Schema.create(driver)
        }
    }

    private abstract class SqliteFixture(private val workers: Int) : AutoCloseable {
        private val directory = Files.createTempDirectory("library-repository-test")
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${directory.resolve("library.db")}",
            Properties().apply {
                setProperty("foreign_keys", "true")
                setProperty("journal_mode", "WAL")
                setProperty("busy_timeout", "5000")
            },
        )
        val dispatcher = Executors.newFixedThreadPool(workers).asCoroutineDispatcher()

        override fun close() {
            try {
                runBlocking {
                    coroutineScope {
                        val barrier = CyclicBarrier(workers)
                        List(workers) {
                            async(dispatcher) {
                                barrier.await(10, TimeUnit.SECONDS)
                                driver.closeConnection(driver.getConnection())
                            }
                        }.awaitAll()
                    }
                }
            } finally {
                driver.close()
                dispatcher.close()
                directory.toFile().deleteRecursively()
            }
        }
    }
}
