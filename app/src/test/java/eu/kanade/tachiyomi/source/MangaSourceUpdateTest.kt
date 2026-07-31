package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Sources built against extensions-lib 1.4/1.5 only implement the split API, so the default
 * implementation of the combined one has to keep serving them.
 */
class MangaSourceUpdateTest {

    @Test
    fun `only fetches what the flags ask for`() = runBlocking<Unit> {
        val source = LegacySource()
        val existingChapters = listOf(chapterOf("existing"))

        val update = source.getMangaUpdate(
            manga = mangaOf("local"),
            chapters = existingChapters,
            fetchDetails = true,
            fetchChapters = false,
        )

        source.detailsCalls shouldBe 1
        source.chapterListCalls shouldBe 0
        update.manga.title shouldBe "remote"
        update.chapters shouldBe existingChapters
    }

    @Test
    fun `fetches details and chapters together`() = runBlocking<Unit> {
        val source = LegacySource()

        val update = source.getMangaUpdate(
            manga = mangaOf("local"),
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true,
        )

        source.detailsCalls shouldBe 1
        source.chapterListCalls shouldBe 1
        update.manga.title shouldBe "remote"
        update.chapters.map { it.name } shouldBe listOf("remote chapter")
    }

    @Test
    fun `returns the given values when nothing is requested`() = runBlocking<Unit> {
        val source = LegacySource()
        val manga = mangaOf("local")
        val chapters = listOf(chapterOf("existing"))

        val update = source.getMangaUpdate(manga, chapters, fetchDetails = false, fetchChapters = false)

        source.detailsCalls shouldBe 0
        source.chapterListCalls shouldBe 0
        update.manga shouldBe manga
        update.chapters shouldBe chapters
    }

    private fun mangaOf(title: String) = SManga.create().apply {
        url = "/$title"
        this.title = title
    }

    private fun chapterOf(name: String) = SChapter.create().apply {
        url = "/$name"
        this.name = name
    }

    private inner class LegacySource : MangaSource {
        override val id: Long = 1
        override val name: String = "Legacy"

        var detailsCalls = 0
        var chapterListCalls = 0

        override suspend fun getMangaDetails(manga: SManga): SManga {
            detailsCalls++
            return mangaOf("remote")
        }

        override suspend fun getChapterList(manga: SManga): List<SChapter> {
            chapterListCalls++
            return listOf(chapterOf("remote chapter"))
        }
    }
}
