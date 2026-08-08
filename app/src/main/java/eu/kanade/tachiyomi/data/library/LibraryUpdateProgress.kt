package eu.kanade.tachiyomi.data.library

/**
 * How far along a running library update is, published so screens can show it without relying on
 * the progress notification. Null while no update is running.
 */
data class LibraryUpdateProgress(
    val current: Int,
    val total: Int,
    val title: String? = null,
)
