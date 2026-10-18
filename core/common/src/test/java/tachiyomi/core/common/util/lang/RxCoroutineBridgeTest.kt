package tachiyomi.core.common.util.lang

import eu.kanade.tachiyomi.network.asObservable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rx.Observable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RxCoroutineBridgeTest {
    @Test
    fun `single value is returned`() = runBlocking {
        assertEquals("value", Observable.just("value").awaitSingle())
    }

    @Test
    fun `source error is preserved`() {
        val error = IOException("Network unavailable")

        val thrown = assertThrows(IOException::class.java) {
            runBlocking { Observable.error<String>(error).awaitSingle() }
        }

        assertEquals(error.message, thrown.message)
    }

    @Test
    fun `empty source fails`() {
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { Observable.empty<String>().awaitSingle() }
        }
    }

    @Test
    fun `multiple values fail`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { Observable.just("first", "second").awaitSingle() }
        }
    }

    @Test
    fun `already cancelled coroutine does not subscribe`() = runBlocking {
        val subscribed = AtomicBoolean(false)
        val source = Observable.unsafeCreate<String> { subscribed.set(true) }

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            source.awaitSingle()
        }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(subscribed.get())
    }

    @Test
    fun `cancelling during synchronous subscription cancels pending call`() = runBlocking {
        val executing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val call = mockk<Call>()
        every { call.clone() } returns call
        every { call.isCanceled() } answers { cancelled.count == 0L }
        every { call.cancel() } answers {
            cancelled.countDown()
            release.countDown()
        }
        every { call.execute() } answers {
            executing.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw IOException("Canceled")
        }

        val request = async(Dispatchers.IO) { call.asObservable().awaitSingle() }
        try {
            assertTrue(executing.await(5, TimeUnit.SECONDS))
            request.cancel()

            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            request.cancel()
            request.join()
        }

        verify(exactly = 1) { call.cancel() }
        assertTrue(request.isCancelled)
    }
}
