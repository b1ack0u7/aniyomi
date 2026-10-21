package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class SearchRequestCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxConcurrentRequests: Int = 5,
) {
    private val semaphore = Semaphore(maxConcurrentRequests)

    @Volatile
    private var current: Session? = null

    fun newSearch(): Session {
        val previous = current
        return Session().also {
            current = it
            previous?.cancel()
        }
    }

    inner class Session internal constructor() {
        private val job = SupervisorJob(scope.coroutineContext[Job])
        private val requestScope = CoroutineScope(scope.coroutineContext + job + dispatcher)
        private val requests = mutableMapOf<Long, Job>()

        val isCurrent: Boolean
            get() = current === this && job.isActive

        fun launch(sourceId: Long, block: suspend CoroutineScope.() -> Unit) {
            requests.remove(sourceId)?.cancel()
            requests[sourceId] = requestScope.launch(block = block)
        }

        suspend fun <T> withRequest(block: suspend () -> T): T = semaphore.withPermit { block() }

        internal fun cancel() {
            requestScope.cancel()
        }
    }
}
