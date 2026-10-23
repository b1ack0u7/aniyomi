package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.MangaSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

class MangaDownloadCacheBatchTest {

    @TempDir
    lateinit var cacheDir: File

    private lateinit var cache: MangaDownloadCache
    private lateinit var provider: MangaDownloadProvider
    private val entry = Manga.create().copy(id = 1, source = 42, title = "Title: One")
    private val directory = mockk<UniFile>()

    @BeforeEach
    fun setUp() {
        val context = mockk<Context> { every { cacheDir } returns this@MangaDownloadCacheBatchTest.cacheDir }
        val storage = mockk<StorageManager> {
            every { getDownloadsDirectory() } returns null
            every { changes } returns MutableSharedFlow<Unit>()
        }
        val source = mockk<MangaSource>()
        val sources = mockk<MangaSourceManager>()
        every { sources.get(any()) } returns source
        provider = spyk(MangaDownloadProvider(context, storage))
        every { provider.findSourceDir(any()) } returns directory
        cache = MangaDownloadCache(context, provider, sources, mockk(), storage)
        MangaDownloadCache::class.java.getDeclaredField("lastRenew").apply {
            isAccessible = true
            setLong(cache, System.currentTimeMillis())
        }
    }

    @AfterEach
    fun tearDown() {
        val scope = MangaDownloadCache::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(cache) as CoroutineScope
        scope.cancel()
    }

    @Test
    fun `batch matches normal and alternate names with scanlator and returns only requested ids`() = runBlocking {
        val first = Chapter.create().copy(id = 10, name = "Part 1", scanlator = "Group A")
        val second = Chapter.create().copy(id = 20, name = "Part 2", scanlator = "Group B")
        val absent = Chapter.create().copy(id = 30, name = "Part 3")
        val notRequested = Chapter.create().copy(id = 40, name = "Part 4")
        val wrongScanlator = first.copy(id = 50, scanlator = "Different Group")
        cache.addChapter(provider.getValidChapterDirNames(first.name, first.scanlator).first(), directory, entry)
        cache.addChapter(provider.getValidChapterDirNames(second.name, second.scanlator).last(), directory, entry)
        cache.addChapter(
            provider.getValidChapterDirNames(notRequested.name, notRequested.scanlator).first(),
            directory,
            entry,
        )
        val requested = listOf(first, second, absent, wrongScanlator)

        cache.getDownloadedChapterIds(requested, entry.title, entry.source) shouldBe setOf(10L, 20L)
        requested.filter {
            cache.isChapterDownloaded(it.name, it.scanlator, entry.title, entry.source, false)
        }.map { it.id }.toSet() shouldBe setOf(10L, 20L)
    }

    @Test
    fun `batch does not match another source or title`() = runBlocking {
        val item = Chapter.create().copy(id = 10, name = "Part 1")
        cache.addChapter(provider.getValidChapterDirNames(item.name, null).first(), directory, entry)

        cache.getDownloadedChapterIds(listOf(item), entry.title, 999) shouldBe emptySet()
        cache.getDownloadedChapterIds(listOf(item), "Missing title", entry.source) shouldBe emptySet()
        cache.getDownloadedChapterIds(emptyList(), entry.title, entry.source) shouldBe emptySet()
    }

    @Test
    fun `batch reflects removal immediately`() = runBlocking {
        val item = Chapter.create().copy(id = 10, name = "Part 1")
        cache.addChapter(provider.getValidChapterDirNames(item.name, null).first(), directory, entry)
        cache.getDownloadedChapterIds(listOf(item), entry.title, entry.source) shouldBe setOf(10L)

        cache.removeChapter(item, entry)

        cache.getDownloadedChapterIds(listOf(item), entry.title, entry.source) shouldBe emptySet()
    }
}
