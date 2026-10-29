package eu.kanade.domain.track

import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.anime.service.DelayedAnimeTrackingUpdateJob
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.domain.track.manga.interactor.TrackChapter
import eu.kanade.domain.track.manga.service.DelayedMangaTrackingUpdateJob
import eu.kanade.domain.track.manga.store.DelayedMangaTrackingStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.domain.track.anime.model.identity
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.model.MangaTrack
import tachiyomi.domain.track.manga.model.identity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class DelayedTrackingUpdateJobTest {
    private val originalInjekt = Injekt

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `missing track retains pending and retries beyond the former attempt cap`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        coEvery { fixture.mangaTracks.awaitOne(7) } returns null
        coEvery { fixture.animeTracks.awaitOne(7) } returns null

        fixture.worker.doWork() shouldBe ListenableWorker.Result.retry()

        fixture.mangaStore.getMangaItems().single().lastChapterRead shouldBe 8f
        fixture.animeStore.getAnimeItems().single().lastEpisodeSeen shouldBe 12f
        coVerify(exactly = 0) { fixture.chapter.await(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { fixture.episode.await(any(), any(), any(), any(), any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `retry dispatch identifies exact tracker and only acknowledges its stack`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        coEvery { fixture.mangaTracks.awaitOne(7) } returns
            MangaTrack(7, 101, 1, 42, null, "Manga", 0.0, 100, 0, 0.0, "", 0, 0, false)
        coEvery { fixture.animeTracks.awaitOne(7) } returns
            AnimeTrack(7, 202, 1, 43, null, "Anime", 0.0, 100, 0, 0.0, "", 0, 0, false)
        coEvery {
            fixture.chapter.await(fixture.preferences.context, 101, 8.0, false, trackingManga().identity, "account-a")
        } answers {
            fixture.mangaStore.removeMangaItem(trackingManga(), 8.0, "account-a")
        }
        coEvery {
            fixture.episode.await(fixture.preferences.context, 202, 12.0, false, trackingAnime().identity, "account-a")
        } answers {
            fixture.animeStore.removeAnimeItem(trackingAnime(), 12.0, "account-a")
        }

        fixture.worker.doWork() shouldBe ListenableWorker.Result.success()

        when (stack) {
            Stack.MANGA -> {
                fixture.mangaStore.getMangaItems() shouldBe emptyList()
                fixture.animeStore.getAnimeItems().single().lastEpisodeSeen shouldBe 12f
                coVerify(exactly = 1) {
                    fixture.chapter.await(
                        fixture.preferences.context,
                        101,
                        8.0,
                        false,
                        trackingManga().identity,
                        "account-a",
                    )
                }
                coVerify(exactly = 0) { fixture.episode.await(any(), any(), any(), any(), any(), any()) }
            }
            Stack.ANIME -> {
                fixture.animeStore.getAnimeItems() shouldBe emptyList()
                fixture.mangaStore.getMangaItems().single().lastChapterRead shouldBe 8f
                coVerify(exactly = 1) {
                    fixture.episode.await(
                        fixture.preferences.context,
                        202,
                        12.0,
                        false,
                        trackingAnime().identity,
                        "account-a",
                    )
                }
                coVerify(exactly = 0) { fixture.chapter.await(any(), any(), any(), any(), any(), any()) }
            }
        }
    }

    enum class Stack { MANGA, ANIME }

    private class Fixture(stack: Stack) {
        val preferences = TrackingPreferencesFixture()
        val mangaStore = DelayedMangaTrackingStore(preferences.context)
        val animeStore = DelayedAnimeTrackingStore(preferences.context)
        val mangaTracks = mockk<GetMangaTracks>()
        val animeTracks = mockk<GetAnimeTracks>()
        val chapter = mockk<TrackChapter>()
        val episode = mockk<TrackEpisode>()
        val worker: CoroutineWorker

        init {
            Injekt = InjektScope(DefaultRegistrar())
            Injekt.addSingleton(mangaStore)
            Injekt.addSingleton(animeStore)
            Injekt.addSingleton(mangaTracks)
            Injekt.addSingleton(animeTracks)
            Injekt.addSingleton(chapter)
            Injekt.addSingleton(episode)
            mangaStore.addManga(trackingManga(), 8.0, "account-a")
            animeStore.addAnime(trackingAnime(), 12.0, "account-a")
            val parameters = mockk<WorkerParameters>()
            every { parameters.runAttemptCount } returns 20
            worker = when (stack) {
                Stack.MANGA -> DelayedMangaTrackingUpdateJob(preferences.context, parameters)
                Stack.ANIME -> DelayedAnimeTrackingUpdateJob(preferences.context, parameters)
            }
        }
    }
}
