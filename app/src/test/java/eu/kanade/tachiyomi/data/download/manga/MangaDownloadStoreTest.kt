package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.source.manga.service.MangaSourceManager

class MangaDownloadStoreTest {

    @Test
    fun `legacy queue entries restore as pending`() = runTest {
        val fixture = Fixture()
        fixture.values["10"] = """{"mangaId":1,"chapterId":10,"order":0}"""

        val restored = fixture.store().restore()

        restored.map { it.chapter.id } shouldBe listOf(10L)
        restored.single().status shouldBe MangaDownload.State.NOT_DOWNLOADED
    }

    @Test
    fun `failed entries retain error status across store recreation`() = runTest {
        val fixture = Fixture()
        val failed = fixture.download(10).apply { status = MangaDownload.State.ERROR }
        fixture.store().addAll(listOf(failed, fixture.download(11)))

        val restored = fixture.store().restore()

        restored.map { it.chapter.id } shouldBe listOf(10L, 11L)
        restored.map { it.status } shouldBe listOf(MangaDownload.State.ERROR, MangaDownload.State.NOT_DOWNLOADED)
    }

    @Test
    fun `status updates preserve queue order and can clear persisted failures`() = runTest {
        val fixture = Fixture()
        val store = fixture.store()
        val first = fixture.download(10)
        val second = fixture.download(11)
        store.addAll(listOf(first, second))
        val originalOrder = Json.parseToJsonElement(fixture.values.getValue("10")).jsonObject.getValue("order")

        first.status = MangaDownload.State.ERROR
        store.update(first)
        Json.parseToJsonElement(fixture.values.getValue("10")).jsonObject.getValue("order") shouldBe originalOrder
        val failed = Json.parseToJsonElement(fixture.values.getValue("10")).jsonObject["failed"]
        failed?.jsonPrimitive?.content shouldBe "true"

        first.status = MangaDownload.State.QUEUE
        store.update(first)
        val restored = fixture.store().restore()
        restored.map { it.chapter.id } shouldBe listOf(10L, 11L)
        restored.first().status shouldBe MangaDownload.State.NOT_DOWNLOADED
    }

    private class Fixture {
        val values = mutableMapOf<String, String>()
        private val context = mockk<Context>()
        private val preferences = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        private val sources = mockk<MangaSourceManager>()
        private val source = mockk<HttpSource>()
        private val manga = mockk<Manga>()
        private val getManga = mockk<GetManga>()
        private val getChapters = mockk<GetChaptersByMangaId>()
        private val chapters = listOf(10L, 11L).map { Chapter.create().copy(id = it, mangaId = 1) }

        init {
            every { context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE) } returns preferences
            every { preferences.all } answers { values.toMap() }
            every { preferences.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { preferences.edit() } returns editor
            every { editor.putString(any(), any()) } answers {
                values[firstArg()] = secondArg<String>()
                editor
            }
            every { editor.clear() } answers {
                values.clear()
                editor
            }
            every { manga.id } returns 1
            every { manga.source } returns 2
            every { sources.get(2) } returns source
            coEvery { getManga.await(1) } returns manga
            coEvery { getChapters.await(1, false) } returns chapters
        }

        fun download(id: Long) = MangaDownload(source, manga, chapters.first { it.id == id })

        fun store() = MangaDownloadStore(context, sources, Json, getManga, getChapters)
    }
}
