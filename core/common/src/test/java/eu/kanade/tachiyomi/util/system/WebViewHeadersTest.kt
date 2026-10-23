package eu.kanade.tachiyomi.util.system

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebViewHeadersTest {
    @Test
    fun `unsafe headers are filtered case insensitively`() {
        val headers = Headers.Builder()
            .add("Origin", "https://example.com")
            .add("hOsT", "example.com")
            .add("Content-Length", "10")
            .add("Proxy-Authorization", "secret")
            .add("Connection", "uPgRaDe")
            .add("Upgrade", "websocket")
            .add("Set-Cookie", "session=value")
            .add("User-Agent", "agent")
            .add("Referer", "https://example.com/")
            .add("Authorization", "Bearer token")
            .build()

        assertEquals(
            mapOf("User-Agent" to "agent", "Referer" to "https://example.com/", "Authorization" to "Bearer token"),
            headers.toWebViewHeaders(),
        )
    }

    @Test
    fun `duplicate header names keep first safe value regardless of case`() {
        val headers = Headers.Builder()
            .add("Accept", "text/html")
            .add("accept", "application/json")
            .build()

        assertEquals(mapOf("Accept" to "text/html"), headers.toWebViewHeaders())
    }
}
