package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal class ReaderPageLoadScheduler(
    private val scope: CoroutineScope,
    private val sourceId: Long,
    private val canLoadAhead: () -> Boolean,
    private val coordinator: ReaderPageLoadCoordinator = SourcePageLoadCoordinator.instance,
    private val requiresNetwork: (ReaderPage) -> Boolean = { true },
    private val loadCached: ((ReaderPage) -> Boolean)? = null,
    private val load: suspend (ReaderPage) -> Unit,
) {
    private val lock = Any()
    private val downloads = mutableMapOf<Int, Load>()
    private val pendingLoads = mutableMapOf<Int, MutableSet<Job>>()
    private var planningJob: Job? = null
    private var plan: Plan? = null
    private var focusedIndex: Int? = null
    private var plannedIndices: Set<Int> = emptySet()

    suspend fun loadPage(pages: List<ReaderPage>, index: Int) {
        if (index !in pages.indices) return
        synchronized(lock) {
            focusedIndex?.let { schedule(pages, it) }
        }
        awaitCancellation()
    }

    fun schedule(pages: List<ReaderPage>, index: Int) {
        if (index !in pages.indices) return
        synchronized(lock) {
            focusedIndex = index
            val nextPlan = Plan(pages, index, canLoadAhead())
            if (plan != nextPlan) {
                downloads.filter { (page, request) -> page != index && !request.started }
                    .values.forEach { it.job.cancel() }
            }
            launchOnce(pages[index], foreground = true)
            if (plan == nextPlan && planningJob?.isActive == true) return
            plan = nextPlan
            val ordered = buildList {
                add(pages[index])
                pages.getOrNull(index - 1)?.let(::add)
                pages.getOrNull(index + 1)?.let(::add)
                addAll(pages.drop(index + 2))
                addAll(pages.take((index - 1).coerceAtLeast(0)).asReversed())
            }
            val permitted = if (nextPlan.loadAhead) ordered else ordered.filter { it.index in (index - 1)..(index + 4) }
            plannedIndices = permitted.mapTo(HashSet()) { it.index }
            // Let an admitted transfer finish; only discard work that has not reached the network.
            downloads.filter { (page, request) -> page !in plannedIndices && !request.started }
                .values.forEach { it.job.cancel() }
            planningJob?.cancel()
            planningJob = scope.launch {
                for (page in permitted) {
                    if (!canLoadAhead() && page.index !in (index - 1)..(index + 4)) continue
                    val job = synchronized(lock) {
                        coroutineContext.ensureActive()
                        if (plan !== nextPlan) return@launch
                        launchOnce(page, foreground = page.index == focusedIndex)
                    }
                    job?.join()
                }
            }
        }
    }

    fun retry(page: ReaderPage) {
        synchronized(lock) {
            downloads[page.index]?.job?.cancel()
            page.status = Page.State.QUEUE
            launchOnce(page, foreground = true, retry = true)
        }
    }

    fun deactivate() {
        synchronized(lock) {
            focusedIndex = null
            plan = null
            plannedIndices = emptySet()
            planningJob?.cancel()
            planningJob = null
            downloads.values.filterNot { it.started }.forEach { it.job.cancel() }
        }
    }

    private fun launchOnce(page: ReaderPage, foreground: Boolean, retry: Boolean = false): Job? {
        val previous = downloads[page.index]
        if (!retry && previous != null && !previous.job.isCancelled && !previous.job.isCompleted) {
            if (!foreground || previous.foreground || previous.started) return previous.job
            previous.job.cancel()
        }
        val predecessors = pendingLoads[page.index].orEmpty().filterNot { it.isCompleted }
        if (!retry && predecessors.isEmpty() && page.status != Page.State.QUEUE) return null
        val request = Load(foreground)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            predecessors.joinAll()
            try {
                coroutineContext.ensureActive()
                val network = requiresNetwork(page)
                // A cache hit can disappear before loading; a miss still needs network admission.
                if (!network && loadCached?.invoke(page) == true) return@launch
                val begin = {
                    synchronized(lock) {
                        coroutineContext.ensureActive()
                        request.started = true
                    }
                }
                if (network || loadCached != null) {
                    coordinator.withPermit(sourceId, foreground, begin) { load(page) }
                } else {
                    begin()
                    load(page)
                }
            } catch (e: CancellationException) {
                page.stream = null
                page.status = Page.State.QUEUE
                throw e
            }
        }
        request.job = job
        downloads[page.index] = request
        pendingLoads.getOrPut(page.index) { mutableSetOf() }.add(job)
        job.invokeOnCompletion {
            synchronized(lock) {
                if (downloads[page.index] === request) downloads.remove(page.index)
                pendingLoads[page.index]?.let { pending ->
                    pending.remove(job)
                    if (pending.isEmpty()) pendingLoads.remove(page.index)
                }
            }
        }
        job.start()
        return job
    }

    private data class Plan(val pages: List<ReaderPage>, val index: Int, val loadAhead: Boolean)

    private class Load(val foreground: Boolean) {
        lateinit var job: Job
        var started = false
    }
}

internal object SourcePageLoadCoordinator {
    val instance = ReaderPageLoadCoordinator()
}

internal class ReaderPageLoadCoordinator(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val sources = ConcurrentHashMap<Long, Source>()

    suspend fun <T> withPermit(
        sourceId: Long,
        foreground: Boolean,
        onStart: () -> Unit = {},
        block: suspend () -> T,
    ): T {
        val source = sources.getOrPut(sourceId) { Source() }
        if (foreground) {
            return source.transfers.withPermit {
                synchronized(source) { source.lastStart = now() }
                onStart()
                block()
            }
        }
        return source.preloads.withPermit {
            while (true) {
                val wait = synchronized(source) { source.lastStart?.let { it + PRELOAD_INTERVAL_MS - now() } ?: 0L }
                if (wait > 0) delay(wait)
                source.transfers.acquire()
                val admitted = synchronized(source) {
                    if (source.lastStart?.let { now() - it >= PRELOAD_INTERVAL_MS } != false) {
                        source.lastStart = now()
                        true
                    } else {
                        false
                    }
                }
                if (!admitted) {
                    source.transfers.release()
                    continue
                }
                try {
                    onStart()
                    return@withPermit block()
                } finally {
                    source.transfers.release()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }
    }

    private class Source {
        val transfers = Semaphore(2)
        val preloads = Semaphore(1)
        var lastStart: Long? = null
    }

    private companion object {
        const val PRELOAD_INTERVAL_MS = 1_000L
    }
}
