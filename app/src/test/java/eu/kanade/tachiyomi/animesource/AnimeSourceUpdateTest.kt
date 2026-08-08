package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Sources built against older versions of extensions-lib only implement the split API, so the
 * default implementation of the combined one has to keep serving them.
 */
class AnimeSourceUpdateTest {

    @Test
    fun `only fetches what the flags ask for`() = runBlocking<Unit> {
        val source = LegacySource()
        val existingEpisodes = listOf(episodeOf("existing"))

        val update = source.getAnimeUpdate(
            anime = animeOf("local"),
            episodes = existingEpisodes,
            fetchDetails = true,
            fetchEpisodes = false,
        )

        source.detailsCalls shouldBe 1
        source.episodeListCalls shouldBe 0
        update.anime.title shouldBe "remote"
        update.episodes shouldBe existingEpisodes
    }

    @Test
    fun `fetches details and episodes together`() = runBlocking<Unit> {
        val source = LegacySource()

        val update = source.getAnimeUpdate(
            anime = animeOf("local"),
            episodes = emptyList(),
            fetchDetails = true,
            fetchEpisodes = true,
        )

        source.detailsCalls shouldBe 1
        source.episodeListCalls shouldBe 1
        update.anime.title shouldBe "remote"
        update.episodes.map { it.name } shouldBe listOf("remote episode")
    }

    @Test
    fun `returns the given values when nothing is requested`() = runBlocking<Unit> {
        val source = LegacySource()
        val anime = animeOf("local")
        val episodes = listOf(episodeOf("existing"))

        val update = source.getAnimeUpdate(anime, episodes, fetchDetails = false, fetchEpisodes = false)

        source.detailsCalls shouldBe 0
        source.episodeListCalls shouldBe 0
        update.anime shouldBe anime
        update.episodes shouldBe episodes
    }

    private fun animeOf(title: String) = SAnime.create().apply {
        url = "/$title"
        this.title = title
    }

    private fun episodeOf(name: String) = SEpisode.create().apply {
        url = "/$name"
        this.name = name
    }

    private inner class LegacySource : AnimeSource {
        override val id: Long = 1
        override val name: String = "Legacy"

        var detailsCalls = 0
        var episodeListCalls = 0

        override suspend fun getAnimeDetails(anime: SAnime): SAnime {
            detailsCalls++
            return animeOf("remote")
        }

        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
            episodeListCalls++
            return listOf(episodeOf("remote episode"))
        }

        override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()
    }
}
