package eu.kanade.tachiyomi.data.suggestions.sources

import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionMediaType
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed

abstract class RecommendationSource {
    abstract val name: String
    abstract val mediaType: SuggestionMediaType

    // Set once the provider identified the seed entry in its own catalogue.
    var matchedBase: Boolean = false
        protected set

    // Implementations return an empty list rather than throwing on a provider-side failure.
    abstract suspend fun fetchSuggestions(seed: SuggestionSeed): List<SuggestionItem>
}
