package eu.kanade.tachiyomi.data.library

/**
 * Outcome of a finished library update run, published so the UI can report it back to the user.
 * Without it a run that found nothing is indistinguishable from one that never started.
 */
data class LibraryUpdateSummary(
    val newItems: Int,
    val failed: Int,
)
