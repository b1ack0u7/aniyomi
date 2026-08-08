package eu.kanade.tachiyomi.data.suggestions

import eu.kanade.tachiyomi.data.suggestions.util.rankForSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

// Curated providers always outrank what the entry's own source can match by string, so painting
// search results the moment they arrive and prepending the providers' a second later reads as the
// list rewriting itself. Search results are held until the providers answer, with a grace period
// so a stalled provider can't keep the list empty.
internal class SuggestionStream(
    scope: CoroutineScope,
    private val seed: SuggestionSeed,
    private val entryUrl: String,
    private val limit: Int,
    private val onUpdate: (List<SuggestionItem>) -> Unit,
) {

    private val collected = mutableListOf<SuggestionItem>()
    private val known = mutableSetOf<String>()
    private val held = mutableListOf<SuggestionItem>()
    private var released = false

    // Scoped to the fetch itself: a reload cancels the timer along with it, and the stale stream
    // stops publishing over whatever replaced it.
    private val fetchJob = scope.coroutineContext.job
    private val graceJob: Job = scope.launch {
        delay(SOURCE_HOLD_MS)
        releaseSource()
    }

    fun publishExternal(items: List<SuggestionItem>) {
        emit(synchronized(this) { absorb(items) })
    }

    fun publishSource(items: List<SuggestionItem>) {
        val ranked = synchronized(this) {
            if (released) {
                absorb(items)
            } else {
                held += items
                null
            }
        }
        emit(ranked)
    }

    fun releaseSource() {
        graceJob.cancel()
        val ranked = synchronized(this) {
            if (released) return
            released = true
            val pending = held.toList()
            held.clear()
            absorb(pending)
        }
        emit(ranked)
    }

    // Only meaningful once releaseSource has run, which every caller does before finishing.
    fun snapshot(): List<SuggestionItem> = synchronized(this) { rank() }

    private fun absorb(items: List<SuggestionItem>): List<SuggestionItem>? {
        val fresh = items.filter { known.add(it.providerUrl) }
        if (fresh.isEmpty()) return null
        collected += fresh
        return rank()
    }

    private fun rank(): List<SuggestionItem> = collected.rankForSeed(seed, entryUrl, limit)

    private fun emit(ranked: List<SuggestionItem>?) {
        if (!ranked.isNullOrEmpty() && !fetchJob.isCancelled) onUpdate(ranked)
    }

    private companion object {
        const val SOURCE_HOLD_MS = 2_500L
    }
}
