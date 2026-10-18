package eu.kanade.tachiyomi.network

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.causeChain
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class OkHttpAwaitTest {
    @Test
    fun `await preserves concrete network exception types`() {
        listOf(
            UnknownHostException("example.com"),
            SocketTimeoutException("timeout"),
            SSLHandshakeException("certificate"),
            CloudflareChallengeException("challenge"),
        ).forEach { failure ->
            val call = failedCall(failure)

            val thrown = assertThrows(IOException::class.java) { runBlocking { call.await() } }

            assertEquals(failure.javaClass, thrown.javaClass)
            assertEquals(failure.message, thrown.message)
        }
    }

    @Test
    fun `await success preserves network stack and adds the suspending caller`() {
        val failure = UnknownHostException("example.com")
        val cause = IOException("resolver unavailable")
        val suppressed = IOException("alternate resolver unavailable")
        failure.initCause(cause)
        failure.addSuppressed(suppressed)
        val origin = StackTraceElement("Resolver", "lookup", "Resolver.kt", 42)
        failure.stackTrace = arrayOf(origin)
        val call = failedCall(failure)

        val thrown = assertThrows(UnknownHostException::class.java) { runBlocking { call.awaitSuccess() } }

        assertEquals(origin, failure.stackTrace.first())
        assertTrue(failure.stackTrace.any { it.className.contains("OkHttpAwaitTest") })
        assertTrue(thrown.causeChain().contains(cause))
        assertEquals(listOf(suppressed), failure.suppressed.toList())
    }

    private fun failedCall(failure: IOException) = mockk<Call> {
        every { enqueue(any()) } answers { firstArg<Callback>().onFailure(this@mockk, failure) }
    }
}
