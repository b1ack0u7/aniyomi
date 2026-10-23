package eu.kanade.presentation.webview

import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebViewSourceHeadersTest {

    private val headers = mapOf(
        "Authorization" to "Bearer source-token",
        "Referer" to "https://source.test/",
        "X-Token" to "source-token",
    )
    private val sourceHeaders = WebViewSourceHeaders("source.test", headers)

    @Test
    fun `source headers stay on their exact host during navigation`() {
        sourceHeaders.forUrl("https://source.test/chapter") shouldBe headers
        sourceHeaders.forUrl("https://SOURCE.TEST/other") shouldBe headers
        sourceHeaders.forUrl("https://other.test/") shouldBe emptyMap()
        sourceHeaders.forUrl("https://sub.source.test/") shouldBe emptyMap()
        sourceHeaders.forUrl("https://source.test.other.test/") shouldBe emptyMap()
        sourceHeaders.forUrl("blob:https://source.test/id") shouldBe emptyMap()
        WebViewSourceHeaders(null, headers).forUrl("https://source.test/") shouldBe emptyMap()
    }

    @Test
    fun `redirected requests drop inherited source credentials case insensitively`() {
        val requestHeaders = mapOf(
            "authorization" to "Bearer source-token",
            "REFERER" to "https://source.test/",
            "Accept" to "text/html",
        )

        sourceHeaders.filterRequestHeaders("https://other.test/redirected", requestHeaders) shouldBe
            mapOf("Accept" to "text/html")
        sourceHeaders.filterRequestHeaders("https://source.test/redirected", requestHeaders) shouldBe requestHeaders
    }

    @Test
    fun `target site headers remain when they do not come from the source`() {
        val requestHeaders = mapOf("Authorization" to "Bearer target-token", "Referer" to "https://other.test/")
        sourceHeaders.filterRequestHeaders("https://other.test/", requestHeaders) shouldBe requestHeaders
    }

    @Test
    fun `network redirects strip source headers at every hop and retain destination content`() {
        val receivedHeaders = ConcurrentHashMap<String, Map<String, List<String>>>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5_000
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()
        val requests = executor.submit {
            repeat(2) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(" ")[1]
                    val requestHeaders = mutableMapOf<String, List<String>>()
                    while (true) {
                        val line = reader.readLine()
                        if (line.isEmpty()) break
                        requestHeaders[line.substringBefore(":")] = listOf(line.substringAfter(":").trim())
                    }
                    receivedHeaders[path] = requestHeaders
                    val response = if (path == "/redirect") {
                        "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$port/final\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\ndestination"
                    }
                    socket.getOutputStream().write(response.toByteArray())
                }
            }
        }
        val policy = WebViewSourceHeaders("localhost", headers)
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain -> chain.proceed(policy.filterRequest(chain.request())) }
            .build()
        try {
            val request = Request.Builder()
                .url("http://localhost:$port/redirect")
                .header("Authorization", "Bearer source-token")
                .header("Referer", "https://source.test/")
                .header("X-Token", "source-token")
                .header("Accept", "text/html")
                .build()
            client.newCall(request).execute().use { response ->
                response.body.string() shouldBe "destination"
                response.request.url.host shouldBe "127.0.0.1"
            }

            requests.get(5, TimeUnit.SECONDS)

            fun received(path: String, name: String) = receivedHeaders.getValue(path)
                .entries.firstOrNull { it.key.equals(name, true) }?.value

            received("/redirect", "Authorization") shouldBe listOf("Bearer source-token")
            received("/redirect", "Referer") shouldBe listOf("https://source.test/")
            received("/redirect", "X-Token") shouldBe listOf("source-token")
            received("/final", "X-Token") shouldBe null
            received("/final", "Authorization") shouldBe null
            received("/final", "Referer") shouldBe null
            received("/final", "Accept") shouldBe listOf("text/html")
        } finally {
            server.close()
            executor.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
