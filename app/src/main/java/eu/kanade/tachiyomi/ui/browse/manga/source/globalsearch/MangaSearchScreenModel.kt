package eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchRequestCoordinator
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.util.lang.SourceSearchQuery
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class MangaSearchScreenModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val extensionManager: MangaExtensionManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val coroutineScope: CoroutineScope? = null,
) : StateScreenModel<MangaSearchScreenModel.State>(initialState) {

    private val requests = SearchRequestCoordinator(coroutineScope ?: ioCoroutineScope)
    private var searchSession: SearchRequestCoordinator.Session? = null

    private val enabledLanguages = sourcePreferences.enabledLanguages().get()
    private val disabledSources = sourcePreferences.disabledMangaSources().get()
    protected val pinnedSources = sourcePreferences.pinnedMangaSources().get()

    private var lastQuery: String? = null
    private var lastSourceFilter: MangaSourceFilter? = null

    private var pendingWebViewRetrySourceId: Long? = null

    protected var extensionFilter: String? = null

    private val sortComparator = { map: Map<CatalogueSource, MangaSearchItemResult> ->
        compareBy<CatalogueSource>(
            { (map[it] as? MangaSearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
            { it.id },
        )
    }

    init {
        val sourceFilter = if (preferences.globalSearchPinnedSourcesOnly().get()) {
            MangaSourceFilter.PinnedOnly
        } else {
            MangaSourceFilter.All
        }
        val onlyShowHasResults = preferences.globalSearchFilterState().get()
        mutableState.update { it.copy(sourceFilter = sourceFilter, onlyShowHasResults = onlyShowHasResults) }
        (coroutineScope ?: screenModelScope).launch {
            preferences.globalSearchFilterState().changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    open fun getEnabledSources(): List<CatalogueSource> {
        return sourceManager.getCatalogueSources()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private fun getSelectedSources(): List<CatalogueSource> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        return extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filterIsInstance<CatalogueSource>()
            .filter { it in enabledSources }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: MangaSourceFilter) {
        preferences.globalSearchPinnedSourcesOnly().set(filter == MangaSourceFilter.PinnedOnly)
        applySourceFilter(filter)
    }

    protected fun applySourceFilter(filter: MangaSourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search(state.value.executedQuery ?: state.value.searchQuery)
    }

    fun toggleFilterResults() {
        val onlyShowHasResults = preferences.globalSearchFilterState().toggle()
        mutableState.update { it.copy(onlyShowHasResults = onlyShowHasResults) }
    }

    fun search() {
        search(state.value.searchQuery)
    }

    private fun search(query: String?) {
        val sourceFilter = state.value.sourceFilter
        if (query.isNullOrBlank()) return
        val sameQuery = lastQuery == query
        if (sameQuery && lastSourceFilter == sourceFilter) return

        lastQuery = query
        lastSourceFilter = sourceFilter
        pendingWebViewRetrySourceId = null
        val session = requests.newSearch()
        searchSession = session
        fun performSearch() {
            val sources = getSelectedSources()

            mutableState.update { state ->
                val items = sources.associateWith { source ->
                    if (sameQuery) {
                        state.items[source] ?: MangaSearchItemResult.Loading
                    } else {
                        MangaSearchItemResult.Loading
                    }
                }.toPersistentMap()
                state.copy(
                    executedQuery = query,
                    items = items.toSortedMap(sortComparator(items)).toPersistentMap(),
                )
            }

            sources.forEach { source ->
                if (state.value.items[source] is MangaSearchItemResult.Loading) {
                    session.launch(source.id) { searchSource(session, source, query) }
                }
            }
        }

        if (sourceManager.isInitialized.value) {
            performSearch()
        } else {
            (coroutineScope ?: screenModelScope).launch {
                sourceManager.isInitialized.first { it }
                if (session.isCurrent) performSearch()
            }
        }
    }

    private suspend fun CoroutineScope.searchSource(
        session: SearchRequestCoordinator.Session,
        source: CatalogueSource,
        query: String,
    ) {
        try {
            val sourceQuery = SourceSearchQuery(query)
            val filters = source.getFilterList()
            val page = sourceQuery.search(page = 1, isEmpty = { it.mangas.isEmpty() }) { effectiveQuery ->
                session.withRequest { source.getSearchManga(1, effectiveQuery, filters) }
            }
            val titles = page.mangas.map {
                networkToLocalManga.await(it.toDomainManga(source.id))
            }
            updateItem(session, source, MangaSearchItemResult.Success(titles, sourceQuery.effectiveQuery))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            updateItem(session, source, MangaSearchItemResult.Error(e))
        } catch (e: LinkageError) {
            currentCoroutineContext().ensureActive()
            updateItem(session, source, MangaSearchItemResult.Error(e))
        }
    }

    fun getSourceQuery(source: CatalogueSource): String? {
        val state = state.value
        return (state.items[source] as? MangaSearchItemResult.Success)?.effectiveQuery ?: state.executedQuery
    }

    fun retrySource(source: CatalogueSource) {
        val query = state.value.executedQuery ?: return
        val session = searchSession ?: return
        if (!session.isCurrent || source !in state.value.items) return
        session.launch(source.id) {
            updateItem(session, source, MangaSearchItemResult.Loading)
            searchSource(session, source, query)
        }
    }

    fun scheduleWebViewRetry(sourceId: Long) {
        pendingWebViewRetrySourceId = sourceId
    }

    fun consumeWebViewRetry() {
        val sourceId = pendingWebViewRetrySourceId ?: return
        pendingWebViewRetrySourceId = null
        val source = state.value.items.keys.find { it.id == sourceId } ?: return
        retrySource(source)
    }

    private fun CoroutineScope.updateItem(
        session: SearchRequestCoordinator.Session,
        source: CatalogueSource,
        result: MangaSearchItemResult,
    ) {
        mutableState.update { state ->
            if (!isActive || !session.isCurrent || source !in state.items) return@update state
            val items = state.items.put(source, result)
            state.copy(items = items.toSortedMap(sortComparator(items)).toPersistentMap())
        }
    }

    @Immutable
    data class State(
        val fromSourceId: Long? = null,
        val searchQuery: String? = null,
        val executedQuery: String? = null,
        val sourceFilter: MangaSourceFilter = MangaSourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<CatalogueSource, MangaSearchItemResult> = persistentMapOf(),
    ) {
        val progress: Int = items.count { it.value !is MangaSearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }
}

enum class MangaSourceFilter {
    All,
    PinnedOnly,
}

sealed interface MangaSearchItemResult {
    data object Loading : MangaSearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : MangaSearchItemResult

    data class Success(
        val result: List<Manga>,
        val effectiveQuery: String? = null,
    ) : MangaSearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
