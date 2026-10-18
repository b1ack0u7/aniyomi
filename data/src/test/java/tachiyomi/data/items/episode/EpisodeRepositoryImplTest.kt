package tachiyomi.data.items.episode

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dataanime.Animehistory
import dataanime.Animes
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
        db.animehistoryQueries.getHistoryByAnimeId(1).executeAsList() shouldBe emptyList()
    }

    @Test
    fun `insert failure rolls back deletions additions and history`() = withFixture {
        val before = repository.getEpisodeByAnimeId(1)
        val historyBefore = db.animehistoryQueries.getHistoryByAnimeId(1).executeAsList()
        failStatement("INSERT", "NEW.url = '/fail'")

        val result = runCatching {
            repository.syncEpisodes(
                episodesToAdd = listOf(item("/new"), item("/fail")),
                episodesToUpdate = emptyList(),
                episodeIdsToDelete = listOf(original.id),
            )
        }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getEpisodeByAnimeId(1) shouldBe before
        db.animehistoryQueries.getHistoryByAnimeId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `update failure also rolls back inserted and deleted rows`() = withFixture {
        val before = repository.getEpisodeByAnimeId(1)
        val historyBefore = db.animehistoryQueries.getHistoryByAnimeId(1).executeAsList()
        failStatement("UPDATE", "NEW.name = 'fail'")

        val result = runCatching {
            repository.syncEpisodes(
                episodesToAdd = listOf(item("/new")),
                episodesToUpdate = listOf(EpisodeUpdate(retained.id, name = "fail")),
                episodeIdsToDelete = listOf(original.id),
            )
        }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getEpisodeByAnimeId(1) shouldBe before
        db.animehistoryQueries.getHistoryByAnimeId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `cancellation propagates and rolls back deleted rows`() = withFixture {
        val before = repository.getEpisodeByAnimeId(1)
        val historyBefore = db.animehistoryQueries.getHistoryByAnimeId(1).executeAsList()
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
        db.animehistoryQueries.getHistoryByAnimeId(1).executeAsList() shouldBe historyBefore
    }

    @Test
    fun `standalone inserts propagate failures`() = withFixture {
        failStatement("INSERT", "NEW.url = '/fail'")

        val result = runCatching { repository.addAllEpisodes(listOf(item("/fail"))) }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
    }

    @Test
    fun `standalone deletes propagate failures`() = withFixture {
        failStatement("DELETE", "OLD._id = ${original.id}")

        val result = runCatching { repository.removeEpisodesWithIds(listOf(original.id)) }

        result.exceptionOrNull()?.message?.contains("injected failure") shouldBe true
        repository.getEpisodeById(original.id) shouldBe original
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
            animehistoryAdapter = Animehistory.Adapter(DateColumnAdapter),
            animesAdapter = Animes.Adapter(
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
                    INSERT INTO animes (
                        _id, source, url, title, status, favorite, initialized, viewer,
                        episode_flags, cover_last_modified, date_added, season_flags,
                        season_number, season_source_order, background_last_modified
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
            db.animehistoryQueries.upsert(original.id, Date(123))
        }

        fun item(url: String) = Episode.create().copy(animeId = 1, url = url, name = url)

        fun failStatement(operation: String, condition: String) {
            driver.execute(
                null,
                """
                    CREATE TEMP TRIGGER fail_write BEFORE $operation ON episodes
                    WHEN $condition BEGIN SELECT RAISE(ABORT, 'injected failure'); END
                """.trimIndent(),
                0,
            )
        }

        override fun close() = driver.close()
    }
}
