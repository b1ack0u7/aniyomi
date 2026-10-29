package tachiyomi.domain.library.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryCategorySelectionTest {

    private val library = listOf(listOf(0L), listOf(1L, 2L), listOf(2L), listOf(3L))

    @Test
    fun `a title in several included categories is selected once`() {
        library.filterByLibraryCategories(listOf(1, 2), emptyList()) { it } shouldBe
            listOf(listOf(1L, 2L), listOf(2L))
    }

    @Test
    fun `any excluded membership overrides an included membership`() {
        library.filterByLibraryCategories(listOf(1, 2), listOf(1)) { it } shouldBe listOf(listOf(2L))
    }

    @Test
    fun `empty inclusion selects every category except exclusions`() {
        library.filterByLibraryCategories(emptyList(), listOf(2)) { it } shouldBe listOf(listOf(0L), listOf(3L))
    }

    @Test
    fun `system category is an explicit membership`() {
        library.filterByLibraryCategories(listOf(0), emptyList()) { it } shouldBe listOf(listOf(0L))
    }
}
