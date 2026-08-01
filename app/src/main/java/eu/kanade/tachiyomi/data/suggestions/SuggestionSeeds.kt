package eu.kanade.tachiyomi.data.suggestions

import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga

// Shared by the entry screen and the full-list screen: seeding them differently would show
// two different result sets for the same entry.
fun Anime.toSuggestionSeed(alternativeTitles: List<String> = emptyList()) = SuggestionSeed(
    mediaType = SuggestionMediaType.ANIME,
    primaryTitle = title,
    candidateTitles = SuggestionTitleResolver.resolveCandidates(
        title = title,
        description = description,
        url = url,
        alternativeTitles = alternativeTitles,
    ),
    description = description,
    author = author,
    genres = genre,
)

fun Manga.toSuggestionSeed(alternativeTitles: List<String> = emptyList()) = SuggestionSeed(
    mediaType = SuggestionMediaType.MANGA,
    primaryTitle = title,
    candidateTitles = SuggestionTitleResolver.resolveCandidates(
        title = title,
        description = description,
        url = url,
        alternativeTitles = alternativeTitles,
    ),
    description = description,
    author = author,
    genres = genre,
)
