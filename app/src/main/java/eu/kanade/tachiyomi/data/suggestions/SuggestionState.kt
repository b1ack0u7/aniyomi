package eu.kanade.tachiyomi.data.suggestions

import androidx.compose.runtime.Immutable

@Immutable
sealed interface SuggestionState {
    data object Idle : SuggestionState

    data object Loading : SuggestionState

    data object Disabled : SuggestionState

    data object Empty : SuggestionState

    @Immutable
    data class Success(val items: List<SuggestionItem>) : SuggestionState

    data class Error(val message: String) : SuggestionState
}
