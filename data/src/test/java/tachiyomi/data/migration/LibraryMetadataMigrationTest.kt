package tachiyomi.data.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.migration.LibrarySchemaMigrationTest.Fixture

class LibraryMetadataMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `duplicate metadata takes first non blank value and retains survivor settings`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true)
                title(2, favorite = false)
                title(3, favorite = false)
                item(1, 1)
                item(2, 2)
                item(3, 3)
                val titleFields = listOf("artist", "author", "description", "genre", "thumbnail_url") +
                    if (anime) listOf("background_url") else emptyList()
                val itemFields = listOf("scanlator") + if (anime) listOf("summary", "preview_url") else emptyList()
                titleFields.forEach { field ->
                    execute("UPDATE $oldTitle SET $field = char(9, 10, 160, 8195) WHERE _id = 1")
                    execute("UPDATE $oldTitle SET $field = 'Preferred' WHERE _id = 2")
                    execute("UPDATE $oldTitle SET $field = 'Later' WHERE _id = 3")
                }
                itemFields.forEach { field ->
                    execute("UPDATE $oldItem SET $field = '' WHERE _id = 1")
                    execute("UPDATE $oldItem SET $field = 'Preferred' WHERE _id = 2")
                    execute("UPDATE $oldItem SET $field = 'Later' WHERE _id = 3")
                }

                migrate()

                titleFields.forEach { field ->
                    val target = when (field) {
                        "thumbnail_url" -> "cover"
                        "background_url" -> "background"
                        else -> field
                    }
                    scalar("SELECT remote_$target FROM $newTitle") shouldBe "Preferred"
                }
                itemFields.forEach { field ->
                    scalar("SELECT remote_${if (field == "preview_url") "preview" else field} FROM $newItem") shouldBe
                        "Preferred"
                }
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `blank values remain unchanged when no non blank alternative exists`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true, artist = " \t")
                title(2, favorite = false, artist = "")
                title(3, favorite = true, url = "/unique", artist = "\n")
                item(1, 1)
                item(2, 2)
                item(3, 3)
                execute("UPDATE $oldItem SET scanlator = char(9) WHERE _id IN (1, 3)")
                execute("UPDATE $oldItem SET scanlator = '' WHERE _id = 2")

                migrate()

                scalar("SELECT remote_artist FROM $newTitle WHERE id = 1") shouldBe " \t"
                scalar("SELECT remote_artist FROM $newTitle WHERE id = 3") shouldBe "\n"
                scalar("SELECT remote_scanlator FROM $newItem WHERE id = 1") shouldBe "\t"
                scalar("SELECT remote_scanlator FROM $newItem WHERE id = 3") shouldBe "\t"
                assertIntegrity()
            }
        }
    }

    @Test
    fun `memo union preserves JSON types and first copy conflicts without changing modification times`() {
        Fixture("manga").use { f ->
            with(f) {
                title(1, favorite = false)
                title(2, favorite = true)
                item(1, 1)
                item(2, 2)
                history(1, 2, 10, 20)
                val lower = """{"lower": [1, true, null], "conflict": "lower", "null": "lower"}"""
                val preferred = """{"preferred": {"nested": "é\"\\"}, "conflict": 42, "null": null}"""
                memo("mangas", 1, lower)
                memo("mangas", 2, preferred)
                memo("chapters", 1, lower)
                memo("chapters", 2, preferred)
                execute("UPDATE mangas SET last_modified_at = 123, version = 7 WHERE _id = 2")
                execute("UPDATE chapters SET last_modified_at = 456, version = 9 WHERE _id = 2")
                val titleModified = scalar("SELECT max(last_modified_at) FROM mangas")
                val itemModified = scalar("SELECT max(last_modified_at) FROM chapters")

                migrate()

                val expected = Json.parseToJsonElement(
                    """{"lower": [1, true, null], "preferred": {"nested": "é\"\\"}, "conflict": 42, "null": null}""",
                )
                Json.parseToJsonElement(scalar("SELECT CAST(remote_memo AS TEXT) FROM manga")!!) shouldBe expected
                Json.parseToJsonElement(scalar("SELECT CAST(remote_memo AS TEXT) FROM chapter")!!) shouldBe expected
                scalar("SELECT last_modified_at FROM manga") shouldBe titleModified
                scalar("SELECT last_modified_at FROM chapter") shouldBe itemModified
                scalar("SELECT version FROM manga") shouldBe "7"
                scalar("SELECT version FROM chapter") shouldBe "9"
                assertIntegrity()
            }
        }
    }

    @Test
    fun `unique malformed memos are preserved without attempting to decode`() {
        Fixture("manga").use { f ->
            with(f) {
                title(1, favorite = true)
                item(1, 1)
                memo("mangas", 1, "malformed")
                memo("chapters", 1, "[1,2]")

                migrate()

                scalar("SELECT CAST(remote_memo AS TEXT) FROM manga") shouldBe "malformed"
                scalar("SELECT CAST(remote_memo AS TEXT) FROM chapter") shouldBe "[1,2]"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "manga, malformed",
        "manga, null",
        "manga, '[1,2]'",
        "chapter, malformed",
        "chapter, null",
        "chapter, '[1,2]'",
    )
    fun `invalid duplicate memo rolls back instead of silently dropping metadata`(kind: String, invalid: String) {
        Fixture("manga").use { f ->
            with(f) {
                title(1, favorite = true)
                title(2, favorite = false)
                item(1, 1)
                item(2, 2)
                val table = if (kind == "manga") "mangas" else "chapters"
                memo(table, 1, """{"valid":true}""")
                memo(table, 2, invalid)

                val previousSchema = schemaObjects(driver)
                val error = shouldThrow<IllegalStateException> { migrate() }

                error.message shouldBe "Cannot merge invalid $kind memo for id 1"
                schemaObjects(driver) shouldBe previousSchema
                scalar("SELECT count(*) FROM mangas") shouldBe "2"
                scalar("SELECT CAST(memo AS TEXT) FROM $table WHERE _id = 2") shouldBe invalid
                scalar("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'manga'") shouldBe "0"
                scalar("SELECT count(*) FROM sqlite_temp_master WHERE type = 'table'") shouldBe "0"
            }
        }
    }

    private fun Fixture.memo(table: String, id: Int, value: String) {
        val encoded = value.encodeToByteArray().joinToString("") { "%02x".format(it) }
        execute("UPDATE $table SET memo = x'$encoded' WHERE _id = $id")
    }
}
