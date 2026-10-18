package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
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

class MangaDownloadManagerStartTest {

    private val context = mockk<Context>()
    private val downloader = mockk<MangaDownloader>(relaxed = true)
    private val queue = MutableStateFlow<List<MangaDownload>>(emptyList())
    private val manager = MangaDownloadManager(
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
        mockkObject(MangaDownloadJob)
        every { MangaDownloadJob.start(context, any()) } just Runs
        every { MangaDownloadJob.isRunning(context) } returns false
        every { downloader.isRunning } returns false
        every { downloader.queueState } returns queue
        every { downloader.prepareAutomaticStart() } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(MangaDownloadJob)
    }

    @Test
    fun `automatic start preserves source challenges and uses automatic worker mode`() {
        manager.startDownloadsAutomatically()

        verify(exactly = 1) { MangaDownloadJob.start(context, automatic = true) }
        verify(exactly = 0) { downloader.resumeChallenges() }
    }

    @Test
    fun `automatic start cannot reactivate a user paused queue`() {
        every { downloader.prepareAutomaticStart() } returns false

        manager.startDownloadsAutomatically()

        verify(exactly = 0) { MangaDownloadJob.start(context, any()) }
        verify(exactly = 0) { downloader.resumeChallenges() }
    }

    @Test
    fun `manual start explicitly releases challenges and permits retrying errors`() {
        manager.startDownloads()

        verify(exactly = 1) { downloader.resumeChallenges() }
        verify(exactly = 1) { MangaDownloadJob.start(context, automatic = false) }
    }

    @Test
    fun `returning a preloaded item to the queue uses automatic start`() {
        manager.addDownloadsToStartOfQueue(listOf(mockk()))

        verify(exactly = 1) { MangaDownloadJob.start(context, automatic = true) }
        verify(exactly = 0) { downloader.resumeChallenges() }
    }

    @Test
    fun `pause action records user intent separately from internal pauses`() {
        manager.pauseDownloads()

        verify(exactly = 1) { downloader.pauseByUser() }
        verify(exactly = 1) { downloader.stop() }
    }
}
