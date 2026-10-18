package tachiyomi.data.items.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.Chapters
import data.History
import data.Mangas
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FailingSqlDriver
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.chapter.model.ChapterUpdate
import java.util.Date

class ChapterRepositoryImplTest {

    @Test
    fun `source changes commit together and return inserted IDs`() = withFixture {
        val added = repository.syncChapters(
            chaptersToAdd = listOf(item("/new")),
            chaptersToUpdate = listOf(ChapterUpdate(retained.id, name = "Renamed")),
            chapterIdsToDelete = listOf(original.id),
        )

        added.single().id shouldBe repository.getChapterByUrlAndMangaId("/new", 1)?.id
        repository.getChapterById(original.id) shouldBe null
        repository.getChapterById(retained.id)?.name shouldBe "Renamed"
        db.historyQueries.getHistoryByMangaId(1).executeAsList() shouldBe emptyList()
    }

    @Test
    fun `insert failure rolls back deletions additions and history`() = withFixture {
        val before = repository.getChapterByMangaId(1)
        val historyBefore = db.historyQueries.getHistoryByMangaId(1).executeAsList()
        failStatement("INSERT", "NEW.url = '/fail'")

        val result = runCatching {
            repository.syncChapters(
                chaptersToAdd = listOf(item("/new"), item("/fail")),
                chaptersToUpdate = emptyList(),
                chapterIdsToDelete = listOf(original.id),
            )
        }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getChapterByMangaId(1) shouldBe before
        db.historyQueries.getHistoryByMangaId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `update failure also rolls back inserted and deleted rows`() = withFixture {
        val before = repository.getChapterByMangaId(1)
        val historyBefore = db.historyQueries.getHistoryByMangaId(1).executeAsList()
        failStatement("UPDATE", "NEW.name = 'fail'")

        val result = runCatching {
            repository.syncChapters(
                chaptersToAdd = listOf(item("/new")),
                chaptersToUpdate = listOf(ChapterUpdate(retained.id, name = "fail")),
                chapterIdsToDelete = listOf(original.id),
            )
        }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getChapterByMangaId(1) shouldBe before
        db.historyQueries.getHistoryByMangaId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `cancellation propagates and rolls back deleted rows`() = withFixture {
        val before = repository.getChapterByMangaId(1)
        val historyBefore = db.historyQueries.getHistoryByMangaId(1).executeAsList()
        val cancellation = CancellationException("cancelled during insertion")
        driver.insertFailure = cancellation

        val result = runCatching {
            repository.syncChapters(
                chaptersToAdd = listOf(item("/new")),
                chaptersToUpdate = emptyList(),
                chapterIdsToDelete = listOf(original.id),
            )
        }

        result.exceptionOrNull() shouldBe cancellation
        repository.getChapterByMangaId(1) shouldBe before
        db.historyQueries.getHistoryByMangaId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `standalone inserts propagate failures`() = withFixture {
        failStatement("INSERT", "NEW.url = '/fail'")

        val result = runCatching { repository.addAllChapters(listOf(item("/fail"))) }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
    }

    @Test
    fun `standalone deletes propagate failures`() = withFixture {
        failStatement("DELETE", "OLD._id = ${original.id}")

        val result = runCatching { repository.removeChaptersWithIds(listOf(original.id)) }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getChapterById(original.id) shouldBe original
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.seed()
            fixture.block()
        }
    }

    private class Fixture : AutoCloseable {
        val driver = FailingSqlDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val db = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(MemoColumnAdapter),
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                StringListColumnAdapter,
                MangaUpdateStrategyColumnAdapter,
                MemoColumnAdapter,
            ),
        )
        val repository = ChapterRepositoryImpl(AndroidMangaDatabaseHandler(db, driver))
        lateinit var original: Chapter
        lateinit var retained: Chapter

        init {
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            Database.Schema.create(driver)
            driver.execute(
                null,
                """
                    INSERT INTO mangas (
                        _id, source, url, title, status, favorite, initialized, viewer,
                        chapter_flags, cover_last_modified, date_added
                    ) VALUES (1, 1, '/manga', 'Manga', 0, 1, 1, 0, 0, 0, 0)
                """.trimIndent(),
                0,
            )
        }

        suspend fun seed() {
            val items = repository.addAllChapters(
                listOf(item("/old").copy(read = true, bookmark = true, lastPageRead = 23), item("/retained")),
            )
            original = items[0]
            retained = items[1]
            db.historyQueries.upsert(original.id, Date(123), 600)
        }

        fun item(url: String) = Chapter.create().copy(mangaId = 1, url = url, name = url)

        fun failStatement(operation: String, condition: String) {
            driver.execute(
                null,
                """
                    CREATE TEMP TRIGGER fail_write BEFORE $operation ON chapters
                    WHEN $condition BEGIN SELECT RAISE(ABORT, 'injected failure'); END
                """.trimIndent(),
                0,
            )
        }

        override fun close() = driver.close()
    }
}
