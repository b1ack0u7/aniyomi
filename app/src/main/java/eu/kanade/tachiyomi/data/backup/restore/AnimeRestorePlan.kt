package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreStore.Index

data class AnimeRestorePlan(
    val ordered: List<Index>,
    val invalid: Set<Long>,
)

// Duplicate backup IDs resolve to the last entry, matching associateBy in the restorer.
fun orderAnimeRestore(entries: List<Index>): AnimeRestorePlan {
    val byId = entries.filter { it.backupId != null }.associateBy { it.backupId }
    val states = mutableMapOf<Long, VisitState>()
    val ordered = mutableListOf<Index>()
    val invalid = mutableSetOf<Long>()

    entries.forEach { entry ->
        if (entry.offset in states) return@forEach
        val path = mutableListOf<Index>()
        var current: Index? = entry
        while (current != null && current.offset !in states) {
            states[current.offset] = VisitState.VISITING
            path.add(current)
            current = current.parentId?.let(byId::get)
        }

        if (current != null && states[current.offset] != VisitState.COMPLETE) {
            path.forEach {
                states[it.offset] = VisitState.INVALID
                invalid.add(it.offset)
            }
        } else {
            path.asReversed().forEach {
                states[it.offset] = VisitState.COMPLETE
                ordered.add(it)
            }
        }
    }

    return AnimeRestorePlan(ordered, invalid)
}

private enum class VisitState {
    VISITING,
    COMPLETE,
    INVALID,
}
