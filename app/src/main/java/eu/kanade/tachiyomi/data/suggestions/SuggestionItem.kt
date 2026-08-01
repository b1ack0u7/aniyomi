package eu.kanade.tachiyomi.data.suggestions

import java.io.Serializable

enum class SuggestionReason {
    EXTERNAL_ANILIST,
    EXTERNAL_MAL,
    EXTERNAL_MU,
    SEARCH_TITLE,
    SEARCH_AUTHOR,
    SEARCH_GENRE,
}

data class SuggestionItem(
    val title: String,
    // Tried in order until one returns a hit, so the original title, the normalized variant
    // and any metadata alias can all be attempted.
    val searchQueries: List<String> = listOf(title),
    val thumbnailUrl: String?,
    val providerName: String,
    val providerUrl: String,
    val providerId: String?,
    val mediaType: SuggestionMediaType,
    val reason: SuggestionReason = SuggestionReason.SEARCH_TITLE,
    // How strongly the provider recommends this item, 0..100 — deliberately *not* similarity
    // to the entry the user is on: a good suggestion is a different work, so ranking by
    // resemblance would push the entry's own sequels to the top.
    val relevance: Int = DEFAULT_RELEVANCE,
) : Serializable {

    val searchQuery: String
        get() = searchQueries.firstOrNull { it.isNotBlank() } ?: title

    // Suggestions from an installed source encode [providerId] as `sourceId:url`, letting the
    // UI open the entry directly instead of running a global search.
    val nativeSourceTarget: NativeSourceTarget?
        get() {
            val id = providerId ?: return null
            val separatorIndex = id.indexOf(':')
            if (separatorIndex <= 0 || separatorIndex == id.lastIndex) return null

            val sourceId = id.substring(0, separatorIndex).toLongOrNull() ?: return null
            val url = id.substring(separatorIndex + 1).takeIf { it.isNotBlank() } ?: return null

            return NativeSourceTarget(sourceId, url)
        }

    companion object {
        private const val serialVersionUID = 1L
    }
}

const val DEFAULT_RELEVANCE = 50

fun relevanceForRank(index: Int, total: Int): Int {
    if (total <= 1) return 100
    return (100 - (index * 100 / total)).coerceIn(1, 100)
}

data class NativeSourceTarget(
    val sourceId: Long,
    val url: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
