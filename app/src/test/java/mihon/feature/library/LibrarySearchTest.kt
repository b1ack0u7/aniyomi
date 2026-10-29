package mihon.feature.library

import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.library.manga.LibraryManga
import java.time.LocalDate
import java.time.ZoneId

class LibrarySearchTest {
    private val added = LocalDate.of(2026, 10, 21).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val manga = mockk<MangaLibraryItem> {
        every { libraryManga } returns LibraryManga(
            Manga.create().copy(
                id = 42,
                title = "One Adventure",
                genre = listOf("Martial Arts", "Action"),
                author = "Writer",
                source = 7,
                dateAdded = added,
                fetchInterval = -7,
            ),
            listOf(0),
            12,
            3,
            0,
            0,
            0,
            0,
        )
        every { sourceName } returns "Example Source"
        every { sourceLang } returns "es"
    }
    private val anime = mockk<AnimeLibraryItem> {
        every { libraryAnime } returns LibraryAnime(
            Anime.create().copy(
                id = 42,
                title = "One Adventure",
                genre = listOf("Martial Arts", "Action"),
                author = "Writer",
                source = 7,
                dateAdded = added,
                fetchInterval = -7,
            ),
            listOf(0), 12, 3, 0, 0, 0, 0, 0,
        )
        every { sourceName } returns "Example Source"
        every { sourceLang } returns "es"
    }

    @Test
    fun `both libraries evaluate compound expressions fields counts and local dates`() {
        listOf(
            "genre:action && unread:>0",
            "title:'one adventure' && -(genre:romance || author:Other)",
            "source:example && source_id:7 && lang:es",
            "id:42 && read>=3 && total:12",
            "added:2026-10-21 && fi=7",
            "artist:\"\"",
            "-genre:romance, genre:martial",
        ).forEach { query ->
            QueryNode.from(query).matches(manga) shouldBe true
            QueryNode.from(query).matches(anime) shouldBe true
        }
        listOf(
            "id:1",
            "unread:>9",
            "added:invalid",
            "total=oops",
            "artist:someone",
            "-author:Writer",
            "nextupdate<2026-10-21",
        )
            .forEach { query ->
                QueryNode.from(query).matches(manga) shouldBe false
                QueryNode.from(query).matches(anime) shouldBe false
            }
    }

    @Test
    fun `anime accepts seen unseen watched and episode fields`() {
        QueryNode.from("seen:3 && unseen>0 && watched>=3 && episodes:12").matches(anime) shouldBe true
        QueryNode.from("unseen:0 || episodes<12").matches(anime) shouldBe false
    }

    @Test
    fun `real library items search source language independently of badge preferences`() {
        val mangaItem = MangaLibraryItem(
            libraryManga = manga.libraryManga,
            sourceLanguage = "",
            sourceManager = mockk(),
            cachedSourceName = "Example Source",
            cachedSourceLang = "es",
        )
        val animeItem = AnimeLibraryItem(
            libraryAnime = anime.libraryAnime,
            sourceLanguage = "",
            sourceManager = mockk(),
            cachedSourceName = "Example Source",
            cachedSourceLang = "es",
        )

        listOf("lang:es", "source:example", "source_id:7", "title:Adventure").forEach { query ->
            mangaItem.matches(query) shouldBe true
            animeItem.matches(query) shouldBe true
        }
        listOf("lang:en", "source_id:999", "es").forEach { query ->
            mangaItem.matches(query) shouldBe false
            animeItem.matches(query) shouldBe false
        }
    }
}
