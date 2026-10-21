package eu.kanade.tachiyomi.ui.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SurfacePlaybackQueueTest {

    @Test
    fun `startup waits for a surface and retains load options and resume position`() {
        val queue = SurfacePlaybackQueue()
        val loads = mutableListOf<Pair<List<String>, Long>>()
        val command = listOf("loadfile", "https://video", "replace", "0", "aid=2")
        val position = 45L

        queue.submit(queue.beginLoad()) { loads.add(command to position) }

        queue.isSurfaceReady shouldBe false
        loads shouldBe emptyList()
        queue.setSurfaceReady(true)
        queue.isSurfaceReady shouldBe true
        loads shouldBe listOf(command to position)
        queue.setSurfaceReady(true)
        loads.size shouldBe 1
    }

    @Test
    fun `new selection replaces pending load and rejects late resolution of the old selection`() {
        val queue = SurfacePlaybackQueue()
        val loads = mutableListOf<String>()
        val first = queue.beginLoad()
        queue.submit(first) { loads.add("first") }
        val second = queue.beginLoad()
        queue.submit(second) { loads.add("second") }
        queue.submit(first) { loads.add("late first") }

        queue.setSurfaceReady(true)

        loads shouldBe listOf("second")
    }

    @Test
    fun `latest pending action for the current generation wins`() {
        val queue = SurfacePlaybackQueue()
        val loads = mutableListOf<String>()
        val generation = queue.beginLoad()
        queue.submit(generation) { loads.add("old") }
        queue.submit(generation) { loads.add("latest") }

        queue.setSurfaceReady(true)

        loads shouldBe listOf("latest")
    }

    @Test
    fun `detaching defers the next load until the surface is attached again`() {
        val queue = SurfacePlaybackQueue()
        val loads = mutableListOf<String>()
        queue.setSurfaceReady(true)
        queue.submit(queue.beginLoad()) { loads.add("first") }
        queue.setSurfaceReady(false)
        queue.submit(queue.beginLoad()) { loads.add("second") }

        loads shouldBe listOf("first")
        queue.setSurfaceReady(true)
        loads shouldBe listOf("first", "second")
    }

    @Test
    fun `inactive playback retains the load until it resumes`() {
        val queue = SurfacePlaybackQueue()
        var loads = 0
        queue.setActive(false)
        queue.submit(queue.beginLoad()) { loads++ }
        queue.setSurfaceReady(true)

        loads shouldBe 0
        queue.setActive(true)
        loads shouldBe 1
        queue.setActive(true)
        loads shouldBe 1
    }

    @Test
    fun `clearing discards pending work and rejects late results`() {
        val queue = SurfacePlaybackQueue()
        var loads = 0
        val generation = queue.beginLoad()
        queue.submit(generation) { loads++ }

        queue.clear()
        queue.submit(generation) { loads++ }
        queue.setSurfaceReady(true)

        loads shouldBe 0
        queue.submit(queue.beginLoad()) { loads++ }
        loads shouldBe 1
    }

    @Test
    fun `reentrant state updates do not invoke the executing load again`() {
        val queue = SurfacePlaybackQueue()
        var loads = 0
        queue.submit(queue.beginLoad()) {
            loads++
            queue.setSurfaceReady(true)
            queue.setActive(true)
        }

        queue.setSurfaceReady(true)

        loads shouldBe 1
    }
}
