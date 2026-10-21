package eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource

class GlobalAnimeSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String? = null,
) : AnimeSearchScreenModel(
    State(
        searchQuery = initialQuery,
    ),
) {

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (!extensionFilter.isNullOrEmpty()) {
                // Keep this extension's scope separate from the saved global source filter.
                applySourceFilter(AnimeSourceFilter.All)
            }
            search()
        }
    }

    override fun getEnabledSources(): List<AnimeCatalogueSource> {
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != AnimeSourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }
}
