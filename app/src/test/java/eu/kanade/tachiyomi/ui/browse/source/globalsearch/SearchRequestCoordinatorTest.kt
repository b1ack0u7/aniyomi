package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRequestCoordinatorTest {

    @Test
    fun `starting another search cancels requests and retries from the previous session`() = runTest {
        val coordinator = SearchRequestCoordinator(backgroundScope, StandardTestDispatcher(testScheduler))
        val previous = coordinator.newSearch()
        val cancelled = mutableSetOf<Long>()
        listOf(1L, 2L).forEach { id ->
            previous.launch(id) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled += id
                }
            }
        }
        runCurrent()

        val current = coordinator.newSearch()
        runCurrent()

        cancelled shouldBe setOf(1L, 2L)
        previous.isCurrent shouldBe false
        current.isCurrent shouldBe true
    }

    @Test
    fun `retrying a source cancels only its previous request`() = runTest {
        val coordinator = SearchRequestCoordinator(backgroundScope, StandardTestDispatcher(testScheduler))
        val session = coordinator.newSearch()
        val cancelled = mutableSetOf<Long>()
        listOf(1L, 2L).forEach { id ->
            session.launch(id) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled += id
                }
            }
        }
        runCurrent()
        var retried = false

        session.launch(1) { retried = true }
        runCurrent()

        cancelled shouldBe setOf(1L)
        retried shouldBe true
        session.isCurrent shouldBe true
    }

    @Test
    fun `suspended requests retain permits until they finish`() = runTest {
        val coordinator = SearchRequestCoordinator(backgroundScope, StandardTestDispatcher(testScheduler))
        val session = coordinator.newSearch()
        val release = List(6) { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        repeat(6) { index ->
            session.launch(index.toLong()) {
                session.withRequest {
                    started += index
                    release[index].await()
                }
            }
        }
        runCurrent()

        started shouldBe listOf(0, 1, 2, 3, 4)
        release[0].complete(Unit)
        runCurrent()
        started shouldBe listOf(0, 1, 2, 3, 4, 5)
    }

    @Test
    fun `new search can use permits released by cancellation`() = runTest {
        val coordinator = SearchRequestCoordinator(backgroundScope, StandardTestDispatcher(testScheduler), 1)
        val previous = coordinator.newSearch()
        previous.launch(1) { previous.withRequest { awaitCancellation() } }
        runCurrent()
        val current = coordinator.newSearch()
        var started = false

        current.launch(2) { current.withRequest { started = true } }
        runCurrent()

        started shouldBe true
    }
}
