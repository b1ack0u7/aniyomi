package eu.kanade.tachiyomi.data.backup.create.creators

import data.GetBackupHistoryByMangaIds
import data.GetCategoryOrdersByMangaIds
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository
import java.util.Date
import data.Chapter as Chapters
import data.Excluded_scanlator as Excluded_scanlators
import data.Manga_track as Manga_sync

class MangaBackupCreatorTest {

    private val handler = mockk<MangaDatabaseHandler>()
    private val repository = mockk<MangaRepository>()
    private val creator = MangaBackupCreator(handler, repository)
    private val options = BackupOptions(chapters = false, categories = false, tracking = false, history = false)

    @Test
    fun `entries are requested lazily with a fixed upper bound and stop on cancellation`() = runBlocking<Unit> {
        val entries = (1L..100L).map { Manga.create().copy(id = it, title = "Title $it") }
        coEvery { repository.getMaxMangaId() } returns 250L
        coEvery { repository.getBackupMangaBatch(0, 250, 100, true) } returns entries
        coEvery { handler.awaitList<Any>(any(), any()) } returns emptyList()

        creator(options).take(1).toList().single().title shouldBe "Title 1"

        coVerify(exactly = 1) { repository.getBackupMangaBatch(any(), any(), any(), any()) }
    }

    @Test
    fun `paging preserves non library option and emits every batch once`() = runBlocking<Unit> {
        coEvery { repository.getMaxMangaId() } returns 250L
        coEvery { repository.getBackupMangaBatch(0, 250, 100, false) } returns listOf(
            Manga.create().copy(id = 1, title = "First"),
            Manga.create().copy(id = 150, title = "Second"),
        )
        coEvery { repository.getBackupMangaBatch(150, 250, 100, false) } returns listOf(
            Manga.create().copy(id = 250, title = "Last"),
        )
        coEvery { repository.getBackupMangaBatch(250, 250, 100, false) } returns emptyList()
        coEvery { handler.awaitList<Any>(any(), any()) } returns emptyList()

        creator(options.copy(readEntries = false)).toList().map { it.title } shouldBe
            listOf("First", "Second", "Last")
        coVerify(exactly = 1) { repository.getMaxMangaId() }
    }

    @Test
    fun `disabled library entries require no title or related queries`() = runBlocking<Unit> {
        creator(BackupOptions(libraryEntries = false)).toList() shouldBe emptyList()

        coVerify(exactly = 0) { repository.getMaxMangaId() }
        coVerify(exactly = 0) { handler.awaitList<Any>(any(), any()) }
    }

    @Test
    fun `history without chapters retains URLs dates and groups by title`() = runBlocking<Unit> {
        coEvery { repository.getMaxMangaId() } returns 2L
        coEvery { repository.getBackupMangaBatch(0, 2, 100, true) } returns listOf(
            Manga.create().copy(id = 1),
            Manga.create().copy(id = 2),
        )
        coEvery { repository.getBackupMangaBatch(2, 2, 100, true) } returns emptyList()
        coEvery { handler.awaitList<Any>(any(), any()) } returnsMany listOf(
            emptyList(),
            listOf(
                GetBackupHistoryByMangaIds(2, "/second", Date(234), 600),
                GetBackupHistoryByMangaIds(1, "/first", null, 900),
            ),
            emptyList(),
        )

        val backup = creator(options.copy(history = true)).toList()

        backup[0].history shouldBe listOf(BackupHistory("/first", 0, 900))
        backup[1].history shouldBe listOf(BackupHistory("/second", 234, 600))
        backup.flatMap { it.chapters } shouldBe emptyList()
        coVerify(exactly = 3) { handler.awaitList<Any>(any(), any()) }
    }

    @Test
    fun `title metadata survive creation`() = runBlocking<Unit> {
        val entry = Manga.create().copy(
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
            chapterFlags = 654,
            memo = JsonObject(
                mapOf("custom" to JsonPrimitive("value")),
            ),
        )
        coEvery { repository.getMaxMangaId() } returns 42L
        coEvery { repository.getBackupMangaBatch(0, 42, 100, true) } returns listOf(entry)
        coEvery { repository.getBackupMangaBatch(42, 42, 100, true) } returns emptyList()
        coEvery { handler.awaitList<Any>(any(), any()) } returns emptyList()

        val backup = creator(options).toList().single()
        val restored = backup.getMangaImpl()

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
        restored.chapterFlags shouldBe entry.chapterFlags
        restored.memo shouldBe entry.memo
    }

    @Test
    fun `batched related rows keep progress categories tracking and title associations`() = runBlocking<Unit> {
        val child = Chapters(
            id = 10,
            manga_id = 1,
            remote_url = "/first/chapter",
            remote_name = "Saved chapter",
            remote_scanlator = "Group",
            user_read = true,
            user_bookmark = true,
            user_last_page_read = 23,
            remote_chapter_number = 2.5,
            remote_source_order = 3,
            state_date_fetch = 45,
            remote_date_upload = 67,
            last_modified_at = 89,
            version = 4,
            is_syncing = 0,
            remote_memo = JsonObject(mapOf("custom" to JsonPrimitive("child"))),
        )
        val track = Manga_sync(
            id = 100,
            manga_id = 2,
            sync_id = 1,
            remote_id = 12345678901,
            library_id = null,
            title = "Tracked title",
            last_chapter_read = 2.5,
            total_chapters = 12,
            status = 3,
            score = 8.5,
            remote_url = "/tracker",
            start_date = 123,
            finish_date = 456,
            private_ = true,
        )
        coEvery { repository.getMaxMangaId() } returns 2L
        coEvery { repository.getBackupMangaBatch(0, 2, 100, true) } returns listOf(
            Manga.create().copy(id = 1),
            Manga.create().copy(id = 2),
        )
        coEvery { repository.getBackupMangaBatch(2, 2, 100, true) } returns emptyList()
        coEvery { handler.awaitList<Any>(any(), any()) } returnsMany listOf(
            listOf(child),
            listOf(GetCategoryOrdersByMangaIds(2, 7), GetCategoryOrdersByMangaIds(1, 3)),
            listOf(track),
            listOf(Excluded_scanlators(2, "Excluded group")),
        )

        val backup = creator(options.copy(chapters = true, categories = true, tracking = true)).toList()

        val savedChild = backup[0].chapters.single()
        savedChild.url shouldBe child.remote_url
        savedChild.name shouldBe child.remote_name
        savedChild.scanlator shouldBe child.remote_scanlator
        savedChild.read shouldBe true
        savedChild.bookmark shouldBe true
        savedChild.lastPageRead shouldBe 23L
        savedChild.chapterNumber shouldBe 2.5F
        savedChild.sourceOrder shouldBe 3L
        savedChild.dateFetch shouldBe 45L
        savedChild.dateUpload shouldBe 67L
        savedChild.lastModifiedAt shouldBe 89L
        savedChild.version shouldBe 4L
        savedChild.toChapterImpl().memo shouldBe child.remote_memo
        backup[1].chapters shouldBe emptyList()
        backup[0].categories shouldBe listOf(3L)
        backup[1].categories shouldBe listOf(7L)
        backup[0].tracking shouldBe emptyList()
        val savedTrack = backup[1].tracking.single()
        savedTrack.mediaId shouldBe track.remote_id
        savedTrack.libraryId shouldBe 0L
        savedTrack.lastChapterRead shouldBe 2.5F
        savedTrack.totalChapters shouldBe 12
        savedTrack.private shouldBe true
        backup[0].excludedScanlators shouldBe emptyList()
        backup[1].excludedScanlators shouldBe listOf("Excluded group")
        coVerify(exactly = 4) { handler.awaitList<Any>(any(), any()) }
    }
}
