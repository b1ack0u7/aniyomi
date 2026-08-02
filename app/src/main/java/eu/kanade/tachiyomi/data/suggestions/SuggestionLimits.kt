package eu.kanade.tachiyomi.data.suggestions

// The source-search cache is keyed by this limit, so the row and the "see all" screen have to
// request the same amount or the screen re-runs every query the row just made and gets throttled.
const val SUGGESTION_SEARCH_LIMIT = 60
