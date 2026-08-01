package eu.kanade.tachiyomi.data.suggestions.util

import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionSeed
import eu.kanade.tachiyomi.data.suggestions.SuggestionSourceWeight
import eu.kanade.tachiyomi.data.suggestions.SuggestionTitleResolver

// Sorting first is what makes the `putIfAbsent` below keep the best of each franchise.
fun List<SuggestionItem>.dedupeByFranchise(): List<SuggestionItem> {
    if (isEmpty()) return this
    val ordered = sortedWith(
        compareByDescending<SuggestionItem> { SuggestionSourceWeight.finalScore(it) }
            .thenByDescending { !it.thumbnailUrl.isNullOrBlank() }
            .thenBy { it.title.length },
    )

    val seenKeys = LinkedHashMap<String, SuggestionItem>()
    for (item in ordered) {
        val key = SuggestionTitleResolver.franchiseKey(item.title)
        if (key.isBlank()) {
            // Titles that clean down to nothing still deserve a slot, keyed by provider
            // so they don't all collapse into each other.
            val providerKey = item.providerId ?: item.providerUrl
            if (seenKeys.values.none { (it.providerId ?: it.providerUrl) == providerKey }) {
                seenKeys["__blank__:${seenKeys.size}:$providerKey"] = item
            }
            continue
        }
        seenKeys.putIfAbsent(key, item)
    }
    return seenKeys.values.toList()
}

fun List<SuggestionItem>.rankForSeed(
    seed: SuggestionSeed,
    entryUrl: String?,
    limit: Int,
): List<SuggestionItem> {
    // Every alias, not just the display title: a source may name the entry in romaji
    // while a provider recommends the same work under its English title.
    val ownTitles = (listOf(seed.primaryTitle) + seed.candidateTitles).filter { it.isNotBlank() }.distinct()

    return filter { item ->
        !SuggestionTitleResolver.isSameProviderEntry(item, entryUrl) &&
            ownTitles.none { SuggestionTitleResolver.isFranchiseDuplicate(item.title, it) }
    }
        .dedupeByFranchise()
        .sortedByDescending { SuggestionSourceWeight.finalScore(it) }
        .take(limit)
}
