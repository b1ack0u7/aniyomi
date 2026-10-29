package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryScreenModel
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.library.anime.LibraryAnime

class AnimeLibrarySelectionTest {

    @Test
    fun `deselecting latest item restores remaining anchor and its category`() {
        val state = state().toggleSelection(item(1)).toggleSelection(item(2)).toggleSelection(item(2))
            .toggleRangeSelection(item(4))
        state.ids shouldBe listOf(1L, 2L, 3L, 4L)
        state.selectionAnchors.last() shouldBe LibrarySelectionAnchor(4, 1)
    }

    @Test
    fun `deselecting another item preserves latest anchor`() {
        val state = state().toggleSelection(item(1)).toggleSelection(item(3)).toggleSelection(item(1))
            .toggleRangeSelection(item(4))
        state.ids shouldBe listOf(3L, 4L)
    }

    @Test
    fun `removing anchor from another category restores original category provenance`() {
        val state = state().toggleSelection(item(1, 2)).toggleSelection(item(2, 1)).toggleSelection(item(2, 1))
        state.selectionAnchors.last() shouldBe LibrarySelectionAnchor(1, 2)
        state.toggleRangeSelection(item(4, 1)).ids shouldBe listOf(1L, 4L)
        state.toggleRangeSelection(item(4, 2)).ids shouldBe listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `backward range anchors at pressed target for the next range`() {
        val state = state().toggleSelection(item(4)).toggleRangeSelection(item(2))
        state.selectionAnchors.last() shouldBe LibrarySelectionAnchor(2, 1)
        val next = state.toggleSelection(item(3)).toggleRangeSelection(item(1))
        next.ids.toSet() shouldBe setOf(1L, 2L, 4L)
    }

    @Test
    fun `select all updates category and anchor for newly added titles`() {
        val state = state().toggleSelection(item(1, 2)).selectAll(0).toggleSelection(item(3))
            .toggleRangeSelection(item(2))
        state.ids.toSet() shouldBe setOf(1L, 2L, 3L, 4L)
        state.selectionAnchors.last() shouldBe LibrarySelectionAnchor(2, 1)
    }

    @Test
    fun `invert updates anchors and removes anchors for deselected titles`() {
        val inverted = state().toggleSelection(item(1)).toggleSelection(item(2)).invertSelection(0)
        inverted.ids shouldBe listOf(3L, 4L)
        inverted.selectionAnchors shouldBe listOf(LibrarySelectionAnchor(3, 1), LibrarySelectionAnchor(4, 1))
        inverted.toggleRangeSelection(item(1)).ids.toSet() shouldBe setOf(1L, 2L, 3L, 4L)
        inverted.selectAll(0).invertSelection(0).selectionAnchors shouldBe emptyList()
    }

    @Test
    fun `clear and deselect last item discard all anchors`() {
        state().toggleSelection(item(1)).clearSelection().selectionAnchors shouldBe emptyList()
        state().toggleSelection(item(1)).toggleSelection(item(1)).selectionAnchors shouldBe emptyList()
    }

    @Test
    fun `range to already selected title in another category remembers pressed category`() {
        val state = state().toggleSelection(item(1, 1)).toggleRangeSelection(item(1, 2))
        state.ids shouldBe listOf(1L)
        state.selectionAnchors.last() shouldBe LibrarySelectionAnchor(1, 2)
        state.toggleRangeSelection(item(4, 2)).ids shouldBe listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `filtered out anchor selects target without spanning hidden titles`() {
        val state = state().toggleSelection(item(1)).copy(
            library = mapOf(category(1) to listOf(item(3), item(4))),
        ).toggleRangeSelection(item(4))
        state.ids shouldBe listOf(1L, 4L)
    }

    private val AnimeLibraryScreenModel.State.ids get() = selection.map { it.id }

    private fun state() = AnimeLibraryScreenModel.State(
        library = listOf(1L, 2L).associate { category ->
            category(category) to (1L..4L).map { item(it, category) }
        },
    )

    private fun category(id: Long) = Category(id, "Category $id", id, 0, false)

    private fun item(id: Long, category: Long = 1) = AnimeLibraryItem(
        libraryAnime = LibraryAnime(
            anime = Anime.create().copy(id = id),
            categories = listOf(1, 2),
            totalCount = 0,
            seenCount = 0,
            bookmarkCount = 0,
            fillermarkCount = 0,
            latestUpload = 0,
            episodeFetchedAt = 0,
            lastSeen = 0,
        ),
        category = category,
        sourceManager = mockk(),
    )
}
