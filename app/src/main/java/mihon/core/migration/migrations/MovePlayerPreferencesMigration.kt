package mihon.core.migration.migrations

import android.app.Application
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext

class MovePlayerPreferencesMigration : Migration {
    override val version = 93f

    // more migrations for player prefs
    @Suppress("SwallowedException")
    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        val playerPreferences = migrationContext.get<PlayerPreferences>() ?: return false
        val gesturePreferences = migrationContext.get<GesturePreferences>() ?: return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        listOf(
            playerPreferences.defaultPlayerOrientationType(),
            gesturePreferences.skipLengthPreference(),
        ).forEach { pref ->
            if (pref.isSet()) {
                prefs.edit {
                    val oldString = try {
                        prefs.getString(pref.key(), null)
                    } catch (e: ClassCastException) {
                        null
                    } ?: return@edit
                    val newInt = oldString.toIntOrNull() ?: return@edit
                    putInt(pref.key(), newInt)
                }
            }
        }

        return true
    }
}
