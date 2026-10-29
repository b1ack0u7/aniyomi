package tachiyomi.domain.entries.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository

class NetworkToLocalMangaTest {

    @Test
    fun `insertion race preserves the stored favorite and its settings`() = runBlocking {
        val repository = mockk<MangaRepository>()
        val incoming = Manga.create().copy(source = 1, url = "/title", title = "Incoming")
        val stored = incoming.copy(id = 42, title = "Favorite", favorite = true, viewerFlags = 123)
        coEvery { repository.getMangaByUrlAndSourceId(incoming.url, incoming.source) } returns null
        coEvery { repository.insertManga(incoming) } returns stored.id
        coEvery { repository.getMangaById(stored.id) } returns stored

        NetworkToLocalManga(repository).await(incoming) shouldBe stored
    }

    @Test
    fun `newly inserted non favorite keeps the source display title`() = runBlocking {
        val repository = mockk<MangaRepository>()
        val incoming = Manga.create().copy(source = 1, url = "/title", title = "Incoming")
        val stored = incoming.copy(id = 42, title = "Stored", favorite = false)
        coEvery { repository.getMangaByUrlAndSourceId(incoming.url, incoming.source) } returns null
        coEvery { repository.insertManga(incoming) } returns stored.id
        coEvery { repository.getMangaById(stored.id) } returns stored

        NetworkToLocalManga(repository).await(incoming) shouldBe stored.copy(title = incoming.title)
    }
}
