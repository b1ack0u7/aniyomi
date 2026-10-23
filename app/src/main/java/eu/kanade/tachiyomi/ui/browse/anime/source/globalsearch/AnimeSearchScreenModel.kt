package eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
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
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class AnimeSearchScreenModel(
    initialState: State = State(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val extensionManager: AnimeExtensionManager = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val coroutineScope: CoroutineScope? = null,
) : StateScreenModel<AnimeSearchScreenModel.State>(initialState) {

    private val requests = SearchRequestCoordinator(coroutineScope ?: ioCoroutineScope)
    private var searchSession: SearchRequestCoordinator.Session? = null

    private val enabledLanguages = sourcePreferences.enabledLanguages().get()
    private val disabledSources = sourcePreferences.disabledAnimeSources().get()
    protected val pinnedSources = sourcePreferences.pinnedAnimeSources().get()

    private var lastQuery: String? = null
    private var lastSourceFilter: AnimeSourceFilter? = null

    private var pendingWebViewRetrySourceId: Long? = null

    protected var extensionFilter: String? = null

    private val sortComparator = { map: Map<AnimeCatalogueSource, AnimeSearchItemResult> ->
        compareBy<AnimeCatalogueSource>(
            { (map[it] as? AnimeSearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
            { it.id },
        )
    }

    init {
        val sourceFilter = if (preferences.globalSearchPinnedSourcesOnly().get()) {
            AnimeSourceFilter.PinnedOnly
        } else {
            AnimeSourceFilter.All
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
    fun getAnime(initialAnime: Anime): androidx.compose.runtime.State<Anime> {
        return produceState(initialValue = initialAnime) {
            getAnime.subscribe(initialAnime.url, initialAnime.source)
                .filterNotNull()
                .collectLatest { anime ->
                    value = anime
                }
        }
    }

    open fun getEnabledSources(): List<AnimeCatalogueSource> {
        return sourceManager.getCatalogueSources()
            .filter { it.lang in enabledLanguages && "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    private fun getSelectedSources(): List<AnimeCatalogueSource> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        return extensionManager.installedExtensionsFlow.value
            .filter { it.pkgName == filter }
            .flatMap { it.sources }
            .filterIsInstance<AnimeCatalogueSource>()
            .filter { it in enabledSources }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: AnimeSourceFilter) {
        preferences.globalSearchPinnedSourcesOnly().set(filter == AnimeSourceFilter.PinnedOnly)
        applySourceFilter(filter)
    }

    protected fun applySourceFilter(filter: AnimeSourceFilter) {
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
                        state.items[source] ?: AnimeSearchItemResult.Loading
                    } else {
                        AnimeSearchItemResult.Loading
                    }
                }.toPersistentMap()
                state.copy(
                    executedQuery = query,
                    items = items.toSortedMap(sortComparator(items)).toPersistentMap(),
                )
            }

            sources.forEach { source ->
                if (state.value.items[source] is AnimeSearchItemResult.Loading) {
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
        source: AnimeCatalogueSource,
        query: String,
    ) {
        try {
            val sourceQuery = SourceSearchQuery(query)
            val filters = source.getFilterList()
            val page = sourceQuery.search(page = 1, isEmpty = { it.animes.isEmpty() }) { effectiveQuery ->
                session.withRequest { source.getSearchAnime(1, effectiveQuery, filters) }
            }
            val titles = page.animes.map {
                networkToLocalAnime.await(it.toDomainAnime(source.id))
            }
            updateItem(session, source, AnimeSearchItemResult.Success(titles, sourceQuery.effectiveQuery))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            updateItem(session, source, AnimeSearchItemResult.Error(e))
        } catch (e: LinkageError) {
            currentCoroutineContext().ensureActive()
            updateItem(session, source, AnimeSearchItemResult.Error(e))
        }
    }

    fun getSourceQuery(source: AnimeCatalogueSource): String? {
        val state = state.value
        return (state.items[source] as? AnimeSearchItemResult.Success)?.effectiveQuery ?: state.executedQuery
    }

    fun retrySource(source: AnimeCatalogueSource) {
        val query = state.value.executedQuery ?: return
        val session = searchSession ?: return
        if (!session.isCurrent || source !in state.value.items) return
        session.launch(source.id) {
            updateItem(session, source, AnimeSearchItemResult.Loading)
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
        source: AnimeCatalogueSource,
        result: AnimeSearchItemResult,
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
        val sourceFilter: AnimeSourceFilter = AnimeSourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<AnimeCatalogueSource, AnimeSearchItemResult> = persistentMapOf(),
    ) {
        val progress: Int = items.count { it.value !is AnimeSearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }
}

enum class AnimeSourceFilter {
    All,
    PinnedOnly,
}

sealed interface AnimeSearchItemResult {
    data object Loading : AnimeSearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : AnimeSearchItemResult

    data class Success(
        val result: List<Anime>,
        val effectiveQuery: String? = null,
    ) : AnimeSearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
