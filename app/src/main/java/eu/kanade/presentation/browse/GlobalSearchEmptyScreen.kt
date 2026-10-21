package eu.kanade.presentation.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

internal enum class GlobalSearchEmptyState {
    Prompt,
    NoPinnedSources,
    NoEnabledSources,
    Loading,
    HiddenErrors,
    NoResults,
}

internal fun globalSearchEmptyState(
    hasSearched: Boolean,
    totalSources: Int,
    visibleSources: Int,
    pendingSources: Int,
    failedSources: Int,
    pinnedOnly: Boolean,
): GlobalSearchEmptyState? = when {
    !hasSearched -> GlobalSearchEmptyState.Prompt
    totalSources == 0 && pinnedOnly -> GlobalSearchEmptyState.NoPinnedSources
    totalSources == 0 -> GlobalSearchEmptyState.NoEnabledSources
    visibleSources > 0 -> null
    pendingSources > 0 -> GlobalSearchEmptyState.Loading
    failedSources > 0 -> GlobalSearchEmptyState.HiddenErrors
    else -> GlobalSearchEmptyState.NoResults
}

@Composable
internal fun GlobalSearchEmptyScreen(
    state: GlobalSearchEmptyState,
    onShowAllSources: () -> Unit,
    onShowErrors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == GlobalSearchEmptyState.Loading) {
        LoadingScreen(modifier)
        return
    }
    val message = when (state) {
        GlobalSearchEmptyState.Prompt -> AYMR.strings.global_search_prompt
        GlobalSearchEmptyState.NoPinnedSources -> AYMR.strings.global_search_no_pinned_sources
        GlobalSearchEmptyState.NoEnabledSources -> AYMR.strings.global_search_no_enabled_sources
        GlobalSearchEmptyState.HiddenErrors -> AYMR.strings.global_search_hidden_errors
        else -> MR.strings.no_results_found
    }
    val actions = when (state) {
        GlobalSearchEmptyState.NoPinnedSources -> persistentListOf(
            EmptyScreenAction(AYMR.strings.global_search_all_sources, Icons.Outlined.DoneAll, onShowAllSources),
        )
        GlobalSearchEmptyState.HiddenErrors -> persistentListOf(
            EmptyScreenAction(AYMR.strings.global_search_show_errors, Icons.Outlined.ErrorOutline, onShowErrors),
        )
        else -> null
    }
    EmptyScreen(stringRes = message, modifier = modifier, actions = actions)
}
