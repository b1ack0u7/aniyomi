package eu.kanade.tachiyomi.ui.entries.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import eu.kanade.tachiyomi.data.suggestions.SuggestionCoordinator
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionMediaType
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.data.suggestions.SuggestionState
import eu.kanade.tachiyomi.data.suggestions.anime.AnimeSearchFallbackEngine
import eu.kanade.tachiyomi.data.suggestions.manga.MangaSearchFallbackEngine
import eu.kanade.tachiyomi.data.suggestions.suggestionCoverModel
import eu.kanade.tachiyomi.data.suggestions.toSuggestionSeed
import eu.kanade.tachiyomi.data.suggestions.util.rankForSeed
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            when (state) {
                is SuggestionState.Loading, SuggestionState.Idle -> LoadingScreen(
                    Modifier.padding(contentPadding),
                )
                is SuggestionState.Error -> EmptyScreen(
                    stringRes = AYMR.strings.similar_titles_error,
                    modifier = Modifier.padding(contentPadding),
                )
                SuggestionState.Empty, SuggestionState.Disabled -> EmptyScreen(
                    stringRes = AYMR.strings.similar_titles_empty,
                    modifier = Modifier.padding(contentPadding),
                )
                is SuggestionState.Success -> SuggestionGrid(
                    items = (state as SuggestionState.Success).items,
                    contentPadding = contentPadding,
                    onItemClick = { item ->
                        scope.launch {
                            navigator.push(item.toDirectEntryScreenOrNull() ?: item.toGlobalSearchScreen())
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SuggestionGrid(
    items: List<SuggestionItem>,
    contentPadding: PaddingValues,
    onItemClick: (SuggestionItem) -> Unit,
) {
    LazyVerticalGrid(
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
        items(items, key = { it.providerId ?: it.providerUrl }) { item ->
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
    }
}

class EntrySuggestionsScreenModel(
    private val entryId: Long,
    private val mediaType: SuggestionMediaType,
    private val coordinator: SuggestionCoordinator = Injekt.get(),
    private val animeSearchFallbackEngine: AnimeSearchFallbackEngine = Injekt.get(),
    private val mangaSearchFallbackEngine: MangaSearchFallbackEngine = Injekt.get(),
) : StateScreenModel<SuggestionState>(SuggestionState.Loading) {

    init {
        fetchSuggestions()
    }

    fun fetchSuggestions() {
        screenModelScope.launchIO {
            mutableState.value = SuggestionState.Loading
            try {
                val request = buildRequest()
                if (request == null) {
                    mutableState.value = SuggestionState.Empty
                    return@launchIO
                }

                val items = coroutineScope {
                    val external = async(Dispatchers.IO) {
                        runCatching { coordinator.fetchSuggestions(request.seed, PROVIDER_LIMIT).items }
                            .getOrDefault(emptyList())
                    }
                    val native = async(Dispatchers.IO) {
                        runCatching { request.searchFallback() }.getOrDefault(emptyList())
                    }
                    external.await() + native.await()
                }.rankForSeed(request.seed, request.entryUrl, SCREEN_LIMIT)

                mutableState.value = if (items.isEmpty()) {
                    SuggestionState.Empty
                } else {
                    SuggestionState.Success(items)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat { "[EntrySuggestionsScreenModel] fetch failed: ${e.message}" }
                mutableState.value = SuggestionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private class Request(
        val seed: SuggestionSeed,
        val entryUrl: String,
        val searchFallback: suspend () -> List<SuggestionItem>,
    )

    private suspend fun buildRequest(): Request? = when (mediaType) {
        SuggestionMediaType.ANIME -> {
            val anime = Injekt.get<GetAnime>().await(entryId)
            val source = anime?.let {
                Injekt.get<AnimeSourceManager>().getOrStub(it.source) as? AnimeCatalogueSource
            }
            anime?.let {
                val seed = it.toSuggestionSeed()
                Request(seed, it.url) {
                    if (source == null) {
                        emptyList()
                    } else {
                        animeSearchFallbackEngine.fetchSearchFallback(it, source, seed, PROVIDER_LIMIT)
                    }
                }
            }
        }
        SuggestionMediaType.MANGA -> {
            val manga = Injekt.get<GetManga>().await(entryId)
            val source = manga?.let {
                Injekt.get<MangaSourceManager>().getOrStub(it.source) as? CatalogueSource
            }
            manga?.let {
                val seed = it.toSuggestionSeed()
                Request(seed, it.url) {
                    if (source == null) {
                        emptyList()
                    } else {
                        mangaSearchFallbackEngine.fetchSearchFallback(it, source, seed, PROVIDER_LIMIT)
                    }
                }
            }
        }
    }

    private companion object {
        const val PROVIDER_LIMIT = 60
        const val SCREEN_LIMIT = 80
    }
}
