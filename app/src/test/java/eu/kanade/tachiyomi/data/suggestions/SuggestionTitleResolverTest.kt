package eu.kanade.tachiyomi.data.suggestions

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SuggestionTitleResolverTest {

    @Test
    fun `normalizeQuery strips season and format suffixes`() {
        SuggestionTitleResolver.normalizeQuery("Attack on Titan Season 3") shouldBe "Attack on Titan"
        SuggestionTitleResolver.normalizeQuery("Bleach Movie") shouldBe "Bleach"
        SuggestionTitleResolver.normalizeQuery("Clannad   OVA") shouldBe "Clannad"
    }

    @Test
    fun `normalizeQuery leaves a plain title untouched`() {
        SuggestionTitleResolver.normalizeQuery("One Piece") shouldBe "One Piece"
    }

    @Test
    fun `scoreMatch is ordinal`() {
        SuggestionTitleResolver.scoreMatch("One Piece", "one piece") shouldBe 100
        SuggestionTitleResolver.scoreMatch("One Piece Film Red", "One Piece") shouldBe 75
        SuggestionTitleResolver.scoreMatch("The One Piece Story", "One Piece") shouldBe 50
        SuggestionTitleResolver.scoreMatch("Naruto", "Bleach") shouldBe 0
    }

    @Test
    fun `scoreMatch falls back to token overlap`() {
        val score = SuggestionTitleResolver.scoreMatch("Fullmetal Alchemist Brotherhood", "Brotherhood Alchemist")
        score shouldBe 33
    }

    @Test
    fun `scoreMatch returns zero for blank input`() {
        SuggestionTitleResolver.scoreMatch("", "One Piece") shouldBe 0
        SuggestionTitleResolver.scoreMatch("One Piece", "  ") shouldBe 0
    }

    @Test
    fun `cleanTitle drops brackets punctuation and volume markers`() {
        SuggestionTitleResolver.cleanTitle("Berserk (2016) Vol. 3") shouldBe "berserk"
        SuggestionTitleResolver.cleanTitle("Re:Zero — Starting Life") shouldBe "re zero starting life"
    }

    @Test
    fun `parseSlugTitle turns a slug into words and ignores numeric ids`() {
        SuggestionTitleResolver.parseSlugTitle("https://example.org/manga/1234--one-piece") shouldBe "one piece"
        SuggestionTitleResolver.parseSlugTitle("https://example.org/manga/1234") shouldBe null
    }

    @Test
    fun `parseOriginalTitle picks up an embedded alias`() {
        val description = "A story about titans.\nOriginal: Shingeki no Kyojin\nMore text."
        SuggestionTitleResolver.parseOriginalTitle(description) shouldBe "Shingeki no Kyojin"
        SuggestionTitleResolver.parseOriginalTitle("Just a plain description.") shouldBe null
    }

    @Test
    fun `resolveCandidates gathers aliases and their normalized variants`() {
        val candidates = SuggestionTitleResolver.resolveCandidates(
            title = "Attack on Titan Season 3",
            description = "Original: Shingeki no Kyojin",
            url = "https://example.org/anime/99--attack-on-titan",
            alternativeTitles = listOf("AoT"),
        )

        candidates shouldContain "Attack on Titan Season 3"
        candidates shouldContain "Attack on Titan"
        candidates shouldContain "Shingeki no Kyojin"
        candidates shouldContain "attack on titan"
        candidates shouldContain "AoT"
        candidates shouldNotContain ""
    }

    @Test
    fun `franchiseKey strips season ordinals parts and format markers`() {
        val base = SuggestionTitleResolver.franchiseKey("Tensei shitara Slime Datta Ken")

        SuggestionTitleResolver.franchiseKey("Tensei shitara Slime Datta Ken 4th Season") shouldBe base
        SuggestionTitleResolver.franchiseKey("Tensei shitara Slime Datta Ken Season 2") shouldBe base
        SuggestionTitleResolver.franchiseKey("Tensei shitara Slime Datta Ken OVA") shouldBe base
        SuggestionTitleResolver.franchiseKey("Tensei shitara Slime Datta Ken Part 3") shouldBe base
        SuggestionTitleResolver.franchiseKey("Tensei shitara Slime Datta Ken II") shouldBe base
    }

    @Test
    fun `franchiseKey keeps an identity for titles that are only markers`() {
        SuggestionTitleResolver.franchiseKey("Movie") shouldBe "movie"
        SuggestionTitleResolver.franchiseKey("2") shouldBe "2"
    }

    @Test
    fun `isFranchiseDuplicate collapses other releases of the same work`() {
        SuggestionTitleResolver.isFranchiseDuplicate("Berserk (2016)", "Berserk") shouldBe true
        SuggestionTitleResolver.isFranchiseDuplicate(
            "Tensei shitara Slime Datta Ken",
            "Tensei shitara Slime Datta Ken 4th Season",
        ) shouldBe true
        SuggestionTitleResolver.isFranchiseDuplicate("Berserk", "Vinland Saga") shouldBe false
        SuggestionTitleResolver.isFranchiseDuplicate("Overlord", "Log Horizon") shouldBe false
    }

    @Test
    fun `isFranchiseDuplicate catches spin-offs carrying the parent name`() {
        SuggestionTitleResolver.isFranchiseDuplicate(
            "Tensura Nikki: Tensei shitara Slime Datta Ken",
            "Tensei shitara Slime Datta Ken 4th Season",
        ) shouldBe true
        SuggestionTitleResolver.isFranchiseDuplicate("One Piece Film Red", "One Piece") shouldBe true
    }

    @Test
    fun `isFranchiseDuplicate does not merge works that share a single word`() {
        SuggestionTitleResolver.isFranchiseDuplicate("Berserk of Gluttony", "Berserk") shouldBe false
        SuggestionTitleResolver.isFranchiseDuplicate("Overlord of the Dead", "Overlord") shouldBe false
    }

    @Test
    fun `isSameProviderEntry matches on url and on the encoded native target`() {
        val item = SuggestionItem(
            title = "One Piece",
            thumbnailUrl = null,
            providerName = "Some Source",
            providerUrl = "/manga/one-piece/",
            providerId = "42:/manga/one-piece",
            mediaType = SuggestionMediaType.MANGA,
        )

        SuggestionTitleResolver.isSameProviderEntry(item, "/manga/one-piece") shouldBe true
        SuggestionTitleResolver.isSameProviderEntry(item, "/manga/nana") shouldBe false
        SuggestionTitleResolver.isSameProviderEntry(item, null) shouldBe false
    }
}
