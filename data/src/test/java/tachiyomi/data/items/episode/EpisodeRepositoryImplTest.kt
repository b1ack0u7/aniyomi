package tachiyomi.data.items.episode

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FailingSqlDriver
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.mi.data.AnimeDatabase
import java.util.Date
import dataanime.Anime as Animes
import dataanime.Anime_history as Animehistory

class EpisodeRepositoryImplTest {

    @Test
    fun `source changes commit together and return inserted IDs`() = withFixture {
        val added = repository.syncEpisodes(
            episodesToAdd = listOf(item("/new")),
            episodesToUpdate = listOf(EpisodeUpdate(retained.id, name = "Renamed")),
            episodeIdsToDelete = listOf(original.id),
        )

        added.single().id shouldBe repository.getEpisodeByUrlAndAnimeId("/new", 1)?.id
        repository.getEpisodeById(original.id) shouldBe null
        repository.getEpisodeById(retained.id)?.name shouldBe "Renamed"
        db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList() shouldBe emptyList()
    }

    @Test
    fun `insert failure rolls back deletions additions and history`() = withFixture {
        val before = repository.getEpisodeByAnimeId(1)
        val historyBefore = db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList()
        failStatement("INSERT", "NEW.remote_url = '/fail'")

        val result = runCatching {
            repository.syncEpisodes(
                episodesToAdd = listOf(item("/new"), item("/fail")),
                episodesToUpdate = emptyList(),
                episodeIdsToDelete = listOf(original.id),
            )
        }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getEpisodeByAnimeId(1) shouldBe before
        db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `update failure also rolls back inserted and deleted rows`() = withFixture {
        val before = repository.getEpisodeByAnimeId(1)
        val historyBefore = db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList()
        failStatement("UPDATE", "NEW.remote_name = 'fail'")

        val result = runCatching {
            repository.syncEpisodes(
                episodesToAdd = listOf(item("/new")),
                episodesToUpdate = listOf(EpisodeUpdate(retained.id, name = "fail")),
                episodeIdsToDelete = listOf(original.id),
            )
        }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getEpisodeByAnimeId(1) shouldBe before
        db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `cancellation propagates and rolls back deleted rows`() = withFixture {
        val before = repository.getEpisodeByAnimeId(1)
        val historyBefore = db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList()
        val cancellation = CancellationException("cancelled during insertion")
        driver.insertFailure = cancellation

        val result = runCatching {
            repository.syncEpisodes(
                episodesToAdd = listOf(item("/new")),
                episodesToUpdate = emptyList(),
                episodeIdsToDelete = listOf(original.id),
            )
        }

        result.exceptionOrNull() shouldBe cancellation
        repository.getEpisodeByAnimeId(1) shouldBe before
        db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `standalone inserts propagate failures`() = withFixture {
        failStatement("INSERT", "NEW.remote_url = '/fail'")

        val result = runCatching { repository.addAllEpisodes(listOf(item("/fail"))) }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
    }

    @Test
    fun `standalone deletes propagate failures`() = withFixture {
        failStatement("DELETE", "OLD.id = ${original.id}")

        val result = runCatching { repository.removeEpisodesWithIds(listOf(original.id)) }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getEpisodeById(original.id) shouldBe original
    }

    @Test
    fun `duplicate URLs return the stored item and its real ID`() = withFixture {
        val inserted = repository.addAllEpisodes(
            listOf(item("/old").copy(name = "Conflicting name"), item("/old")),
        )

        inserted shouldBe listOf(original, original)
        repository.getEpisodeByAnimeId(1) shouldBe listOf(original, retained)
    }

    @Test
    fun `sync collision reuses the original item without replacing its history`() = withFixture {
        val historyBefore = db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList()

        val inserted = repository.syncEpisodes(
            episodesToAdd = listOf(item("/old")),
            episodesToUpdate = emptyList(),
            episodeIdsToDelete = emptyList(),
        )

        inserted shouldBe listOf(original)
        db.anime_historyQueries.getHistoryByAnimeId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `tracker upsert updates the existing row without replacing its ID`() = withFixture {
        db.anime_trackQueries.insert(1, 1, 10, null, "Original", 2.0, 12, 1, 5.0, "/track", 0, 0, false)
        val before = db.anime_trackQueries.getTracksByAnimeId(1).executeAsOne()

        db.anime_trackQueries.insert(1, 1, 20, null, "Updated", 4.0, 24, 2, 8.0, "/updated", 0, 0, true)
        val after = db.anime_trackQueries.getTracksByAnimeId(1).executeAsOne()

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
        val db = AnimeDatabase(
            driver = driver,
            anime_historyAdapter = Animehistory.Adapter(DateColumnAdapter),
            animeAdapter = Animes.Adapter(
                StringListColumnAdapter,
                AnimeUpdateStrategyColumnAdapter,
                FetchTypeColumnAdapter,
            ),
        )
        val repository = EpisodeRepositoryImpl(AndroidAnimeDatabaseHandler(db, driver))
        lateinit var original: Episode
        lateinit var retained: Episode

        init {
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            AnimeDatabase.Schema.create(driver)
            driver.execute(
                null,
                """
                    INSERT INTO anime (
                        id, source_id, remote_url, remote_title, remote_status, favorite, state_initialized, user_viewer,
                        user_episode_flags, state_cover_last_modified, date_added, user_season_flags,
                        remote_season_number, remote_season_source_order, state_background_last_modified
                    ) VALUES (1, 1, '/anime', 'Anime', 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0)
                """.trimIndent(),
                0,
            )
        }

        suspend fun seed() {
            val items = repository.addAllEpisodes(
                listOf(item("/old").copy(seen = true, bookmark = true, lastSecondSeen = 23), item("/retained")),
            )
            original = items[0]
            retained = items[1]
            db.anime_historyQueries.upsert(original.id, Date(123))
        }

        fun item(url: String) = Episode.create().copy(animeId = 1, url = url, name = url)

        fun failStatement(operation: String, condition: String) {
            driver.execute(
                null,
                """
                    CREATE TEMP TRIGGER fail_write BEFORE $operation ON episode
                    WHEN $condition BEGIN SELECT RAISE(ABORT, 'injected failure'); END
                """.trimIndent(),
                0,
            )
        }

        override fun close() = driver.close()
    }
}
