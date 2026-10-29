package tachiyomi.domain.entries.anime.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager

class NetworkToLocalAnimeTest {

    @Test
    fun `insertion race preserves the stored favorite and its settings`() = runBlocking {
        val repository = mockk<AnimeRepository>()
        val incoming = Anime.create().copy(source = 1, url = "/title", title = "Incoming")
        val stored = incoming.copy(id = 42, title = "Favorite", favorite = true, viewerFlags = 123)
        coEvery { repository.getAnimeByUrlAndSourceId(incoming.url, incoming.source) } returns null
        coEvery { repository.insertAnime(incoming) } returns stored.id
        coEvery { repository.getAnimeById(stored.id) } returns stored

        NetworkToLocalAnime(repository, mockk<AnimeSourceManager>()).await(incoming) shouldBe stored
    }

    @Test
    fun `newly inserted non favorite keeps the source display title`() = runBlocking {
        val repository = mockk<AnimeRepository>()
        val incoming = Anime.create().copy(source = 1, url = "/title", title = "Incoming")
        val stored = incoming.copy(id = 42, title = "Stored", favorite = false)
        coEvery { repository.getAnimeByUrlAndSourceId(incoming.url, incoming.source) } returns null
        coEvery { repository.insertAnime(incoming) } returns stored.id
        coEvery { repository.getAnimeById(stored.id) } returns stored

        NetworkToLocalAnime(repository, mockk<AnimeSourceManager>()).await(incoming) shouldBe
            stored.copy(title = incoming.title)
    }
}
