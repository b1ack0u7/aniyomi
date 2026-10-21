package eu.kanade.tachiyomi.ui.player.loader

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

class HosterLoaderDiagnosticsTest {

    @Test
    fun `diagnostics identify source and nested error types without exception messages`() {
        val error = IOException(
            "https://cdn.invalid/video?token=secret Authorization: Bearer credential",
            IllegalArgumentException("Cookie: session=private"),
        )

        videoResolutionFailureMessage(42L, error) shouldBe
            "Video resolution failed: sourceId=42, errors=IOException -> IllegalArgumentException"
    }

    @Test
    fun `cyclic exception causes produce a bounded diagnostic`() {
        val outer = IOException("secret outer")
        val inner = IllegalArgumentException("secret inner", outer)
        outer.initCause(inner)

        videoResolutionFailureMessage(42L, outer) shouldBe
            "Video resolution failed: sourceId=42, errors=IOException -> IllegalArgumentException"
    }

    @Test
    fun `cancellation escapes resolution in both player and download mode`() = runTest {
        listOf(false, true).forEach { propagateSourceErrors ->
            val source = mockk<AnimeHttpSource>()
            val video = Video(videoUrl = "https://video", initialized = false)
            val cancellation = CancellationException("Cancelled")
            coEvery { source.resolveVideo(video) } throws cancellation

            runCatching {
                HosterLoader.getResolvedVideo(source, video, propagateSourceErrors)
            }.exceptionOrNull() shouldBe cancellation
        }
    }

    @Test
    fun `ordinary resolution errors still return null and successful videos stay initialized`() = runTest {
        val source = mockk<AnimeHttpSource>(relaxed = true)
        val video = Video(videoUrl = "https://video", initialized = false)
        coEvery { source.resolveVideo(video) } throws IllegalArgumentException("secret")
        HosterLoader.getResolvedVideo(source, video) shouldBe null

        coEvery { source.resolveVideo(video) } returns video
        val resolved = HosterLoader.getResolvedVideo(source, video)
        resolved shouldBe video.copy(initialized = true)
        HosterLoader.getResolvedVideo(source, resolved!!) shouldBe resolved
        coVerify(exactly = 2) { source.resolveVideo(any()) }
    }
}
