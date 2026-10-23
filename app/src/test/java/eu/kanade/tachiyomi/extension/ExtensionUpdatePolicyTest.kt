package eu.kanade.tachiyomi.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExtensionUpdatePolicyTest {
    private data class Candidate(
        val signingKey: String,
        val versionCode: Long,
        val libVersion: Double = 1.4,
    )

    private fun select(
        candidates: List<Candidate>,
        signatures: Set<String>?,
    ): Candidate? = ExtensionUpdatePolicy.select(
        candidates,
        signatures,
        Candidate::signingKey,
        Candidate::versionCode,
        Candidate::libVersion,
    )

    @Test
    fun `highest version from a different signing key cannot replace an installed extension`() {
        val compatible = Candidate("trusted", 2)
        select(listOf(Candidate("other", 99), compatible), setOf("trusted")) shouldBe compatible
    }

    @Test
    fun `highest compatible repository wins regardless of repository order`() {
        val expected = Candidate("trusted", 3)
        for (candidates in listOf(
            listOf(Candidate("trusted", 2), expected, Candidate("other", 99)),
            listOf(Candidate("other", 99), expected, Candidate("trusted", 2)),
        )) {
            select(candidates, setOf("trusted")) shouldBe expected
        }
    }

    @Test
    fun `absent matching repository means no update`() {
        select(listOf(Candidate("other", 9)), setOf("trusted")) shouldBe null
        select(listOf(Candidate("other", 9)), emptySet()) shouldBe null
    }

    @Test
    fun `new installations can select the highest version across repositories`() {
        select(listOf(Candidate("one", 1), Candidate("two", 5)), null) shouldBe Candidate("two", 5)
    }

    @Test
    fun `signing history allows a repository signed with an earlier key`() {
        val compatible = Candidate("old", 3)
        select(listOf(compatible), setOf("old", "current")) shouldBe compatible
    }

    @Test
    fun `downloaded APK must match repository signature package and offered version`() {
        fun matches(pkg: String = "expected.pkg", version: Long = 3, signatures: Set<String> = setOf("trusted")) =
            ExtensionUpdatePolicy.matchesDownloadedApk(pkg, version, signatures, "expected.pkg", 3, "trusted")

        matches() shouldBe true
        matches(signatures = setOf("other")) shouldBe false
        matches(signatures = emptySet()) shouldBe false
        matches(pkg = "another.pkg") shouldBe false
        matches(version = 2) shouldBe false
        matches(version = 4) shouldBe false
        ExtensionUpdatePolicy.matchesDownloadedApk("pkg", 1, setOf(""), "pkg", 1, "") shouldBe false
    }
}
