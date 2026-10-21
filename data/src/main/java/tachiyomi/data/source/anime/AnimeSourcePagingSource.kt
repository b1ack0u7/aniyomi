package tachiyomi.data.source.anime

import androidx.paging.PagingState
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.SourceSearchQuery
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.items.episode.model.NoEpisodesException
import tachiyomi.domain.source.anime.repository.AnimeSourcePagingSourceType

class AnimeSourceSearchPagingSource(
    source: AnimeCatalogueSource,
    val query: String,
    val filters: AnimeFilterList,
) : AnimeSourcePagingSource(source) {
    private val searchQuery = SourceSearchQuery(query)

    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return searchQuery.search(currentPage, isEmpty = { it.animes.isEmpty() }) { effectiveQuery ->
            source.getSearchAnime(currentPage, effectiveQuery, filters)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, SAnime>): Long? {
        return if (searchQuery.effectiveQuery != query) 1L else super.getRefreshKey(state)
    }
}

class AnimeSourcePopularPagingSource(source: AnimeCatalogueSource) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getPopularAnime(currentPage)
    }
}

class AnimeSourceLatestPagingSource(source: AnimeCatalogueSource) : AnimeSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class AnimeSourcePagingSource(
    protected val source: AnimeCatalogueSource,
) : AnimeSourcePagingSourceType() {

    abstract suspend fun requestNextPage(currentPage: Int): AnimesPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, SAnime> {
        val page = params.key ?: 1

        val animesPage = try {
            withIOContext {
                requestNextPage(page.toInt())
                    .takeIf { it.animes.isNotEmpty() }
                    ?: throw NoEpisodesException()
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return LoadResult.Error(e)
        } catch (e: LinkageError) {
            currentCoroutineContext().ensureActive()
            // An extension built against a newer library than the app bundles; surface it instead of crashing.
            return LoadResult.Error(e)
        }

        return LoadResult.Page(
            data = animesPage.animes,
            prevKey = null,
            nextKey = if (animesPage.hasNextPage) page + 1 else null,
        )
    }

    override fun getRefreshKey(state: PagingState<Long, SAnime>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}
