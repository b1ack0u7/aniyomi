package eu.kanade.presentation.webview

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebView
import com.kevinnzou.web.rememberWebViewNavigator
import com.kevinnzou.web.rememberWebViewState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun WebViewScreenContent(
    onNavigateUp: () -> Unit,
    initialTitle: String?,
    url: String,
    onShare: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
    headers: Map<String, String> = emptyMap(),
    headersHost: String? = null,
    onUrlChange: (String) -> Unit = {},
) {
    val sourceHeaders = remember(headersHost, headers) { WebViewSourceHeaders(headersHost, headers) }
    val state = rememberWebViewState(url = url)
    val navigator = rememberWebViewNavigator()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val network = remember { Injekt.get<NetworkHelper>() }
    val spoofedPackageName = remember { WebViewUtil.spoofedPackageName(context) }
    val webViewClient = remember(network, sourceHeaders) {
        network.nonCloudflareClient.newBuilder()
            .addNetworkInterceptor { chain -> chain.proceed(sourceHeaders.filterRequest(chain.request())) }
            .build()
    }

    var currentUrl by remember { mutableStateOf(url) }
    var showCloudflareHelp by remember { mutableStateOf(false) }
    var rendererGone by remember { mutableStateOf(false) }
    var htmlJob by remember { mutableStateOf<Job?>(null) }

    val webClient = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (rendererGone) return
                htmlJob?.cancel()
                htmlJob = scope.launch {
                    val html = view.getHtml()
                    showCloudflareHelp = "window._cf_chl_opt" in html || "Ray ID is" in html
                }
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean,
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                return request?.url?.scheme == "intent"
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                logcat(LogPriority.ERROR) {
                    "WebView renderer gone: crashed=${detail.didCrash()}, " +
                        "priority=${detail.rendererPriorityAtExit()}"
                }
                rendererGone = true
                htmlJob?.cancel()
                view.context.toast(MR.strings.information_webview_crashed, Toast.LENGTH_LONG)
                onNavigateUp()
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                return try {
                    val internalRequest = Request.Builder().apply {
                        url(request!!.url.toString())
                        sourceHeaders.filterRequestHeaders(request.url.toString(), request.requestHeaders)
                            .forEach { (key, value) ->
                                if (key == "X-Requested-With" &&
                                    value in setOf(context.packageName, spoofedPackageName)
                                ) {
                                    return@forEach
                                }
                                addHeader(key, value)
                            }
                        if (request.isForMainFrame) {
                            sourceHeaders.forUrl(request.url.toString()).forEach { (key, value) -> header(key, value) }
                        }
                        method(request.method, null)
                    }.build()

                    val response = webViewClient.newCall(internalRequest).execute()

                    val contentType = response.body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "text/html"
                    val contentEncoding = response.body.contentType()?.charset()?.name() ?: "utf-8"

                    WebResourceResponse(
                        contentType,
                        contentEncoding,
                        response.code,
                        response.message,
                        response.headers.associate { it.first to it.second },
                        response.body.byteStream(),
                    )
                } catch (e: Throwable) {
                    super.shouldInterceptRequest(view, request)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Box {
                Column {
                    AppBar(
                        title = state.pageTitle ?: initialTitle,
                        subtitle = currentUrl,
                        navigateUp = onNavigateUp,
                        navigationIcon = Icons.Outlined.Close,
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_back),
                                        icon = Icons.Outlined.ArrowBack,
                                        onClick = {
                                            if (navigator.canGoBack) {
                                                navigator.navigateBack()
                                            }
                                        },
                                        enabled = navigator.canGoBack,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_forward),
                                        icon = Icons.Outlined.ArrowForward,
                                        onClick = {
                                            if (navigator.canGoForward) {
                                                navigator.navigateForward()
                                            }
                                        },
                                        enabled = navigator.canGoForward,
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_webview_refresh),
                                        onClick = { navigator.reload() },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_share),
                                        onClick = { onShare(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_open_in_browser),
                                        onClick = { onOpenInBrowser(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.pref_clear_cookies),
                                        onClick = { onClearCookies(currentUrl) },
                                    ),
                                ),
                            )
                        },
                    )

                    if (showCloudflareHelp) {
                        Surface(
                            modifier = Modifier.padding(8.dp),
                        ) {
                            WarningBanner(
                                textRes = MR.strings.information_cloudflare_help,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        uriHandler.openUri(
                                            "https://aniyomi.org/docs/guides/troubleshooting/#cloudflare",
                                        )
                                    },
                            )
                        }
                    }
                }
                when (val loadingState = state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    is LoadingState.Loading -> LinearProgressIndicator(
                        progress = { (loadingState as? LoadingState.Loading)?.progress ?: 1f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        if (!rendererGone) {
            WebView(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                navigator = navigator,
                onCreated = { webView ->
                    webView.setDefaultSettings()

                    // Debug mode (chrome://inspect/#devices)
                    if (BuildConfig.DEBUG &&
                        0 != webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
                    ) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }

                    sourceHeaders.forUrl(url).entries.firstOrNull { it.key.equals("user-agent", true) }?.value?.let {
                        webView.settings.userAgentString = it
                    }
                },
                onDispose = { webView ->
                    htmlJob?.cancel()
                    if (!rendererGone) webView.stopLoading()
                    webView.destroy()
                },
                client = webClient,
            )
        }
    }
}
