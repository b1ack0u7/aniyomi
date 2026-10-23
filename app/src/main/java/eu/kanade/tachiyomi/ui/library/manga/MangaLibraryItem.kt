package eu.kanade.tachiyomi.ui.library.manga

import eu.kanade.tachiyomi.source.manga.getNameForMangaInfo
import mihon.domain.library.model.search.QueryNode
import mihon.feature.library.matches
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaLibraryItem(
    val libraryManga: LibraryManga,
    var downloadCount: Long = -1,
    var unreadCount: Long = -1,
    var isLocal: Boolean = false,
    var sourceLanguage: String = "",
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val cachedSourceName: String? = null,
    private val cachedSourceLang: String? = null,
) {
    val sourceName: String by lazy {
        cachedSourceName ?: sourceManager.getOrStub(libraryManga.manga.source).getNameForMangaInfo()
    }
    val sourceLang: String by lazy { cachedSourceLang ?: sourceManager.getOrStub(libraryManga.manga.source).lang }

    fun matches(constraint: String): Boolean = QueryNode.from(constraint).matches(this)
}
