package eu.kanade.tachiyomi.data.suggestions

import eu.kanade.tachiyomi.data.suggestions.util.dedupeByFranchise
import eu.kanade.tachiyomi.data.suggestions.util.rankForSeed
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SuggestionRankingTest {

    private val seed = SuggestionSeed(
        mediaType = SuggestionMediaType.MANGA,
        primaryTitle = "One Piece",
        candidateTitles = listOf("One Piece"),
        description = null,
    )

    private fun item(
        title: String,
        reason: SuggestionReason = SuggestionReason.SEARCH_TITLE,
        url: String = "/$title",
        thumbnailUrl: String? = null,
        relevance: Int = 100,
    ) = SuggestionItem(
        title = title,
        thumbnailUrl = thumbnailUrl,
        providerName = "test",
        providerUrl = url,
        providerId = null,
        mediaType = SuggestionMediaType.MANGA,
        reason = reason,
        relevance = relevance,
    )

    @Test
    fun `dedupe keeps the entry from the strongest source`() {
        val items = listOf(
            item("Vinland Saga", SuggestionReason.SEARCH_GENRE, url = "/weak"),
            item("Vinland Saga", SuggestionReason.EXTERNAL_ANILIST, url = "/strong"),
        )

        items.dedupeByFranchise().map { it.providerUrl } shouldContainExactly listOf("/strong")
    }

    @Test
    fun `dedupe collapses titles that only differ by decoration`() {
        val items = listOf(
            item("Berserk", url = "/a"),
            item("Berserk (2016)", url = "/b"),
        )

        items.dedupeByFranchise().size shouldBe 1
    }

    @Test
    fun `dedupe collapses seasons of the same show into one entry`() {
        val items = listOf(
            item("Overlord", url = "/a"),
            item("Overlord II", url = "/b"),
            item("Overlord 3rd Season", url = "/c"),
        )

        items.dedupeByFranchise().size shouldBe 1
    }

    @Test
    fun `dedupe keeps distinct entries whose titles clean down to nothing`() {
        val items = listOf(
            item("!!!", url = "/a"),
            item("???", url = "/b"),
        )

        items.dedupeByFranchise().size shouldBe 2
    }

    @Test
    fun `rankForSeed drops the entry itself and other releases of the same work`() {
        val items = listOf(
            item("One Piece", url = "/manga/one-piece"),
            item("One Piece (Colored)", url = "/manga/one-piece-colored"),
            item("Vinland Saga", SuggestionReason.EXTERNAL_ANILIST, url = "/manga/vinland-saga"),
        )

        val ranked = items.rankForSeed(seed, entryUrl = "/manga/one-piece", limit = 10)

        ranked.map { it.title } shouldContainExactly listOf("Vinland Saga")
    }

    @Test
    fun `rankForSeed drops sequels of the entry regardless of how they are numbered`() {
        val seasonSeed = seed.copy(primaryTitle = "Tensei shitara Slime Datta Ken 4th Season")
        val items = listOf(
            item("Tensei shitara Slime Datta Ken", url = "/a"),
            item("Tensei shitara Slime Datta Ken 2nd Season Part 2", url = "/b"),
            item("Tensei shitara Slime Datta Ken OVA", url = "/c"),
            item("Overlord", SuggestionReason.EXTERNAL_ANILIST, url = "/d"),
        )

        val ranked = items.rankForSeed(seasonSeed, entryUrl = null, limit = 10)

        ranked.map { it.title } shouldContainExactly listOf("Overlord")
    }

    @Test
    fun `rankForSeed trusts the provider rather than resemblance to the entry`() {
        val items = listOf(
            item("Nanatsu no Taizai", SuggestionReason.SEARCH_TITLE, url = "/a", relevance = 100),
            item("Overlord", SuggestionReason.EXTERNAL_ANILIST, url = "/b", relevance = 90),
            item("Log Horizon", SuggestionReason.EXTERNAL_MAL, url = "/c", relevance = 80),
        )

        val ranked = items.rankForSeed(seed, entryUrl = null, limit = 2)

        ranked.map { it.title } shouldContainExactly listOf("Overlord", "Log Horizon")
    }

    @Test
    fun `rankForSeed honors the limit`() {
        val titles = listOf("Overlord", "Log Horizon", "Konosuba", "Re Zero", "No Game No Life")
        val items = titles.mapIndexed { index, title ->
            item(title, SuggestionReason.EXTERNAL_ANILIST, url = "/$index")
        }

        items.rankForSeed(seed, entryUrl = null, limit = 3).size shouldBe 3
    }

    @Test
    fun `a trailing number is read as a sequel marker`() {
        val items = listOf(
            item("Overlord", url = "/a"),
            item("Overlord 2", url = "/b"),
        )

        items.dedupeByFranchise().size shouldBe 1
    }
}
