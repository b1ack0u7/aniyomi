package eu.kanade.tachiyomi.ui.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category

internal fun <T> libraryTracksFlow(
    categories: Flow<List<Category>>,
    trackingFilters: Flow<Map<Long, TriState>>,
    usesTrackerSort: (Category) -> Boolean,
    subscribeTracks: () -> Flow<Map<Long, List<T>>>,
): Flow<Map<Long, List<T>>> {
    return combine(categories, trackingFilters) { visibleCategories, filters ->
        filters.values.any { it != TriState.DISABLED } || visibleCategories.any(usesTrackerSort)
    }
        .distinctUntilChanged()
        .flatMapLatest { needsTracks -> if (needsTracks) subscribeTracks() else flowOf(emptyMap()) }
}
