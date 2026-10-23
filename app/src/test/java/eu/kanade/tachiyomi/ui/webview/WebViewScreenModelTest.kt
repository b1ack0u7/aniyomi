package eu.kanade.tachiyomi.ui.webview

import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Headers
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager

class WebViewScreenModelTest {

    private val mangaManager = mockk<MangaSourceManager>()
    private val animeManager = mockk<AnimeSourceManager>()
    private val network = mockk<NetworkHelper>()
    private val sourceHeaders = Headers.headersOf("Origin", "https://source.test", "X-Token", "secret")

    @Test
    fun `manga webview resolves manga headers without consulting anime with the same id`() {
        val source = mockk<HttpSource> {
            every { headers } returns sourceHeaders
            every { baseUrl } returns "https://manga.test"
        }
        every { mangaManager.get(42) } returns source

        val model = WebViewScreenModel(42, false, mangaManager, animeManager, network)

        model.headers shouldBe mapOf("X-Token" to "secret")
        model.headersHost shouldBe "manga.test"
        verify(exactly = 0) { animeManager.get(any()) }
    }

    @Test
    fun `anime webview resolves anime headers without consulting manga with the same id`() {
        val source = mockk<AnimeHttpSource> {
            every { headers } returns sourceHeaders
            every { baseUrl } returns "https://anime.test"
        }
        every { animeManager.get(42) } returns source

        val model = WebViewScreenModel(42, true, mangaManager, animeManager, network)

        model.headers shouldBe mapOf("X-Token" to "secret")
        model.headersHost shouldBe "anime.test"
        verify(exactly = 0) { mangaManager.get(any()) }
    }
}
