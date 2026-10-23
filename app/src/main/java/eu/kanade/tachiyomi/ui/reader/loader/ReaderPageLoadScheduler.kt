package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal class ReaderPageLoadScheduler(
    private val scope: CoroutineScope,
    private val sourceId: Long,
    private val canLoadAhead: () -> Boolean,
    private val load: suspend (ReaderPage) -> Unit,
) {
    private val lock = Any()
    private val downloads = mutableMapOf<Int, Job>()
    private val pendingLoads = mutableMapOf<Int, MutableSet<Job>>()
    private var planningJob: Job? = null
    private val holders = mutableMapOf<Int, Int>()
    private var plannedIndices: Set<Int> = emptySet()

    suspend fun loadPage(pages: List<ReaderPage>, index: Int) {
        if (index !in pages.indices) return
        synchronized(lock) { holders[index] = holders.getOrDefault(index, 0) + 1 }
        try {
            schedule(pages, index)
            awaitCancellation()
        } finally {
            synchronized(lock) {
                val remaining = holders.getValue(index) - 1
                if (remaining > 0) {
                    holders[index] = remaining
                } else {
                    holders.remove(index)
                    if (index !in plannedIndices) downloads[index]?.cancel()
                }
            }
        }
    }

    fun schedule(pages: List<ReaderPage>, index: Int, initial: Boolean = false) {
        if (index !in pages.indices) return
        synchronized(lock) {
            launchOnce(pages[index])
            val ordered = buildList {
                add(pages[index])
                pages.getOrNull(index - 1)?.let(::add)
                pages.getOrNull(index + 1)?.let(::add)
                addAll(pages.drop(index + 2))
                addAll(pages.take((index - 1).coerceAtLeast(0)).asReversed())
            }
            val permitted = if (canLoadAhead()) ordered else ordered.filter { it.index in (index - 1)..(index + 4) }
            val chunks = permitted.rollingChunks(if (initial) 1 else 3, if (initial) 1 else 5)
            plannedIndices = chunks.take(2).flatten().mapTo(HashSet()) { it.index }
            downloads.filterKeys { it !in plannedIndices && it !in holders }.values.forEach { it.cancel() }
            planningJob?.cancel()
            planningJob = scope.launch {
                for (chunk in chunks) {
                    val jobs = synchronized(lock) {
                        val loadAhead = canLoadAhead()
                        chunk.filter { loadAhead || it.index in (index - 1)..(index + 4) }
                            .mapNotNull(::launchOnce)
                    }
                    jobs.joinAll()
                }
            }
        }
    }

    fun retry(page: ReaderPage) {
        synchronized(lock) {
            downloads[page.index]?.cancel()
            launchOnce(page, retry = true)
        }
    }

    private fun launchOnce(page: ReaderPage, retry: Boolean = false): Job? {
        val previous = downloads[page.index]
        if (!retry && previous != null && !previous.isCancelled && !previous.isCompleted) return previous
        val predecessors = pendingLoads[page.index].orEmpty().filterNot { it.isCompleted }
        if (!retry && predecessors.isEmpty() && page.status != Page.State.QUEUE) return null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Include older requests when a cancelled replacement was still waiting for their cleanup.
            predecessors.joinAll()
            try {
                SourcePageLoadCoordinator.withPermit(sourceId) { load(page) }
            } catch (e: CancellationException) {
                page.stream = null
                page.status = Page.State.QUEUE
                throw e
            }
        }
        downloads[page.index] = job
        pendingLoads.getOrPut(page.index) { mutableSetOf() }.add(job)
        job.invokeOnCompletion {
            synchronized(lock) {
                if (downloads[page.index] === job) downloads.remove(page.index)
                pendingLoads[page.index]?.let { pending ->
                    pending.remove(job)
                    if (pending.isEmpty()) pendingLoads.remove(page.index)
                }
            }
        }
        job.start()
        return job
    }
}

internal object SourcePageLoadCoordinator {
    private val permits = ConcurrentHashMap<Long, Semaphore>()

    suspend fun <T> withPermit(sourceId: Long, block: suspend () -> T): T =
        permits.getOrPut(sourceId) { Semaphore(5) }.withPermit { block() }
}

private fun <T> List<T>.rollingChunks(startSize: Int, endSize: Int): List<List<T>> {
    val chunks = mutableListOf<List<T>>()
    var index = 0
    var size = startSize
    while (index < this.size) {
        chunks += subList(index, (index + size).coerceAtMost(this.size))
        index += size
        size = (size + 1).coerceAtMost(endSize)
    }
    return chunks
}
