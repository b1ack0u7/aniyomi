package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.util.system.isOutdated
import eu.kanade.tachiyomi.util.system.toWebViewHeaders
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val executor = ContextCompat.getMainExecutor(context)
    private val challenges = CloudflareChallengeCoordinator()

    override fun shouldIntercept(response: Response): Boolean {
        // https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/
        return response.header("cf-mitigated") == "challenge" && response.header("Server") in SERVER_CHECK
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return challenges.withHost(request.url.host) { challenge ->
            val originalClearance = clearance(request.url)
            val response = chain.proceed(request)
            if (!shouldIntercept(response)) return@withHost response
            if (!prepareWebView()) return@withHost response
            response.close()

            challenge.resolve(chain.call()::isCanceled) {
                if (!isCleared(request.url, originalClearance)) solve(chain, request, originalClearance)
            }
            retry(chain, request)
        }
    }

    override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response {
        response.close()
        challenges.withHost(request.url.host) { challenge ->
            val originalClearance = clearance(request.url)
            challenge.resolve(chain.call()::isCanceled) {
                if (!isCleared(request.url, originalClearance)) solve(chain, request, originalClearance)
            }
        }
        return retry(chain, request)
    }

    private fun retry(chain: Interceptor.Chain, request: Request): Response {
        if (chain.call().isCanceled()) throw IOException("Canceled")
        val response = chain.proceed(request)
        if (shouldIntercept(response)) {
            response.close()
            throw bypassException()
        }
        return response
    }

    private fun clearance(url: HttpUrl): String? =
        cookieManager.get(url).firstOrNull { it.name == "cf_clearance" }?.value

    private fun isCleared(url: HttpUrl, originalClearance: String?): Boolean {
        val value = clearance(url)
        return !value.isNullOrBlank() && value != originalClearance
    }

    private fun bypassException(cause: Throwable? = null) = CloudflareChallengeException(
        context.stringResource(MR.strings.information_cloudflare_bypass_failure),
        cause,
    )

    private fun solve(chain: Interceptor.Chain, request: Request, originalClearance: String?) {
        try {
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            resolveWithWebView(request, originalClearance, chain.call()::isCanceled)
        } catch (e: IOException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(e)
        } catch (e: Exception) {
            throw bypassException(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(
        originalRequest: Request,
        originalClearance: String?,
        isCanceled: () -> Boolean,
    ) {
        val latch = CountDownLatch(1)
        val finished = AtomicBoolean()
        val cloudflareBypassed = AtomicBoolean()
        val failure = AtomicReference<Exception?>()
        var webview: WebView? = null
        var challengeFound = false
        var isRenderProcessGone = false
        val challengeUrl = originalRequest.cloudflareChallengeUrl().toString()
        val headers = originalRequest.headers.toWebViewHeaders()

        try {
            executor.execute {
                if (finished.get()) return@execute
                try {
                    val view = createWebView(originalRequest)
                    webview = view
                    view.addJavascriptInterface(
                        object {
                            @Suppress("unused")
                            @JavascriptInterface
                            fun interactiveDetected() = latch.countDown()
                        },
                        "aniyomi",
                    )
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (isCleared(originalRequest.url, originalClearance)) {
                                cloudflareBypassed.set(true)
                                latch.countDown()
                            }
                            if (url == challengeUrl) {
                                if (!challengeFound) {
                                    latch.countDown()
                                } else {
                                    view.evaluateJavascript(
                                        """
                                            addEventListener("message", ({data}) => {
                                                if (data?.source === "cloudflare-challenge" && data?.event === "interactiveBegin") {
                                                    aniyomi.interactiveDetected();
                                                }
                                            })
                                        """.trimIndent(),
                                        null,
                                    )
                                }
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                if (errorResponse?.responseHeaders?.get("cf-mitigated") == "challenge") {
                                    challengeFound = true
                                } else {
                                    latch.countDown()
                                }
                            }
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            logcat(LogPriority.ERROR) {
                                "WebView renderer gone during Cloudflare bypass: crashed=${detail.didCrash()}, " +
                                    "priority=${detail.rendererPriorityAtExit()}"
                            }
                            isRenderProcessGone = true
                            cloudflareBypassed.set(false)
                            latch.countDown()
                            return true
                        }
                    }
                    view.loadUrl(challengeUrl, headers)
                } catch (e: Exception) {
                    failure.set(e)
                    latch.countDown()
                }
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                if (isCanceled()) throw IOException("Canceled")
                if (System.nanoTime() >= deadline) break
            }
            if (isCanceled()) throw IOException("Canceled")
            failure.get()?.let { throw it }
            if (!cloudflareBypassed.get()) throw bypassException()
        } finally {
            finished.set(true)
            executor.execute {
                webview?.run {
                    try {
                        // A WebView with a dead renderer only permits destroy().
                        if (!isRenderProcessGone) {
                            if (!cloudflareBypassed.get() && isOutdated()) {
                                context.toast(MR.strings.information_webview_outdated, Toast.LENGTH_LONG)
                            }
                            removeJavascriptInterface("aniyomi")
                            stopLoading()
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e)
                    } finally {
                        try {
                            destroy()
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e)
                        }
                        webview = null
                    }
                }
            }
        }
    }
}

internal fun Request.cloudflareChallengeUrl(): HttpUrl = if (method == "GET") {
    url
} else {
    // WebView loads GET; non-GET endpoints often cannot serve the challenge page.
    url.newBuilder().encodedPath("/").query(null).fragment(null).build()
}

private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")
