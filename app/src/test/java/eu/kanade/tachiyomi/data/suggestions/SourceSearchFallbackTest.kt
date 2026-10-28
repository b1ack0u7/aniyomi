package eu.kanade.tachiyomi.data.suggestions

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException

class SourceSearchFallbackTest {

    @BeforeEach
    @AfterEach
    fun clearCache() {
        SuggestionCache.invalidateAll()
    }

    @ParameterizedTest
    @EnumSource(SuggestionMediaType::class)
    fun `cached exhausted searches do not invent further pages`(mediaType: SuggestionMediaType) = runTest {
        var requests = 0
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { _, _ ->
            requests++
            page("seedling")
        }
        val seed = seed(mediaType)
        pager(seed, search = search).loadInitial().size shouldBe 1

        val cached = pager(seed, search = search)
        cached.loadInitial().size shouldBe 1
        cached.hasNextPage shouldBe false
        cached.loadNextPage().size shouldBe 1
        requests shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(SuggestionMediaType::class)
    fun `cached searches start skipped queries on their first page`(mediaType: SuggestionMediaType) = runTest {
        val requests = mutableListOf<Pair<String, Int>>()
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { query, number ->
            requests += query to number
            page(if (query == "seed") "seedling" else "related")
        }
        val seed = seed(mediaType)
        pager(seed, author = "Creator", maxResults = 1, search = search).loadInitial()
        requests shouldContainExactly listOf("seed" to 1)

        val cached = pager(seed, author = "Creator", maxResults = 1, search = search)
        cached.loadInitial().size shouldBe 1
        cached.hasNextPage shouldBe true
        cached.loadNextPage().map { it.title } shouldContainExactly listOf("seedling", "related")
        requests shouldContainExactly listOf("seed" to 1, "Creator" to 1)
        cached.hasNextPage shouldBe false
    }

    @Test
    fun `cached cursor progress is copied for each pager`() = runTest {
        val requests = mutableListOf<Int>()
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { _, number ->
            requests += number
            page("seedling $number", hasNextPage = number == 1)
        }
        pager(search = search).loadInitial()
        val first = pager(search = search)
        val second = pager(search = search)
        first.loadInitial()
        second.loadInitial()

        first.loadNextPage().size shouldBe 2
        second.loadNextPage().size shouldBe 2
        requests shouldContainExactly listOf(1, 2, 2)
    }

    @Test
    fun `queued queries stay on page one once the initial limit is reached`() = runTest {
        val requests = mutableListOf<Pair<String, Int>>()
        val seed = seed().copy(candidateTitles = listOf("seed", "alias", "second", "third"))
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { query, number ->
            requests += query to number
            delay(10)
            page("$query result")
        }
        pager(seed, maxResults = 1, search = search).loadInitial().size shouldBe 1
        requests shouldContainExactly listOf("seed" to 1, "alias" to 1)

        val cached = pager(seed, maxResults = 1, search = search)
        cached.loadInitial()
        cached.loadNextPage().size shouldBe 3
        requests shouldContainExactly listOf("seed" to 1, "alias" to 1, "second" to 1, "third" to 1)
    }

    @Test
    fun `successful empty searches remain cacheable`() = runTest {
        var requests = 0
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { _, _ ->
            requests++
            SourceSearchFallback.SearchPage(emptyList(), false)
        }
        pager(search = search).loadInitial() shouldBe emptyList()
        val cached = pager(search = search)
        cached.loadInitial() shouldBe emptyList()
        cached.hasNextPage shouldBe false
        requests shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(SuggestionMediaType::class)
    fun `failed initial searches are retried after reopening`(mediaType: SuggestionMediaType) = runTest {
        var failing = true
        var requests = 0
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { _, _ ->
            requests++
            if (failing) throw IOException("offline")
            page("seedling")
        }
        val seed = seed(mediaType)
        pager(seed, search = search).loadInitial() shouldBe emptyList()

        failing = false
        pager(seed, search = search).loadInitial().map { it.title } shouldContainExactly listOf("seedling")
        requests shouldBe 2
    }

    @Test
    fun `partial initial failures do not freeze an incomplete cache`() = runTest {
        var failing = true
        val requests = mutableListOf<Pair<String, Int>>()
        val seed = seed().copy(candidateTitles = listOf("seed", "alias"))
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { query, number ->
            requests += query to number
            if (query == "alias" && failing) throw IOException("rate limited")
            page(if (query == "alias") "alias result" else "seedling")
        }
        pager(seed, search = search).loadInitial().size shouldBe 1

        failing = false
        pager(seed, search = search).loadInitial().size shouldBe 2
        requests shouldContainExactly listOf("seed" to 1, "alias" to 1, "seed" to 1, "alias" to 1)
    }

    @Test
    fun `an initial failed cursor can recover on its first page`() = runTest {
        var failing = true
        val requests = mutableListOf<Int>()
        val pager = pager { _, number ->
            requests += number
            if (failing) throw IOException("offline")
            page("seedling")
        }
        pager.loadInitial() shouldBe emptyList()
        pager.hasNextPage shouldBe true

        failing = false
        pager.loadNextPage().size shouldBe 1
        pager.hasNextPage shouldBe false
        requests shouldContainExactly listOf(1, 1)
    }

    @Test
    fun `pagination failures retain their page without immediately retrying`() = runTest {
        var failing = false
        val requests = mutableListOf<Int>()
        val pager = pager { _, number ->
            requests += number
            if (failing) throw IOException("offline")
            page("seedling $number", hasNextPage = number == 1)
        }
        pager.loadInitial().size shouldBe 1
        failing = true
        pager.loadNextPage().size shouldBe 1
        pager.hasNextPage shouldBe true
        requests shouldContainExactly listOf(1, 2)

        failing = false
        pager.loadNextPage().size shouldBe 2
        pager.hasNextPage shouldBe false
        requests shouldContainExactly listOf(1, 2, 2)
    }

    @Test
    fun `failed title queries do not starve pending creator and genre queries`() = runTest {
        val requests = mutableListOf<Pair<String, Int>>()
        val seed = seed().copy(candidateTitles = listOf("seed", "alias", "third"))
        val pager = pager(seed, author = "Creator", genres = listOf("Fantasy"), maxResults = 1) { query, number ->
            requests += query to number
            when (query) {
                "Creator" -> page("creator result $number", hasNextPage = number == 1)
                "Fantasy" -> page("genre result")
                else -> throw IOException("offline")
            }
        }
        pager.loadInitial().size shouldBe 1
        requests.clear()

        pager.loadNextPage().size shouldBe 1
        requests shouldContainExactly listOf("seed" to 1, "alias" to 1, "third" to 1)
        pager.hasNextPage shouldBe true
        requests.clear()

        pager.loadNextPage().size shouldBe 3
        requests shouldContainExactly listOf("Creator" to 2, "Fantasy" to 1, "seed" to 1)
        pager.hasNextPage shouldBe true
    }

    @Test
    fun `enriched creator and genre metadata bypasses the earlier cache`() = runTest {
        val requests = mutableListOf<String>()
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { query, _ ->
            requests += query
            SourceSearchFallback.SearchPage(emptyList(), false)
        }
        pager(search = search).loadInitial()
        requests.clear()
        pager(author = "Creator", genres = listOf("Fantasy"), search = search).loadInitial()

        requests shouldContainExactly listOf("seed", "Creator", "Fantasy")
    }

    @Test
    fun `manual invalidation removes cached source search state`() = runTest {
        var requests = 0
        val search: suspend (String, Int) -> SourceSearchFallback.SearchPage = { _, _ ->
            requests++
            page("seedling")
        }
        pager(search = search).loadInitial()
        SuggestionCache.invalidateForSeed(seed(), "/entry")
        pager(search = search).loadInitial()

        requests shouldBe 2
    }

    @Test
    fun `cancellation propagates without caching the interrupted search`() = runTest {
        val failure = CancellationException("cancelled")
        val cancelled = pager { _, _ -> throw failure }
        runCatching { cancelled.loadInitial() }.exceptionOrNull() shouldBe failure

        val recovered = pager { _, _ -> page("seedling") }
        recovered.loadInitial().size shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(SuggestionMediaType::class)
    fun `rate limit stops remaining tiers and automatic pagination while retaining partial results`(
        mediaType: SuggestionMediaType,
    ) = runTest {
        val requests = mutableListOf<Pair<String, Int>>()
        val seed = seed(mediaType)
        val pager = pager(seed, author = "Creator", genres = listOf("Fantasy")) { query, number ->
            requests += query to number
            if (query == "Creator") throw HttpException(429, "600")
            page(if (query == "seed") "seedling" else "genre result", hasNextPage = true)
        }

        pager.loadInitial().map { it.title } shouldContainExactly listOf("seedling")
        pager.hasNextPage shouldBe false
        pager.loadNextPage().map { it.title } shouldContainExactly listOf("seedling")
        pager.loadInitial().map { it.title } shouldContainExactly listOf("seedling")
        requests shouldContainExactly listOf("seed" to 1, "Creator" to 1)

        val recovered = pager(seed, author = "Creator", genres = listOf("Fantasy")) { query, number ->
            requests += query to number
            page(if (query == "seed") "seedling" else "$query result")
        }
        recovered.loadInitial().size shouldBe 3
        requests.count { it == ("seed" to 1) } shouldBe 2
    }

    @Test
    fun `pagination access denial does not advance or repeat a rejected cursor`() = runTest {
        val requests = mutableListOf<Int>()
        val pager = pager { _, number ->
            requests += number
            if (number == 2) throw HttpException(403)
            page("seedling", hasNextPage = true)
        }
        pager.loadInitial().size shouldBe 1

        repeat(3) { pager.loadNextPage().size shouldBe 1 }

        requests shouldContainExactly listOf(1, 2)
        pager.hasNextPage shouldBe false
    }

    private fun seed(mediaType: SuggestionMediaType = SuggestionMediaType.MANGA) = SuggestionSeed(
        mediaType = mediaType,
        primaryTitle = "seed",
        candidateTitles = listOf("seed"),
        description = null,
    )

    private fun pager(
        seed: SuggestionSeed = seed(),
        author: String? = null,
        genres: List<String>? = null,
        maxResults: Int = 5,
        search: suspend (String, Int) -> SourceSearchFallback.SearchPage,
    ) = SourceSearchFallback(
        seed = seed,
        entryTitle = seed.primaryTitle,
        entryUrl = "/entry",
        author = author,
        artist = null,
        genres = genres,
        sourceId = 1,
        sourceName = "Test source",
        maxResults = maxResults,
        totalLimit = 10,
        search = search,
    )

    private fun page(title: String, hasNextPage: Boolean = false) = SourceSearchFallback.SearchPage(
        candidates = listOf(SourceSearchFallback.Candidate(title, "/$title", null)),
        hasNextPage = hasNextPage,
    )
}
