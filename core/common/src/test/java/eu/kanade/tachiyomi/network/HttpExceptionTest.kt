package eu.kanade.tachiyomi.network

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HttpExceptionTest {
    @Test
    fun `legacy constructor preserves code and message`() {
        val exception = HttpException(429)

        assertEquals(429, exception.code)
        assertEquals("HTTP error 429", exception.message)
        assertNull(exception.retryAfter)
    }

    @Test
    fun `observable failure retains retry after`() {
        val call = mockk<Call>()
        every { call.clone() } returns call
        every { call.execute() } returns throttledResponse()
        every { call.cancel() } returns Unit
        every { call.isCanceled() } returns false

        val subscriber = call.asObservableSuccess().test()

        subscriber.assertError(HttpException::class.java)
        val exception = subscriber.onErrorEvents.single() as HttpException
        assertEquals(429, exception.code)
        assertEquals("120", exception.retryAfter)
    }

    @Test
    fun `suspending failure retains retry after`() {
        val call = mockk<Call>()
        every { call.enqueue(any()) } answers {
            firstArg<Callback>().onResponse(call, throttledResponse())
        }

        val exception = assertThrows(HttpException::class.java) {
            runBlocking { call.awaitSuccess() }
        }

        assertEquals(429, exception.code)
        assertEquals("120", exception.retryAfter)
    }

    @Test
    fun `observable detects challenge regardless of status`() {
        listOf(403, 200).forEach { code ->
            val call = mockk<Call>()
            every { call.clone() } returns call
            every { call.execute() } returns challengeResponse(code)
            every { call.cancel() } returns Unit
            every { call.isCanceled() } returns false

            val subscriber = call.asObservableSuccess().test()

            subscriber.assertError(CloudflareChallengeException::class.java)
            subscriber.assertNoValues()
        }
    }

    @Test
    fun `suspending conversion detects challenge regardless of status`() {
        listOf(403, 200).forEach { code ->
            val call = mockk<Call>()
            every { call.enqueue(any()) } answers {
                firstArg<Callback>().onResponse(call, challengeResponse(code))
            }

            assertThrows(CloudflareChallengeException::class.java) {
                runBlocking { call.awaitSuccess() }
            }
        }
    }

    private fun challengeResponse(code: Int) = throttledResponse().newBuilder()
        .code(code)
        .header("cf-mitigated", "challenge")
        .build()

    private fun throttledResponse() = Response.Builder()
        .request(Request.Builder().url("https://example.com/image.jpg").build())
        .protocol(Protocol.HTTP_1_1)
        .code(429)
        .message("Too Many Requests")
        .header("Retry-After", "120")
        .body("".toResponseBody())
        .build()
}
