package eu.kanade.tachiyomi.data.download.anime

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.AnimeSource
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
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.File

class AnimeDownloadCacheBatchTest {

    @TempDir
    lateinit var cacheDir: File

    private lateinit var cache: AnimeDownloadCache
    private lateinit var provider: AnimeDownloadProvider
    private val entry = Anime.create().copy(id = 1, source = 42, title = "Title: One")
    private val directory = mockk<UniFile>()

    @BeforeEach
    fun setUp() {
        val context = mockk<Context> { every { cacheDir } returns this@AnimeDownloadCacheBatchTest.cacheDir }
        val storage = mockk<StorageManager> {
            every { getDownloadsDirectory() } returns null
            every { changes } returns MutableSharedFlow<Unit>()
        }
        val source = mockk<AnimeSource>()
        val sources = mockk<AnimeSourceManager>()
        every { sources.get(any()) } returns source
        provider = spyk(AnimeDownloadProvider(context, storage))
        every { provider.findSourceDir(any()) } returns directory
        cache = AnimeDownloadCache(context, provider, sources, mockk(), storage)
        AnimeDownloadCache::class.java.getDeclaredField("lastRenew").apply {
            isAccessible = true
            setLong(cache, System.currentTimeMillis())
        }
    }

    @AfterEach
    fun tearDown() {
        val scope = AnimeDownloadCache::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(cache) as CoroutineScope
        scope.cancel()
    }

    @Test
    fun `batch matches normal and alternate names with scanlator and returns only requested ids`() = runBlocking {
        val first = Episode.create().copy(id = 10, name = "Part 1", scanlator = "Group A")
        val second = Episode.create().copy(id = 20, name = "Part 2", scanlator = "Group B")
        val absent = Episode.create().copy(id = 30, name = "Part 3")
        val notRequested = Episode.create().copy(id = 40, name = "Part 4")
        val wrongScanlator = first.copy(id = 50, scanlator = "Different Group")
        cache.addEpisode(provider.getValidEpisodeDirNames(first.name, first.scanlator).first(), directory, entry)
        cache.addEpisode(provider.getValidEpisodeDirNames(second.name, second.scanlator).last(), directory, entry)
        cache.addEpisode(
            provider.getValidEpisodeDirNames(notRequested.name, notRequested.scanlator).first(),
            directory,
            entry,
        )
        val requested = listOf(first, second, absent, wrongScanlator)

        cache.getDownloadedEpisodeIds(requested, entry.title, entry.source) shouldBe setOf(10L, 20L)
        requested.filter {
            cache.isEpisodeDownloaded(it.name, it.scanlator, entry.title, entry.source, false)
        }.map { it.id }.toSet() shouldBe setOf(10L, 20L)
    }

    @Test
    fun `batch does not match another source or title`() = runBlocking {
        val item = Episode.create().copy(id = 10, name = "Part 1")
        cache.addEpisode(provider.getValidEpisodeDirNames(item.name, null).first(), directory, entry)

        cache.getDownloadedEpisodeIds(listOf(item), entry.title, 999) shouldBe emptySet()
        cache.getDownloadedEpisodeIds(listOf(item), "Missing title", entry.source) shouldBe emptySet()
        cache.getDownloadedEpisodeIds(emptyList(), entry.title, entry.source) shouldBe emptySet()
    }

    @Test
    fun `batch reflects removal immediately`() = runBlocking {
        val item = Episode.create().copy(id = 10, name = "Part 1")
        cache.addEpisode(provider.getValidEpisodeDirNames(item.name, null).first(), directory, entry)
        cache.getDownloadedEpisodeIds(listOf(item), entry.title, entry.source) shouldBe setOf(10L)

        cache.removeEpisode(item, entry)

        cache.getDownloadedEpisodeIds(listOf(item), entry.title, entry.source) shouldBe emptySet()
    }
}
