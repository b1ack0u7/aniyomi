package eu.kanade.presentation.browse

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlobalSearchEmptyStateTest {

    @Test
    fun `opening search does not imply that no sources are installed`() {
        state(hasSearched = false) shouldBe GlobalSearchEmptyState.Prompt
    }

    @Test
    fun `an empty pinned selection offers all sources`() {
        state(pinnedOnly = true) shouldBe GlobalSearchEmptyState.NoPinnedSources
        state(pinnedOnly = false) shouldBe GlobalSearchEmptyState.NoEnabledSources
    }

    @Test
    fun `filtered loading sources do not report no matches or final errors`() {
        state(total = 2, pending = 1, failed = 1) shouldBe GlobalSearchEmptyState.Loading
    }

    @Test
    fun `hidden failed sources remain discoverable after search finishes`() {
        state(total = 2, failed = 1) shouldBe GlobalSearchEmptyState.HiddenErrors
    }

    @Test
    fun `successful empty searches report no matches`() {
        state(total = 2) shouldBe GlobalSearchEmptyState.NoResults
    }

    @Test
    fun `visible results remain accessible while other sources load or fail`() {
        state(total = 3, visible = 1, pending = 1, failed = 1) shouldBe null
    }

    private fun state(
        hasSearched: Boolean = true,
        total: Int = 0,
        visible: Int = 0,
        pending: Int = 0,
        failed: Int = 0,
        pinnedOnly: Boolean = false,
    ) = globalSearchEmptyState(hasSearched, total, visible, pending, failed, pinnedOnly)
}
