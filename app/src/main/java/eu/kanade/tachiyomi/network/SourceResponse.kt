package eu.kanade.tachiyomi.network

import okhttp3.Response

internal fun Response.requireSourceSuccess(): Response {
    val error = when {
        header("cf-mitigated") == "challenge" -> CloudflareChallengeException("Cloudflare challenge requires WebView")
        !isSuccessful -> HttpException(code, header("Retry-After"))
        else -> return this
    }
    close()
    throw error
}
