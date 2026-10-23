package eu.kanade.tachiyomi.extension

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.installer.PackageInstallerInstallerAnime
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.installer.PackageInstallerInstallerManga
import eu.kanade.tachiyomi.util.system.getUriSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PackageInstallerInstallerTest {
    private val originalInjekt = Injekt
    private val service = mockk<Service>(relaxed = true)
    private val animeManager = mockk<AnimeExtensionManager>(relaxed = true)
    private val mangaManager = mockk<MangaExtensionManager>(relaxed = true)
    private val packageInstaller = mockk<PackageInstaller>(relaxed = true)
    private val receiver = slot<BroadcastReceiver>()
    private val uri = mockk<Uri>()

    @BeforeEach
    fun setUp() {
        mockkStatic(LocalBroadcastManager::class, ContextCompat::class, PendingIntent::class)
        mockkStatic("eu.kanade.tachiyomi.util.system.ContextExtensionsKt")
        mockkConstructor(Intent::class, PackageInstaller.SessionParams::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<PackageInstaller.SessionParams>().setSize(any()) } returns Unit
        every { anyConstructed<PackageInstaller.SessionParams>().setRequireUserAction(any()) } returns Unit
        every { LocalBroadcastManager.getInstance(service) } returns mockk(relaxed = true)
        every { ContextCompat.registerReceiver(service, capture(receiver), any(), any()) } returns null
        every { service.packageManager.packageInstaller } returns packageInstaller
        every { service.getUriSize(uri) } returns 1L
        every { service.contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(byteArrayOf(0)) }
        every { packageInstaller.createSession(any()) } returnsMany listOf(101, 102)
        every { packageInstaller.openSession(any()) } answers {
            mockk<PackageInstaller.Session>(relaxed = true) {
                every { openWrite(any(), any(), any()) } answers { ByteArrayOutputStream() }
            }
        }
        every { PendingIntent.getBroadcast(service, any(), any(), any()) } returns mockk(relaxed = true)
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(animeManager)
        Injekt.addSingleton(mangaManager)
    }

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
        unmockkConstructor(Intent::class, PackageInstaller.SessionParams::class)
        unmockkStatic("eu.kanade.tachiyomi.util.system.ContextExtensionsKt")
        unmockkStatic(LocalBroadcastManager::class, ContextCompat::class, PendingIntent::class)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `late result from an earlier session does not finish the next install`(anime: Boolean) {
        if (anime) {
            val installer = PackageInstallerInstallerAnime(service)
            installer.addToQueue(1, uri)
            installer.addToQueue(2, uri)
        } else {
            val installer = PackageInstallerInstallerManga(service)
            installer.addToQueue(1, uri)
            installer.addToQueue(2, uri)
        }
        verify(exactly = 1) { packageInstaller.createSession(any()) }

        deliver(101, PackageInstaller.STATUS_SUCCESS)
        verify(exactly = 2) { packageInstaller.createSession(any()) }
        deliver(101, PackageInstaller.STATUS_FAILURE_ABORTED)
        deliver(-1, PackageInstaller.STATUS_FAILURE)

        if (anime) {
            verify(exactly = 0) { animeManager.updateInstallStep(2, any()) }
        } else {
            verify(exactly = 0) { mangaManager.updateInstallStep(2, any()) }
        }
        verify(exactly = 0) { service.stopSelf() }

        deliver(102, PackageInstaller.STATUS_SUCCESS)
        if (anime) {
            verify(exactly = 1) { animeManager.updateInstallStep(2, InstallStep.Installed) }
        } else {
            verify(exactly = 1) { mangaManager.updateInstallStep(2, InstallStep.Installed) }
        }
        verify(exactly = 1) { service.stopSelf() }
    }

    private fun deliver(sessionId: Int, status: Int) {
        val intent = mockk<Intent>()
        every { intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) } returns sessionId
        every { intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) } returns status
        receiver.captured.onReceive(service, intent)
    }
}
