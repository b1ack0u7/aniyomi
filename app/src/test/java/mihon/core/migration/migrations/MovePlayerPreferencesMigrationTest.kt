package mihon.core.migration.migrations

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class MovePlayerPreferencesMigrationTest {
    private val originalInjekt = Injekt

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
        unmockkStatic(PreferenceManager::class)
    }

    @Test
    fun `player preference migration never rewrites or reads untyped legacy tracking queue`() = runTest {
        val application = mockk<Application>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val key = "pref_skip_length_preference"
        val store = InMemoryPreferenceStore(sequenceOf(InMemoryPreference(key, 10, 10)))
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(application)
        Injekt.addSingleton(PlayerPreferences(store))
        Injekt.addSingleton(GesturePreferences(store))
        mockkStatic(PreferenceManager::class)
        every { PreferenceManager.getDefaultSharedPreferences(application) } returns preferences
        every { preferences.edit() } returns editor
        every { preferences.getString(key, null) } returns "15"

        MovePlayerPreferencesMigration().invoke(MigrationContext(false)) shouldBe true

        verify { editor.putInt(key, 15) }
        verify(exactly = 0) { application.getSharedPreferences("tracking_queue", any()) }
    }
}
