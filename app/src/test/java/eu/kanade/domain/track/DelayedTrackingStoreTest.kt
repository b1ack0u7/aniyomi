package eu.kanade.domain.track

import eu.kanade.domain.track.anime.store.DelayedAnimeTrackingStore
import eu.kanade.domain.track.manga.store.DelayedMangaTrackingStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DelayedTrackingStoreTest {

    @Test
    fun `same track ID is independent across stacks and survives recreation`() {
        val fixture = TrackingPreferencesFixture()
        val manga = DelayedMangaTrackingStore(fixture.context)
        val anime = DelayedAnimeTrackingStore(fixture.context)
        manga.addManga(trackingManga(7), 20.0, "account-a")
        anime.addAnime(trackingAnime(7), 8.0, "account-a")

        manga.removeMangaItem(trackingManga(7), 20.0, "account-a")

        DelayedMangaTrackingStore(fixture.context).getMangaItems() shouldBe emptyList()
        DelayedAnimeTrackingStore(fixture.context).getAnimeItems().single().lastEpisodeSeen shouldBe 8f
        anime.removeAnimeItem(trackingAnime(7), 8.0, "account-a")
        anime.getAnimeItems() shouldBe emptyList()
    }

    @Test
    fun `ambiguous legacy floats and strings are preserved and never replayed by either stack`() {
        val fixture = TrackingPreferencesFixture()
        val legacy = fixture.values("tracking_queue")
        legacy.putAll(mapOf("7" to 15f, "8" to "42:23.5", "broken" to "unknown"))
        val before = legacy.toMap()
        val manga = DelayedMangaTrackingStore(fixture.context)
        val anime = DelayedAnimeTrackingStore(fixture.context)

        manga.getMangaItems() shouldBe emptyList()
        anime.getAnimeItems() shouldBe emptyList()
        manga.addManga(trackingManga(7), 3.0, "account-a")
        anime.addAnime(trackingAnime(8), 5.0, "account-a")
        manga.removeMangaItem(trackingManga(7), 3.0, "account-a")
        anime.removeAnimeItem(trackingAnime(8), 5.0, "account-a")

        legacy shouldBe before
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `acknowledgement preserves a newer update across instances`(stack: Stack) {
        val fixture = TrackingPreferencesFixture()
        val first = store(fixture, stack)
        val second = store(fixture, stack)
        first.add(7, 8.0)
        second.add(7, 12.0)

        first.remove(7, 8.0)

        second.items() shouldBe listOf(7L to 12f)
        second.remove(7, 12.0)
        first.items() shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `fractional progress can be acknowledged without float rounding leftovers`(stack: Stack) {
        val fixture = TrackingPreferencesFixture()
        val store = store(fixture, stack)
        store.add(7, 8.1)
        store.remove(7, 8.1)
        store.items() shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `invalid records remain intact without blocking valid records`(stack: Stack) {
        val fixture = TrackingPreferencesFixture()
        val records = fixture.values(stack.file)
        records.putAll(mapOf("bad" to 1f, "2" to "3.0", "3" to Float.NaN, "4" to -1f, "5" to 9f))
        val store = store(fixture, stack)
        store.add(6, 9.0)
        val before = records.toMap()

        store.items() shouldBe listOf(6L to 9f)
        store.remove(2, 50.0)
        records shouldBe before
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `concurrent producers retain the greatest pending progress`(stack: Stack) {
        val fixture = TrackingPreferencesFixture()
        val first = store(fixture, stack)
        val second = store(fixture, stack)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..40).map { progress ->
                executor.submit {
                    start.await()
                    (if (progress % 2 == 0) first else second).add(7, progress.toDouble())
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            first.items() shouldBe listOf(7L to 40f)
        } finally {
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `update lock is shared by separately constructed stores`(stack: Stack) = runTest {
        val fixture = TrackingPreferencesFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            store(fixture, stack).withLock(7) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var secondEntered = false
        val second = async { store(fixture, stack).withLock(7) { secondEntered = true } }
        testScheduler.runCurrent()
        secondEntered shouldBe false
        release.complete(Unit)
        first.await()
        second.await()
        secondEntered shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `different recipients with the same row ID coexist without overwriting`(stack: Stack) {
        val fixture = TrackingPreferencesFixture()
        when (stack) {
            Stack.MANGA -> {
                val store = DelayedMangaTrackingStore(fixture.context)
                val previous = trackingManga().copy(remoteId = 1001)
                val replacement = previous.copy(remoteId = 2002)
                store.addManga(previous, 20.0, "account-a")
                store.addManga(replacement, 3.0, "account-a")
                store.removeMangaItem(replacement, 3.0, "account-a")
                store.getMangaItems().single().identity.remoteId shouldBe 1001L
                store.getMangaItems().single().lastChapterRead shouldBe 20f
            }
            Stack.ANIME -> {
                val store = DelayedAnimeTrackingStore(fixture.context)
                val previous = trackingAnime().copy(remoteId = 1001)
                val replacement = previous.copy(remoteId = 2002)
                store.addAnime(previous, 20.0, "account-a")
                store.addAnime(replacement, 3.0, "account-a")
                store.removeAnimeItem(replacement, 3.0, "account-a")
                store.getAnimeItems().single().identity.remoteId shouldBe 1001L
                store.getAnimeItems().single().lastEpisodeSeen shouldBe 20f
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `proven library assignment redirects late producers after store recreation`(stack: Stack) {
        val fixture = TrackingPreferencesFixture()
        when (stack) {
            Stack.MANGA -> {
                val original = trackingManga()
                val updated = original.copy(libraryId = 42)
                DelayedMangaTrackingStore(fixture.context).apply {
                    addManga(original, 12.0, "account-a")
                    acknowledge(original, updated, 8.0, "account-a")
                }
                val recreated = DelayedMangaTrackingStore(fixture.context)
                recreated.addManga(original, 15.0, "account-a")
                recreated.getMangaItems().single().identity.libraryId shouldBe 42L
                recreated.getMangaItems().single().lastChapterRead shouldBe 15f
                recreated.removeMangaItem(updated, 15.0, "account-a")
                recreated.getMangaItems() shouldBe emptyList()
            }
            Stack.ANIME -> {
                val original = trackingAnime()
                val updated = original.copy(libraryId = 42)
                DelayedAnimeTrackingStore(fixture.context).apply {
                    addAnime(original, 12.0, "account-a")
                    acknowledge(original, updated, 8.0, "account-a")
                }
                val recreated = DelayedAnimeTrackingStore(fixture.context)
                recreated.addAnime(original, 15.0, "account-a")
                recreated.getAnimeItems().single().identity.libraryId shouldBe 42L
                recreated.getAnimeItems().single().lastEpisodeSeen shouldBe 15f
                recreated.removeAnimeItem(updated, 15.0, "account-a")
                recreated.getAnimeItems() shouldBe emptyList()
            }
        }
    }

    enum class Stack(val file: String) { MANGA("tracking_queue_manga"), ANIME("tracking_queue_anime") }

    private fun store(fixture: TrackingPreferencesFixture, stack: Stack): Store = when (stack) {
        Stack.MANGA -> DelayedMangaTrackingStore(fixture.context).let { store ->
            object : Store {
                override fun add(id: Long, progress: Double) = store.addManga(trackingManga(id), progress, "account-a")
                override fun remove(id: Long, progress: Double) =
                    store.removeMangaItem(trackingManga(id), progress, "account-a")
                override fun items() = store.getMangaItems().map { it.trackId to it.lastChapterRead }
                override suspend fun withLock(id: Long, action: suspend () -> Unit) = store.withUpdateLock(id, action)
            }
        }
        Stack.ANIME -> DelayedAnimeTrackingStore(fixture.context).let { store ->
            object : Store {
                override fun add(id: Long, progress: Double) = store.addAnime(trackingAnime(id), progress, "account-a")
                override fun remove(id: Long, progress: Double) =
                    store.removeAnimeItem(trackingAnime(id), progress, "account-a")
                override fun items() = store.getAnimeItems().map { it.trackId to it.lastEpisodeSeen }
                override suspend fun withLock(id: Long, action: suspend () -> Unit) = store.withUpdateLock(id, action)
            }
        }
    }

    private interface Store {
        fun add(id: Long, progress: Double)
        fun remove(id: Long, progress: Double)
        fun items(): List<Pair<Long, Float>>
        suspend fun withLock(id: Long, action: suspend () -> Unit)
    }
}
