package eu.kanade.tachiyomi.data.track.kitsu

import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.database.models.manga.MangaTrack
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuListSearchResult
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Locale

class KitsuRatingSystemTest {

    @Test
    fun `unknown missing and mixed case preferences safely resolve`() {
        KitsuRatingSystem.fromPreference("SIMPLE") shouldBe KitsuRatingSystem.SIMPLE
        KitsuRatingSystem.fromPreference(" ReGuLaR ") shouldBe KitsuRatingSystem.REGULAR
        KitsuRatingSystem.fromPreference("ADVANCED") shouldBe KitsuRatingSystem.ADVANCED
        KitsuRatingSystem.fromPreference("invalid") shouldBe KitsuRatingSystem.ADVANCED
        KitsuRatingSystem.fromPreference(null) shouldBe KitsuRatingSystem.ADVANCED
    }

    @Test
    fun `all scales select scores in the existing local ten point representation`() {
        (0..4).map(KitsuRatingSystem.SIMPLE::indexToScore) shouldBe listOf(0.0, 1.0, 4.0, 7.0, 10.0)
        (0..10).map(KitsuRatingSystem.REGULAR::indexToScore) shouldBe (0..10).map(Int::toDouble)
        (0..19).map(KitsuRatingSystem.ADVANCED::indexToScore) shouldBe listOf(0.0) + (2..20).map { it / 2.0 }
    }

    @Test
    fun `coarser display rounds down existing scores without changing their value`() {
        val manga = MangaTrack.create(5).apply { score = 8.5 }
        val anime = AnimeTrack.create(5).apply { score = 8.5 }
        KitsuRatingSystem.SIMPLE.displayScore(manga.score) shouldBe "😊"
        KitsuRatingSystem.REGULAR.displayScore(anime.score) shouldBe "4 ★"
        KitsuRatingSystem.ADVANCED.displayScore(anime.score) shouldBe "8.5"
        manga.score shouldBe 8.5
        anime.score shouldBe 8.5
        manga.toApiScore() shouldBe "17"
        anime.toApiScore() shouldBe "17"
    }

    @Test
    fun `zero and negative scores remain unrated in every scale`() {
        for (ratingSystem in KitsuRatingSystem.entries) {
            ratingSystem.displayScore(0.0) shouldBe ratingSystem.scoreList().first()
            ratingSystem.displayScore(-1.0) shouldBe ratingSystem.scoreList().first()
        }
        MangaTrack.create(5).apply { score = 0.0 }.toApiScore() shouldBe null
        AnimeTrack.create(5).apply { score = 0.0 }.toApiScore() shouldBe null
    }

    @Test
    fun `old serialized library ratings still decode and round trip on both stacks`() {
        for (ratingTwenty in 2..20) {
            val result = Json.decodeFromString<KitsuListSearchResult>(
                """
                {
                  "data": [{"id": 42, "attributes": {
                    "status": "current", "startedAt": null, "finishedAt": null,
                    "ratingTwenty": $ratingTwenty, "progress": 3, "private": false
                  }}],
                  "included": [{"id": 7, "attributes": {
                    "canonicalTitle": "Title", "chapterCount": 10, "episodeCount": 12,
                    "mangaType": "manga", "showType": "TV", "posterImage": null,
                    "synopsis": null, "startDate": null, "status": "current"
                  }}]
                }
                """.trimIndent(),
            )
            val manga = result.firstToMangaTrack()
            val anime = result.firstToAnimeTrack()
            manga.score shouldBe ratingTwenty / 2.0
            anime.score shouldBe ratingTwenty / 2.0
            manga.toApiScore() shouldBe ratingTwenty.toString()
            anime.toApiScore() shouldBe ratingTwenty.toString()
        }
    }

    @Test
    fun `decimal labels follow current locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            KitsuRatingSystem.REGULAR.scoreList()[1] shouldBe "0,5 ★"
            KitsuRatingSystem.ADVANCED.displayScore(8.5) shouldBe "8,5"
        } finally {
            Locale.setDefault(original)
        }
    }
}
