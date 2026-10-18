package eu.kanade.tachiyomi.ui.player.loader

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.UnknownHostException

class HosterLoaderSourceErrorsTest {

    @Test
    fun `download discovery propagates challenges and retryable hoster failures`() = runTest {
        val source = source()
        val hoster = Hoster(hosterName = "source")

        sourceErrors().forEach { error ->
            coEvery { source.getVideoList(hoster) } throws error

            runCatching {
                EpisodeLoader.loadHosterVideos(source, hoster, propagateSourceErrors = true)
            }.exceptionOrNull() shouldBe error
        }
    }

    @Test
    fun `download resolution propagates challenges and retryable failures`() = runTest {
        val source = source()
        val video = Video(videoUrl = "https://video", initialized = false)

        sourceErrors().forEach { error ->
            coEvery { source.resolveVideo(video) } throws error

            runCatching {
                HosterLoader.getResolvedVideo(source, video, propagateSourceErrors = true)
            }.exceptionOrNull() shouldBe error
        }
    }

    @Test
    fun `player defaults still expose error states and null for source failures`() = runTest {
        val source = source()
        val hoster = Hoster(hosterName = "source")
        val video = Video(videoUrl = "https://video", initialized = false)

        sourceErrors().forEach { error ->
            coEvery { source.getVideoList(hoster) } throws error
            coEvery { source.resolveVideo(video) } throws error

            EpisodeLoader.loadHosterVideos(source, hoster) shouldBe HosterState.Error("source")
            HosterLoader.getResolvedVideo(source, video) shouldBe null
        }
    }

    @Test
    fun `download discovery keeps parse and permanent http errors as fallback candidates`() = runTest {
        val source = source()
        val hoster = Hoster(hosterName = "broken")
        val video = Video(videoUrl = "https://broken", initialized = false)

        listOf(IllegalArgumentException("Invalid response"), HttpException(404), IOException("Invalid local file"))
            .forEach { error ->
                coEvery { source.getVideoList(hoster) } throws error
                coEvery { source.resolveVideo(video) } throws error

                EpisodeLoader.loadHosterVideos(source, hoster, propagateSourceErrors = true) shouldBe
                    HosterState.Error("broken")
                HosterLoader.getResolvedVideo(source, video, propagateSourceErrors = true) shouldBe null
            }
    }

    @Test
    fun `best video forwards propagation to hoster discovery`() = runTest {
        val source = source()
        val hoster = Hoster(hosterName = "source")
        val error = IOException("Wrapped challenge", CloudflareChallengeException("Challenge"))
        coEvery { source.getVideoList(hoster) } throws error

        runCatching {
            HosterLoader.getBestVideo(source, listOf(hoster), propagateSourceErrors = true)
        }.exceptionOrNull() shouldBe error
    }

    @Test
    fun `best video forwards propagation to preferred and ordinary resolution`() = runTest {
        listOf(false, true).forEach { preferred ->
            val source = source()
            val video = Video(videoUrl = "https://video", preferred = preferred, initialized = false)
            val hoster = Hoster(videoList = listOf(video))
            val error = HttpException(429)
            coEvery { source.resolveVideo(video) } throws error

            runCatching {
                HosterLoader.getBestVideo(source, listOf(hoster), propagateSourceErrors = true)
            }.exceptionOrNull() shouldBe error
        }
    }

    @Test
    fun `best video can fall back after a parse error in download mode`() = runTest {
        val source = source()
        val broken = Hoster(hosterName = "broken")
        val available = Hoster(hosterName = "available")
        val video = Video(videoUrl = "https://available", initialized = true)
        coEvery { source.getVideoList(broken) } throws IllegalArgumentException("Invalid response")
        coEvery { source.getVideoList(available) } returns listOf(video)

        HosterLoader.getBestVideo(source, listOf(broken, available), propagateSourceErrors = true) shouldBe video
    }

    @Test
    fun `best video can fall back after a resolution parse error in download mode`() = runTest {
        val source = source()
        val broken = Video(videoUrl = "https://broken", initialized = false)
        val available = Video(videoUrl = "https://available", initialized = true)
        val hoster = Hoster(videoList = listOf(broken, available))
        coEvery { source.resolveVideo(broken) } throws IllegalArgumentException("Invalid response")

        HosterLoader.getBestVideo(source, listOf(hoster), propagateSourceErrors = true) shouldBe available
    }

    @Test
    fun `player best video still falls back after a challenge`() = runTest {
        val source = source()
        val blocked = Hoster(hosterName = "blocked")
        val available = Hoster(hosterName = "available")
        val video = Video(videoUrl = "https://available", initialized = true)
        coEvery { source.getVideoList(blocked) } throws CloudflareChallengeException("Challenge")
        coEvery { source.getVideoList(available) } returns listOf(video)

        HosterLoader.getBestVideo(source, listOf(blocked, available)) shouldBe video
    }

    private fun source(): AnimeHttpSource {
        val source = mockk<AnimeHttpSource>()
        every { with(source) { any<List<Video>>().sortVideos() } } answers { firstArg<List<Video>>() }
        return source
    }

    private fun sourceErrors(): List<Exception> = listOf(
        CloudflareChallengeException("Challenge"),
        IOException("Wrapped challenge", CloudflareChallengeException("Challenge")),
        HttpException(429),
        HttpException(503),
        UnknownHostException("source.invalid"),
    )
}
