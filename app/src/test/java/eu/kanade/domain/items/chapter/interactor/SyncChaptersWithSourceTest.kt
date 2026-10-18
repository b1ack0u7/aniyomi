package eu.kanade.domain.items.chapter.interactor

import eu.kanade.domain.entries.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences

class SyncChaptersWithSourceTest {

    @Test
    fun `source changes persist before updating the fetch interval`() = runTest {
        val fixture = Fixture()

        val added = fixture.sync.await(listOf(fixture.remote), fixture.entry, fixture.source)

        added.single().id shouldBe 10L
        coVerifyOrder {
            fixture.repository.syncChapters(any(), any(), listOf(5L))
            fixture.updateEntry.awaitUpdateFetchIntervalAndLastUpdate(fixture.entry, any(), any())
        }
    }

    @Test
    fun `persistence failures propagate without advancing the fetch interval`() = runTest {
        val fixture = Fixture()
        val failure = IllegalStateException("write failed")
        coEvery { fixture.repository.syncChapters(any(), any(), any()) } throws failure

        val result = runCatching { fixture.sync.await(listOf(fixture.remote), fixture.entry, fixture.source) }

        result.exceptionOrNull() shouldBe failure
        coVerify(exactly = 0) { fixture.updateEntry.awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any()) }
    }

    @Test
    fun `persistence cancellation propagates without advancing the fetch interval`() = runTest {
        val fixture = Fixture()
        val cancellation = CancellationException("cancelled during write")
        coEvery { fixture.repository.syncChapters(any(), any(), any()) } throws cancellation

        val result = runCatching { fixture.sync.await(listOf(fixture.remote), fixture.entry, fixture.source) }

        result.exceptionOrNull() shouldBe cancellation
        coVerify(exactly = 0) { fixture.updateEntry.awaitUpdateFetchIntervalAndLastUpdate(any(), any(), any()) }
    }

    private class Fixture {
        val repository = mockk<ChapterRepository>()
        val updateEntry = mockk<UpdateManga>(relaxed = true)
        val entry = Manga.create().copy(id = 1, title = "Entry", source = 1)
        val source = mockk<MangaSource>()
        val remote = SChapter.create().apply {
            url = "/new"
            name = "Chapter 2"
            chapter_number = 2f
        }
        private val getItems = mockk<GetChaptersByMangaId>()
        private val preferences = mockk<LibraryPreferences>(relaxed = true)
        private val getExcludedScanlators = mockk<GetExcludedScanlators>()
        val sync = SyncChaptersWithSource(
            downloadManager = mockk(),
            downloadProvider = mockk(),
            chapterRepository = repository,
            shouldUpdateDbChapter = mockk(),
            updateManga = updateEntry,
            getChaptersByMangaId = getItems,
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = preferences,
        )

        init {
            every { source.id } returns 1
            every { preferences.markDuplicateReadChapterAsRead().get() } returns emptySet()
            coEvery { getItems.await(1) } returns listOf(
                Chapter.create().copy(id = 5, mangaId = 1, url = "/old", name = "Chapter 1", chapterNumber = 1.0),
            )
            coEvery { repository.syncChapters(any(), any(), any()) } answers {
                firstArg<List<Chapter>>().map { it.copy(id = 10) }
            }
            coEvery { getExcludedScanlators.await(1) } returns emptySet()
        }
    }
}
