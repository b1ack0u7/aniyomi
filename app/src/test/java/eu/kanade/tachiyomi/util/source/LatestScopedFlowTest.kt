package eu.kanade.tachiyomi.util.source

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LatestScopedFlowTest {

    @Test
    fun `changing search cancels previous result subscriptions and keeps the current generation alive`() = runTest {
        val queries = MutableStateFlow("old")
        val subscriptions = mutableSetOf<String>()
        val results = mutableListOf<String>()
        val collector = launch {
            queries.mapWithLatestScope { query ->
                flow {
                    subscriptions += query
                    try {
                        emit(query)
                        awaitCancellation()
                    } finally {
                        subscriptions -= query
                    }
                }.stateIn(this)
            }.collect { results += it.value }
        }
        runCurrent()
        assertEquals(setOf("old"), subscriptions)

        queries.value = "new"
        runCurrent()

        assertEquals(listOf("old", "new"), results)
        assertEquals(setOf("new"), subscriptions)
        collector.cancelAndJoin()
        assertEquals(emptySet<String>(), subscriptions)
    }

    @Test
    fun `leaving source cancels every current generation child`() = runTest {
        val queries = MutableStateFlow("query")
        var activeChildren = 0
        val collector = launch {
            queries.mapWithLatestScope {
                repeat(3) {
                    launch {
                        activeChildren++
                        try {
                            awaitCancellation()
                        } finally {
                            activeChildren--
                        }
                    }
                }
            }.collect {}
        }
        runCurrent()
        assertEquals(3, activeChildren)

        collector.cancelAndJoin()

        assertEquals(0, activeChildren)
    }
}
