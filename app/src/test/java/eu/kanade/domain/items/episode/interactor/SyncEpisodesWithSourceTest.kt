package eu.kanade.domain.items.episode.interactor

import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SEpisode
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.domain.library.service.LibraryPreferences

class SyncEpisodesWithSourceTest {

    @Test
    fun `source changes persist before updating the fetch interval`() = runTest {
        val fixture = Fixture()

        val added = fixture.sync.await(listOf(fixture.remote), fixture.entry, fixture.source)

        added.single().id shouldBe 10L
        coVerifyOrder {
            fixture.repository.syncEpisodes(any(), any(), listOf(5L))
            fixture.updateEntry.awaitUpdateFetchIntervalAndLastUpdate(fixture.entry, any(), any())
        }
    }

    @Test
    fun `persistence failures propagate without advancing the fetch interval`() = runTest {
        val fixture = Fixture()
        val failure = IllegalStateException("write failed")
        coEvery { fixture.repository.syncEpisodes(any(), any(), any()) } throws failure

        val result = runCatching { fixture.sync.await(listOf(fixture.remote), fixture.entry, fixture.source) }

        result.exceptionOrNull() shouldBe failure
        coVerify(exactly = 0) { fixture.updateEntry.awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any()) }
    }

    @Test
    fun `persistence cancellation propagates without advancing the fetch interval`() = runTest {
        val fixture = Fixture()
        val cancellation = CancellationException("cancelled during write")
        coEvery { fixture.repository.syncEpisodes(any(), any(), any()) } throws cancellation

        val result = runCatching { fixture.sync.await(listOf(fixture.remote), fixture.entry, fixture.source) }

        result.exceptionOrNull() shouldBe cancellation
        coVerify(exactly = 0) { fixture.updateEntry.awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any()) }
    }

    private class Fixture {
        val repository = mockk<EpisodeRepository>()
        val updateEntry = mockk<UpdateAnime>(relaxed = true)
        val entry = Anime.create().copy(id = 1, title = "Entry", source = 1)
        val source = mockk<AnimeSource>()
        val remote = SEpisode.create().apply {
            url = "/new"
            name = "Episode 2"
            episode_number = 2f
        }
        private val getItems = mockk<GetEpisodesByAnimeId>()
        private val preferences = mockk<LibraryPreferences>(relaxed = true)
        val sync = SyncEpisodesWithSource(
            downloadManager = mockk(),
            downloadProvider = mockk(),
            episodeRepository = repository,
            shouldUpdateDbEpisode = mockk(),
            updateAnime = updateEntry,
            getEpisodesByAnimeId = getItems,
            libraryPreferences = preferences,
        )

        init {
            every { source.id } returns 1
            every { preferences.markDuplicateSeenEpisodeAsSeen().get() } returns emptySet()
            coEvery { getItems.await(1) } returns listOf(
                Episode.create().copy(id = 5, animeId = 1, url = "/old", name = "Episode 1", episodeNumber = 1.0),
            )
            coEvery { repository.syncEpisodes(any(), any(), any()) } answers {
                firstArg<List<Episode>>().map { it.copy(id = 10) }
            }
        }
    }
}
