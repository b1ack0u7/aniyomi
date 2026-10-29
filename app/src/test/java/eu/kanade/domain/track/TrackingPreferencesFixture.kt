package eu.kanade.domain.track

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap

internal class TrackingPreferencesFixture {
    val context = mockk<Context>()
    private val files = ConcurrentHashMap<String, MutableMap<String, Any>>()
    private val preferences = ConcurrentHashMap<String, SharedPreferences>()

    init {
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
            preferences.computeIfAbsent(firstArg()) { name ->
                val values = values(name)
                val prefs = mockk<SharedPreferences>()
                every { prefs.all } answers { values.toMap() }
                every { prefs.edit() } answers {
                    val editor = mockk<SharedPreferences.Editor>(relaxed = true)
                    val changes = mutableMapOf<String, Any?>()
                    every { editor.putFloat(any(), any()) } answers {
                        changes[firstArg()] = secondArg<Float>()
                        editor
                    }
                    every { editor.putString(any(), any()) } answers {
                        changes[firstArg()] = secondArg<String>()
                        editor
                    }
                    every { editor.remove(any()) } answers {
                        changes[firstArg()] = null
                        editor
                    }
                    every { editor.apply() } answers {
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                    }
                    editor
                }
                prefs
            }
        }
    }

    fun values(file: String): MutableMap<String, Any> = files.computeIfAbsent(file) { ConcurrentHashMap() }
}

internal fun trackingManga(id: Long = 7) = tachiyomi.domain.track.manga.model.MangaTrack(
    id, 101, 1, 42, null, "Manga", 0.0, 100, 0, 0.0, "", 0, 0, false,
)

internal fun trackingAnime(id: Long = 7) = tachiyomi.domain.track.anime.model.AnimeTrack(
    id, 202, 1, 43, null, "Anime", 0.0, 100, 0, 0.0, "", 0, 0, false,
)
