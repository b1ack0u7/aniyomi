package eu.kanade.domain.track

import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.anime.service.DelayedAnimeTrackingUpdateJob
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.domain.track.manga.interactor.TrackChapter
import eu.kanade.domain.track.manga.service.DelayedMangaTrackingUpdateJob
import eu.kanade.domain.track.manga.store.DelayedMangaTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
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
import tachiyomi.data.track.anime.AnimeTrackRepositoryImpl
import tachiyomi.data.track.manga.MangaTrackRepositoryImpl
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.interactor.InsertMangaTrack
import tachiyomi.domain.track.manga.model.MangaTrack
import tachiyomi.mi.data.AnimeDatabase
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.Executors
import java.util.stream.Stream
import data.Chapter as DbChapter
import data.History as DbHistory
import data.Manga as DbManga
import dataanime.Anime as DbAnime
import dataanime.Anime_history as DbAnimeHistory
import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack as DbAnimeTrack
import eu.kanade.tachiyomi.data.database.models.manga.MangaTrack as DbMangaTrack

class TrackingRecipientIdentityTest {

    @ParameterizedTest
    @MethodSource("recipients")
    fun `recipient changes preserve pending without overwriting replacement bindings`(
        stack: Stack,
        change: RecipientChange,
    ) = runBlocking<Unit> {
        Fixture(stack).use { fixture ->
            fixture.insert(1001)
            val originalId = fixture.trackId
            fixture.add(8.0)
            when (change) {
                RecipientChange.REUSE -> {
                    fixture.delete()
                    fixture.worker.doWork() shouldBe ListenableWorker.Result.retry()
                    fixture.insert(2002)
                    fixture.trackId shouldBe originalId
                }
                RecipientChange.REMOTE_ID -> fixture.relink(remoteId = 2002)
                RecipientChange.REMOTE_URL -> fixture.relink(remoteUrl = "https://new-recipient/")
                RecipientChange.LIBRARY_ID -> fixture.relink(libraryId = 42)
                RecipientChange.ACCOUNT -> fixture.account = "account-b"
                RecipientChange.DURING_REFRESH -> fixture.onRefresh = { fixture.relink(remoteId = 2002) }
                RecipientChange.DURING_UPDATE -> fixture.onUpdate = { fixture.relink(remoteId = 2002) }
            }

            fixture.worker.doWork() shouldBe ListenableWorker.Result.retry()
            fixture.writes shouldBe if (change == RecipientChange.DURING_UPDATE) {
                listOf(1001L to 8.0)
            } else {
                emptyList()
            }
            fixture.localProgress() shouldBe 0.0
            fixture.pending() shouldBe listOf(1001L to 8f)

            fixture.onRefresh = {}
            fixture.onUpdate = {}
            fixture.advance(3.0)

            fixture.writes.last() shouldBe (fixture.remoteId to 3.0)
            fixture.localProgress() shouldBe 3.0
            fixture.pending() shouldBe listOf(1001L to 8f)
        }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `library ID resolution preserves higher progress queued during RPC`(stack: Stack) = runBlocking<Unit> {
        Fixture(stack).use { fixture ->
            fixture.insert(1001)
            fixture.add(8.0)
            fixture.assignLibrary = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.onUpdate = {
                if (fixture.writes.last().second == 8.0) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val first = async { fixture.worker.doWork() }
            entered.await()
            val newer = async { fixture.advance(12.0) }
            try {
                withTimeout(10_000) {
                    while (fixture.pending() != listOf(1001L to 12f)) yield()
                }
            } finally {
                release.complete(Unit)
            }
            first.await()
            newer.await()
            fixture.onUpdate = {}

            fixture.worker.doWork() shouldBe ListenableWorker.Result.success()

            fixture.localProgress() shouldBe 12.0
            fixture.pending() shouldBe emptyList()
            fixture.writes shouldBe listOf(1001L to 8.0, 1001L to 12.0)
        }
    }

    enum class Stack { MANGA, ANIME }

    enum class RecipientChange {
        REUSE,
        REMOTE_ID,
        REMOTE_URL,
        LIBRARY_ID,
        ACCOUNT,
        DURING_REFRESH,
        DURING_UPDATE,
    }

    private class Fixture(private val stack: Stack) : AutoCloseable {
        private val originalInjekt = Injekt
        private val preferences = TrackingPreferencesFixture()
        private val mangaStore = DelayedMangaTrackingStore(preferences.context)
        private val animeStore = DelayedAnimeTrackingStore(preferences.context)
        private val directory = Files.createTempDirectory("tracking-recipient-test")
        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val properties = Properties().apply { setProperty("foreign_keys", "true") }
        private val mangaDriver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("manga.db")}", properties)
        private val animeDriver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("anime.db")}", properties)
        private val mangaDb = Database(
            driver = mangaDriver,
            chapterAdapter = DbChapter.Adapter(MemoColumnAdapter),
            historyAdapter = DbHistory.Adapter(DateColumnAdapter),
            mangaAdapter = DbManga.Adapter(
                StringListColumnAdapter,
                MangaUpdateStrategyColumnAdapter,
                MemoColumnAdapter,
            ),
        )
        private val animeDb = AnimeDatabase(
            driver = animeDriver,
            animeAdapter = DbAnime.Adapter(
                StringListColumnAdapter,
                AnimeUpdateStrategyColumnAdapter,
                FetchTypeColumnAdapter,
            ),
            anime_historyAdapter = DbAnimeHistory.Adapter(DateColumnAdapter),
        )
        private val mangaHandler = AndroidMangaDatabaseHandler(mangaDb, mangaDriver, dispatcher, dispatcher)
        private val animeHandler = AndroidAnimeDatabaseHandler(animeDb, animeDriver, dispatcher, dispatcher)
        private val mangaRepository = MangaTrackRepositoryImpl(mangaHandler)
        private val animeRepository = AnimeTrackRepositoryImpl(animeHandler)
        private val mangaTitles = MangaRepositoryImpl(mangaHandler)
        private val animeTitles = AnimeRepositoryImpl(animeHandler)
        private var manga: MangaTrack? = null
        private var anime: AnimeTrack? = null
        private val trackerManager = mockk<TrackerManager>()
        private val tracker = mockk<MyAnimeList>()
        private val getMangaTracks = GetMangaTracks(mangaRepository)
        private val getAnimeTracks = GetAnimeTracks(animeRepository)
        private val trackChapter = TrackChapter(
            getMangaTracks,
            trackerManager,
            InsertMangaTrack(mangaRepository),
            mangaStore,
            {},
        )
        private val trackEpisode = TrackEpisode(
            getAnimeTracks,
            trackerManager,
            InsertAnimeTrack(animeRepository),
            animeStore,
            {},
        )
        var account = "account-a"
        var assignLibrary = false
        var onRefresh: suspend () -> Unit = {}
        var onUpdate: suspend () -> Unit = {}
        val writes = mutableListOf<Pair<Long, Double>>()
        val worker: CoroutineWorker
        val trackId get() = if (stack == Stack.MANGA) manga!!.id else anime!!.id
        val remoteId get() = if (stack == Stack.MANGA) manga!!.remoteId else anime!!.remoteId

        init {
            Database.Schema.create(mangaDriver)
            AnimeDatabase.Schema.create(animeDriver)
            every { trackerManager.get(any()) } returns tracker
            every { tracker.isLoggedIn } returns true
            every { tracker.getUsername() } answers { account }
            coEvery { tracker.refresh(any<DbMangaTrack>()) } coAnswers {
                onRefresh()
                firstArg()
            }
            coEvery { tracker.refresh(any<DbAnimeTrack>()) } coAnswers {
                onRefresh()
                firstArg()
            }
            coEvery { tracker.update(any<DbMangaTrack>(), true) } coAnswers {
                val track = firstArg<DbMangaTrack>()
                if (assignLibrary) track.library_id = 42
                writes += track.remote_id to track.last_chapter_read
                onUpdate()
                track
            }
            coEvery { tracker.update(any<DbAnimeTrack>(), true) } coAnswers {
                val track = firstArg<DbAnimeTrack>()
                if (assignLibrary) track.library_id = 42
                writes += track.remote_id to track.last_episode_seen
                onUpdate()
                track
            }
            Injekt = InjektScope(DefaultRegistrar())
            Injekt.addSingleton(getMangaTracks)
            Injekt.addSingleton(getAnimeTracks)
            Injekt.addSingleton(mangaStore)
            Injekt.addSingleton(animeStore)
            Injekt.addSingleton(trackChapter)
            Injekt.addSingleton(trackEpisode)
            val parameters = mockk<WorkerParameters>()
            every { parameters.runAttemptCount } returns 0
            worker = when (stack) {
                Stack.MANGA -> DelayedMangaTrackingUpdateJob(preferences.context, parameters)
                Stack.ANIME -> DelayedAnimeTrackingUpdateJob(preferences.context, parameters)
            }
        }

        suspend fun insert(remoteId: Long) {
            when (stack) {
                Stack.MANGA -> {
                    val parent = requireNotNull(
                        mangaTitles.insertManga(
                            Manga.create().copy(source = 1, url = "/title/$remoteId", title = "Manga $remoteId"),
                        ),
                    )
                    mangaRepository.insertManga(
                        MangaTrack(-1, parent, 1, remoteId, null, "Manga", 0.0, 100, 0, 0.0, "", 0, 0, false),
                    )
                    manga = mangaRepository.getTracksByMangaId(parent).single()
                }
                Stack.ANIME -> {
                    val parent = requireNotNull(
                        animeTitles.insertAnime(
                            Anime.create().copy(source = 1, url = "/title/$remoteId", title = "Anime $remoteId"),
                        ),
                    )
                    animeRepository.insertAnime(
                        AnimeTrack(-1, parent, 1, remoteId, null, "Anime", 0.0, 100, 0, 0.0, "", 0, 0, false),
                    )
                    anime = animeRepository.getTracksByAnimeId(parent).single()
                }
            }
        }

        suspend fun relink(remoteId: Long? = null, remoteUrl: String? = null, libraryId: Long? = null) {
            when (stack) {
                Stack.MANGA -> {
                    val current = manga!!
                    mangaRepository.insertManga(
                        current.copy(
                            remoteId = remoteId ?: current.remoteId,
                            remoteUrl = remoteUrl ?: current.remoteUrl,
                            libraryId = libraryId ?: current.libraryId,
                        ),
                    )
                    manga = mangaRepository.getTrackByMangaId(current.id)
                }
                Stack.ANIME -> {
                    val current = anime!!
                    animeRepository.insertAnime(
                        current.copy(
                            remoteId = remoteId ?: current.remoteId,
                            remoteUrl = remoteUrl ?: current.remoteUrl,
                            libraryId = libraryId ?: current.libraryId,
                        ),
                    )
                    anime = animeRepository.getTrackByAnimeId(current.id)
                }
            }
        }

        suspend fun delete() {
            when (stack) {
                Stack.MANGA -> mangaRepository.delete(manga!!.mangaId, 1)
                Stack.ANIME -> animeRepository.delete(anime!!.animeId, 1)
            }
        }

        fun add(progress: Double) {
            when (stack) {
                Stack.MANGA -> mangaStore.addManga(manga!!, progress, account)
                Stack.ANIME -> animeStore.addAnime(anime!!, progress, account)
            }
        }

        suspend fun advance(progress: Double) {
            when (stack) {
                Stack.MANGA -> trackChapter.await(preferences.context, manga!!.mangaId, progress, false)
                Stack.ANIME -> trackEpisode.await(preferences.context, anime!!.animeId, progress, false)
            }
        }

        fun pending(): List<Pair<Long, Float>> = when (stack) {
            Stack.MANGA -> mangaStore.getMangaItems().map { it.identity.remoteId to it.lastChapterRead }
            Stack.ANIME -> animeStore.getAnimeItems().map { it.identity.remoteId to it.lastEpisodeSeen }
        }

        suspend fun localProgress(): Double = when (stack) {
            Stack.MANGA -> mangaRepository.getTrackByMangaId(trackId)!!.lastChapterRead
            Stack.ANIME -> animeRepository.getTrackByAnimeId(trackId)!!.lastEpisodeSeen
        }

        override fun close() {
            Injekt = originalInjekt
            runBlocking {
                withContext(dispatcher) {
                    mangaDriver.closeConnection(mangaDriver.getConnection())
                    animeDriver.closeConnection(animeDriver.getConnection())
                }
            }
            mangaDriver.close()
            animeDriver.close()
            dispatcher.close()
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        fun recipients(): Stream<Arguments> = Stack.entries.flatMap { stack ->
            RecipientChange.entries.map { Arguments.of(stack, it) }
        }.stream()
    }
}
