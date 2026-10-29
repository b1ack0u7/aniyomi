package tachiyomi.data.items.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.History
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
import data.Chapter as Chapters
import data.Manga as Mangas

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
        failStatement("INSERT", "NEW.remote_url = '/fail'")

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
        failStatement("UPDATE", "NEW.remote_name = 'fail'")

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
        failStatement("INSERT", "NEW.remote_url = '/fail'")

        val result = runCatching { repository.addAllChapters(listOf(item("/fail"))) }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
    }

    @Test
    fun `standalone deletes propagate failures`() = withFixture {
        failStatement("DELETE", "OLD.id = ${original.id}")

        val result = runCatching { repository.removeChaptersWithIds(listOf(original.id)) }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getChapterById(original.id) shouldBe original
    }

    @Test
    fun `duplicate URLs return the stored item and its real ID`() = withFixture {
        val inserted = repository.addAllChapters(
            listOf(item("/old").copy(name = "Conflicting name"), item("/old")),
        )

        inserted shouldBe listOf(original, original)
        repository.getChapterByMangaId(1, applyScanlatorFilter = false) shouldBe listOf(original, retained)
    }

    @Test
    fun `sync collision reuses the original item without replacing its history`() = withFixture {
        val historyBefore = db.historyQueries.getHistoryByMangaId(1).executeAsList()

        val inserted = repository.syncChapters(
            chaptersToAdd = listOf(item("/old")),
            chaptersToUpdate = emptyList(),
            chapterIdsToDelete = emptyList(),
        )

        inserted shouldBe listOf(original)
        db.historyQueries.getHistoryByMangaId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `tracker upsert updates the existing row without replacing its ID`() = withFixture {
        db.manga_trackQueries.insert(1, 1, 10, null, "Original", 2.0, 12, 1, 5.0, "/track", 0, 0, false)
        val before = db.manga_trackQueries.getTracksByMangaId(1).executeAsOne()

        db.manga_trackQueries.insert(1, 1, 20, null, "Updated", 4.0, 24, 2, 8.0, "/updated", 0, 0, true)
        val after = db.manga_trackQueries.getTracksByMangaId(1).executeAsOne()

        after.id shouldBe before.id
        after.remote_id shouldBe 20L
        after.title shouldBe "Updated"
        after.private_ shouldBe true
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
            chapterAdapter = Chapters.Adapter(MemoColumnAdapter),
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangaAdapter = Mangas.Adapter(
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
                    INSERT INTO manga (
                        id, source_id, remote_url, remote_title, remote_status, favorite, state_initialized, user_viewer,
                        user_chapter_flags, state_cover_last_modified, date_added
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
                    CREATE TEMP TRIGGER fail_write BEFORE $operation ON chapter
                    WHEN $condition BEGIN SELECT RAISE(ABORT, 'injected failure'); END
                """.trimIndent(),
                0,
            )
        }

        override fun close() = driver.close()
    }
}
