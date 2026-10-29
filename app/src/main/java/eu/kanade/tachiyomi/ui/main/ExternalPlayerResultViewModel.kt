package eu.kanade.tachiyomi.ui.main

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class ExternalPlayerResultViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private val results = savedState.getStateFlow<ArrayList<Intent>>(RESULTS, arrayListOf())
    private var processing: Job? = null

    fun enqueue(animeId: Long, episodeId: Long, data: Intent?, legacyIds: Boolean = false) {
        if (data == null || animeId <= 0 || episodeId <= 0) return
        savedState[ANIME_IDS] = (savedState.get<LongArray>(ANIME_IDS) ?: longArrayOf()) + animeId
        savedState[EPISODE_IDS] = (savedState.get<LongArray>(EPISODE_IDS) ?: longArrayOf()) + episodeId
        savedState[LEGACY_IDS] = (savedState.get<BooleanArray>(LEGACY_IDS) ?: booleanArrayOf()) + legacyIds
        savedState[RESULTS] = ArrayList(results.value + data)
    }

    fun start(
        awaitReady: suspend () -> Unit,
        initialize: suspend (Long, Long, Boolean) -> Boolean,
        consume: (Intent) -> Unit,
    ) {
        if (processing?.isActive == true) return
        processing = viewModelScope.launch {
            results.filter { it.isNotEmpty() }.collect { pending ->
                val data = pending.first()
                val animeId = savedState.get<LongArray>(ANIME_IDS)?.firstOrNull()
                val episodeId = savedState.get<LongArray>(EPISODE_IDS)?.firstOrNull()
                val legacyIds = savedState.get<BooleanArray>(LEGACY_IDS)?.firstOrNull() ?: false
                awaitReady()
                if (animeId != null && episodeId != null && initialize(animeId, episodeId, legacyIds)) {
                    consume(data)
                }
                savedState[ANIME_IDS] = (savedState.get<LongArray>(ANIME_IDS) ?: longArrayOf()).drop(1).toLongArray()
                savedState[EPISODE_IDS] =
                    (savedState.get<LongArray>(EPISODE_IDS) ?: longArrayOf()).drop(1).toLongArray()
                savedState[LEGACY_IDS] =
                    (savedState.get<BooleanArray>(LEGACY_IDS) ?: booleanArrayOf()).drop(1).toBooleanArray()
                savedState[RESULTS] = ArrayList(results.value.drop(1))
            }
        }
    }

    private companion object {
        const val RESULTS = "external_player_results"
        const val ANIME_IDS = "external_player_result_anime_ids"
        const val EPISODE_IDS = "external_player_result_episode_ids"
        const val LEGACY_IDS = "external_player_result_legacy_ids"
    }
}
