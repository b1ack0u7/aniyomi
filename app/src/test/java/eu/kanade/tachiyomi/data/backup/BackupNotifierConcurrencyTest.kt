package eu.kanade.tachiyomi.data.backup

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BackupNotifierConcurrencyTest {
    private val originalInjekt = Injekt
    private val context = mockk<Context>()
    private val builder = mockk<NotificationCompat.Builder>(relaxed = true)
    private val text = AtomicReference("")
    private val builds = AtomicInteger()
    private val firstBuildEntered = CountDownLatch(1)
    private val releaseFirstBuild = CountDownLatch(1)
    private val secondMutation = CountDownLatch(1)
    private val snapshots = Collections.synchronizedMap(IdentityHashMap<Notification, String>())
    private val published = ConcurrentLinkedQueue<Notification>()

    @BeforeEach
    fun setUp() {
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton<SecurityPreferences>(
            mockk {
                every { hideNotificationContent().get() } returns false
            },
        )
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        mockkStatic("eu.kanade.tachiyomi.util.system.NotificationExtensionsKt")
        mockkObject(NotificationReceiver.Companion)
        every { context.stringResource(any()) } returns "Restore"
        every { context.notificationBuilder(any(), any()) } returns builder
        every { NotificationReceiver.cancelRestorePendingBroadcast(any(), any()) } returns mockk()
        every { builder.setContentText(any()) } answers {
            text.set(firstArg<CharSequence>().toString())
            if (firstBuildEntered.count == 0L) secondMutation.countDown()
            builder
        }
        every { builder.build() } answers {
            val number = builds.incrementAndGet()
            if (number == 1) {
                firstBuildEntered.countDown()
                check(releaseFirstBuild.await(5, TimeUnit.SECONDS))
            }
            mockk<Notification>(name = "snapshot-$number").also { snapshots[it] = text.get() }
        }
        every { context.notify(any<Int>(), any<Notification>()) } answers {
            published.add(lastArg<Notification>())
            Unit
        }
    }

    @AfterEach
    fun tearDown() {
        releaseFirstBuild.countDown()
        unmockkObject(NotificationReceiver.Companion)
        unmockkStatic("eu.kanade.tachiyomi.util.system.NotificationExtensionsKt")
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        Injekt = originalInjekt
    }

    @Test
    fun `concurrent manga and anime progress cannot mutate a notification being built`() {
        val notifier = BackupNotifier(context)
        assertSerializedSnapshots(
            notifier = notifier,
            firstContent = "Manga",
            secondContent = "Anime",
            firstCall = { notifier.showRestoreProgress("Manga", 1, 2) },
            secondCall = { notifier.showRestoreProgress("Anime", 2, 2) },
        )
    }

    @Test
    fun `foreground startup consumes its snapshot without rebuilding an escaped mutable builder`() {
        val worker = BackupRestoreJob(context, mockk<WorkerParameters>())
        val notifier = BackupRestoreJob::class.java.getDeclaredField("notifier")
            .apply { isAccessible = true }
            .get(worker) as BackupNotifier
        assertSerializedSnapshots(
            notifier = notifier,
            firstContent = "",
            secondContent = "Manga",
            firstCall = { runBlocking { worker.getForegroundInfo().notification } },
            secondCall = { notifier.showRestoreProgress("Manga", 1, 1) },
        )
    }

    private fun assertSerializedSnapshots(
        notifier: BackupNotifier,
        firstContent: String,
        secondContent: String,
        firstCall: () -> Notification,
        secondCall: () -> Notification,
    ) {
        val first = FutureTask(Callable { firstCall() })
        val secondStarted = CountDownLatch(1)
        val second = FutureTask(
            Callable {
                secondStarted.countDown()
                secondCall()
            },
        )
        val firstThread = Thread(first, "first-restore-progress")
        val secondThread = Thread(second, "second-restore-progress")
        try {
            firstThread.start()
            firstBuildEntered.await(5, TimeUnit.SECONDS) shouldBe true
            secondThread.start()
            secondStarted.await(5, TimeUnit.SECONDS) shouldBe true
            val threadBean = Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean").invoke(null)
            val getThreadInfo = Class.forName("java.lang.management.ThreadMXBean")
                .getMethod("getThreadInfo", Long::class.javaPrimitiveType)
            val infoType = Class.forName("java.lang.management.ThreadInfo")
            val getLock = infoType.getMethod("getLockInfo")
            val getOwner = infoType.getMethod("getLockOwnerId")
            val getIdentity = Class.forName("java.lang.management.LockInfo").getMethod("getIdentityHashCode")
            fun blockedLock(): Pair<Int, Long>? {
                val info = getThreadInfo.invoke(threadBean, secondThread.id) ?: return null
                val lock = getLock.invoke(info) ?: return null
                return (getIdentity.invoke(lock) as Int) to (getOwner.invoke(info) as Long)
            }
            val notifierIdentity = System.identityHashCode(notifier)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (blockedLock()?.first != notifierIdentity &&
                secondMutation.count != 0L &&
                secondThread.isAlive &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }
            secondThread.state shouldBe Thread.State.BLOCKED
            blockedLock() shouldBe (notifierIdentity to firstThread.id)
            secondMutation.count shouldBe 1L
            text.get() shouldBe firstContent
            releaseFirstBuild.countDown()
            val firstSnapshot = first.get(5, TimeUnit.SECONDS)
            val secondSnapshot = second.get(5, TimeUnit.SECONDS)
            (firstSnapshot !== secondSnapshot) shouldBe true
            snapshots[firstSnapshot] shouldBe firstContent
            snapshots[secondSnapshot] shouldBe secondContent
            published.toList() shouldBe listOf(firstSnapshot, secondSnapshot)
            verify(exactly = 2) { builder.build() }
        } finally {
            releaseFirstBuild.countDown()
            firstThread.join(5000)
            secondThread.join(5000)
        }
    }
}
