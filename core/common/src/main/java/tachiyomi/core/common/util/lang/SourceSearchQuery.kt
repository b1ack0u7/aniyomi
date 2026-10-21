package tachiyomi.core.common.util.lang

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SourceSearchQuery(private val originalQuery: String) {

    private val lock = Mutex()

    @Volatile
    var effectiveQuery: String = originalQuery
        private set

    suspend fun <T> search(page: Int, isEmpty: (T) -> Boolean, fetch: suspend (String) -> T): T = lock.withLock {
        val result = fetch(effectiveQuery)
        currentCoroutineContext().ensureActive()
        if (page != 1 || effectiveQuery != originalQuery || !isEmpty(result)) return@withLock result

        val normalizedQuery = normalize(originalQuery) ?: return@withLock result
        val normalizedResult = fetch(normalizedQuery)
        currentCoroutineContext().ensureActive()
        if (!isEmpty(normalizedResult)) effectiveQuery = normalizedQuery
        normalizedResult
    }

    companion object {
        private val whitespace = Regex("[\\p{Z}\\s]+")
        private val spaceBeforePunctuation = Regex(" +([?!])")
        private val urlScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

        internal fun normalize(query: String): String? {
            if (query.isBlank() || urlScheme.containsMatchIn(query.trim())) return null
            val normalized = query
                .replace('¿', '?')
                .replace('¡', '!')
                .replace(whitespace, " ")
                .trim()
                .replace(spaceBeforePunctuation, "$1")
            return normalized.takeIf { it.isNotBlank() && it != query }
        }
    }
}
