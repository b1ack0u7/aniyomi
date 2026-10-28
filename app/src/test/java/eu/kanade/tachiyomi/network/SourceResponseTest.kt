package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.jupiter.api.Test

class SourceResponseTest {
    @Test
    fun `custom source forbidden response is closed and remains distinct from a challenge`() {
        val body = mockk<ResponseBody>(relaxed = true)
        val error = runCatching { response(403, body).requireSourceSuccess() }.exceptionOrNull()

        (error as HttpException).code shouldBe 403
        verify(exactly = 1) { body.close() }
        verify(exactly = 0) { body.source() }
    }

    @Test
    fun `custom source rate limit retains retry after and closes response`() {
        val body = mockk<ResponseBody>(relaxed = true)
        val error = runCatching {
            response(429, body).newBuilder().header("Retry-After", "600").build().requireSourceSuccess()
        }.exceptionOrNull()

        (error as HttpException).retryAfter shouldBe "600"
        verify(exactly = 1) { body.close() }
    }

    @Test
    fun `challenge with successful HTTP status never becomes an image`() {
        val body = mockk<ResponseBody>(relaxed = true)
        val error = runCatching {
            response(200, body).newBuilder().header("cf-mitigated", "challenge").build().requireSourceSuccess()
        }.exceptionOrNull()

        (error is CloudflareChallengeException) shouldBe true
        verify(exactly = 1) { body.close() }
        verify(exactly = 0) { body.source() }
    }

    private fun response(code: Int, body: ResponseBody) = Response.Builder()
        .request(Request.Builder().url("https://example.com/image").build())
        .protocol(Protocol.HTTP_2)
        .code(code)
        .message("Test response")
        .body(body)
        .build()
}
