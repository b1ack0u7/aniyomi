package eu.kanade.tachiyomi.data.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LibraryUpdateRunTest {

    @Test
    fun `cancellation during synchronous finalization does not record success`() = runTest {
        var successfulRuns = 0
        var downloadsStarted = 0
        val run = LibraryUpdateRun({ downloadsStarted++ }, { successfulRuns++ })
        val worker = launch {
            run.execute {
                queueDownloads {}
                currentCoroutineContext().cancel()
                LibraryUpdateSummary(1, 0)
            }
        }

        worker.join()

        worker.isCancelled shouldBe true
        successfulRuns shouldBe 0
        downloadsStarted shouldBe 1
    }

    @Test
    fun `cancellation starts queued downloads after all sources stop without recording success`() = runTest {
        val queued = CompletableDeferred<Unit>()
        val slowSourceStarted = CompletableDeferred<Unit>()
        var slowSourceStopped = false
        var downloadsStarted = 0
        var successfulRuns = 0
        val run = LibraryUpdateRun(
            startDownloads = {
                slowSourceStopped shouldBe true
                downloadsStarted++
            },
            onSuccess = { successfulRuns++ },
        )
        val worker = launch {
            run.execute {
                coroutineScope {
                    launch {
                        slowSourceStarted.await()
                        queueDownloads { queued.complete(Unit) }
                    }
                    launch {
                        try {
                            slowSourceStarted.complete(Unit)
                            awaitCancellation()
                        } finally {
                            slowSourceStopped = true
                        }
                    }
                }
                LibraryUpdateSummary(1, 0)
            }
        }

        queued.await()
        downloadsStarted shouldBe 0
        worker.cancelAndJoin()

        downloadsStarted shouldBe 1
        successfulRuns shouldBe 0
        worker.isCancelled shouldBe true
    }

    @Test
    fun `successful parallel sources start downloads once after the update finishes`() = runTest {
        var queued = 0
        var downloadsStarted = 0
        var successfulRuns = 0
        val run = LibraryUpdateRun(
            startDownloads = {
                queued shouldBe 5
                downloadsStarted++
            },
            onSuccess = { successfulRuns++ },
        )

        val summary = run.execute {
            coroutineScope {
                repeat(5) {
                    launch { queueDownloads { queued++ } }
                }
            }
            downloadsStarted shouldBe 0
            LibraryUpdateSummary(5, 0)
        }

        summary shouldBe LibraryUpdateSummary(5, 0)
        downloadsStarted shouldBe 1
        successfulRuns shouldBe 1
    }

    @Test
    fun `partial source failures still start saved downloads without advancing success timestamp`() = runTest {
        var downloadsStarted = 0
        var successfulRuns = 0
        val run = LibraryUpdateRun({ downloadsStarted++ }, { successfulRuns++ })

        run.execute {
            queueDownloads {}
            LibraryUpdateSummary(2, 1)
        }

        downloadsStarted shouldBe 1
        successfulRuns shouldBe 0
    }

    @Test
    fun `failure during finalization does not strand downloads or record a successful run`() = runTest {
        var downloadsStarted = 0
        var successfulRuns = 0
        val run = LibraryUpdateRun({ downloadsStarted++ }, { successfulRuns++ })

        val error = runCatching {
            run.execute {
                queueDownloads {}
                throw IllegalStateException("notification failed")
            }
        }.exceptionOrNull()

        error?.message shouldBe "notification failed"
        downloadsStarted shouldBe 1
        successfulRuns shouldBe 0
    }

    @Test
    fun `a run without downloads records success without resuming a paused queue`() = runTest {
        var downloadsStarted = 0
        var successfulRuns = 0
        val run = LibraryUpdateRun({ downloadsStarted++ }, { successfulRuns++ })

        run.execute { LibraryUpdateSummary(0, 0) }

        downloadsStarted shouldBe 0
        successfulRuns shouldBe 1
    }

    @Test
    fun `a rejected enqueue does not resume downloads`() = runTest {
        var downloadsStarted = 0
        val run = LibraryUpdateRun({ downloadsStarted++ }, {})

        assertThrows(IllegalStateException::class.java) {
            run.queueDownloads { throw IllegalStateException("enqueue failed") }
        }
        run.execute { LibraryUpdateSummary(0, 1) }

        downloadsStarted shouldBe 0
    }
}
