package eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch

import eu.kanade.tachiyomi.source.CatalogueSource

class GlobalMangaSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String? = null,
) : MangaSearchScreenModel(
    State(
        searchQuery = initialQuery,
    ),
) {

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (!extensionFilter.isNullOrEmpty()) {
                // Keep this extension's scope separate from the saved global source filter.
                applySourceFilter(MangaSourceFilter.All)
            }
            search()
        }
    }

    override fun getEnabledSources(): List<CatalogueSource> {
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != MangaSourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }
}
