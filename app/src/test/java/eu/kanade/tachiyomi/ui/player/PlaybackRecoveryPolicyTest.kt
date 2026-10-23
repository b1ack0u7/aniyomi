package eu.kanade.tachiyomi.ui.player

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlaybackRecoveryPolicyTest {

    @Test
    fun `duplicate signals from different failure paths reserve only one switch`() {
        val policy = PlaybackRecoveryPolicy()
        policy.beginEpisode(1, 10)
        val attempt = policy.beginAttempt()

        policy.reserve(attempt, PlaybackFailureReason.Opening, 3, true) shouldBe RecoveryDecision.Allowed
        policy.reserve(attempt, PlaybackFailureReason.NoContent, 3, true) shouldBe RecoveryDecision.Duplicate
        policy.totalSwitches shouldBe 1
        policy.countsByReason shouldBe mapOf(PlaybackFailureReason.Opening to 1)
        policy.remaining(3) shouldBe 2
    }

    @Test
    fun `all failure reasons share the episode budget across replacement attempts`() {
        val policy = PlaybackRecoveryPolicy()
        policy.beginEpisode(1, 10)
        listOf(PlaybackFailureReason.Resolution, PlaybackFailureReason.Opening, PlaybackFailureReason.Stall).forEach {
            policy.reserve(policy.beginAttempt(), it, 3, true) shouldBe RecoveryDecision.Allowed
        }
        val attempt = policy.beginAttempt()
        policy.reserve(attempt, PlaybackFailureReason.NoContent, 3, true) shouldBe RecoveryDecision.Exhausted
        policy.reserve(attempt, PlaybackFailureReason.Stall, 3, true) shouldBe RecoveryDecision.Duplicate
        policy.totalSwitches shouldBe 3
        policy.countsByReason shouldBe mapOf(
            PlaybackFailureReason.Resolution to 1,
            PlaybackFailureReason.Opening to 1,
            PlaybackFailureReason.Stall to 1,
        )
        policy.remaining(3) shouldBe 0
        policy.remaining(1) shouldBe 0
    }

    @Test
    fun `missing candidates do not spend budget and duplicate terminal signals stay quiet`() {
        val policy = PlaybackRecoveryPolicy()
        val attempt = policy.beginAttempt()

        policy.reserve(attempt, PlaybackFailureReason.Resolution, 3, false) shouldBe RecoveryDecision.NoCandidate
        policy.reserve(attempt, PlaybackFailureReason.Opening, 3, true) shouldBe RecoveryDecision.Duplicate
        policy.totalSwitches shouldBe 0
        policy.countsByReason shouldBe emptyMap<PlaybackFailureReason, Int>()
        policy.remaining(3) shouldBe 3
        policy.reserve(policy.beginAttempt(), PlaybackFailureReason.NoContent, 3, true) shouldBe
            RecoveryDecision.Allowed
    }

    @Test
    fun `same episode and manual replacement attempts do not reset the budget`() {
        val policy = PlaybackRecoveryPolicy()
        policy.beginEpisode(1, 10) shouldBe true
        val first = policy.beginAttempt()
        policy.reserve(first, PlaybackFailureReason.Stall, 3, true) shouldBe RecoveryDecision.Allowed

        policy.beginEpisode(1, 10) shouldBe false
        policy.reserve(first, PlaybackFailureReason.Stall, 3, true) shouldBe RecoveryDecision.Duplicate
        val manualAttempt = policy.beginAttempt()
        policy.totalSwitches shouldBe 1
        policy.reserve(first, PlaybackFailureReason.NoContent, 3, true) shouldBe RecoveryDecision.Stale
        policy.reserve(manualAttempt, PlaybackFailureReason.Resolution, 3, true) shouldBe RecoveryDecision.Allowed
        policy.totalSwitches shouldBe 2
    }

    @Test
    fun `changing either anime or episode clears counters and invalidates outstanding attempts`() {
        val policy = PlaybackRecoveryPolicy()
        policy.beginEpisode(1, 10)
        var oldAttempt = policy.beginAttempt()
        policy.reserve(oldAttempt, PlaybackFailureReason.Stall, 3, true) shouldBe RecoveryDecision.Allowed

        listOf(1L to 11L, 2L to 11L).forEach { (anime, episode) ->
            policy.beginEpisode(anime, episode) shouldBe true
            policy.reserve(oldAttempt, PlaybackFailureReason.Opening, 3, true) shouldBe RecoveryDecision.Stale
            policy.totalSwitches shouldBe 0
            policy.countsByReason shouldBe emptyMap<PlaybackFailureReason, Int>()
            val newAttempt = policy.beginAttempt()
            (newAttempt > oldAttempt) shouldBe true
            policy.reserve(oldAttempt, PlaybackFailureReason.Stall, 3, true) shouldBe RecoveryDecision.Stale
            policy.reserve(newAttempt, PlaybackFailureReason.NoContent, 3, true) shouldBe RecoveryDecision.Allowed
            oldAttempt = newAttempt
        }
    }

    @Test
    fun `zero or negative limits disable automatic recovery without spending budget`() {
        val policy = PlaybackRecoveryPolicy()
        listOf(0, -1, Int.MIN_VALUE).forEach { limit ->
            policy.reserve(policy.beginAttempt(), PlaybackFailureReason.Stall, limit, true) shouldBe
                RecoveryDecision.Exhausted
            policy.remaining(limit) shouldBe 0
        }
        policy.totalSwitches shouldBe 0
        policy.reserve(policy.beginAttempt(), PlaybackFailureReason.Stall, 1, true) shouldBe RecoveryDecision.Allowed
    }

    @Test
    fun `reason counts are immutable snapshots of successful reservations`() {
        val policy = PlaybackRecoveryPolicy()
        policy.reserve(policy.beginAttempt(), PlaybackFailureReason.Stall, 3, true) shouldBe RecoveryDecision.Allowed
        val snapshot = policy.countsByReason
        policy.reserve(policy.beginAttempt(), PlaybackFailureReason.Stall, 3, true) shouldBe RecoveryDecision.Allowed
        snapshot shouldBe mapOf(PlaybackFailureReason.Stall to 1)
        policy.countsByReason shouldBe mapOf(PlaybackFailureReason.Stall to 2)
    }

    @Test
    fun `concurrent failures cannot reserve the same attempt twice`() {
        val policy = PlaybackRecoveryPolicy()
        val attempt = policy.beginAttempt()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) {
                executor.submit<RecoveryDecision> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    policy.reserve(attempt, PlaybackFailureReason.Stall, 3, true)
                }
            }
            ready.await(5, TimeUnit.SECONDS) shouldBe true
            start.countDown()
            val decisions = futures.map { it.get(5, TimeUnit.SECONDS) }
            decisions.count { it == RecoveryDecision.Allowed } shouldBe 1
            decisions.count { it == RecoveryDecision.Duplicate } shouldBe 7
            policy.totalSwitches shouldBe 1
            policy.countsByReason shouldBe mapOf(PlaybackFailureReason.Stall to 1)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
