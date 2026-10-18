package eu.kanade.tachiyomi.data.download

import android.content.SharedPreferences
import androidx.core.content.edit

internal class DownloadStartState(private val store: SharedPreferences) {

    fun canStartAutomatically(): Boolean = synchronized(store) { !store.getBoolean("manual_paused", false) }

    fun prepareAutomaticStart(): Boolean = synchronized(store) {
        if (!canStartAutomatically()) return@synchronized false
        store.edit { putBoolean("resume", true) }
        true
    }

    fun resumeManually() = synchronized(store) {
        store.edit {
            putBoolean("manual_paused", false)
            putBoolean("resume", true)
        }
    }

    fun pauseByUser() = synchronized(store) {
        store.edit {
            putBoolean("manual_paused", true)
            putBoolean("resume", false)
        }
    }
}
