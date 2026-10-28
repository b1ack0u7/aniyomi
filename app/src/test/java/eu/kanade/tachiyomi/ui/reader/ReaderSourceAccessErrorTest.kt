package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceAccessRestriction
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSourceAccessErrorTest {
    @Test
    fun `image rejection after a successful or cached page list exposes the source warning`() = runTest {
        val page = ReaderPage(0)
        val restrictions = MutableStateFlow<SourceAccessRestriction?>(null)
        val warnings = mutableListOf<SourceAccessRestriction>()
        backgroundScope.launch {
            page.collectSourceAccessWarnings(restrictions, flowOf(true)) {
                warnings += it
                true
            }
        }
        runCurrent()
        page.status = Page.State.DOWNLOAD_IMAGE
        restrictions.value = SourceAccessRestriction.RateLimited(60_000)
        runCurrent()
        warnings shouldBe emptyList()

        page.errorFlow.value = HttpException(429, "60")
        page.status = Page.State.ERROR
        runCurrent()

        warnings.last() shouldBe SourceAccessRestriction.RateLimited(60_000)
        restrictions.value = SourceAccessRestriction.ForbiddenAndRateLimited(60_000)
        runCurrent()
        warnings.size shouldBe 1
        page.status = Page.State.QUEUE
        page.errorFlow.value = null
        runCurrent()
        warnings.size shouldBe 1
    }

    @Test
    fun `existing image errors are observed when selecting a page after source rejection`() = runTest {
        val page = ReaderPage(0).apply {
            errorFlow.value = IOException("wrapper", SourceRequestPausedException(forbidden = true))
            status = Page.State.ERROR
        }
        val restrictions = MutableStateFlow<SourceAccessRestriction?>(SourceAccessRestriction.Forbidden())
        val warnings = mutableListOf<SourceAccessRestriction>()
        backgroundScope.launch {
            page.collectSourceAccessWarnings(restrictions, flowOf(true)) {
                warnings += it
                true
            }
        }
        runCurrent()

        warnings shouldBe listOf(SourceAccessRestriction.Forbidden())
    }

    @Test
    fun `source pauses do not interrupt ready cached images or replace unrelated errors`() = runTest {
        val page = ReaderPage(0).apply { status = Page.State.READY }
        val restrictions = MutableStateFlow<SourceAccessRestriction?>(SourceAccessRestriction.Forbidden())
        val warnings = mutableListOf<SourceAccessRestriction>()
        backgroundScope.launch {
            page.collectSourceAccessWarnings(restrictions, flowOf(true)) {
                warnings += it
                true
            }
        }
        runCurrent()
        page.errorFlow.value = IOException("Decode failed")
        page.status = Page.State.ERROR
        runCurrent()

        warnings shouldBe emptyList()
    }

    @Test
    fun `image errors wait for loading dialogs and a failed explicit retry presents the warning again`() = runTest {
        val page = ReaderPage(0).apply { status = Page.State.ERROR }
        val restrictions = MutableStateFlow<SourceAccessRestriction?>(SourceAccessRestriction.Forbidden())
        val available = MutableStateFlow(false)
        val warnings = mutableListOf<SourceAccessRestriction>()
        backgroundScope.launch {
            page.collectSourceAccessWarnings(restrictions, available) {
                warnings += it
                true
            }
        }
        page.errorFlow.value = HttpException(403)
        runCurrent()
        warnings shouldBe emptyList()

        available.value = true
        runCurrent()
        warnings.size shouldBe 1
        available.value = false
        runCurrent()
        available.value = true
        runCurrent()
        warnings.size shouldBe 1

        available.value = false
        page.errorFlow.value = HttpException(403)
        runCurrent()
        warnings.size shouldBe 1
        available.value = true
        runCurrent()
        warnings.size shouldBe 2
    }

    @Test
    fun `cloudflare and generic error cooldowns keep their distinct handling`() {
        IOException("wrapper", HttpException(403)).isSourceAccessError() shouldBe true
        SourceRequestPausedException(60_000, rateLimited = true).isSourceAccessError() shouldBe true
        HttpException(403).apply { initCause(CloudflareChallengeException("challenge")) }
            .isSourceAccessError() shouldBe false
        SourceRequestPausedException(60_000).isSourceAccessError() shouldBe false
        HttpException(404).isSourceAccessError() shouldBe false
        HttpException(503).isSourceAccessError() shouldBe false
    }

    @Test
    fun `a warning rejected by a competing dialog is retried when the dialog becomes available`() = runTest {
        val page = ReaderPage(0).apply {
            errorFlow.value = HttpException(403)
            status = Page.State.ERROR
        }
        val restrictions = MutableStateFlow<SourceAccessRestriction?>(SourceAccessRestriction.Forbidden())
        val available = MutableStateFlow(true)
        var acceptsWarning = false
        var attempts = 0
        val warnings = mutableListOf<SourceAccessRestriction>()
        backgroundScope.launch {
            page.collectSourceAccessWarnings(restrictions, available) {
                attempts++
                if (acceptsWarning) warnings += it
                acceptsWarning
            }
        }
        runCurrent()
        warnings shouldBe emptyList()
        attempts shouldBe 1

        available.value = false
        runCurrent()
        acceptsWarning = true
        available.value = true
        runCurrent()
        warnings shouldBe listOf(SourceAccessRestriction.Forbidden())
        attempts shouldBe 2

        available.value = false
        runCurrent()
        available.value = true
        runCurrent()
        attempts shouldBe 2
    }
}
