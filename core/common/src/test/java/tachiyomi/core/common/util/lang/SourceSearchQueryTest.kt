package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException

@Timeout(10)
class SourceSearchQueryTest {

    @Test
    fun `normalization fixes the reported title without changing its spelling or case`() {
        val original = "Ore No Genjitsu Wa Ren’ai Game ¿¿ Ka To Omottara Inochigake No Game Datta"

        assertEquals(
            "Ore No Genjitsu Wa Ren’ai Game?? Ka To Omottara Inochigake No Game Datta",
            SourceSearchQuery.normalize(original),
        )
    }

    @Test
    fun `normalization preserves accents apostrophes and existing punctuation`() {
        assertEquals(
            "Café Ren’ai Game??!Go!",
            SourceSearchQuery.normalize("  Café\u00a0\u00a0Ren’ai\tGame ¿¿  ¡Go !  "),
        )
        assertNull(SourceSearchQuery.normalize("Café Ren’ai Game??: Part 2"))
        assertNull(SourceSearchQuery.normalize("   "))
    }

    @Test
    fun `direct source URLs are left intact`() {
        assertNull(SourceSearchQuery.normalize("  https://example.org/title?q=¿Qué ?  "))
        assertNull(SourceSearchQuery.normalize("custom://Title ¿¿"))
    }

    @Test
    fun `a successful original search never sends a normalized request`() = runBlocking {
        val query = SourceSearchQuery("Title ¿¿")
        val requests = mutableListOf<String>()

        val result = query.search(1, List<String>::isEmpty) {
            requests += it
            listOf("original match")
        }

        assertEquals(listOf("original match"), result)
        assertEquals(listOf("Title ¿¿"), requests)
        assertEquals("Title ¿¿", query.effectiveQuery)
    }

    @Test
    fun `an empty first page gets one normalized attempt and retains it for following pages`() = runBlocking {
        val query = SourceSearchQuery("Title ¿¿")
        val requests = mutableListOf<Pair<Int, String>>()

        for (page in 1..2) {
            val result = query.search(page, List<String>::isEmpty) {
                requests += page to it
                if (it == "Title ¿¿") emptyList() else listOf("page $page")
            }
            assertEquals(listOf("page $page"), result)
        }

        assertEquals(listOf(1 to "Title ¿¿", 1 to "Title??", 2 to "Title??"), requests)
        assertEquals("Title??", query.effectiveQuery)
    }

    @Test
    fun `two empty attempts do not recurse or replace the original query`() = runBlocking {
        val query = SourceSearchQuery("Title ¿¿")
        val requests = mutableListOf<String>()

        val result = query.search(1, List<String>::isEmpty) {
            requests += it
            emptyList<String>()
        }

        assertTrue(result.isEmpty())
        assertEquals(listOf("Title ¿¿", "Title??"), requests)
        assertEquals("Title ¿¿", query.effectiveQuery)
    }

    @Test
    fun `an unchanged query gets no redundant empty retry`() = runBlocking {
        val requests = mutableListOf<String>()
        SourceSearchQuery("Title??").search(1, List<String>::isEmpty) {
            requests += it
            emptyList<String>()
        }
        assertEquals(listOf("Title??"), requests)
    }

    @Test
    fun `empty later pages never switch away from the original successful query`() = runBlocking {
        val query = SourceSearchQuery("Title ¿¿")
        val requests = mutableListOf<Pair<Int, String>>()
        for (page in 1..2) {
            query.search(page, List<String>::isEmpty) {
                requests += page to it
                if (page == 1) listOf("original match") else emptyList()
            }
        }
        assertEquals(listOf(1 to "Title ¿¿", 2 to "Title ¿¿"), requests)
    }

    @Test
    fun `original request failures propagate without a second attempt`() = runBlocking {
        for (error in listOf(IOException("offline"), NoSuchMethodError("incompatible extension"))) {
            val requests = mutableListOf<String>()
            val failure = runCatching {
                SourceSearchQuery("Title ¿¿").search<List<String>>(1, List<String>::isEmpty) {
                    requests += it
                    throw error
                }
            }.exceptionOrNull()
            assertSame(error, failure)
            assertEquals(listOf("Title ¿¿"), requests)
        }
    }

    @Test
    fun `a failed normalized request does not become the effective query`() = runBlocking {
        val query = SourceSearchQuery("Title ¿¿")
        val error = IOException("rate limited")
        val failure = runCatching {
            query.search(1, List<String>::isEmpty) {
                if (it == "Title??") throw error
                emptyList<String>()
            }
        }.exceptionOrNull()

        assertSame(error, failure)
        assertEquals("Title ¿¿", query.effectiveQuery)
    }

    @Test
    fun `cancellation after the original response prevents the additional request`() = runBlocking {
        val requests = mutableListOf<String>()
        val job = launch {
            SourceSearchQuery("Title ¿¿").search(1, List<String>::isEmpty) {
                requests += it
                currentCoroutineContext().cancel()
                emptyList<String>()
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("Title ¿¿"), requests)
    }

    @Test
    fun `cancellation during the normalized response does not promote its query`() = runBlocking {
        val query = SourceSearchQuery("Title ¿¿")
        val job = launch {
            query.search(1, List<String>::isEmpty) {
                if (it == "Title ¿¿") {
                    emptyList()
                } else {
                    currentCoroutineContext().cancel()
                    listOf("stale match")
                }
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals("Title ¿¿", query.effectiveQuery)
    }

    @Test
    fun `concurrent page loads use the query selected by the first page`() = runBlocking {
        val query = SourceSearchQuery("Title ¿¿")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<Int, String>>()
        val first = async {
            query.search(1, List<String>::isEmpty) {
                requests += 1 to it
                if (it == "Title ¿¿") {
                    started.complete(Unit)
                    release.await()
                    emptyList()
                } else {
                    listOf("page 1")
                }
            }
        }
        started.await()
        val next = async {
            query.search(2, List<String>::isEmpty) {
                requests += 2 to it
                listOf("page 2")
            }
        }
        release.complete(Unit)
        first.await()
        next.await()

        assertEquals(listOf(1 to "Title ¿¿", 1 to "Title??", 2 to "Title??"), requests)
    }
}
