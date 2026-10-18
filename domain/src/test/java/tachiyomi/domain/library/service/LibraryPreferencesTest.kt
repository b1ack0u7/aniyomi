package tachiyomi.domain.library.service

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LibraryPreferencesTest {

    @Test
    fun `both libraries retain every update from concurrent sources`() {
        val preferences = preferences()
        preferences.newMangaUpdatesCount().set(7)
        preferences.newAnimeUpdatesCount().set(11)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(5)
        try {
            val tasks = (1..5).map { count ->
                executor.submit {
                    start.await()
                    repeat(100) {
                        preferences.incrementNewMangaUpdatesCount(count)
                        preferences.incrementNewAnimeUpdatesCount(count * 2)
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        preferences.newMangaUpdatesCount().get() shouldBe 1507
        preferences.newAnimeUpdatesCount().get() shouldBe 3011
    }

    @Test
    fun `resetting one library leaves the other count intact and accepts subsequent updates`() {
        val preferences = preferences()
        preferences.incrementNewMangaUpdatesCount(2)
        preferences.incrementNewAnimeUpdatesCount(3)

        preferences.resetNewMangaUpdatesCount()
        preferences.newMangaUpdatesCount().get() shouldBe 0
        preferences.newAnimeUpdatesCount().get() shouldBe 3
        preferences.incrementNewMangaUpdatesCount(5)
        preferences.resetNewAnimeUpdatesCount()

        preferences.newMangaUpdatesCount().get() shouldBe 5
        preferences.newAnimeUpdatesCount().get() shouldBe 0
    }

    @Test
    fun `success timestamps are independent and do not reuse the ambiguous legacy timestamp`() {
        val values = ConcurrentHashMap<String, Any>()
        values[Preference.appStateKey("library_update_last_timestamp")] = 123L
        val preferences = preferences(values)

        preferences.mangaLastUpdatedTimestamp().get() shouldBe 0L
        preferences.animeLastUpdatedTimestamp().get() shouldBe 0L
        preferences.mangaLastUpdatedTimestamp().set(456L)
        preferences.animeLastUpdatedTimestamp().get() shouldBe 0L
        preferences.animeLastUpdatedTimestamp().set(789L)

        preferences.mangaLastUpdatedTimestamp().get() shouldBe 456L
        preferences.animeLastUpdatedTimestamp().get() shouldBe 789L
        Preference.isAppState(preferences.mangaLastUpdatedTimestamp().key()) shouldBe true
        Preference.isAppState(preferences.animeLastUpdatedTimestamp().key()) shouldBe true
    }

    private fun preferences(values: ConcurrentHashMap<String, Any> = ConcurrentHashMap()): LibraryPreferences {
        val store = mockk<PreferenceStore>()
        val ints = ConcurrentHashMap<String, Preference<Int>>()
        val longs = ConcurrentHashMap<String, Preference<Long>>()
        every { store.getInt(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Int>()
            ints.computeIfAbsent(key) { preference(key, default, values) }
        }
        every { store.getLong(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Long>()
            longs.computeIfAbsent(key) { preference(key, default, values) }
        }
        return LibraryPreferences(store)
    }

    private inline fun <reified T : Any> preference(
        preferenceKey: String,
        default: T,
        values: ConcurrentHashMap<String, Any>,
    ): Preference<T> = mockk {
        every { key() } returns preferenceKey
        every { get() } answers { values[preferenceKey] as? T ?: default }
        every { set(any()) } answers {
            values[preferenceKey] = firstArg<T>()
            Unit
        }
    }
}
