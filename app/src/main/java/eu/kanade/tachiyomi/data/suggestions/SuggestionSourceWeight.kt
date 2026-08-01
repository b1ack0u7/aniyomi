package eu.kanade.tachiyomi.data.suggestions

// Tuned so curated recommendation databases dominate: the entry's own source only knows how
// to match strings, so what it returns is related by creator or theme at best.
object SuggestionSourceWeight {

    const val EXTERNAL_ANILIST: Double = 1.0
    const val EXTERNAL_MAL: Double = 0.9
    const val EXTERNAL_MU: Double = 0.9
    const val SEARCH_TITLE: Double = 0.6
    const val SEARCH_AUTHOR: Double = 0.4
    const val SEARCH_GENRE: Double = 0.3

    fun of(reason: SuggestionReason): Double = when (reason) {
        SuggestionReason.EXTERNAL_ANILIST -> EXTERNAL_ANILIST
        SuggestionReason.EXTERNAL_MAL -> EXTERNAL_MAL
        SuggestionReason.EXTERNAL_MU -> EXTERNAL_MU
        SuggestionReason.SEARCH_TITLE -> SEARCH_TITLE
        SuggestionReason.SEARCH_AUTHOR -> SEARCH_AUTHOR
        SuggestionReason.SEARCH_GENRE -> SEARCH_GENRE
    }

    fun finalScore(item: SuggestionItem): Double =
        of(item.reason) * item.relevance.coerceIn(0, 100).toDouble()
}
