package tachiyomi.data.track.manga

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.History
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler
import tachiyomi.domain.track.manga.model.MangaTrack
import data.Chapter as Chapters
import data.Manga as Mangas

class MangaTrackConditionalUpdateTest {

    @Test
    fun `conditional write preserves higher progress already confirmed locally`() = withFixture {
        val original = insert()
        repository.insertManga(original.copy(lastChapterRead = 20.0))

        repository.updateIfSameIdentity(original.copy(lastChapterRead = 8.0), original) shouldBe true

        current().lastChapterRead shouldBe 20.0
    }

    @Test
    fun `relinked remote retains its binding when an old request completes`() = withFixture {
        val original = insert()
        val replacement = original.copy(remoteId = 2002)
        repository.insertManga(replacement)

        repository.updateIfSameIdentity(original.copy(lastChapterRead = 8.0), original) shouldBe false

        current() shouldBe replacement
    }

    @Test
    fun `recycled primary key never receives the previous recipients completion`() = withFixture {
        val original = insert()
        repository.delete(1, 1)
        repository.insertManga(original.copy(mangaId = 2, remoteId = 2002))
        val replacement = repository.getTracksByMangaId(2).single()
        replacement.id shouldBe original.id

        repository.updateIfSameIdentity(original.copy(lastChapterRead = 8.0), original) shouldBe false

        repository.getTracksByMangaId(1) shouldBe emptyList()
        repository.getTracksByMangaId(2).single() shouldBe replacement
    }

    @Test
    fun `resolved library ID is accepted only while the expected binding still exists`() = withFixture {
        val original = insert()
        val resolved = original.copy(libraryId = 42, lastChapterRead = 8.0)

        repository.updateIfSameIdentity(resolved, original) shouldBe true

        current() shouldBe resolved
        repository.updateIfSameIdentity(original.copy(lastChapterRead = 12.0), original) shouldBe false
        current() shouldBe resolved
    }

    @Test
    fun `URL recipient change is protected even with zero remote ID`() = withFixture {
        val original = insert().copy(remoteId = 0)
        repository.insertManga(original)
        val replacement = original.copy(remoteUrl = "https://other.example/api/items/2")
        repository.insertManga(replacement)

        repository.updateIfSameIdentity(original.copy(lastChapterRead = 8.0), original) shouldBe false

        current() shouldBe replacement
    }

    @Test
    fun `conditional write rejects a response for another recipient`() = withFixture {
        val original = insert()

        repository.updateIfSameIdentity(original.copy(remoteId = 2002, lastChapterRead = 8.0), original) shouldBe false

        current() shouldBe original
    }

    @Test
    fun `resolved library cannot be replaced by a different response identity`() = withFixture {
        val original = insert().copy(libraryId = 42)
        repository.insertManga(original)

        repository.updateIfSameIdentity(original.copy(libraryId = 99, lastChapterRead = 8.0), original) shouldBe false
        repository.updateIfSameIdentity(original.copy(libraryId = null, lastChapterRead = 8.0), original) shouldBe false

        current() shouldBe original
    }

    @Test
    fun `zero library ID can be resolved to a positive ID`() = withFixture {
        val original = insert().copy(libraryId = 0)
        repository.insertManga(original)
        val resolved = original.copy(libraryId = 42, lastChapterRead = 8.0)

        repository.updateIfSameIdentity(resolved, original) shouldBe true

        current() shouldBe resolved
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        Fixture().use { it.block() }
    }

    private class Fixture : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = Database(
            driver = driver,
            chapterAdapter = Chapters.Adapter(MemoColumnAdapter),
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangaAdapter = Mangas.Adapter(
                StringListColumnAdapter,
                MangaUpdateStrategyColumnAdapter,
                MemoColumnAdapter,
            ),
        )
        val repository = MangaTrackRepositoryImpl(AndroidMangaDatabaseHandler(database, driver))

        init {
            Database.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            for (id in 1..2) {
                driver.execute(
                    null,
                    """
                        INSERT INTO manga (
                            id, source_id, remote_url, remote_title, remote_status, favorite, state_initialized, user_viewer,
                            user_chapter_flags, state_cover_last_modified, date_added
                        ) VALUES ($id, 1, '/$id', 'Title', 0, 1, 1, 0, 0, 0, 0)
                    """.trimIndent(),
                    0,
                )
            }
        }

        suspend fun insert(): MangaTrack {
            repository.insertManga(MangaTrack(0, 1, 1, 1001, null, "Title", 0.0, 100, 0, 0.0, "", 0, 0, false))
            return current()
        }

        suspend fun current() = repository.getTracksByMangaId(1).single()

        override fun close() = driver.close()
    }
}
