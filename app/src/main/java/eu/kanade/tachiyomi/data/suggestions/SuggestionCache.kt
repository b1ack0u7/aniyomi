package eu.kanade.tachiyomi.data.suggestions

import java.util.concurrent.ConcurrentHashMap

object SuggestionCache {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<SuggestionItem>>>()

    // Suggestions barely change day to day, so a generous TTL keeps repeat visits to an
    // entry instant and spares the public APIs.
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 300

    fun get(key: String): List<SuggestionItem>? {
        val (timestamp, list) = cache[key] ?: return null
        return if (System.currentTimeMillis() - timestamp < TTL_MS) {
            list
        } else {
            cache.remove(key)
            null
        }
    }

    fun put(key: String, list: List<SuggestionItem>) {
        evictExpiredAndOverflow()
        cache[key] = Pair(System.currentTimeMillis(), list)
    }

    private fun evictExpiredAndOverflow() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now - it.value.first >= TTL_MS }
        if (cache.size < MAX_ENTRIES) return
        val overflow = cache.size - MAX_ENTRIES + 1
        cache.entries
            .sortedBy { it.value.first }
            .take(overflow)
            .forEach { cache.remove(it.key) }
    }

    fun invalidateAll() {
        cache.clear()
    }

    fun invalidateForSeed(seed: SuggestionSeed, entryUrl: String? = null) {
        val title = seed.primaryTitle.lowercase().trim()
        val url = entryUrl?.lowercase()?.trim()
        cache.keys.forEach { key ->
            if (key.contains(title) || (url != null && key.contains(url))) {
                cache.remove(key)
            }
        }
    }

    // The key fingerprints the metadata, so a seed enriched with description/author misses
    // the entry the weaker seed wrote — which is what lets a real second fetch happen.
    fun makeKey(
        sourceName: String,
        primaryTitle: String,
        mediaType: String,
        candidateTitles: List<String> = emptyList(),
        description: String? = null,
        author: String? = null,
    ): String {
        val parts = mutableListOf("$sourceName:${primaryTitle.lowercase().trim()}:$mediaType")
        if (candidateTitles.isNotEmpty()) {
            parts += candidateTitles
                .map { it.lowercase().trim() }
                .sorted()
                .joinToString("|")
        }
        if (!description.isNullOrBlank()) {
            parts += "d:" + description.lowercase().trim().hashCode()
        }
        if (!author.isNullOrBlank()) {
            parts += "a:" + author.lowercase().trim().hashCode()
        }
        return parts.joinToString(":")
    }
}
