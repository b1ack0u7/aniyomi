package eu.kanade.domain.track

import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.domain.track.manga.interactor.TrackChapter
import eu.kanade.domain.track.manga.model.toDbTrack
import eu.kanade.domain.track.manga.model.toDomainTrack
import eu.kanade.domain.track.manga.store.DelayedMangaTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.domain.track.anime.model.identity
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.domain.track.manga.interactor.InsertMangaTrack
import tachiyomi.domain.track.manga.model.MangaTrack
import tachiyomi.domain.track.manga.model.identity
import tachiyomi.domain.track.manga.repository.MangaTrackRepository
import tachiyomi.domain.track.model.TrackingIdentity
import java.io.IOException
import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack as DbAnimeTrack
import eu.kanade.tachiyomi.data.database.models.manga.MangaTrack as DbMangaTrack

class TrackProgressRetryTest {

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `online network failure stays pending until a successful retry`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.failUpdate = true

        fixture.await(8.0)

        fixture.pending() shouldBe listOf(7L to 8f)
        fixture.scheduled shouldBe 1
        fixture.failUpdate = false
        fixture.await(8.0, setupJob = false)
        fixture.progress() shouldBe 8.0
        fixture.pending() shouldBe emptyList()
        fixture.scheduled shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `refresh failure retains pending and schedules durable retry before RPC`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.onRefresh = {
            fixture.scheduled shouldBe 1
            fixture.pending() shouldBe listOf(7L to 12f)
            throw IOException("offline")
        }

        fixture.await(12.0)

        fixture.pending() shouldBe listOf(7L to 12f)
        fixture.progress() shouldBe 0.0
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `failed local persistence does not acknowledge a remote success`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.failPersistence = true

        fixture.await(8.0)

        fixture.remoteWrites shouldBe listOf(8.0)
        fixture.pending() shouldBe listOf(7L to 8f)
        fixture.progress() shouldBe 0.0
        fixture.failPersistence = false
        fixture.await(8.0, setupJob = false)
        fixture.pending() shouldBe emptyList()
        fixture.progress() shouldBe 8.0
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `missing lookup retains pending instead of treating database failure as deletion`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.lookupMissing = true

        fixture.await(8.0)

        fixture.remoteWrites shouldBe emptyList()
        fixture.pending() shouldBe listOf(7L to 8f)
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `already persisted progress acknowledges pending without sending stale progress`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.setProgress(20.0)
        fixture.add(8.0)

        fixture.await(8.0, setupJob = false)

        fixture.remoteWrites shouldBe emptyList()
        fixture.pending() shouldBe emptyList()
        fixture.progress() shouldBe 20.0
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `remote progress ahead of pending is preserved`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.remoteProgress = 25.0

        fixture.await(8.0)

        fixture.remoteWrites shouldBe listOf(25.0)
        fixture.progress() shouldBe 25.0
        fixture.pending() shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `earlier completion preserves newer pending and serialized retry advances it`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.onUpdate = { progress ->
            if (progress == 8.0) {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = async { fixture.await(8.0) }
        entered.await()
        val second = async { fixture.await(12.0) }
        testScheduler.runCurrent()
        fixture.pending() shouldBe listOf(7L to 12f)
        release.complete(Unit)
        first.await()
        second.await()

        fixture.remoteWrites shouldBe listOf(8.0, 12.0)
        fixture.progress() shouldBe 12.0
        fixture.pending() shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `older request that arrives after newer completion cannot regress remote or local progress`(
        stack: Stack,
    ) = runTest {
        val fixture = Fixture(stack)
        fixture.await(20.0)

        fixture.await(8.0)

        fixture.remoteWrites shouldBe listOf(20.0)
        fixture.progress() shouldBe 20.0
        fixture.pending() shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `worker retry targets exactly the queued track`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.includeOtherTrack = true

        fixture.await(8.0, setupJob = false, onlyTrackId = 7)

        fixture.updatedIds shouldBe listOf(7L)
        fixture.pending() shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `logged out tracker preserves existing pending work`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.loggedIn = false
        fixture.add(8.0)

        fixture.await(8.0, setupJob = false)

        fixture.pending() shouldBe listOf(7L to 8f)
        fixture.remoteWrites shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `relinked recipient never inherits old pending progress`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        val oldIdentity = fixture.identity()
        fixture.add(20.0)
        fixture.relink(2002)

        fixture.replay(oldIdentity, "account-a", 20.0)

        fixture.remoteWrites shouldBe emptyList()
        fixture.pending() shouldBe listOf(7L to 20f)
        fixture.await(3.0)
        fixture.remoteWrites shouldBe listOf(3.0)
        fixture.pending() shouldBe listOf(7L to 20f)
        fixture.progress() shouldBe 3.0
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `relink during refresh prevents update to the newly bound recipient`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.onRefresh = { fixture.relink(2002) }

        fixture.await(8.0)

        fixture.remoteWrites shouldBe emptyList()
        fixture.pending() shouldBe listOf(7L to 8f)
        fixture.progress() shouldBe 0.0
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `relink during RPC never persists old response over replacement binding`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.onUpdate = { fixture.relink(2002) }

        fixture.await(8.0)

        fixture.identity().remoteId shouldBe 2002L
        fixture.progress() shouldBe 0.0
        fixture.pending() shouldBe listOf(7L to 8f)
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `switching accounts quarantines old pending and new account cannot consume it`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.add(20.0)
        val identity = fixture.identity()
        fixture.account = "account-b"

        fixture.replay(identity, "account-a", 20.0)

        fixture.remoteWrites shouldBe emptyList()
        fixture.await(3.0)
        fixture.remoteWrites shouldBe listOf(3.0)
        fixture.pending() shouldBe listOf(7L to 20f)
        fixture.progress() shouldBe 3.0
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `account change during refresh prevents RPC`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.onRefresh = { fixture.account = "account-b" }

        fixture.await(8.0)

        fixture.remoteWrites shouldBe emptyList()
        fixture.pending() shouldBe listOf(7L to 8f)
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `account change during RPC retains old pending without local acknowledgement`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.onUpdate = { fixture.account = "account-b" }

        fixture.await(8.0)

        fixture.progress() shouldBe 0.0
        fixture.pending() shouldBe listOf(7L to 8f)
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `library ID discovery transfers concurrent higher pending and retry completes it`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.remoteLibraryId = 42
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.onUpdate = { progress ->
            if (progress == 8.0) {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = async { fixture.await(8.0) }
        entered.await()
        val second = async { fixture.await(12.0) }
        testScheduler.runCurrent()
        release.complete(Unit)
        first.await()
        second.await()

        fixture.pending() shouldBe listOf(7L to 12f)
        fixture.pendingIdentity().libraryId shouldBe 42L
        fixture.replay(fixture.pendingIdentity(), "account-a", 12.0)
        fixture.remoteWrites shouldBe listOf(8.0, 12.0)
        fixture.pending() shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `enhanced tracker update returning search without local identity still acknowledges sent track`(stack: Stack) =
        runTest {
            val fixture = Fixture(stack)
            fixture.returnSearchWithoutIdentity = true

            fixture.await(8.0)

            fixture.remoteWrites shouldBe listOf(8.0)
            fixture.progress() shouldBe 8.0
            fixture.pending() shouldBe emptyList()
        }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `unexpected change to a resolved library ID retains binding and pending`(stack: Stack) = runTest {
        val fixture = Fixture(stack)
        fixture.mangaTrack = fixture.mangaTrack.copy(libraryId = 42)
        fixture.animeTrack = fixture.animeTrack.copy(libraryId = 42)
        fixture.remoteLibraryId = 99

        fixture.await(8.0)

        fixture.remoteWrites shouldBe emptyList()
        fixture.identity().libraryId shouldBe 42L
        fixture.pendingIdentity().libraryId shouldBe 42L
        fixture.pending() shouldBe listOf(7L to 8f)
    }

    enum class Stack { MANGA, ANIME }

    private class Fixture(val stack: Stack) {
        val preferences = TrackingPreferencesFixture()
        val mangaStore = DelayedMangaTrackingStore(preferences.context)
        val animeStore = DelayedAnimeTrackingStore(preferences.context)
        var mangaTrack = MangaTrack(7, 101, 1, 42, null, "Manga", 0.0, 100, 0, 0.0, "", 0, 0, false)
        var animeTrack = AnimeTrack(7, 202, 1, 43, null, "Anime", 0.0, 100, 0, 0.0, "", 0, 0, false)
        var failUpdate = false
        var failPersistence = false
        var lookupMissing = false
        var includeOtherTrack = false
        var loggedIn = true
        var account = "account-a"
        var remoteProgress = 0.0
        var remoteLibraryId: Long? = null
        var returnSearchWithoutIdentity = false
        var scheduled = 0
        var onRefresh: suspend () -> Unit = {}
        var onUpdate: suspend (Double) -> Unit = {}
        val remoteWrites = mutableListOf<Double>()
        val updatedIds = mutableListOf<Long>()
        private val manager = mockk<TrackerManager>()
        private val tracker = mockk<MyAnimeList>()
        private val mangaRepository = mockk<MangaTrackRepository>()
        private val animeRepository = mockk<AnimeTrackRepository>()
        private val trackChapter = TrackChapter(
            GetMangaTracks(mangaRepository),
            manager,
            InsertMangaTrack(mangaRepository),
            mangaStore,
            { scheduled++ },
        )
        private val trackEpisode = TrackEpisode(
            GetAnimeTracks(animeRepository),
            manager,
            InsertAnimeTrack(animeRepository),
            animeStore,
            { scheduled++ },
        )

        init {
            every { manager.get(any()) } returns tracker
            every { tracker.isLoggedIn } answers { loggedIn }
            every { tracker.getUsername() } answers { account }
            coEvery { mangaRepository.getTracksByMangaId(101) } answers {
                listOf(mangaTrack) + if (includeOtherTrack) listOf(mangaTrack.copy(id = 8)) else emptyList()
            }
            coEvery { animeRepository.getTracksByAnimeId(202) } answers {
                listOf(animeTrack) + if (includeOtherTrack) listOf(animeTrack.copy(id = 8)) else emptyList()
            }
            coEvery { mangaRepository.getTrackByMangaId(any()) } answers {
                if (lookupMissing) null else mangaTrack.copy(id = firstArg())
            }
            coEvery { animeRepository.getTrackByAnimeId(any()) } answers {
                if (lookupMissing) null else animeTrack.copy(id = firstArg())
            }
            coEvery { mangaRepository.updateIfSameIdentity(any(), any()) } answers {
                if (failPersistence) throw IOException("database unavailable")
                if (mangaTrack.identity != secondArg<MangaTrack>().identity) {
                    false
                } else {
                    mangaTrack = firstArg<MangaTrack>().copy(
                        lastChapterRead = maxOf(mangaTrack.lastChapterRead, firstArg<MangaTrack>().lastChapterRead),
                    )
                    true
                }
            }
            coEvery { animeRepository.updateIfSameIdentity(any(), any()) } answers {
                if (failPersistence) throw IOException("database unavailable")
                if (animeTrack.identity != secondArg<AnimeTrack>().identity) {
                    false
                } else {
                    animeTrack = firstArg<AnimeTrack>().copy(
                        lastEpisodeSeen = maxOf(animeTrack.lastEpisodeSeen, firstArg<AnimeTrack>().lastEpisodeSeen),
                    )
                    true
                }
            }
            coEvery { tracker.refresh(any<DbMangaTrack>()) } coAnswers {
                onRefresh()
                firstArg<DbMangaTrack>().toDomainTrack()!!.copy(
                    lastChapterRead = remoteProgress,
                    libraryId = remoteLibraryId,
                ).toDbTrack()
            }
            coEvery { tracker.refresh(any<DbAnimeTrack>()) } coAnswers {
                onRefresh()
                firstArg<DbAnimeTrack>().toDomainTrack()!!.copy(
                    lastEpisodeSeen = remoteProgress,
                    libraryId = remoteLibraryId,
                ).toDbTrack()
            }
            coEvery { tracker.update(any<DbMangaTrack>(), true) } coAnswers {
                val track = firstArg<DbMangaTrack>()
                onUpdate(track.last_chapter_read)
                if (failUpdate) throw IOException("connection interrupted")
                remoteWrites += track.last_chapter_read
                updatedIds += track.id!!
                if (returnSearchWithoutIdentity) {
                    trackingManga().toDbTrack().apply {
                        id = null
                        manga_id = 0
                    }
                } else {
                    track
                }
            }
            coEvery { tracker.update(any<DbAnimeTrack>(), true) } coAnswers {
                val track = firstArg<DbAnimeTrack>()
                onUpdate(track.last_episode_seen)
                if (failUpdate) throw IOException("connection interrupted")
                remoteWrites += track.last_episode_seen
                updatedIds += track.id!!
                if (returnSearchWithoutIdentity) {
                    trackingAnime().toDbTrack().apply {
                        id = null
                        anime_id = 0
                    }
                } else {
                    track
                }
            }
        }

        suspend fun await(progress: Double, setupJob: Boolean = true, onlyTrackId: Long? = null) {
            when (stack) {
                Stack.MANGA -> trackChapter.await(
                    preferences.context,
                    101,
                    progress,
                    setupJob,
                    onlyTrackId?.let { mangaTrack.copy(id = it).identity },
                )
                Stack.ANIME -> trackEpisode.await(
                    preferences.context,
                    202,
                    progress,
                    setupJob,
                    onlyTrackId?.let { animeTrack.copy(id = it).identity },
                )
            }
        }

        fun identity(): TrackingIdentity = when (stack) {
            Stack.MANGA -> mangaTrack.identity
            Stack.ANIME -> animeTrack.identity
        }

        fun pendingIdentity(): TrackingIdentity = when (stack) {
            Stack.MANGA -> mangaStore.getMangaItems().single().identity
            Stack.ANIME -> animeStore.getAnimeItems().single().identity
        }

        fun relink(remoteId: Long) {
            mangaTrack = mangaTrack.copy(remoteId = remoteId)
            animeTrack = animeTrack.copy(remoteId = remoteId)
        }

        suspend fun replay(identity: TrackingIdentity, account: String, progress: Double) {
            when (stack) {
                Stack.MANGA -> trackChapter.await(preferences.context, 101, progress, false, identity, account)
                Stack.ANIME -> trackEpisode.await(preferences.context, 202, progress, false, identity, account)
            }
        }

        fun progress() = when (stack) {
            Stack.MANGA -> mangaTrack.lastChapterRead
            Stack.ANIME -> animeTrack.lastEpisodeSeen
        }

        fun setProgress(progress: Double) {
            mangaTrack = mangaTrack.copy(lastChapterRead = progress)
            animeTrack = animeTrack.copy(lastEpisodeSeen = progress)
        }

        fun add(progress: Double) = when (stack) {
            Stack.MANGA -> mangaStore.addManga(mangaTrack, progress, account)
            Stack.ANIME -> animeStore.addAnime(animeTrack, progress, account)
        }

        fun pending(): List<Pair<Long, Float>> = when (stack) {
            Stack.MANGA -> mangaStore.getMangaItems().map { it.trackId to it.lastChapterRead }
            Stack.ANIME -> animeStore.getAnimeItems().map { it.trackId to it.lastEpisodeSeen }
        }
    }
}
