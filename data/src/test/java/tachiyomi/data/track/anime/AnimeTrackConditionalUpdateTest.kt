package tachiyomi.data.track.anime

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.mi.data.AnimeDatabase
import dataanime.Anime as Animes
import dataanime.Anime_history as Animehistory

class AnimeTrackConditionalUpdateTest {

    @Test
    fun `conditional write preserves higher progress already confirmed locally`() = withFixture {
        val original = insert()
        repository.insertAnime(original.copy(lastEpisodeSeen = 20.0))

        repository.updateIfSameIdentity(original.copy(lastEpisodeSeen = 8.0), original) shouldBe true

        current().lastEpisodeSeen shouldBe 20.0
    }

    @Test
    fun `relinked remote retains its binding when an old request completes`() = withFixture {
        val original = insert()
        val replacement = original.copy(remoteId = 2002)
        repository.insertAnime(replacement)

        repository.updateIfSameIdentity(original.copy(lastEpisodeSeen = 8.0), original) shouldBe false

        current() shouldBe replacement
    }

    @Test
    fun `recycled primary key never receives the previous recipients completion`() = withFixture {
        val original = insert()
        repository.delete(1, 1)
        repository.insertAnime(original.copy(animeId = 2, remoteId = 2002))
        val replacement = repository.getTracksByAnimeId(2).single()
        replacement.id shouldBe original.id

        repository.updateIfSameIdentity(original.copy(lastEpisodeSeen = 8.0), original) shouldBe false

        repository.getTracksByAnimeId(1) shouldBe emptyList()
        repository.getTracksByAnimeId(2).single() shouldBe replacement
    }

    @Test
    fun `resolved library ID is accepted only while the expected binding still exists`() = withFixture {
        val original = insert()
        val resolved = original.copy(libraryId = 42, lastEpisodeSeen = 8.0)

        repository.updateIfSameIdentity(resolved, original) shouldBe true

        current() shouldBe resolved
        repository.updateIfSameIdentity(original.copy(lastEpisodeSeen = 12.0), original) shouldBe false
        current() shouldBe resolved
    }

    @Test
    fun `URL recipient change is protected even with zero remote ID`() = withFixture {
        val original = insert().copy(remoteId = 0)
        repository.insertAnime(original)
        val replacement = original.copy(remoteUrl = "https://other.example/api/items/2")
        repository.insertAnime(replacement)

        repository.updateIfSameIdentity(original.copy(lastEpisodeSeen = 8.0), original) shouldBe false

        current() shouldBe replacement
    }

    @Test
    fun `conditional write rejects a response for another recipient`() = withFixture {
        val original = insert()

        repository.updateIfSameIdentity(original.copy(remoteId = 2002, lastEpisodeSeen = 8.0), original) shouldBe false

        current() shouldBe original
    }

    @Test
    fun `resolved library cannot be replaced by a different response identity`() = withFixture {
        val original = insert().copy(libraryId = 42)
        repository.insertAnime(original)

        repository.updateIfSameIdentity(original.copy(libraryId = 99, lastEpisodeSeen = 8.0), original) shouldBe false
        repository.updateIfSameIdentity(original.copy(libraryId = null, lastEpisodeSeen = 8.0), original) shouldBe false

        current() shouldBe original
    }

    @Test
    fun `zero library ID can be resolved to a positive ID`() = withFixture {
        val original = insert().copy(libraryId = 0)
        repository.insertAnime(original)
        val resolved = original.copy(libraryId = 42, lastEpisodeSeen = 8.0)

        repository.updateIfSameIdentity(resolved, original) shouldBe true

        current() shouldBe resolved
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        Fixture().use { it.block() }
    }

    private class Fixture : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = AnimeDatabase(
            driver = driver,
            anime_historyAdapter = Animehistory.Adapter(DateColumnAdapter),
            animeAdapter = Animes.Adapter(
                StringListColumnAdapter,
                AnimeUpdateStrategyColumnAdapter,
                FetchTypeColumnAdapter,
            ),
        )
        val repository = AnimeTrackRepositoryImpl(AndroidAnimeDatabaseHandler(database, driver))

        init {
            AnimeDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            for (id in 1..2) {
                driver.execute(
                    null,
                    """
                        INSERT INTO anime (
                            id, source_id, remote_url, remote_title, remote_status, favorite, state_initialized, user_viewer,
                            user_episode_flags, state_cover_last_modified, date_added, user_season_flags,
                            remote_season_number, remote_season_source_order, state_background_last_modified
                        ) VALUES ($id, 1, '/$id', 'Title', 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0)
                    """.trimIndent(),
                    0,
                )
            }
        }

        suspend fun insert(): AnimeTrack {
            repository.insertAnime(AnimeTrack(0, 1, 1, 1001, null, "Title", 0.0, 100, 0, 0.0, "", 0, 0, false))
            return current()
        }

        suspend fun current() = repository.getTracksByAnimeId(1).single()

        override fun close() = driver.close()
    }
}
