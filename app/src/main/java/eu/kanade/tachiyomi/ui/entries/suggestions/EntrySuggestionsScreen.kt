package eu.kanade.tachiyomi.ui.entries.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.suggestions.SourceSearchFallback
import eu.kanade.tachiyomi.data.suggestions.SuggestionCoordinator
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionMediaType
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.data.suggestions.anime.AnimeSearchFallbackEngine
import eu.kanade.tachiyomi.data.suggestions.manga.MangaSearchFallbackEngine
import eu.kanade.tachiyomi.data.suggestions.suggestionCoverModel
import eu.kanade.tachiyomi.data.suggestions.toSuggestionSeed
import eu.kanade.tachiyomi.data.suggestions.util.rankForSeed
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

// Takes the entry id rather than a pre-built result list so the screen survives process death
// and can re-run the pipeline on its own.
class EntrySuggestionsScreen(
    private val entryId: Long,
    private val mediaType: SuggestionMediaType,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { EntrySuggestionsScreenModel(entryId, mediaType) }
        val state by screenModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(AYMR.strings.similar_titles),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when {
                state.items.isNotEmpty() -> SuggestionGrid(
                    items = state.items,
                    showLoadingFooter = state.isLoading || state.isLoadingMore || state.canLoadMore,
                    contentPadding = contentPadding,
                    onLoadMore = screenModel::loadMore,
                    onItemClick = { item ->
                        scope.launch {
                            navigator.push(item.toDirectEntryScreenOrNull() ?: item.toGlobalSearchScreen())
                        }
                    },
                )
                state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                state.error != null -> EmptyScreen(
                    stringRes = AYMR.strings.similar_titles_error,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> EmptyScreen(
                    stringRes = AYMR.strings.similar_titles_empty,
                    modifier = Modifier.padding(contentPadding),
                )
            }
        }
    }
}

@Composable
private fun SuggestionGrid(
    items: List<SuggestionItem>,
    showLoadingFooter: Boolean,
    contentPadding: PaddingValues,
    onLoadMore: () -> Unit,
    onItemClick: (SuggestionItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // Only a screenful is committed up front; the rest is revealed as the user reaches the end,
    // and once the loaded set runs out the source is asked for another page.
    var revealed by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    val visibleItems = remember(items, revealed) { items.take(revealed) }
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                // Measured against what is actually laid out, not against `revealed`: the reveal
                // window is usually wider than the list and the end would never be reached.
                val loaded = minOf(revealed, currentItems.size)
                if (revealed < currentItems.size) {
                    if (lastVisible >= loaded - REVEAL_THRESHOLD) revealed += PAGE_SIZE
                } else if (lastVisible >= loaded - LOAD_MORE_THRESHOLD) {
                    onLoadMore()
                }
            }
    }

    // Late external results outrank what the source already returned and get inserted above the
    // anchored item, which reads as the screen having scrolled itself.
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { if (it) userScrolled = true }
    }
    LaunchedEffect(items.firstOrNull()?.providerUrl) {
        if (!userScrolled) gridState.scrollToItem(0)
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(
            start = MaterialTheme.padding.small,
            end = MaterialTheme.padding.small,
            top = contentPadding.calculateTopPadding() + MaterialTheme.padding.small,
            bottom = contentPadding.calculateBottomPadding() + MaterialTheme.padding.small,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        items(visibleItems, key = { it.providerUrl }) { item ->
            Column {
                ItemCover.Book(
                    data = suggestionCoverModel(item),
                    contentDescription = item.title,
                    onClick = { onItemClick(item) },
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
                )
            }
        }

        if (showLoadingFooter || visibleItems.size < items.size) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.padding.medium),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

class EntrySuggestionsScreenModel(
    private val entryId: Long,
    private val mediaType: SuggestionMediaType,
    private val coordinator: SuggestionCoordinator = Injekt.get(),
    private val animeSearchFallbackEngine: AnimeSearchFallbackEngine = Injekt.get(),
    private val mangaSearchFallbackEngine: MangaSearchFallbackEngine = Injekt.get(),
) : StateScreenModel<EntrySuggestionsScreenModel.State>(State()) {

    private val collected = Collections.synchronizedList(mutableListOf<SuggestionItem>())
    private var request: Request? = null
    private var loadMoreJob: Job? = null

    init {
        fetchSuggestions()
    }

    // Publishes as each provider answers instead of waiting on the slowest one, so the grid
    // fills with whatever the row already cached and grows from there.
    private fun fetchSuggestions() {
        screenModelScope.launchIO {
            try {
                val request = buildRequest()
                this@EntrySuggestionsScreenModel.request = request
                if (request == null) {
                    mutableState.update { it.copy(isLoading = false) }
                    return@launchIO
                }

                coroutineScope {
                    launch {
                        try {
                            publish(coordinator.fetchSuggestions(request.seed, PROVIDER_LIMIT).items)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat { "[EntrySuggestionsScreenModel] external failed: ${e.message}" }
                        }
                    }
                    launch {
                        try {
                            publish(request.pager?.loadInitial(::publish).orEmpty())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat { "[EntrySuggestionsScreenModel] source fallback failed: ${e.message}" }
                        }
                    }
                }

                mutableState.update {
                    it.copy(
                        items = rank(),
                        isLoading = false,
                        canLoadMore = request.pager?.hasNextPage == true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat { "[EntrySuggestionsScreenModel] fetch failed: ${e.message}" }
                mutableState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun loadMore() {
        val pager = request?.pager ?: return
        if (state.value.isLoading || loadMoreJob?.isActive == true || !pager.hasNextPage) return

        loadMoreJob = screenModelScope.launchIO {
            mutableState.update { it.copy(isLoadingMore = true) }
            try {
                publish(pager.loadNextPage())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat { "[EntrySuggestionsScreenModel] load more failed: ${e.message}" }
            }
            mutableState.update { it.copy(isLoadingMore = false, canLoadMore = pager.hasNextPage) }
        }
    }

    private fun publish(incoming: List<SuggestionItem>) {
        if (incoming.isEmpty()) return
        synchronized(collected) {
            val known = collected.mapTo(mutableSetOf()) { it.providerUrl }
            collected.addAll(incoming.filter { it.providerUrl !in known })
        }
        val ranked = rank()
        if (ranked.isNotEmpty()) {
            mutableState.update { it.copy(items = ranked) }
        }
    }

    private fun rank(): List<SuggestionItem> {
        val request = request ?: return emptyList()
        return synchronized(collected) { collected.toList() }
            .rankForSeed(request.seed, request.entryUrl, SCREEN_LIMIT)
    }

    private class Request(
        val seed: SuggestionSeed,
        val entryUrl: String,
        val pager: SourceSearchFallback?,
    )

    private suspend fun buildRequest(): Request? = when (mediaType) {
        SuggestionMediaType.ANIME -> {
            val anime = Injekt.get<GetAnime>().await(entryId)
            anime?.let {
                val seed = it.toSuggestionSeed()
                val source = Injekt.get<AnimeSourceManager>().getOrStub(it.source) as? AnimeCatalogueSource
                Request(
                    seed = seed,
                    entryUrl = it.url,
                    pager = source?.let { catalogue ->
                        animeSearchFallbackEngine.createPager(it, catalogue, seed, totalLimit = SCREEN_LIMIT)
                    },
                )
            }
        }
        SuggestionMediaType.MANGA -> {
            val manga = Injekt.get<GetManga>().await(entryId)
            manga?.let {
                val seed = it.toSuggestionSeed()
                val source = Injekt.get<MangaSourceManager>().getOrStub(it.source) as? CatalogueSource
                Request(
                    seed = seed,
                    entryUrl = it.url,
                    pager = source?.let { catalogue ->
                        mangaSearchFallbackEngine.createPager(it, catalogue, seed, totalLimit = SCREEN_LIMIT)
                    },
                )
            }
        }
    }

    @Immutable
    data class State(
        val items: List<SuggestionItem> = emptyList(),
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val canLoadMore: Boolean = false,
        val error: String? = null,
    )

    private companion object {
        const val PROVIDER_LIMIT = 60
        const val SCREEN_LIMIT = 200
    }
}

private const val PAGE_SIZE = 24

private const val REVEAL_THRESHOLD = 6

// Asking the source for another page is a network round trip, so it waits for the actual end of
// the list rather than prefetching like the local reveal does.
private const val LOAD_MORE_THRESHOLD = 2
