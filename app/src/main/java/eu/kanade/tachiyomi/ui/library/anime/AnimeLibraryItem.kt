package eu.kanade.tachiyomi.ui.library.anime

import eu.kanade.tachiyomi.source.anime.getNameForAnimeInfo
import mihon.domain.library.model.search.QueryNode
import mihon.feature.library.matches
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class AnimeLibraryItem(
    val libraryAnime: LibraryAnime,
    val category: Long = libraryAnime.categories.first(),
    var downloadCount: Long = -1,
    var unseenCount: Long = -1,
    var isLocal: Boolean = false,
    var sourceLanguage: String = "",
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val cachedSourceName: String? = null,
    private val cachedSourceLang: String? = null,
) {
    val sourceName: String by lazy {
        cachedSourceName ?: sourceManager.getOrStub(libraryAnime.anime.source).getNameForAnimeInfo()
    }
    val sourceLang: String by lazy { cachedSourceLang ?: sourceManager.getOrStub(libraryAnime.anime.source).lang }

    fun matches(constraint: String): Boolean = QueryNode.from(constraint).matches(this)
}
