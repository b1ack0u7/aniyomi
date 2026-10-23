package eu.kanade.tachiyomi.extension.anime.installer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class InstallerAnimeTest {

    private val originalInjekt = Injekt
    private val service = mockk<Service>(relaxed = true)
    private val manager = mockk<AnimeExtensionManager>(relaxed = true)
    private val receiver = slot<BroadcastReceiver>()
    private val processed = mutableListOf<Long>()
    private val cancelled = mutableListOf<Long>()
    private var beforeCancel: (() -> Unit)? = null
    private lateinit var installer: InstallerAnime

    @BeforeEach
    fun setUp() {
        val broadcasts = mockk<LocalBroadcastManager>(relaxed = true)
        mockkStatic(LocalBroadcastManager::class)
        every { LocalBroadcastManager.getInstance(service) } returns broadcasts
        every { broadcasts.registerReceiver(capture(receiver), any()) } returns Unit
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(manager)
        installer = object : InstallerAnime(service) {
            override var ready = true
            override fun processEntry(entry: Entry) {
                processed += entry.downloadId
            }
            override fun cancelEntry(entry: Entry): Boolean {
                cancelled += entry.downloadId
                beforeCancel?.invoke()
                return true
            }
        }
        installer.addToQueue(1, mockk())
        installer.addToQueue(2, mockk())
    }

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
        unmockkStatic(LocalBroadcastManager::class)
    }

    @Test
    fun `cancelling an unrelated download leaves active and queued installs alone`() {
        cancel(3)
        cancelled shouldBe emptyList()
        processed shouldBe listOf(1L)
        installer.continueQueue(InstallStep.Installed)
        processed shouldBe listOf(1L, 2L)
        verify(exactly = 0) { manager.updateInstallStep(3, any()) }
    }

    @Test
    fun `cancelling a queued download preserves the active install`() {
        cancel(2)
        cancelled shouldBe listOf(2L)
        processed shouldBe listOf(1L)
        installer.continueQueue(InstallStep.Installed)
        processed shouldBe listOf(1L)
        verify { manager.updateInstallStep(2, InstallStep.Idle) }
    }

    @Test
    fun `cancelling the active download advances the queue`() {
        cancel(1)
        cancelled shouldBe listOf(1L)
        processed shouldBe listOf(1L, 2L)
        verify { manager.updateInstallStep(1, InstallStep.Idle) }
    }

    @Test
    fun `checking an empty queue keeps the service alive while an install is active`() {
        cancel(2)
        installer.checkQueue()
        verify(exactly = 0) { service.stopSelf() }
    }

    @Test
    fun `completion during cancellation preserves the next active install`() {
        beforeCancel = { installer.continueQueue(InstallStep.Installed) }

        cancel(1)
        installer.continueQueue(InstallStep.Installed)

        processed shouldBe listOf(1L, 2L)
        verify { manager.updateInstallStep(2, InstallStep.Installed) }
    }

    private fun cancel(downloadId: Long) {
        val intent = mockk<Intent>()
        every { intent.getLongExtra(any(), -1) } returns downloadId
        receiver.captured.onReceive(service, intent)
    }
}
