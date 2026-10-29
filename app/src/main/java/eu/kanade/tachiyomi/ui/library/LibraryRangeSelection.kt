package eu.kanade.tachiyomi.ui.library

import androidx.compose.runtime.Immutable

internal fun <T> selectLibraryRange(
    items: List<T>,
    selection: List<T>,
    target: T,
    sameCategory: Boolean,
    anchorId: Long? = null,
    id: (T) -> Long,
): List<T> {
    val selectedIds = selection.mapTo(mutableSetOf(), id)
    val anchor = anchorId ?: selection.lastOrNull()?.let(id)
    val anchorIndex = items.indexOfFirst { id(it) == anchor }
    val targetIndex = items.indexOfFirst { id(it) == id(target) }
    if (!sameCategory || anchorIndex < 0 || targetIndex < 0) {
        return if (id(target) in selectedIds) selection else selection + target
    }
    val range = minOf(anchorIndex, targetIndex)..maxOf(anchorIndex, targetIndex)
    return selection + range.mapNotNull { index -> items[index].takeIf { selectedIds.add(id(it)) } }
}

@Immutable
data class LibrarySelectionAnchor(val id: Long, val category: Long)

internal fun updateLibrarySelectionAnchors(
    anchors: List<LibrarySelectionAnchor>,
    selectedIds: List<Long>,
    category: Long,
    targetId: Long? = null,
): List<LibrarySelectionAnchor> {
    val selected = selectedIds.toSet()
    val retained = anchors.filter { it.id in selected }
    val retainedIds = retained.mapTo(mutableSetOf()) { it.id }
    val updated = retained + selectedIds.mapNotNull { id ->
        LibrarySelectionAnchor(id, category).takeIf { retainedIds.add(id) }
    }
    return if (targetId != null && targetId in selected) {
        updated.filterNot { it.id == targetId } + LibrarySelectionAnchor(targetId, category)
    } else {
        updated
    }
}
