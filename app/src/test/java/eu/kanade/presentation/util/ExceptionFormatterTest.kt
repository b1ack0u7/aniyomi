package eu.kanade.presentation.util

import android.content.Context
import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import eu.kanade.tachiyomi.util.system.isOnline
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.items.chapter.model.NoChaptersException
import tachiyomi.domain.items.episode.model.NoEpisodesException
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import java.io.IOException
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date

class ExceptionFormatterTest {
    private val context = mockk<Context>()

    @BeforeEach
    fun setUp() {
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        mockkStatic("eu.kanade.tachiyomi.util.system.NetworkExtensionsKt")
        every { context.isOnline() } returns true
        every { context.stringResource(MR.strings.information_cloudflare_bypass_failure) } returns
            "Cloudflare challenge"
        every { context.stringResource(MR.strings.exception_offline) } returns "Offline"
        every { context.stringResource(MR.strings.exception_unknown_host, "example.com") } returns "DNS: example.com"
        every { context.stringResource(MR.strings.exception_http, 429) } returns "HTTP 429"
        every { context.stringResource(MR.strings.no_results_found) } returns "No results"
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        unmockkStatic("eu.kanade.tachiyomi.util.system.NetworkExtensionsKt")
    }

    @Test
    fun `wrapped cloudflare challenge has its own diagnostic`() {
        val error = IOException("wrapper", CloudflareChallengeException("challenge"))

        assertEquals("Cloudflare challenge", format(error))
    }

    @Test
    fun `source paused by cloudflare retains the challenge diagnostic`() {
        assertEquals("Cloudflare challenge", format(IOException("wrapper", SourceRequestPausedException())))
    }

    @Test
    fun `temporary source pause reports its localized retry date`() {
        val retryAt = 1_800_000_000_000L
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(retryAt))
        every { context.stringResource(AYMR.strings.smart_download_waiting, date) } returns "Waiting until $date"

        assertEquals("Waiting until $date", format(IOException("wrapper", SourceRequestPausedException(retryAt))))
    }

    @Test
    fun `cloudflare diagnostic takes priority over an enclosing HTTP error`() {
        val error = HttpException(429).apply { initCause(CloudflareChallengeException("challenge")) }

        assertEquals("Cloudflare challenge", format(error))
    }

    @Test
    fun `wrapped unknown host reports DNS failure when online`() {
        val error = IOException("wrapper", UnknownHostException("example.com"))

        assertEquals("DNS: example.com", format(error))
    }

    @Test
    fun `wrapped unknown host reports offline when disconnected`() {
        every { context.isOnline() } returns false
        val error = IOException("wrapper", UnknownHostException("example.com"))

        assertEquals("Offline", format(error))
    }

    @Test
    fun `wrapped HTTP errors retain their status code`() {
        val error = IOException("wrapper", HttpException(429))

        assertEquals("HTTP 429", format(error))
    }

    @Test
    fun `wrapped empty lists use the same diagnostic for manga and anime`() {
        listOf(NoChaptersException(), NoEpisodesException()).forEach {
            assertEquals("No results", format(IOException("wrapper", it)))
        }
    }

    @Test
    fun `unknown errors retain the existing fallback`() {
        assertEquals("wrapper", format(IOException("wrapper", IllegalArgumentException("detail"))))
    }

    private fun format(error: Throwable) = with(context) { error.formattedMessage }
}
