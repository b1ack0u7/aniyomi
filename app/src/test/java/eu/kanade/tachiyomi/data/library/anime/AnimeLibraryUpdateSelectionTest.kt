package eu.kanade.tachiyomi.data.library.anime

import aniyomi.domain.anime.SeasonAnime
import eu.kanade.tachiyomi.animesource.model.FetchType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime

class AnimeLibraryUpdateSelectionTest {

    @Test
    fun `retry finds a non favorite season through its favorite parent`() = runTest {
        val parent = entry(1, favorite = true, fetchType = FetchType.Seasons)
        val season = entry(2, parentId = 1)
        val requestedParents = mutableListOf<Long>()

        val selected = selectAnimeForLibraryUpdate(
            libraryAnime = listOf(parent.toLibraryAnime()),
            includeSeasons = true,
            retryIds = setOf(2),
        ) { parentId ->
            requestedParents.add(parentId)
            listOf(season)
        }

        selected.map { it.anime.id } shouldBe listOf(2L)
        requestedParents shouldBe listOf(1L)
        selected.single().anime.favorite shouldBe false
    }

    @Test
    fun `retry requests only due episodes after expanding seasons`() = runTest {
        val parent = entry(1, favorite = true, fetchType = FetchType.Seasons)
        val direct = entry(4, favorite = true)

        val selected = selectAnimeForLibraryUpdate(
            libraryAnime = listOf(parent.toLibraryAnime(), direct.toLibraryAnime()),
            includeSeasons = true,
            retryIds = setOf(2, 4),
        ) {
            listOf(entry(2, parentId = 1), entry(3, parentId = 1))
        }

        selected.map { it.anime.id } shouldBe listOf(2L, 4L)
    }

    @Test
    fun `disabling season updates removes a pending season from selection`() = runTest {
        val parent = entry(1, favorite = true, fetchType = FetchType.Seasons)

        val selected = selectAnimeForLibraryUpdate(
            libraryAnime = listOf(parent.toLibraryAnime()),
            includeSeasons = false,
            retryIds = setOf(2),
        ) {
            error("Disabled seasons must not be loaded")
        }

        selected shouldBe emptyList()
    }

    @Test
    fun `retry cannot load seasons whose parent is no longer in the library`() = runTest {
        val selected = selectAnimeForLibraryUpdate(
            libraryAnime = listOf(entry(4, favorite = true).toLibraryAnime()),
            includeSeasons = true,
            retryIds = setOf(2),
        ) {
            error("A removed parent must not be loaded")
        }

        selected shouldBe emptyList()
    }

    @Test
    fun `parents in multiple categories are expanded once`() = runTest {
        val parent = entry(1, favorite = true, fetchType = FetchType.Seasons).toLibraryAnime()
        var calls = 0

        val selected = selectAnimeForLibraryUpdate(
            libraryAnime = listOf(parent.copy(categories = listOf(1, 2))),
            includeSeasons = true,
            retryIds = null,
        ) {
            calls++
            listOf(entry(2, parentId = 1))
        }

        calls shouldBe 1
        selected.map { it.anime.id } shouldBe listOf(2L)
    }

    @Test
    fun `a favorite season is selected through its own library entry`() = runTest {
        val parent = entry(1, favorite = true, fetchType = FetchType.Seasons)
        val favoriteSeason = entry(2, favorite = true, parentId = 1)

        val selected = selectAnimeForLibraryUpdate(
            libraryAnime = listOf(parent.toLibraryAnime(), favoriteSeason.toLibraryAnime()),
            includeSeasons = true,
            retryIds = setOf(2),
        ) {
            listOf(favoriteSeason)
        }

        selected.map { it.anime.id } shouldBe listOf(2L)
        selected.single().anime.favorite shouldBe true
    }

    private fun entry(
        id: Long,
        favorite: Boolean = false,
        parentId: Long? = null,
        fetchType: FetchType = FetchType.Episodes,
    ) = SeasonAnime(
        anime = Anime.create().copy(id = id, favorite = favorite, parentId = parentId, fetchType = fetchType),
        totalCount = 1,
        seenCount = 0,
        bookmarkCount = 0,
        fillermarkCount = 0,
        latestUpload = 0,
        fetchedAt = 0,
        lastSeen = 0,
    )
}
