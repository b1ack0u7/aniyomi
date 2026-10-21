package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSearchItemResult
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSourceFilter
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.MangaSearchItemResult
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.MangaSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.MangaSourceFilter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import rx.Observable
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.model.StubMangaSource
import tachiyomi.domain.source.manga.service.MangaSourceManager
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Timeout(30)
class GlobalSearchScreenModelTest {

    @Test
    fun `an old response cannot overwrite the latest query`() = runBlocking {
        for (anime in listOf(true, false)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness = harness(anime) { _, query ->
                if (query == "old") {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                query
            }
            try {
                harness.submit("old")
                started.await()
                harness.submit("new")
                harness.awaitTitle(1, "new")
                release.complete(Unit)
                harness.awaitIdle()

                harness.current().executedQuery shouldBe "new"
                harness.current().titles[1] shouldBe listOf("new")
            } finally {
                release.complete(Unit)
                harness.close()
            }
        }
    }

    @Test
    fun `a retry from the previous query cannot replace newer results`() = runBlocking {
        for (anime in listOf(true, false)) {
            val attempts = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness = harness(anime) { _, query ->
                if (query == "old" && attempts.incrementAndGet() == 2) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                query
            }
            try {
                harness.submit("old")
                harness.awaitTitle(1, "old")
                harness.retry(1)
                started.await()
                harness.submit("new")
                harness.awaitTitle(1, "new")
                release.complete(Unit)
                harness.awaitIdle()

                harness.current().titles[1] shouldBe listOf("new")
            } finally {
                release.complete(Unit)
                harness.close()
            }
        }
    }

    @Test
    fun `editing the draft does not change the query used by retry`() = runBlocking {
        for (anime in listOf(true, false)) {
            val queries = CopyOnWriteArrayList<String>()
            harness(anime) { _, query ->
                queries += query
                query
            }.use { harness ->
                harness.submit("executed")
                harness.awaitTitle(1, "executed")
                harness.draft("unsent")
                harness.retry(1)
                harness.awaitIdle()

                queries shouldBe listOf("executed", "executed")
                harness.current().executedQuery shouldBe "executed"
                harness.current().draft shouldBe "unsent"
            }
        }
    }

    @Test
    fun `changing the source filter ignores the draft and excludes late removed sources`() = runBlocking {
        for (anime in listOf(true, false)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness = harness(anime, sourceCount = 2) { id, query ->
                if (id == 2L) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                query
            }
            try {
                harness.submit("executed")
                started.await()
                harness.awaitTitle(1, "executed")
                harness.draft("unsent")
                harness.pinnedOnly()
                release.complete(Unit)
                harness.awaitIdle()

                harness.current().ids shouldBe setOf(1L)
                harness.current().executedQuery shouldBe "executed"
                harness.current().titles[1] shouldBe listOf("executed")
            } finally {
                release.complete(Unit)
                harness.close()
            }
        }
    }

    @Test
    fun `a binary incompatible source becomes an error without cancelling other sources`() = runBlocking {
        for (anime in listOf(true, false)) {
            val error = NoSuchMethodError("Extension requires a newer API")
            harness(anime, sourceCount = 2) { id, query ->
                if (id == 1L) throw error
                query
            }.use { harness ->
                harness.submit("query")
                harness.awaitTitle(2, "query")
                harness.awaitIdle()

                harness.current().errors[1] shouldBe error
                harness.current().titles[2] shouldBe listOf("query")
            }
        }
    }

    @Test
    fun `a source timeout becomes an error while the search session remains active`() = runBlocking {
        for (anime in listOf(true, false)) {
            harness(anime) { _, _ -> withTimeout(20) { awaitCancellation() } }.use { harness ->
                harness.submit("query")
                harness.awaitError(1)
                harness.awaitIdle()

                (harness.current().errors[1] is TimeoutCancellationException) shouldBe true
            }
        }
    }

    @Test
    fun `sources with the same display name remain separate`() = runBlocking {
        for (anime in listOf(true, false)) {
            harness(anime, sourceCount = 3, sameName = true) { _, query -> query }.use { harness ->
                harness.submit("query")
                harness.awaitIdle()

                harness.current().ids shouldBe setOf(1L, 2L, 3L)
                harness.current().titles.size shouldBe 3
            }
        }
    }

    @Test
    fun `concurrent source completions preserve every result`() = runBlocking {
        for (anime in listOf(true, false)) {
            val release = CompletableDeferred<Unit>()
            harness(anime, sourceCount = 30) { _, query ->
                release.await()
                query
            }.use { harness ->
                harness.submit("query")
                release.complete(Unit)
                harness.awaitIdle()

                harness.current().titles.size shouldBe 30
                harness.current().titles.values.all { it == listOf("query") } shouldBe true
            }
        }
    }

    @Test
    fun `only the empty source retries normalized text while other results remain visible`() = runBlocking {
        for (anime in listOf(true, false)) {
            val original = "Title   Query"
            val normalized = "Title Query"
            val attempts = CopyOnWriteArrayList<Pair<Long, String>>()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness = harness(anime, sourceCount = 2) { id, query ->
                attempts += id to query
                when {
                    id == 1L -> query
                    query == original -> null
                    else -> {
                        started.complete(Unit)
                        release.await()
                        "normalized result"
                    }
                }
            }
            try {
                harness.submit(original)
                started.await()
                harness.awaitTitle(1, original)
                harness.current().titles.keys shouldBe setOf(1L)
                harness.current().effectiveQueries[1] shouldBe original

                release.complete(Unit)
                harness.awaitTitle(2, "normalized result")
                harness.awaitIdle()
                harness.draft("unsent")

                attempts.filter { it.first == 1L } shouldBe listOf(1L to original)
                attempts.filter { it.first == 2L } shouldBe listOf(2L to original, 2L to normalized)
                harness.current().titles[1] shouldBe listOf(original)
                harness.current().effectiveQueries shouldBe mapOf(1L to original, 2L to normalized)
                harness.sourceQuery(1) shouldBe original
                harness.sourceQuery(2) shouldBe normalized
            } finally {
                release.complete(Unit)
                harness.close()
            }
        }
    }

    @Test
    fun `two empty responses stop after one normalized attempt`() = runBlocking {
        for (anime in listOf(true, false)) {
            val attempts = CopyOnWriteArrayList<String>()
            harness(anime) { _, query ->
                attempts += query
                null
            }.use { harness ->
                harness.submit("Title   Query")
                harness.awaitIdle()

                attempts shouldBe listOf("Title   Query", "Title Query")
                harness.current().titles[1] shouldBe emptyList()
                harness.current().effectiveQueries[1] shouldBe "Title   Query"
            }
        }
    }

    @Test
    fun `an unchanged empty query does not retry`() = runBlocking {
        for (anime in listOf(true, false)) {
            val attempts = CopyOnWriteArrayList<String>()
            harness(anime) { _, query ->
                attempts += query
                null
            }.use { harness ->
                harness.submit("Title Query")
                harness.awaitIdle()

                attempts shouldBe listOf("Title Query")
                harness.current().titles[1] shouldBe emptyList()
                harness.sourceQuery(1) shouldBe "Title Query"
            }
        }
    }

    @Test
    fun `an original request error does not trigger normalized search`() = runBlocking {
        for (anime in listOf(true, false)) {
            val attempts = CopyOnWriteArrayList<String>()
            val error = IOException("offline")
            harness(anime) { _, query ->
                attempts += query
                throw error
            }.use { harness ->
                harness.submit("Title   Query")
                harness.awaitError(1)
                harness.awaitIdle()

                attempts shouldBe listOf("Title   Query")
                harness.current().errors[1] shouldBe error
                harness.sourceQuery(1) shouldBe "Title   Query"
            }
        }
    }

    @Test
    fun `a normalized request error does not remove another source success`() = runBlocking {
        for (anime in listOf(true, false)) {
            val attempts = CopyOnWriteArrayList<Pair<Long, String>>()
            val error = IOException("normalized request failed")
            harness(anime, sourceCount = 2) { id, query ->
                attempts += id to query
                when {
                    id == 2L -> query
                    query == "Title   Query" -> null
                    else -> throw error
                }
            }.use { harness ->
                harness.submit("Title   Query")
                harness.awaitTitle(2, "Title   Query")
                harness.awaitError(1)
                harness.awaitIdle()

                attempts.filter { it.first == 1L } shouldBe listOf(1L to "Title   Query", 1L to "Title Query")
                attempts.filter { it.first == 2L } shouldBe listOf(2L to "Title   Query")
                harness.current().errors[1] shouldBe error
                harness.current().titles[2] shouldBe listOf("Title   Query")
            }
        }
    }

    @Test
    fun `a normalized response from an old session cannot replace a newer search`() = runBlocking {
        for (anime in listOf(true, false)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val attempts = CopyOnWriteArrayList<String>()
            val harness = harness(anime) { _, query ->
                attempts += query
                when (query) {
                    "Old   Query" -> null
                    "Old Query" -> {
                        started.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        "old result"
                    }
                    else -> query
                }
            }
            try {
                harness.submit("Old   Query")
                started.await()
                harness.submit("New Query")
                harness.awaitTitle(1, "New Query")
                release.complete(Unit)
                harness.awaitIdle()

                attempts shouldBe listOf("Old   Query", "Old Query", "New Query")
                harness.current().titles[1] shouldBe listOf("New Query")
                harness.sourceQuery(1) shouldBe "New Query"
            } finally {
                release.complete(Unit)
                harness.close()
            }
        }
    }

    @Test
    fun `the normalized attempt reuses the original filters`() = runBlocking {
        for (anime in listOf(true, false)) {
            harness(anime) { _, query ->
                if (query == "Title   Query") null else "result"
            }.use { harness ->
                harness.submit("Title   Query")
                harness.awaitTitle(1, "result")
                harness.awaitIdle()

                val filters = harness.filters(1)
                filters.size shouldBe 2
                (filters[0] === filters[1]) shouldBe true
            }
        }
    }

    @Test
    fun `manual retry starts from the original query after normalized success`() = runBlocking {
        for (anime in listOf(true, false)) {
            val attempts = CopyOnWriteArrayList<String>()
            harness(anime) { _, query ->
                attempts += query
                if (query == "Title   Query") null else "result"
            }.use { harness ->
                harness.submit("Title   Query")
                harness.awaitTitle(1, "result")
                harness.draft("unsent")
                harness.retry(1)
                harness.awaitIdle()

                attempts shouldBe listOf("Title   Query", "Title Query", "Title   Query", "Title Query")
                harness.current().executedQuery shouldBe "Title   Query"
                harness.sourceQuery(1) shouldBe "Title Query"
            }
        }
    }

    @Test
    fun `both global filters survive closing and recreating either search stack`() {
        for (anime in listOf(true, false)) {
            for (pinnedOnly in listOf(true, false)) {
                for (hasResults in listOf(true, false)) {
                    val settings = BooleanSettings(!pinnedOnly, !hasResults, observe = false)
                    harness(anime, settings = settings) { _, query -> query }.use { harness ->
                        harness.selectPinnedOnly(pinnedOnly)
                        harness.toggleResults()
                        harness.current().pinnedOnly shouldBe pinnedOnly
                        harness.current().onlyShowHasResults shouldBe hasResults
                    }

                    harness(anime, settings = settings) { _, query -> query }.use { recreated ->
                        recreated.current().pinnedOnly shouldBe pinnedOnly
                        recreated.current().onlyShowHasResults shouldBe hasResults
                    }
                }
            }
        }
    }

    @Test
    fun `anime and manga share global filter preferences across recreation`() {
        for (anime in listOf(true, false)) {
            val settings = BooleanSettings(pinnedOnly = true, hasResults = false, observe = false)
            harness(anime, settings = settings) { _, query -> query }.use { harness ->
                harness.selectPinnedOnly(false)
                harness.toggleResults()
            }

            harness(!anime, settings = settings) { _, query -> query }.use { otherStack ->
                otherStack.current().pinnedOnly shouldBe false
                otherStack.current().onlyShowHasResults shouldBe true
                otherStack.selectPinnedOnly(true)
                otherStack.toggleResults()
            }

            harness(anime, settings = settings) { _, query -> query }.use { recreated ->
                recreated.current().pinnedOnly shouldBe true
                recreated.current().onlyShowHasResults shouldBe false
            }
        }
    }

    @Test
    fun `pinned sources remain the default before preference observers emit`() {
        for (anime in listOf(true, false)) {
            val settings = BooleanSettings(observe = false)
            harness(anime, settings = settings) { _, query -> query }.use { harness ->
                harness.current().pinnedOnly shouldBe true
                harness.current().onlyShowHasResults shouldBe false
                settings.writes shouldBe emptyList()
            }
        }
    }

    @Test
    fun `saved filters restore and toggle immediately without preference flow emissions`() {
        for (anime in listOf(true, false)) {
            val settings = BooleanSettings(pinnedOnly = false, hasResults = true, observe = false)
            harness(anime, settings = settings) { _, query -> query }.use { harness ->
                harness.current().pinnedOnly shouldBe false
                harness.current().onlyShowHasResults shouldBe true

                harness.selectPinnedOnly(true)
                harness.toggleResults()

                harness.current().pinnedOnly shouldBe true
                harness.current().onlyShowHasResults shouldBe false
            }
        }
    }

    @Test
    fun `temporarily showing all sources does not replace the saved source filter`() {
        for (anime in listOf(true, false)) {
            val settings = BooleanSettings(pinnedOnly = true, hasResults = true, observe = false)
            harness(anime, settings = settings) { _, query -> query }.use { harness ->
                harness.temporaryAll()
                harness.current().pinnedOnly shouldBe false
                harness.current().onlyShowHasResults shouldBe true
                settings.writes shouldBe emptyList()
            }

            harness(anime, settings = settings) { _, query -> query }.use { recreated ->
                recreated.current().pinnedOnly shouldBe true
                recreated.current().onlyShowHasResults shouldBe true
            }
        }
    }

    @Test
    fun `the results filter still observes external preference changes`() = runBlocking {
        for (anime in listOf(true, false)) {
            val settings = BooleanSettings(pinnedOnly = false, hasResults = false, observe = true)
            harness(anime, settings = settings) { _, query -> query }.use { harness ->
                settings.preference(HAS_RESULTS_KEY, false).set(true)
                withTimeout(5_000) { harness.states.first { it.onlyShowHasResults } }
                harness.current().onlyShowHasResults shouldBe true

                settings.preference(HAS_RESULTS_KEY, false).set(false)
                withTimeout(5_000) { harness.states.first { !it.onlyShowHasResults } }
                harness.current().onlyShowHasResults shouldBe false
            }
        }
    }

    @Test
    fun `an explicit source filter selection after a temporary override is persisted`() {
        for (anime in listOf(true, false)) {
            for (pinnedOnly in listOf(true, false)) {
                val settings = BooleanSettings(pinnedOnly = true, observe = false)
                harness(anime, settings = settings) { _, query -> query }.use { harness ->
                    harness.temporaryAll()
                    harness.selectPinnedOnly(pinnedOnly)
                    settings.writes shouldBe listOf(PINNED_ONLY_KEY to pinnedOnly)
                }

                harness(anime, settings = settings) { _, query -> query }.use { recreated ->
                    recreated.current().pinnedOnly shouldBe pinnedOnly
                }
            }
        }
    }

    private fun harness(
        anime: Boolean,
        sourceCount: Int = 1,
        sameName: Boolean = false,
        settings: BooleanSettings = BooleanSettings(pinnedOnly = false, observe = false),
        respond: suspend (Long, String) -> String?,
    ): Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = mockk<SourcePreferences> {
            every { enabledLanguages().get() } returns setOf("en")
            every { disabledAnimeSources().get() } returns emptySet()
            every { disabledMangaSources().get() } returns emptySet()
            every { pinnedAnimeSources().get() } returns setOf("1")
            every { pinnedMangaSources().get() } returns setOf("1")
            every { globalSearchPinnedSourcesOnly() } answers { settings.preference(PINNED_ONLY_KEY, true) }
            every { globalSearchFilterState() } answers { settings.preference(HAS_RESULTS_KEY, false) }
        }
        return if (anime) {
            animeHarness(scope, preferences, sourceCount, sameName, respond)
        } else {
            mangaHarness(scope, preferences, sourceCount, sameName, respond)
        }
    }

    private fun animeHarness(
        scope: CoroutineScope,
        preferences: SourcePreferences,
        sourceCount: Int,
        sameName: Boolean,
        respond: suspend (Long, String) -> String?,
    ): Harness {
        val sources = (1..sourceCount).map { index ->
            TestAnimeSource(
                id = index.toLong(),
                name = if (sameName) "Source" else "Source $index",
                respond = respond,
            )
        }
        val sourceManager = object : AnimeSourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources = flowOf(sources)
            override fun get(sourceKey: Long) = sources.firstOrNull { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long): AnimeSource = sources.first { it.id == sourceKey }
            override fun getOnlineSources() = emptyList<AnimeHttpSource>()
            override fun getCatalogueSources() = sources
            override fun getStubSources() = emptyList<StubAnimeSource>()
        }
        val networkToLocal = mockk<NetworkToLocalAnime> {
            coEvery { await(any()) } coAnswers { firstArg() }
        }
        val model = object : AnimeSearchScreenModel(
            initialState = AnimeSearchScreenModel.State(sourceFilter = AnimeSourceFilter.All),
            sourcePreferences = preferences,
            sourceManager = sourceManager,
            extensionManager = mockk(),
            networkToLocalAnime = networkToLocal,
            getAnime = mockk(),
            preferences = preferences,
            coroutineScope = scope,
        ) {
            override fun getEnabledSources() = super.getEnabledSources().filter {
                state.value.sourceFilter != AnimeSourceFilter.PinnedOnly || it.id == 1L
            }

            fun temporarilyShowAll() = applySourceFilter(AnimeSourceFilter.All)
        }
        fun snapshot(state: AnimeSearchScreenModel.State) = Snapshot(
            draft = state.searchQuery,
            executedQuery = state.executedQuery,
            pinnedOnly = state.sourceFilter == AnimeSourceFilter.PinnedOnly,
            onlyShowHasResults = state.onlyShowHasResults,
            ids = state.items.keys.map { it.id }.toSet(),
            titles = state.items.mapNotNull { (source, result) ->
                (result as? AnimeSearchItemResult.Success)?.let { source.id to it.result.map { it.title } }
            }.toMap(),
            errors = state.items.mapNotNull { (source, result) ->
                (result as? AnimeSearchItemResult.Error)?.let { source.id to it.throwable }
            }.toMap(),
            effectiveQueries = state.items.mapNotNull { (source, result) ->
                (result as? AnimeSearchItemResult.Success)?.let { source.id to it.effectiveQuery }
            }.toMap(),
        )
        return Harness(
            scope = scope,
            states = model.state.map(::snapshot),
            current = { snapshot(model.state.value) },
            draft = model::updateSearchQuery,
            search = model::search,
            retry = { id -> model.retrySource(sources.first { it.id == id }) },
            pinnedOnly = { model.setSourceFilter(AnimeSourceFilter.PinnedOnly) },
            selectPinnedOnly = { pinned ->
                model.setSourceFilter(if (pinned) AnimeSourceFilter.PinnedOnly else AnimeSourceFilter.All)
            },
            toggleResults = model::toggleFilterResults,
            temporaryAll = model::temporarilyShowAll,
            sourceQuery = { id -> model.getSourceQuery(sources.first { it.id == id }) },
            filters = { id -> sources.first { it.id == id }.requestedFilters.toList() },
        )
    }

    private fun mangaHarness(
        scope: CoroutineScope,
        preferences: SourcePreferences,
        sourceCount: Int,
        sameName: Boolean,
        respond: suspend (Long, String) -> String?,
    ): Harness {
        val sources = (1..sourceCount).map { index ->
            TestMangaSource(
                id = index.toLong(),
                name = if (sameName) "Source" else "Source $index",
                respond = respond,
            )
        }
        val sourceManager = object : MangaSourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources = flowOf(sources)
            override fun get(sourceKey: Long) = sources.firstOrNull { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long): MangaSource = sources.first { it.id == sourceKey }
            override fun getOnlineSources() = emptyList<HttpSource>()
            override fun getCatalogueSources() = sources
            override fun getStubSources() = emptyList<StubMangaSource>()
        }
        val networkToLocal = mockk<NetworkToLocalManga> {
            coEvery { await(any()) } coAnswers { firstArg() }
        }
        val model = object : MangaSearchScreenModel(
            initialState = MangaSearchScreenModel.State(sourceFilter = MangaSourceFilter.All),
            sourcePreferences = preferences,
            sourceManager = sourceManager,
            extensionManager = mockk(),
            networkToLocalManga = networkToLocal,
            getManga = mockk(),
            preferences = preferences,
            coroutineScope = scope,
        ) {
            override fun getEnabledSources() = super.getEnabledSources().filter {
                state.value.sourceFilter != MangaSourceFilter.PinnedOnly || it.id == 1L
            }

            fun temporarilyShowAll() = applySourceFilter(MangaSourceFilter.All)
        }
        fun snapshot(state: MangaSearchScreenModel.State) = Snapshot(
            draft = state.searchQuery,
            executedQuery = state.executedQuery,
            pinnedOnly = state.sourceFilter == MangaSourceFilter.PinnedOnly,
            onlyShowHasResults = state.onlyShowHasResults,
            ids = state.items.keys.map { it.id }.toSet(),
            titles = state.items.mapNotNull { (source, result) ->
                (result as? MangaSearchItemResult.Success)?.let { source.id to it.result.map { it.title } }
            }.toMap(),
            errors = state.items.mapNotNull { (source, result) ->
                (result as? MangaSearchItemResult.Error)?.let { source.id to it.throwable }
            }.toMap(),
            effectiveQueries = state.items.mapNotNull { (source, result) ->
                (result as? MangaSearchItemResult.Success)?.let { source.id to it.effectiveQuery }
            }.toMap(),
        )
        return Harness(
            scope = scope,
            states = model.state.map(::snapshot),
            current = { snapshot(model.state.value) },
            draft = model::updateSearchQuery,
            search = model::search,
            retry = { id -> model.retrySource(sources.first { it.id == id }) },
            pinnedOnly = { model.setSourceFilter(MangaSourceFilter.PinnedOnly) },
            selectPinnedOnly = { pinned ->
                model.setSourceFilter(if (pinned) MangaSourceFilter.PinnedOnly else MangaSourceFilter.All)
            },
            toggleResults = model::toggleFilterResults,
            temporaryAll = model::temporarilyShowAll,
            sourceQuery = { id -> model.getSourceQuery(sources.first { it.id == id }) },
            filters = { id -> sources.first { it.id == id }.requestedFilters.toList() },
        )
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private class TestAnimeSource(
        override val id: Long,
        override val name: String,
        private val respond: suspend (Long, String) -> String?,
    ) : AnimeCatalogueSource {
        override val lang = "en"
        override val supportsLatest = false
        val requestedFilters = CopyOnWriteArrayList<AnimeFilterList>()
        override fun getFilterList() = AnimeFilterList()
        override suspend fun getSeasonList(anime: SAnime) = emptyList<SAnime>()

        override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
            requestedFilters += filters
            val title = respond(id, query) ?: return AnimesPage(emptyList(), hasNextPage = false)
            return AnimesPage(
                listOf(
                    SAnime.create().apply {
                        this.title = title
                        url = "/$title"
                    },
                ),
                hasNextPage = false,
            )
        }

        override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = error("Not used")
        override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
            error("Not used")
        override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = error("Not used")
    }

    private class TestMangaSource(
        override val id: Long,
        override val name: String,
        private val respond: suspend (Long, String) -> String?,
    ) : CatalogueSource {
        override val lang = "en"
        override val supportsLatest = false
        val requestedFilters = CopyOnWriteArrayList<FilterList>()
        override fun getFilterList() = FilterList()

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            requestedFilters += filters
            val title = respond(id, query) ?: return MangasPage(emptyList(), hasNextPage = false)
            return MangasPage(
                listOf(
                    SManga.create().apply {
                        this.title = title
                        url = "/$title"
                    },
                ),
                hasNextPage = false,
            )
        }
    }

    private class BooleanSettings(
        pinnedOnly: Boolean? = null,
        hasResults: Boolean? = null,
        private val observe: Boolean = true,
    ) {
        private val values = MutableStateFlow(
            buildMap {
                pinnedOnly?.let { put(PINNED_ONLY_KEY, it) }
                hasResults?.let { put(HAS_RESULTS_KEY, it) }
            },
        )
        val writes = CopyOnWriteArrayList<Pair<String, Boolean>>()

        fun preference(key: String, default: Boolean): Preference<Boolean> = object : Preference<Boolean> {
            override fun key() = key
            override fun get() = values.value[key] ?: default
            override fun set(value: Boolean) {
                writes += key to value
                values.value += key to value
            }
            override fun isSet() = key in values.value
            override fun delete() {
                values.value -= key
            }
            override fun defaultValue() = default
            override fun changes(): Flow<Boolean> = if (observe) values.map { it[key] ?: default } else emptyFlow()
            override fun stateIn(scope: CoroutineScope): StateFlow<Boolean> =
                changes().stateIn(scope, SharingStarted.Eagerly, get())
        }
    }

    private companion object {
        const val PINNED_ONLY_KEY = "__APP_STATE_global_search_pinned_only"
        const val HAS_RESULTS_KEY = "__APP_STATE_has_filters_toggle_state"
    }

    private data class Snapshot(
        val draft: String?,
        val executedQuery: String?,
        val pinnedOnly: Boolean,
        val onlyShowHasResults: Boolean,
        val ids: Set<Long>,
        val titles: Map<Long, List<String>>,
        val errors: Map<Long, Throwable>,
        val effectiveQueries: Map<Long, String?>,
    )

    private class Harness(
        val scope: CoroutineScope,
        val states: Flow<Snapshot>,
        val current: () -> Snapshot,
        val draft: (String) -> Unit,
        val search: () -> Unit,
        val retry: (Long) -> Unit,
        val pinnedOnly: () -> Unit,
        val selectPinnedOnly: (Boolean) -> Unit,
        val toggleResults: () -> Unit,
        val temporaryAll: () -> Unit,
        val sourceQuery: (Long) -> String?,
        val filters: (Long) -> List<Any>,
    ) : AutoCloseable {
        fun submit(query: String) {
            draft(query)
            search()
        }

        suspend fun awaitTitle(id: Long, title: String) {
            withTimeout(5_000) { states.first { it.titles[id] == listOf(title) } }
        }

        suspend fun awaitError(id: Long) {
            withTimeout(5_000) { states.first { id in it.errors } }
        }

        suspend fun awaitIdle() {
            withTimeout(5_000) {
                scope.coroutineContext.job.children.toList().forEach { it.children.toList().joinAll() }
            }
        }

        override fun close() {
            scope.cancel()
        }
    }
}
