package eu.kanade.tachiyomi.data.download.anime

import android.content.Context
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AnimeDownloadManagerStartTest {

    private val context = mockk<Context>()
    private val downloader = mockk<AnimeDownloader>(relaxed = true)
    private val queue = MutableStateFlow<List<AnimeDownload>>(emptyList())
    private val manager = AnimeDownloadManager(
        context = context,
        storageManager = mockk(),
        provider = mockk(),
        cache = mockk(),
        getCategories = mockk(),
        sourceManager = mockk(),
        downloadPreferences = mockk(),
        downloader = downloader,
        pendingDeleter = mockk(),
    )

    @BeforeEach
    fun setUp() {
        mockkObject(AnimeDownloadJob)
        every { AnimeDownloadJob.start(context, any()) } just Runs
        every { AnimeDownloadJob.isRunning(context) } returns false
        every { downloader.isRunning } returns false
        every { downloader.queueState } returns queue
        every { downloader.prepareAutomaticStart() } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AnimeDownloadJob)
    }

    @Test
    fun `automatic start preserves source challenges and uses automatic worker mode`() {
        manager.startDownloadsAutomatically()

        verify(exactly = 1) { AnimeDownloadJob.start(context, automatic = true) }
        verify(exactly = 0) { downloader.resumeChallenges() }
    }

    @Test
    fun `automatic start cannot reactivate a user paused queue`() {
        every { downloader.prepareAutomaticStart() } returns false

        manager.startDownloadsAutomatically()

        verify(exactly = 0) { AnimeDownloadJob.start(context, any()) }
        verify(exactly = 0) { downloader.resumeChallenges() }
    }

    @Test
    fun `manual start explicitly releases challenges and permits retrying errors`() {
        manager.startDownloads()

        verify(exactly = 1) { downloader.resumeChallenges() }
        verify(exactly = 1) { AnimeDownloadJob.start(context, automatic = false) }
    }

    @Test
    fun `returning a preloaded item to the queue uses automatic start`() {
        manager.addDownloadsToStartOfQueue(listOf(mockk()))

        verify(exactly = 1) { AnimeDownloadJob.start(context, automatic = true) }
        verify(exactly = 0) { downloader.resumeChallenges() }
    }

    @Test
    fun `pause action records user intent separately from internal pauses`() {
        manager.pauseDownloads()

        verify(exactly = 1) { downloader.pauseByUser() }
        verify(exactly = 1) { downloader.stop() }
    }

    @Test
    fun `network suspension does not record a user pause or reset errors`() {
        manager.suspendForNetwork("offline")

        verify(exactly = 1) { downloader.suspendForNetwork("offline") }
        verify(exactly = 0) { downloader.pauseByUser() }
        verify(exactly = 0) { downloader.stop(any()) }
        verify(exactly = 0) { downloader.resumeChallenges() }
    }
}
