package eu.kanade.tachiyomi.ui.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryRangeSelectionTest {

    @Test
    fun `range selection adds intervening titles once in either direction`() {
        selectLibraryRange(listOf(1L, 2L, 3L, 4L), listOf(1L, 2L), 4L, true) { it } shouldBe
            listOf(1L, 2L, 3L, 4L)
        selectLibraryRange(listOf(1L, 2L, 3L, 4L), listOf(4L), 1L, true) { it } shouldBe
            listOf(4L, 1L, 2L, 3L)
    }

    @Test
    fun `another category selects only the target even when the anchor also belongs there`() {
        selectLibraryRange(listOf(1L, 2L, 3L), listOf(1L), 3L, false) { it } shouldBe listOf(1L, 3L)
    }

    @Test
    fun `filtered out anchor does not create an invalid range`() {
        selectLibraryRange(listOf(2L, 3L), listOf(1L), 3L, true) { it } shouldBe listOf(1L, 3L)
    }

    @Test
    fun `metadata changes keep the anchor identified by title id`() {
        val items = listOf(Entry(1, "new"), Entry(2, "second"), Entry(3, "third"))
        selectLibraryRange(items, listOf(Entry(1, "old")), items.last(), true) { it.id }.map { it.id } shouldBe
            listOf(1L, 2L, 3L)
    }

    @Test
    fun `target already selected in another category is not duplicated`() {
        selectLibraryRange(listOf(1L, 2L, 3L), listOf(3L, 1L), 3L, false) { it } shouldBe listOf(3L, 1L)
    }

    private data class Entry(val id: Long, val title: String)
}
