package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CloudflareChallengeCoordinatorTest {
    @Test
    fun `concurrent requests to one host share a challenge`() {
        val coordinator = CloudflareChallengeCoordinator()
        val requestsReady = CountDownLatch(8)
        val attempts = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val requests = List(8) {
                executor.submit {
                    coordinator.withHost("example.com") { challenge ->
                        requestsReady.countDown()
                        assertTrue(requestsReady.await(5, TimeUnit.SECONDS))
                        challenge.resolve({ false }) { attempts.incrementAndGet() }
                    }
                }
            }
            requests.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, attempts.get())
            coordinator.withHost("example.com") { it.resolve({ false }) { attempts.incrementAndGet() } }
            assertEquals(2, attempts.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `hosts resolve independently and cancelled waiters do not cancel the owner`() {
        val coordinator = CloudflareChallengeCoordinator()
        val solving = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val owner = executor.submit {
                coordinator.withHost("example.com") { challenge ->
                    challenge.resolve({ false }) {
                        solving.countDown()
                        assertTrue(finish.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(solving.await(5, TimeUnit.SECONDS))
            val waiter = executor.submit {
                coordinator.withHost("example.com") { challenge ->
                    assertThrows(IOException::class.java) {
                        challenge.resolve({ true }) { error("Cancelled request must not solve") }
                    }
                }
            }
            waiter.get(2, TimeUnit.SECONDS)
            coordinator.withHost("another.example") { challenge ->
                challenge.resolve({ false }) { }
            }
            assertFalse(owner.isDone)
            finish.countDown()
            owner.get(5, TimeUnit.SECONDS)
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed challenge is shared only with requests already in flight`() {
        val coordinator = CloudflareChallengeCoordinator()
        val failure = IOException("Challenge failed")
        val attempts = AtomicInteger()
        coordinator.withHost("example.com") { first ->
            coordinator.withHost("example.com") { second ->
                assertEquals(
                    failure,
                    assertThrows(IOException::class.java) {
                        first.resolve({ false }) {
                            attempts.incrementAndGet()
                            throw failure
                        }
                    },
                )
                assertEquals(
                    failure,
                    assertThrows(IOException::class.java) {
                        second.resolve({ false }) { attempts.incrementAndGet() }
                    },
                )
                coordinator.withHost("example.com") { next ->
                    next.resolve({ false }) { attempts.incrementAndGet() }
                }
            }
        }
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancelled owner allows another request to solve`() {
        val coordinator = CloudflareChallengeCoordinator()
        val canceled = AtomicBoolean()
        var solved = false
        coordinator.withHost("example.com") { first ->
            coordinator.withHost("example.com") { second ->
                assertThrows(IOException::class.java) {
                    first.resolve(canceled::get) {
                        canceled.set(true)
                        throw IOException("Canceled")
                    }
                }
                second.resolve({ false }) { solved = true }
            }
        }
        assertTrue(solved)
    }

    @Test
    fun `non GET challenge uses site root while original request keeps its body and headers`() {
        val body = "credentials".toRequestBody()
        val request = Request.Builder()
            .url("https://example.com:8443/api/login?token=value#fragment")
            .header("Authorization", "Bearer value")
            .post(body)
            .build()

        assertEquals("https://example.com:8443/", request.cloudflareChallengeUrl().toString())
        assertEquals("POST", request.method)
        assertEquals(body, request.body)
        assertEquals("Bearer value", request.header("Authorization"))
        assertEquals("https://example.com:8443/api/login?token=value#fragment", request.url.toString())
        val get = request.newBuilder().get().build()
        assertEquals(get.url, get.cloudflareChallengeUrl())
    }
}
