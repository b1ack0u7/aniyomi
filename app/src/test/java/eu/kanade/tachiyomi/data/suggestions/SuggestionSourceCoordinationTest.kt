package eu.kanade.tachiyomi.data.suggestions

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.data.library.LibraryUpdateType
import eu.kanade.tachiyomi.data.suggestions.anime.AnimeSearchFallbackEngine
import eu.kanade.tachiyomi.data.suggestions.manga.MangaSearchFallbackEngine
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceRequestCoordinator
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.model.Manga

class SuggestionSourceCoordinationTest {
    @AfterEach
    @BeforeEach
    fun clearCache() = SuggestionCache.invalidateAll()

    @ParameterizedTest
    @EnumSource(SuggestionMediaType::class)
    fun `suggestion rate limit pauses source before any later query or filter request`(
        media: SuggestionMediaType,
    ) = runTest {
        val fixture = Fixture()
        var searches = 0
        var filters = 0
        fun pager() = fixture.pager(media, onFilters = { filters++ }) {
            searches++
            throw HttpException(429, "600")
        }

        pager().loadInitial() shouldBe emptyList()
        pager().loadInitial() shouldBe emptyList()

        searches shouldBe 1
        filters shouldBe 1
        val type = if (media == SuggestionMediaType.MANGA) LibraryUpdateType.MANGA else LibraryUpdateType.ANIME
        fixture.coordinator.deadline(type, 42) shouldBe 600_000L
        val download = runCatching {
            fixture.coordinator.withRequest(type, 42) { searches++ }
        }.exceptionOrNull() as SourceRequestPausedException
        download.retryAt shouldBe 600_000L
        searches shouldBe 1
    }

    @Test
    fun `manga suggestions share access denial with reader without requesting the next title`() = runTest {
        val fixture = Fixture()
        var searches = 0
        fixture.pager(SuggestionMediaType.MANGA) {
            searches++
            throw HttpException(403)
        }.loadInitial()

        val reader = runCatching {
            fixture.coordinator.withReaderRequest(42) { searches++ }
        }.exceptionOrNull() as SourceRequestPausedException

        reader.forbidden shouldBe true
        fixture.coordinator.forbidden(LibraryUpdateType.MANGA, 42) shouldBe true
        searches shouldBe 1
    }

    private class Fixture {
        private val preferences = mockk<DownloadPreferences> {
            every { smartMangaDownloads() } returns mockk { every { get() } returns true }
            every { smartMangaRequestIntervalMs() } returns intPreference(500)
            every { smartMangaChapterDelaySeconds() } returns intPreference(10)
            every { smartMangaBatchSize() } returns intPreference(5)
            every { smartMangaBatchDelaySeconds() } returns intPreference(120)
            every { smartMangaRetryInitialSeconds() } returns intPreference(60)
            every { smartMangaRetryMaxSeconds() } returns intPreference(300)
        }
        val coordinator = SourceRequestCoordinator(memoryPreferences(), memoryPreferences(), preferences) { 0L }

        fun pager(
            media: SuggestionMediaType,
            onFilters: () -> Unit = {},
            search: suspend () -> Unit,
        ): SourceSearchFallback {
            val seed = SuggestionSeed(media, "seed", listOf("seed", "alias"), null)
            return when (media) {
                SuggestionMediaType.MANGA -> {
                    val source = mockk<CatalogueSource> {
                        every { id } returns 42
                        every { name } returns "Test source"
                        every { getFilterList() } answers {
                            onFilters()
                            eu.kanade.tachiyomi.source.model.FilterList()
                        }
                    }
                    coEvery { source.getSearchManga(any(), any(), any()) } coAnswers {
                        search()
                        MangasPage(emptyList(), false)
                    }
                    MangaSearchFallbackEngine(coordinator).createPager(
                        Manga.create().copy(source = 42, title = "seed", url = "/entry", genre = listOf("Fantasy")),
                        source,
                        seed,
                    )
                }
                SuggestionMediaType.ANIME -> {
                    val source = mockk<AnimeCatalogueSource> {
                        every { id } returns 42
                        every { name } returns "Test source"
                        every { getFilterList() } answers {
                            onFilters()
                            eu.kanade.tachiyomi.animesource.model.AnimeFilterList()
                        }
                    }
                    coEvery { source.getSearchAnime(any(), any(), any()) } coAnswers {
                        search()
                        AnimesPage(emptyList(), false)
                    }
                    AnimeSearchFallbackEngine(coordinator).createPager(
                        Anime.create().copy(source = 42, title = "seed", url = "/entry", genre = listOf("Fantasy")),
                        source,
                        seed,
                    )
                }
            }
        }

        private fun memoryPreferences(): SharedPreferences {
            val values = mutableMapOf<String, Any>()
            val preferences = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            every { preferences.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
            every { preferences.getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { preferences.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
            every { preferences.edit() } returns editor
            every { editor.putBoolean(any(), any()) } answers {
                values[firstArg()] = secondArg<Boolean>()
                editor
            }
            every { editor.putLong(any(), any()) } answers {
                values[firstArg()] = secondArg<Long>()
                editor
            }
            every { editor.putInt(any(), any()) } answers {
                values[firstArg()] = secondArg<Int>()
                editor
            }
            every { editor.apply() } returns Unit
            return preferences
        }
    }

    companion object {
        private fun intPreference(value: Int): Preference<Int> = mockk { every { get() } returns value }
    }
}
