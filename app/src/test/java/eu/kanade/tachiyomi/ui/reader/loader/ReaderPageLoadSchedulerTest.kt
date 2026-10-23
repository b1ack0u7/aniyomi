package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPageLoadSchedulerTest {

    @Test
    fun `restricted network loads only the current page and nearby pages`() = runTest {
        val pages = pages(30)
        val loaded = mutableListOf<Int>()
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 100, { false }) {
            loaded += it.index
            it.status = Page.State.READY
        }

        scheduler.schedule(pages, 10)
        runCurrent()

        loaded.first() shouldBe 10
        loaded.toSet() shouldBe (9..14).toSet()
        loaded.size shouldBe 6
    }

    @Test
    fun `wifi preloads the whole chapter without downloading a page twice`() = runTest {
        val pages = pages(40)
        val loaded = mutableListOf<Int>()
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 101, { true }) {
            loaded += it.index
            it.status = Page.State.READY
        }

        scheduler.schedule(pages, 20)
        runCurrent()

        loaded.first() shouldBe 20
        loaded.toSet() shouldBe pages.indices.toSet()
        loaded.size shouldBe pages.size
    }

    @Test
    fun `neighboring chapters share five permits per source`() = runTest {
        val release = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0
        var completed = 0
        val load: suspend (ReaderPage) -> Unit = {
            running++
            peak = maxOf(peak, running)
            release.await()
            running--
            completed++
            it.status = Page.State.READY
        }
        val first = ReaderPageLoadScheduler(backgroundScope, 102, { true }, load)
        val second = ReaderPageLoadScheduler(backgroundScope, 102, { true }, load)

        first.schedule(pages(20), 10)
        second.schedule(pages(20), 10)
        runCurrent()
        running shouldBe 5

        release.complete(Unit)
        runCurrent()
        completed shouldBe 40
        peak shouldBe 5
    }

    @Test
    fun `one source does not hold up another source`() = runTest {
        val started = mutableListOf<Int>()
        val load: suspend (ReaderPage) -> Unit = {
            started += it.index
            awaitCancellation()
        }
        val first = ReaderPageLoadScheduler(backgroundScope, 103, { true }, load)
        val second = ReaderPageLoadScheduler(backgroundScope, 104, { true }, load)

        first.schedule(pages(20), 10)
        second.schedule(pages(20), 10)
        runCurrent()

        started.size shouldBe 6
    }

    @Test
    fun `jump cancels distant pages and those pages can load again`() = runTest {
        val pages = pages(30)
        val starts = mutableMapOf<Int, Int>()
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 105, { true }) {
            starts[it.index] = starts.getOrDefault(it.index, 0) + 1
            it.status = Page.State.DOWNLOAD_IMAGE
            awaitCancellation()
        }

        scheduler.schedule(pages, 0)
        runCurrent()
        pages[0].status shouldBe Page.State.DOWNLOAD_IMAGE

        scheduler.schedule(pages, 20)
        runCurrent()
        pages[0].status shouldBe Page.State.QUEUE
        pages[1].status shouldBe Page.State.QUEUE
        pages[20].status shouldBe Page.State.DOWNLOAD_IMAGE

        scheduler.schedule(pages, 0)
        runCurrent()
        starts[0] shouldBe 2
        pages[0].status shouldBe Page.State.DOWNLOAD_IMAGE
    }

    @Test
    fun `repeated holder requests share downloads already in flight`() = runTest {
        val pages = pages(20)
        val starts = mutableListOf<Int>()
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 106, { true }) {
            starts += it.index
            it.status = Page.State.DOWNLOAD_IMAGE
            awaitCancellation()
        }

        scheduler.schedule(pages, 10)
        runCurrent()
        repeat(5) { scheduler.schedule(pages, 10) }
        runCurrent()

        starts.sorted() shouldBe listOf(9, 10, 11)
    }

    @Test
    fun `leaving wifi stops loading distant chunks`() = runTest {
        val pages = pages(30)
        val release = CompletableDeferred<Unit>()
        var wifi = true
        val loaded = mutableListOf<Int>()
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 107, { wifi }) {
            loaded += it.index
            release.await()
            it.status = Page.State.READY
        }

        scheduler.schedule(pages, 10)
        runCurrent()
        wifi = false
        release.complete(Unit)
        runCurrent()

        loaded.toSet() shouldBe (9..14).toSet()
    }

    @Test
    fun `retry replaces one failed request without starting duplicate downloads`() = runTest {
        val pages = pages(1)
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 108, { true }) {
            starts++
            if (starts == 1) {
                it.status = Page.State.ERROR
            } else {
                it.status = Page.State.DOWNLOAD_IMAGE
                release.await()
                it.status = Page.State.READY
            }
        }
        scheduler.schedule(pages, 0)
        runCurrent()
        pages[0].status shouldBe Page.State.ERROR

        scheduler.retry(pages[0])
        runCurrent()
        scheduler.schedule(pages, 0)
        runCurrent()
        starts shouldBe 2
        release.complete(Unit)
        runCurrent()
        pages[0].status shouldBe Page.State.READY
    }

    @Test
    fun `failed neighboring pages stay failed until explicitly requested again`() = runTest {
        val pages = pages(3)
        val starts = mutableMapOf<Int, Int>()
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 109, { true }) {
            starts[it.index] = starts.getOrDefault(it.index, 0) + 1
            it.status = Page.State.ERROR
        }

        scheduler.schedule(pages, 0)
        runCurrent()
        starts shouldBe mapOf(0 to 1, 1 to 1, 2 to 1)

        scheduler.schedule(pages, 0)
        runCurrent()
        starts shouldBe mapOf(0 to 1, 1 to 1, 2 to 1)

        pages[0].status = Page.State.QUEUE
        scheduler.schedule(pages, 0)
        runCurrent()
        starts shouldBe mapOf(0 to 2, 1 to 1, 2 to 1)
    }

    @Test
    fun `repeated retries wait for the original cancelled request to finish cleanup`() = runTest {
        val pages = pages(1)
        val cleanup = CompletableDeferred<Unit>()
        var starts = 0
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 110, { true }) {
            starts++
            it.status = Page.State.DOWNLOAD_IMAGE
            if (starts == 1) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            it.status = Page.State.READY
        }
        scheduler.schedule(pages, 0)
        runCurrent()

        scheduler.retry(pages[0])
        runCurrent()
        scheduler.retry(pages[0])
        runCurrent()
        starts shouldBe 1

        cleanup.complete(Unit)
        runCurrent()
        starts shouldBe 2
        pages[0].status shouldBe Page.State.READY
    }

    @Test
    fun `retained holder keeps its page loading across a distant jump`() = runTest {
        val pages = pages(30)
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 111, { false }) {
            it.status = Page.State.DOWNLOAD_IMAGE
            awaitCancellation()
        }
        val holder = backgroundScope.launch { scheduler.loadPage(pages, 10) }
        runCurrent()
        pages[10].status shouldBe Page.State.DOWNLOAD_IMAGE

        scheduler.schedule(pages, 20)
        runCurrent()
        pages[10].status shouldBe Page.State.DOWNLOAD_IMAGE

        holder.cancel()
        runCurrent()
        pages[10].status shouldBe Page.State.QUEUE
    }

    @Test
    fun `split page holders share demand until the last holder releases it`() = runTest {
        val pages = pages(30)
        var starts = 0
        val scheduler = ReaderPageLoadScheduler(backgroundScope, 112, { false }) {
            if (it.index == 10) starts++
            it.status = Page.State.DOWNLOAD_IMAGE
            awaitCancellation()
        }
        val first = backgroundScope.launch { scheduler.loadPage(pages, 10) }
        val second = backgroundScope.launch { scheduler.loadPage(pages, 10) }
        runCurrent()
        scheduler.schedule(pages, 20)
        runCurrent()

        first.cancel()
        runCurrent()
        starts shouldBe 1
        pages[10].status shouldBe Page.State.DOWNLOAD_IMAGE

        second.cancel()
        runCurrent()
        pages[10].status shouldBe Page.State.QUEUE
    }

    private fun pages(count: Int) = List(count) { ReaderPage(it) }
}
