package eu.kanade.tachiyomi.data.backup.create.creators

import dataanime.GetBackupHistoryByAnimeIds
import dataanime.GetCategoryOrdersByAnimeIds
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import java.util.Date
import dataanime.Anime_track as Anime_sync
import dataanime.Episode as Episodes

class AnimeBackupCreatorTest {

    private val handler = mockk<AnimeDatabaseHandler>()
    private val repository = mockk<AnimeRepository>()
    private val creator = AnimeBackupCreator(handler, repository)
    private val options = BackupOptions(chapters = false, categories = false, tracking = false, history = false)

    @Test
    fun `entries are requested lazily with a fixed upper bound and stop on cancellation`() = runBlocking<Unit> {
        val entries = (1L..100L).map { Anime.create().copy(id = it, title = "Title $it") }
        coEvery { repository.getMaxAnimeId() } returns 250L
        coEvery { repository.getBackupAnimeBatch(0, 250, 100, true) } returns entries

        creator(options).take(1).toList().single().title shouldBe "Title 1"

        coVerify(exactly = 1) { repository.getBackupAnimeBatch(any(), any(), any(), any()) }
    }

    @Test
    fun `paging preserves non library option and emits every batch once`() = runBlocking<Unit> {
        coEvery { repository.getMaxAnimeId() } returns 250L
        coEvery { repository.getBackupAnimeBatch(0, 250, 100, false) } returns listOf(
            Anime.create().copy(id = 1, title = "First"),
            Anime.create().copy(id = 150, title = "Second"),
        )
        coEvery { repository.getBackupAnimeBatch(150, 250, 100, false) } returns listOf(
            Anime.create().copy(id = 250, title = "Last"),
        )
        coEvery { repository.getBackupAnimeBatch(250, 250, 100, false) } returns emptyList()

        creator(options.copy(readEntries = false)).toList().map { it.title } shouldBe
            listOf("First", "Second", "Last")
        coVerify(exactly = 1) { repository.getMaxAnimeId() }
    }

    @Test
    fun `disabled library entries require no title or related queries`() = runBlocking<Unit> {
        creator(BackupOptions(libraryEntries = false)).toList() shouldBe emptyList()

        coVerify(exactly = 0) { repository.getMaxAnimeId() }
        coVerify(exactly = 0) { handler.awaitList<Any>(any(), any()) }
    }

    @Test
    fun `history without chapters retains URLs dates and groups by title`() = runBlocking<Unit> {
        coEvery { repository.getMaxAnimeId() } returns 2L
        coEvery { repository.getBackupAnimeBatch(0, 2, 100, true) } returns listOf(
            Anime.create().copy(id = 1),
            Anime.create().copy(id = 2),
        )
        coEvery { repository.getBackupAnimeBatch(2, 2, 100, true) } returns emptyList()
        coEvery { handler.awaitList<Any>(any(), any()) } returnsMany listOf(
            emptyList(),
            listOf(
                GetBackupHistoryByAnimeIds(2, "/second", Date(234)),
                GetBackupHistoryByAnimeIds(1, "/first", null),
            ),
        )

        val backup = creator(options.copy(history = true)).toList()

        backup[0].history shouldBe listOf(BackupAnimeHistory("/first", 0))
        backup[1].history shouldBe listOf(BackupAnimeHistory("/second", 234))
        backup.flatMap { it.episodes } shouldBe emptyList()
        coVerify(exactly = 2) { handler.awaitList<Any>(any(), any()) }
    }

    @Test
    fun `title metadata and parent season identity survive creation`() = runBlocking<Unit> {
        val entry = Anime.create().copy(
            id = 42,
            source = 99,
            url = "/title",
            title = "Saved title",
            artist = "Artist",
            author = "Author",
            description = "Description",
            genre = listOf("Adventure"),
            status = 2,
            thumbnailUrl = "/cover",
            favorite = true,
            dateAdded = 123,
            viewerFlags = 456,
            lastModifiedAt = 789,
            favoriteModifiedAt = 987,
            version = 12,
            backgroundUrl = "/background",
            episodeFlags = 654,
            parentId = 21,
            fetchType = FetchType.Seasons,
            seasonFlags = 321,
            seasonNumber = 2.5,
            seasonSourceOrder = 4,
        )
        coEvery { repository.getMaxAnimeId() } returns 42L
        coEvery { repository.getBackupAnimeBatch(0, 42, 100, true) } returns listOf(entry)
        coEvery { repository.getBackupAnimeBatch(42, 42, 100, true) } returns emptyList()

        val backup = creator(options).toList().single()
        val restored = backup.getAnimeImpl()

        restored.url shouldBe entry.url
        restored.title shouldBe entry.title
        restored.source shouldBe entry.source
        restored.artist shouldBe entry.artist
        restored.author shouldBe entry.author
        restored.description shouldBe entry.description
        restored.genre shouldBe entry.genre
        restored.status shouldBe entry.status
        restored.thumbnailUrl shouldBe entry.thumbnailUrl
        restored.favorite shouldBe entry.favorite
        restored.dateAdded shouldBe entry.dateAdded
        restored.viewerFlags shouldBe entry.viewerFlags
        restored.lastModifiedAt shouldBe entry.lastModifiedAt
        restored.favoriteModifiedAt shouldBe entry.favoriteModifiedAt
        restored.version shouldBe entry.version
        restored.updateStrategy shouldBe entry.updateStrategy
        restored.episodeFlags shouldBe entry.episodeFlags
        restored.backgroundUrl shouldBe entry.backgroundUrl
        backup.id shouldBe entry.id
        backup.parentId shouldBe entry.parentId
        restored.fetchType shouldBe entry.fetchType
        restored.seasonFlags shouldBe entry.seasonFlags
        restored.seasonNumber shouldBe entry.seasonNumber
        restored.seasonSourceOrder shouldBe entry.seasonSourceOrder
    }

    @Test
    fun `batched related rows keep progress categories tracking and title associations`() = runBlocking<Unit> {
        val child = Episodes(
            id = 10,
            anime_id = 1,
            remote_url = "/first/episode",
            remote_name = "Saved episode",
            remote_scanlator = "Group",
            user_seen = true,
            user_bookmark = true,
            user_last_second_seen = 23,
            remote_total_seconds = 1440,
            remote_episode_number = 2.5,
            remote_source_order = 3,
            state_date_fetch = 45,
            remote_date_upload = 67,
            last_modified_at = 89,
            version = 4,
            is_syncing = 0,
            remote_summary = "Summary",
            remote_preview = "/preview",
            user_fillermark = true,
        )
        val track = Anime_sync(
            id = 100,
            anime_id = 2,
            sync_id = 1,
            remote_id = 12345678901,
            library_id = null,
            title = "Tracked title",
            last_episode_seen = 2.5,
            total_episodes = 12,
            status = 3,
            score = 8.5,
            remote_url = "/tracker",
            start_date = 123,
            finish_date = 456,
            private_ = true,
        )
        coEvery { repository.getMaxAnimeId() } returns 2L
        coEvery { repository.getBackupAnimeBatch(0, 2, 100, true) } returns listOf(
            Anime.create().copy(id = 1),
            Anime.create().copy(id = 2),
        )
        coEvery { repository.getBackupAnimeBatch(2, 2, 100, true) } returns emptyList()
        coEvery { handler.awaitList<Any>(any(), any()) } returnsMany listOf(
            listOf(child),
            listOf(GetCategoryOrdersByAnimeIds(2, 7), GetCategoryOrdersByAnimeIds(1, 3)),
            listOf(track),
        )

        val backup = creator(options.copy(chapters = true, categories = true, tracking = true)).toList()

        val savedChild = backup[0].episodes.single()
        savedChild.url shouldBe child.remote_url
        savedChild.name shouldBe child.remote_name
        savedChild.scanlator shouldBe child.remote_scanlator
        savedChild.seen shouldBe true
        savedChild.bookmark shouldBe true
        savedChild.lastSecondSeen shouldBe 23L
        savedChild.episodeNumber shouldBe 2.5F
        savedChild.sourceOrder shouldBe 3L
        savedChild.dateFetch shouldBe 45L
        savedChild.dateUpload shouldBe 67L
        savedChild.lastModifiedAt shouldBe 89L
        savedChild.version shouldBe 4L
        savedChild.totalSeconds shouldBe 1440L
        savedChild.summary shouldBe child.remote_summary
        savedChild.previewUrl shouldBe child.remote_preview
        savedChild.fillermark shouldBe true
        backup[1].episodes shouldBe emptyList()
        backup[0].categories shouldBe listOf(3L)
        backup[1].categories shouldBe listOf(7L)
        backup[0].tracking shouldBe emptyList()
        val savedTrack = backup[1].tracking.single()
        savedTrack.mediaId shouldBe track.remote_id
        savedTrack.libraryId shouldBe 0L
        savedTrack.lastEpisodeSeen shouldBe 2.5F
        savedTrack.totalEpisodes shouldBe 12
        savedTrack.private shouldBe true
        coVerify(exactly = 3) { handler.awaitList<Any>(any(), any()) }
    }
}
