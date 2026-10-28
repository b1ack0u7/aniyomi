package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPageLoadSchedulerTest {
    @Test
    fun `deactivation between planner policy check and launch cannot admit a new page`() = runTest {
        val pages = pages(3)
        val loaded = mutableListOf<Int>()
        var policyChecks = 0
        lateinit var scheduler: ReaderPageLoadScheduler
        scheduler = scheduler(ahead = {
            policyChecks++
            if (policyChecks == 3) {
                loaded shouldBe listOf(0)
                scheduler.deactivate()
            }
            true
        }) {
            loaded += it.index
            it.status = Page.State.READY
        }

        scheduler.schedule(pages, 0)
        policyChecks shouldBe 1
        runCurrent()
        policyChecks shouldBe 3
        advanceTimeBy(5_000)
        runCurrent()

        loaded shouldBe listOf(0)
    }

    @Test
    fun `a replaced planner cannot enqueue pages from its old plan`() = runTest {
        val pages = pages(20)
        val loaded = mutableListOf<Int>()
        var policyChecks = 0
        var ahead = true
        lateinit var scheduler: ReaderPageLoadScheduler
        scheduler = scheduler(ahead = {
            policyChecks++
            if (policyChecks == 3) {
                ahead = false
                scheduler.schedule(pages, 15)
            }
            ahead
        }) {
            loaded += it.index
            it.status = Page.State.READY
        }

        scheduler.schedule(pages, 0)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        loaded.first() shouldBe 0
        loaded.drop(1).toSet() shouldBe (14..19).toSet()
    }

    @Test
    fun `deactivation before a worker marks admission prevents its transfer`() = runTest {
        val loaded = mutableListOf<Int>()
        lateinit var scheduler: ReaderPageLoadScheduler
        scheduler = scheduler(requiresNetwork = {
            scheduler.deactivate()
            true
        }) {
            loaded += it.index
            it.status = Page.State.READY
        }

        scheduler.schedule(pages(1), 0)
        runCurrent()

        loaded shouldBe emptyList()
    }

    @Test
    fun `deactivated chapters preserve transfers and ignore old holders until reactivated`() = runTest {
        val pages = pages(20)
        val release = CompletableDeferred<Unit>()
        val loaded = mutableListOf<Int>()
        val scheduler = scheduler {
            loaded += it.index
            if (it.index == 0) release.await()
            it.status = Page.State.READY
        }
        scheduler.schedule(pages, 0)
        runCurrent()
        scheduler.deactivate()
        backgroundScope.launch { scheduler.loadPage(pages, 1) }
        advanceTimeBy(5_000)
        runCurrent()
        loaded shouldBe listOf(0)
        release.complete(Unit)
        runCurrent()
        pages[0].status shouldBe Page.State.READY
        loaded shouldBe listOf(0)
        scheduler.schedule(pages, 5)
        runCurrent()
        loaded shouldBe listOf(0, 5)
    }

    @Test
    fun `deactivating a chapter discards preload waiters before they reach the source`() = runTest {
        val pages = pages(20)
        val loaded = mutableListOf<Int>()
        val scheduler = scheduler {
            loaded += it.index
            it.status = Page.State.READY
        }
        scheduler.schedule(pages, 0)
        runCurrent()
        advanceTimeBy(500)
        scheduler.deactivate()
        advanceTimeBy(5_000)
        runCurrent()
        loaded shouldBe listOf(0)
    }

    @Test
    fun `inactive adjacent chapter holders do not start images before selecting a page`() = runTest {
        val pages = pages(20)
        val loaded = mutableListOf<Int>()
        val scheduler = scheduler {
            loaded += it.index
            it.status = Page.State.READY
        }
        backgroundScope.launch { scheduler.loadPage(pages, 0) }
        backgroundScope.launch { scheduler.loadPage(pages, 1) }
        runCurrent()
        loaded shouldBe emptyList()
        scheduler.schedule(pages, 5)
        runCurrent()
        loaded shouldBe listOf(5)
    }

    @Test
    fun `restricted network preloads only nearby pages with spaced background starts`() = runTest {
        val pages = pages(30)
        val loaded = mutableListOf<Pair<Int, Long>>()
        val scheduler = scheduler(ahead = { false }) {
            loaded += it.index to testScheduler.currentTime
            it.status = Page.State.READY
        }

        scheduler.schedule(pages, 10)
        runCurrent()
        loaded shouldBe listOf(10 to 0L)
        advanceTimeBy(5_000)
        runCurrent()

        loaded.map { it.first }.toSet() shouldBe (9..14).toSet()
        loaded.map { it.second } shouldBe (0L..5_000L step 1_000L).toList()
    }

    @Test
    fun `full preload stays available without downloading a page twice`() = runTest {
        val pages = pages(40)
        val loaded = mutableListOf<Int>()
        val scheduler = scheduler {
            loaded += it.index
            it.status = Page.State.READY
        }

        scheduler.schedule(pages, 20)
        runCurrent()
        advanceTimeBy(40_000)
        runCurrent()

        loaded.first() shouldBe 20
        loaded.toSet() shouldBe pages.indices.toSet()
        loaded.size shouldBe pages.size
    }

    @Test
    fun `page changes let admitted requests finish without cancel and restart cycles`() = runTest {
        val pages = pages(30)
        val release = CompletableDeferred<Unit>()
        val starts = mutableListOf<Int>()
        val scheduler = scheduler {
            starts += it.index
            it.status = Page.State.DOWNLOAD_IMAGE
            release.await()
            it.status = Page.State.READY
        }

        scheduler.schedule(pages, 0)
        runCurrent()
        scheduler.schedule(pages, 20)
        runCurrent()
        scheduler.schedule(pages, 0)
        runCurrent()

        starts shouldBe listOf(0, 20)
        pages[0].status shouldBe Page.State.DOWNLOAD_IMAGE
        pages[20].status shouldBe Page.State.DOWNLOAD_IMAGE
        release.complete(Unit)
        runCurrent()
        pages[0].status shouldBe Page.State.READY
        pages[20].status shouldBe Page.State.READY
        starts.count { it == 0 } shouldBe 1
        starts.count { it == 20 } shouldBe 1
    }

    @Test
    fun `offscreen holder binds do not become foreground demand or move focus`() = runTest {
        val pages = pages(30)
        val loaded = mutableListOf<Int>()
        val scheduler = scheduler {
            loaded += it.index
            it.status = Page.State.READY
        }
        scheduler.schedule(pages, 10)
        runCurrent()
        listOf(9, 11, 12, 13, 14).forEach { index ->
            backgroundScope.launch { scheduler.loadPage(pages, index) }
        }
        runCurrent()
        loaded shouldBe listOf(10)
        advanceTimeBy(1_000)
        runCurrent()
        loaded shouldBe listOf(10, 9)
    }

    @Test
    fun `foreground promotion bypasses preload wait and resets background spacing`() = runTest {
        val pages = pages(10)
        val starts = mutableListOf<Pair<Int, Long>>()
        val scheduler = scheduler {
            starts += it.index to testScheduler.currentTime
            it.status = Page.State.READY
        }
        scheduler.schedule(pages, 0)
        runCurrent()
        advanceTimeBy(500)
        scheduler.schedule(pages, 1)
        runCurrent()
        starts shouldBe listOf(0 to 0L, 1 to 500L)
        advanceTimeBy(999)
        runCurrent()
        starts.size shouldBe 2
        advanceTimeBy(1)
        runCurrent()
        starts shouldBe listOf(0 to 0L, 1 to 500L, 2 to 1_500L)
    }

    @Test
    fun `visible page can start while a background transfer remains in flight`() = runTest {
        val pages = pages(20)
        val release = CompletableDeferred<Unit>()
        val starts = mutableListOf<Int>()
        val scheduler = scheduler {
            starts += it.index
            if (it.index == 1) release.await()
            it.status = Page.State.READY
        }
        scheduler.schedule(pages, 0)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        starts shouldBe listOf(0, 1)
        scheduler.schedule(pages, 10)
        runCurrent()
        starts shouldBe listOf(0, 1, 10)
        release.complete(Unit)
    }

    @Test
    fun `chapters share two transfers and a single background lane per source`() = runTest {
        val coordinator = ReaderPageLoadCoordinator { testScheduler.currentTime }
        val release = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0
        val load: suspend (ReaderPage) -> Unit = {
            running++
            peak = maxOf(peak, running)
            release.await()
            running--
            it.status = Page.State.READY
        }
        val first = scheduler(coordinator = coordinator, load = load)
        val second = scheduler(coordinator = coordinator, load = load)
        first.schedule(pages(10), 0)
        second.schedule(pages(10), 0)
        runCurrent()
        running shouldBe 2
        release.complete(Unit)
        runCurrent()
        peak shouldBe 2

        var backgroundRunning = 0
        var backgroundPeak = 0
        val preloadRelease = CompletableDeferred<Unit>()
        repeat(3) {
            backgroundScope.launch {
                coordinator.withPermit(2, foreground = false) {
                    backgroundRunning++
                    backgroundPeak = maxOf(backgroundPeak, backgroundRunning)
                    preloadRelease.await()
                    backgroundRunning--
                }
            }
        }
        runCurrent()
        backgroundRunning shouldBe 1
        preloadRelease.complete(Unit)
        advanceTimeBy(3_000)
        runCurrent()
        backgroundPeak shouldBe 1
    }

    @Test
    fun `one source does not hold another source`() = runTest {
        val coordinator = ReaderPageLoadCoordinator { testScheduler.currentTime }
        val starts = mutableListOf<Int>()
        val load: suspend (ReaderPage) -> Unit = {
            starts += it.index
            awaitCancellation()
        }
        scheduler(source = 1, coordinator = coordinator, load = load).schedule(pages(10), 0)
        scheduler(source = 2, coordinator = coordinator, load = load).schedule(pages(10), 5)
        runCurrent()
        starts shouldBe listOf(0, 5)
    }

    @Test
    fun `local cached pages do not wait for network preload pacing`() = runTest {
        val pages = pages(30)
        val starts = mutableListOf<Int>()
        val scheduler = scheduler(requiresNetwork = { false }) {
            starts += it.index
            it.status = Page.State.READY
        }
        scheduler.schedule(pages, 10)
        runCurrent()
        starts.size shouldBe 30
        testScheduler.currentTime shouldBe 0L
    }

    @Test
    fun `a cache miss after classification still waits for network admission`() = runTest {
        val coordinator = ReaderPageLoadCoordinator { testScheduler.currentTime }
        val release = CompletableDeferred<Unit>()
        repeat(2) {
            backgroundScope.launch { coordinator.withPermit(1, foreground = true) { release.await() } }
        }
        runCurrent()
        val loaded = mutableListOf<Int>()
        val scheduler = scheduler(coordinator = coordinator, requiresNetwork = { false }, loadCached = { false }) {
            loaded += it.index
            it.status = Page.State.READY
        }

        scheduler.schedule(pages(1), 0)
        runCurrent()
        loaded shouldBe emptyList()
        release.complete(Unit)
        runCurrent()
        loaded shouldBe listOf(0)
    }

    @Test
    fun `cache-only loading completes even while network permits are occupied`() = runTest {
        val coordinator = ReaderPageLoadCoordinator { testScheduler.currentTime }
        repeat(2) { backgroundScope.launch { coordinator.withPermit(1, foreground = true) { awaitCancellation() } } }
        runCurrent()
        val pages = pages(1)
        var networkLoads = 0
        val scheduler = scheduler(
            coordinator = coordinator,
            requiresNetwork = { false },
            loadCached = {
                it.status = Page.State.READY
                true
            },
        ) { networkLoads++ }

        scheduler.schedule(pages, 0)
        runCurrent()

        pages[0].status shouldBe Page.State.READY
        networkLoads shouldBe 0
        testScheduler.currentTime shouldBe 0L
    }

    @Test
    fun `a cache miss waiter is canceled when its chapter is deactivated`() = runTest {
        val coordinator = ReaderPageLoadCoordinator { testScheduler.currentTime }
        val release = CompletableDeferred<Unit>()
        repeat(2) {
            backgroundScope.launch { coordinator.withPermit(1, foreground = true) { release.await() } }
        }
        runCurrent()
        val loaded = mutableListOf<Int>()
        val scheduler = scheduler(coordinator = coordinator, requiresNetwork = { false }, loadCached = { false }) {
            loaded += it.index
        }
        scheduler.schedule(pages(1), 0)
        runCurrent()

        scheduler.deactivate()
        release.complete(Unit)
        runCurrent()

        loaded shouldBe emptyList()
    }

    @Test
    fun `failed pages are not retried by holder rebind or replanning`() = runTest {
        val pages = pages(3)
        var starts = 0
        val scheduler = scheduler {
            starts++
            it.status = Page.State.ERROR
        }
        scheduler.schedule(pages, 0)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        starts shouldBe 3
        repeat(5) { backgroundScope.launch { scheduler.loadPage(pages, 0) } }
        runCurrent()
        scheduler.schedule(pages, 0)
        runCurrent()
        starts shouldBe 3
        scheduler.retry(pages[0])
        runCurrent()
        starts shouldBe 4
    }

    @Test
    fun `repeated manual retries wait for cancelled request cleanup`() = runTest {
        val pages = pages(1)
        val cleanup = CompletableDeferred<Unit>()
        var starts = 0
        val scheduler = scheduler {
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
    fun `leaving wifi stops distant background loads`() = runTest {
        val pages = pages(30)
        var wifi = true
        val starts = mutableListOf<Int>()
        val scheduler = scheduler(ahead = { wifi }) {
            starts += it.index
            it.status = Page.State.READY
        }
        scheduler.schedule(pages, 10)
        runCurrent()
        wifi = false
        advanceTimeBy(30_000)
        runCurrent()
        starts.toSet() shouldBe (9..14).toSet()
    }

    private fun TestScope.scheduler(
        source: Long = 1,
        ahead: () -> Boolean = { true },
        coordinator: ReaderPageLoadCoordinator = ReaderPageLoadCoordinator { testScheduler.currentTime },
        requiresNetwork: (ReaderPage) -> Boolean = { true },
        loadCached: ((ReaderPage) -> Boolean)? = null,
        load: suspend (ReaderPage) -> Unit,
    ) = ReaderPageLoadScheduler(backgroundScope, source, ahead, coordinator, requiresNetwork, loadCached, load)

    private fun pages(count: Int) = List(count) { ReaderPage(it) }
}
