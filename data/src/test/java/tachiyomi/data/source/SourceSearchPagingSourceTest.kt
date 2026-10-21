package tachiyomi.data.source

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.data.source.anime.AnimeSourceSearchPagingSource
import tachiyomi.data.source.manga.SourceSearchPagingSource
import java.io.IOException

@Timeout(15)
class SourceSearchPagingSourceTest {

    @Test
    fun `empty first pages retry once and append with the successful query and original filters`() = runBlocking {
        for (anime in listOf(true, false)) {
            val fixture = fixture(anime) { _, query -> query == NORMALIZED_QUERY }

            val first = fixture.load(1)
            val second = fixture.load(2)

            assertTrue(first is PagingSource.LoadResult.Page<*, *>)
            assertTrue(second is PagingSource.LoadResult.Page<*, *>)
            assertEquals(listOf(1 to ORIGINAL_QUERY, 1 to NORMALIZED_QUERY, 2 to NORMALIZED_QUERY), fixture.queries())
            fixture.calls.forEach { assertSame(fixture.filters, it.filters) }
        }
    }

    @Test
    fun `a successful original query remains unchanged on later pages and refresh`() = runBlocking {
        for (anime in listOf(true, false)) {
            val fixture = fixture(anime) { _, _ -> true }

            fixture.load(1)
            fixture.load(2)

            assertEquals(listOf(1 to ORIGINAL_QUERY, 2 to ORIGINAL_QUERY), fixture.queries())
            assertEquals(2L, fixture.refreshKey())
        }
    }

    @Test
    fun `an empty append never retries with a different query`() = runBlocking {
        for (anime in listOf(true, false)) {
            val fixture = fixture(anime) { page, _ -> page == 1 }

            fixture.load(1)
            val append = fixture.load(2)

            assertTrue(append is PagingSource.LoadResult.Error<*, *>)
            assertEquals(listOf(1 to ORIGINAL_QUERY, 2 to ORIGINAL_QUERY), fixture.queries())
        }
    }

    @Test
    fun `two empty first pages do not cause further search attempts`() = runBlocking {
        for (anime in listOf(true, false)) {
            val fixture = fixture(anime) { _, _ -> false }

            val result = fixture.load(1)

            assertTrue(result is PagingSource.LoadResult.Error<*, *>)
            assertEquals(listOf(1 to ORIGINAL_QUERY, 1 to NORMALIZED_QUERY), fixture.queries())
        }
    }

    @Test
    fun `network errors do not trigger a normalized retry`() = runBlocking {
        for (anime in listOf(true, false)) {
            val error = IOException("network unavailable")
            val fixture = fixture(anime) { _, _ -> throw error }

            val result = fixture.load(1)

            val actual = (result as PagingSource.LoadResult.Error<*, *>).throwable
            assertEquals(error.javaClass, actual.javaClass)
            assertEquals(error.message, actual.message)
            assertEquals(listOf(1 to ORIGINAL_QUERY), fixture.queries())
        }
    }

    @Test
    fun `normalized retry errors remain visible without further attempts`() = runBlocking {
        for (anime in listOf(true, false)) {
            val error = IOException("retry failed")
            val fixture = fixture(anime) { _, query ->
                if (query == NORMALIZED_QUERY) throw error
                false
            }

            val result = fixture.load(1)

            val actual = (result as PagingSource.LoadResult.Error<*, *>).throwable
            assertEquals(error.javaClass, actual.javaClass)
            assertEquals(error.message, actual.message)
            assertEquals(listOf(1 to ORIGINAL_QUERY, 1 to NORMALIZED_QUERY), fixture.queries())
        }
    }

    @Test
    fun `cancelling either search attempt propagates without returning a paging error`() = runBlocking {
        for (anime in listOf(true, false)) {
            for (cancelNormalized in listOf(true, false)) {
                val started = CompletableDeferred<Unit>()
                val fixture = fixture(anime) { _, query ->
                    if ((query == NORMALIZED_QUERY) == cancelNormalized) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    false
                }
                var returned = false
                val job = launch {
                    fixture.load(1)
                    returned = true
                }
                started.await()

                job.cancelAndJoin()

                assertFalse(returned)
                assertEquals(if (cancelNormalized) 2 else 1, fixture.calls.size)
            }
        }
    }

    @Test
    fun `a source timeout on either attempt becomes a paging error instead of cancelling the load`() = runBlocking {
        for (anime in listOf(true, false)) {
            for (timeoutNormalized in listOf(true, false)) {
                val fixture = fixture(anime) { _, query ->
                    if ((query == NORMALIZED_QUERY) == timeoutNormalized) {
                        withTimeout(1) { awaitCancellation() }
                    }
                    false
                }

                val result = fixture.load(1)

                assertTrue((result as PagingSource.LoadResult.Error<*, *>).throwable is TimeoutCancellationException)
                assertEquals(if (timeoutNormalized) 2 else 1, fixture.calls.size)
            }
        }
    }

    @Test
    fun `refresh after a normalized search restarts from page one`() = runBlocking {
        for (anime in listOf(true, false)) {
            val fixture = fixture(anime) { _, query -> query == NORMALIZED_QUERY }
            fixture.load(1)
            fixture.load(2)

            assertEquals(1L, fixture.refreshKey())
        }
    }

    private fun fixture(anime: Boolean, respond: suspend (Int, String) -> Boolean): Fixture {
        val calls = mutableListOf<Request>()
        return if (anime) {
            val filters = AnimeFilterList()
            val source = mockk<AnimeCatalogueSource> {
                coEvery { getSearchAnime(any(), any(), any()) } coAnswers {
                    val page = firstArg<Int>()
                    val query = secondArg<String>()
                    calls += Request(page, query, thirdArg())
                    AnimesPage(if (respond(page, query)) listOf(sampleAnime()) else emptyList(), hasNextPage = true)
                }
            }
            val pager = AnimeSourceSearchPagingSource(source, ORIGINAL_QUERY, filters)
            Fixture(
                calls = calls,
                filters = filters,
                loadPage = { pager.load(params(it)) },
                refreshKey = { pager.getRefreshKey(pagingState(sampleAnime())) },
            )
        } else {
            val filters = FilterList()
            val source = mockk<CatalogueSource> {
                coEvery { getSearchManga(any(), any(), any()) } coAnswers {
                    val page = firstArg<Int>()
                    val query = secondArg<String>()
                    calls += Request(page, query, thirdArg())
                    MangasPage(if (respond(page, query)) listOf(sampleManga()) else emptyList(), hasNextPage = true)
                }
            }
            val pager = SourceSearchPagingSource(source, ORIGINAL_QUERY, filters)
            Fixture(
                calls = calls,
                filters = filters,
                loadPage = { pager.load(params(it)) },
                refreshKey = { pager.getRefreshKey(pagingState(sampleManga())) },
            )
        }
    }

    private fun params(page: Long): PagingSource.LoadParams<Long> = if (page == 1L) {
        PagingSource.LoadParams.Refresh(key = page, loadSize = 25, placeholdersEnabled = false)
    } else {
        PagingSource.LoadParams.Append(key = page, loadSize = 25, placeholdersEnabled = false)
    }

    private fun <T : Any> pagingState(item: T) = PagingState(
        pages = listOf(PagingSource.LoadResult.Page(data = listOf(item), prevKey = null, nextKey = 2L)),
        anchorPosition = 0,
        config = PagingConfig(pageSize = 25),
        leadingPlaceholderCount = 0,
    )

    private fun sampleAnime() = SAnime.create().apply {
        title = "Title"
        url = "/title"
    }

    private fun sampleManga() = SManga.create().apply {
        title = "Title"
        url = "/title"
    }

    private data class Request(val page: Int, val query: String, val filters: Any)

    private class Fixture(
        val calls: List<Request>,
        val filters: Any,
        val loadPage: suspend (Long) -> PagingSource.LoadResult<Long, *>,
        val refreshKey: () -> Long?,
    ) {
        suspend fun load(page: Int) = loadPage(page.toLong())
        fun queries() = calls.map { it.page to it.query }
    }

    companion object {
        private const val ORIGINAL_QUERY = "My   Title ¿¿"
        private const val NORMALIZED_QUERY = "My Title??"
    }
}
