package eu.kanade.presentation.webview

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

internal class WebViewSourceHeaders(
    private val host: String?,
    private val headers: Map<String, String>,
) {
    fun forUrl(url: String): Map<String, String> {
        return if (host != null && url.toHttpUrlOrNull()?.host == host) headers else emptyMap()
    }

    fun filterRequest(request: Request): Request {
        if (host != null && request.url.host == host) return request
        val filteredHeaders = request.headers.newBuilder()
        for ((name, value) in request.headers) {
            if (headers.any { (sourceName, sourceValue) -> sourceName.equals(name, true) && sourceValue == value }) {
                filteredHeaders.removeAll(name)
            }
        }
        return request.newBuilder().headers(filteredHeaders.build()).build()
    }

    fun filterRequestHeaders(url: String, requestHeaders: Map<String, String>): Map<String, String> {
        if (host != null && url.toHttpUrlOrNull()?.host == host) return requestHeaders
        return requestHeaders.filterNot { (name, value) ->
            headers.any { (sourceName, sourceValue) -> sourceName.equals(name, true) && sourceValue == value }
        }
    }
}
