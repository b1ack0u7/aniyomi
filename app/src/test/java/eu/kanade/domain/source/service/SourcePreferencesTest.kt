package eu.kanade.domain.source.service

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SourcePreferencesTest {

    @Test
    fun `global search stores boolean app state with pinned default and the existing results key`() {
        val pinnedOnly = mockk<Preference<Boolean>>()
        val hasResults = mockk<Preference<Boolean>>()
        val store = mockk<PreferenceStore> {
            every { getBoolean("__APP_STATE_global_search_pinned_only", true) } returns pinnedOnly
            every { getBoolean("__APP_STATE_has_filters_toggle_state", false) } returns hasResults
        }
        val preferences = SourcePreferences(store)

        preferences.globalSearchPinnedSourcesOnly() shouldBe pinnedOnly
        preferences.globalSearchFilterState() shouldBe hasResults

        verify(exactly = 1) { store.getBoolean("__APP_STATE_global_search_pinned_only", true) }
        verify(exactly = 1) { store.getBoolean("__APP_STATE_has_filters_toggle_state", false) }
        Preference.isAppState("__APP_STATE_global_search_pinned_only") shouldBe true
        Preference.isAppState("__APP_STATE_has_filters_toggle_state") shouldBe true
    }
}
