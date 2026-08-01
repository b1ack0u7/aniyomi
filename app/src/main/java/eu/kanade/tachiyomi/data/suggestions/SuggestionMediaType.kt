package eu.kanade.tachiyomi.data.suggestions

import java.io.Serializable

enum class SuggestionMediaType : Serializable {
    ANIME,
    MANGA,
}

fun SuggestionMediaType.toAniListType(): String = when (this) {
    SuggestionMediaType.ANIME -> "ANIME"
    SuggestionMediaType.MANGA -> "MANGA"
}

fun SuggestionMediaType.toJikanPath(): String = when (this) {
    SuggestionMediaType.ANIME -> "anime"
    SuggestionMediaType.MANGA -> "manga"
}
