package eu.kanade.tachiyomi.data.library

import java.io.File
import java.util.Locale

enum class LibraryUpdateType {
    MANGA,
    ANIME,
    ;

    fun notificationTag(entryId: Long): String = "library_update:${name.lowercase(Locale.ROOT)}:$entryId"

    fun createErrorFile(cacheDir: File): File = File.createTempFile(
        "aniyomi_${name.lowercase(Locale.ROOT)}_update_errors_",
        ".txt",
        cacheDir,
    )
}
