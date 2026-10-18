package eu.kanade.tachiyomi.data.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.StatusBarNotification
import eu.kanade.tachiyomi.data.library.LibraryUpdateType
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateNotifier
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateNotifier
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.notify
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.episode.model.Episode

class LibraryUpdateNotificationTest {

    private val context = mockk<Context>()
    private val manager = mockk<NotificationManager>()

    @BeforeEach
    fun setUp() {
        mockkStatic("eu.kanade.tachiyomi.util.system.NotificationExtensionsKt")
        every { context.notificationManager } returns manager
        every { context.cancelNotification(any<Int>()) } just Runs
        every { context.cancelNotification(any<String>(), any()) } just Runs
        every { context.cancelNotification(null, any()) } just Runs
        every { context.notify(any(), any<String>(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("eu.kanade.tachiyomi.util.system.NotificationExtensionsKt")
    }

    @Test
    fun `dismissing anime leaves manga with the same id untouched`() {
        val animeTag = LibraryUpdateType.ANIME.notificationTag(1)
        val mangaTag = LibraryUpdateType.MANGA.notificationTag(1)
        every { manager.activeNotifications } returns arrayOf(
            notification(1, animeTag, Notifications.GROUP_NEW_EPISODES),
            notification(Notifications.ID_NEW_EPISODES, null, Notifications.GROUP_NEW_EPISODES),
            notification(1, mangaTag, Notifications.GROUP_NEW_CHAPTERS),
            notification(Notifications.ID_NEW_CHAPTERS, null, Notifications.GROUP_NEW_CHAPTERS),
        )

        NotificationReceiver.dismissNotification(context, 1, Notifications.ID_NEW_EPISODES, animeTag)

        verify(exactly = 1) { context.cancelNotification(animeTag, 1) }
        verify(exactly = 1) { context.cancelNotification(Notifications.ID_NEW_EPISODES) }
        verify(exactly = 0) { context.cancelNotification(mangaTag, any()) }
        verify(exactly = 0) { context.cancelNotification(Notifications.ID_NEW_CHAPTERS) }
    }

    @Test
    fun `dismissing one manga keeps its summary when another update remains`() {
        val firstTag = LibraryUpdateType.MANGA.notificationTag(1)
        val secondTag = LibraryUpdateType.MANGA.notificationTag(2)
        every { manager.activeNotifications } returns arrayOf(
            notification(1, firstTag, Notifications.GROUP_NEW_CHAPTERS),
            notification(2, secondTag, Notifications.GROUP_NEW_CHAPTERS),
            notification(Notifications.ID_NEW_CHAPTERS, null, Notifications.GROUP_NEW_CHAPTERS),
        )

        NotificationReceiver.dismissNotification(context, 1, Notifications.ID_NEW_CHAPTERS, firstTag)

        verify(exactly = 1) { context.cancelNotification(firstTag, 1) }
        verify(exactly = 0) { context.cancelNotification(Notifications.ID_NEW_CHAPTERS) }
        verify(exactly = 0) { context.cancelNotification(secondTag, any()) }
    }

    @Test
    fun `legacy fallback only dismisses untagged entries in the requested library`() {
        val animeTag = LibraryUpdateType.ANIME.notificationTag(1)
        every { manager.activeNotifications } returns arrayOf(
            notification(1, null, Notifications.GROUP_NEW_CHAPTERS),
            notification(Notifications.ID_NEW_CHAPTERS, null, Notifications.GROUP_NEW_CHAPTERS),
        )

        NotificationReceiver.dismissNotification(context, 1, Notifications.ID_NEW_EPISODES, animeTag)

        verify(exactly = 0) { context.cancelNotification(animeTag, 1) }
        verify(exactly = 0) { context.cancelNotification(null, 1) }
        verify(exactly = 0) { context.cancelNotification(Notifications.ID_NEW_CHAPTERS) }
    }

    @Test
    fun `legacy notification from the requested library can still be dismissed`() {
        val animeTag = LibraryUpdateType.ANIME.notificationTag(1)
        every { manager.activeNotifications } returns arrayOf(
            notification(1, null, Notifications.GROUP_NEW_EPISODES),
            notification(Notifications.ID_NEW_EPISODES, null, Notifications.GROUP_NEW_EPISODES),
        )

        NotificationReceiver.dismissNotification(context, 1, Notifications.ID_NEW_EPISODES, animeTag)

        verify(exactly = 1) { context.cancelNotification(null, 1) }
        verify(exactly = 1) { context.cancelNotification(Notifications.ID_NEW_EPISODES) }
    }

    @Test
    fun `progress cancellation and error notifications stay in their own library`() {
        val animeNotifier = AnimeLibraryUpdateNotifier(context, mockk(), mockk())
        val mangaNotifier = MangaLibraryUpdateNotifier(context, mockk(), mockk())
        val uri = mockk<Uri>()

        animeNotifier.cancelProgressNotification()
        animeNotifier.showUpdateErrorNotification(1, uri)

        verify(exactly = 1) { context.cancelNotification(Notifications.ID_ANIME_LIBRARY_PROGRESS) }
        verify(exactly = 0) { context.cancelNotification(Notifications.ID_LIBRARY_PROGRESS) }
        verify(exactly = 1) {
            context.notify(Notifications.ID_ANIME_LIBRARY_ERROR, Notifications.CHANNEL_LIBRARY_ERROR, any())
        }
        verify(exactly = 0) {
            context.notify(Notifications.ID_LIBRARY_ERROR, Notifications.CHANNEL_LIBRARY_ERROR, any())
        }

        mangaNotifier.cancelProgressNotification()
        mangaNotifier.showUpdateErrorNotification(1, uri)

        verify(exactly = 1) { context.cancelNotification(Notifications.ID_LIBRARY_PROGRESS) }
        verify(exactly = 1) {
            context.notify(Notifications.ID_LIBRARY_ERROR, Notifications.CHANNEL_LIBRARY_ERROR, any())
        }
    }

    @Test
    fun `anime download action dismisses the episode group`() {
        mockkObject(NotificationReceiver)
        try {
            val anime = Anime.create().copy(id = 1)
            val episodes = arrayOf(Episode.create())
            val pendingIntent = mockk<PendingIntent>()
            every {
                NotificationReceiver.downloadEpisodesPendingBroadcast(context, anime, episodes, any())
            } returns pendingIntent
            val notifier = AnimeLibraryUpdateNotifier(context, mockk(), mockk())

            notifier.downloadEpisodesIntent(anime, episodes) shouldBe pendingIntent

            verify(exactly = 1) {
                NotificationReceiver.downloadEpisodesPendingBroadcast(
                    context,
                    anime,
                    episodes,
                    Notifications.ID_NEW_EPISODES,
                )
            }
        } finally {
            unmockkObject(NotificationReceiver)
        }
    }

    @Test
    fun `reader pending intents distinguish full ids when request codes collide`() {
        mockkObject(ReaderActivity)
        mockkStatic(PendingIntent::class, Uri::class)
        try {
            val firstId = 1L
            val secondId = 1L shl 32
            firstId.hashCode() shouldBe secondId.hashCode()
            val firstIntent = mockk<Intent>()
            val secondIntent = mockk<Intent>()
            val firstData = slot<Uri>()
            val secondData = slot<Uri>()
            every { firstIntent.setData(capture(firstData)) } returns firstIntent
            every { secondIntent.setData(capture(secondData)) } returns secondIntent
            every { ReaderActivity.newIntent(context, firstId, any()) } returns firstIntent
            every { ReaderActivity.newIntent(context, secondId, any()) } returns secondIntent
            val firstUri = mockk<Uri>()
            val secondUri = mockk<Uri>()
            val parsedValues = mutableListOf<String>()
            every { Uri.parse(capture(parsedValues)) } returnsMany listOf(firstUri, secondUri)
            every { PendingIntent.getActivity(context, any(), any(), any()) } returns mockk()
            val chapter = Chapter.create().copy(id = 1)

            NotificationReceiver.openChapterPendingActivity(context, Manga.create().copy(id = firstId), chapter)
            NotificationReceiver.openChapterPendingActivity(context, Manga.create().copy(id = secondId), chapter)

            firstData.captured shouldBe firstUri
            secondData.captured shouldBe secondUri
            parsedValues shouldBe listOf(
                "aniyomi://library-update/${LibraryUpdateType.MANGA.notificationTag(firstId)}",
                "aniyomi://library-update/${LibraryUpdateType.MANGA.notificationTag(secondId)}",
            )
        } finally {
            unmockkObject(ReaderActivity)
            unmockkStatic(PendingIntent::class, Uri::class)
        }
    }

    private fun notification(id: Int, tag: String?, group: String): StatusBarNotification {
        val content = mockk<Notification>()
        every { content.group } returns group
        val notification = mockk<StatusBarNotification>()
        every { notification.id } returns id
        every { notification.tag } returns tag
        every { notification.groupKey } returns group
        every { notification.notification } returns content
        return notification
    }
}
