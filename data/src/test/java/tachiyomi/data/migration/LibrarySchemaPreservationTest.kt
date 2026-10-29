package tachiyomi.data.migration

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.migration.LibrarySchemaMigrationTest.Fixture

class LibrarySchemaPreservationTest {

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `removed titles recover their categories when added back after migration`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true)
                seed("categories", mapOf("_id" to 3, "name" to "Hidden", "sort" to 7, "hidden" to 1))
                seed(oldMembership, mapOf("_id" to 10, titleForeignKey to 1, "category_id" to 3))
                execute("UPDATE $oldTitle SET favorite = 0 WHERE _id = 1")
                scalar("SELECT category_id FROM $oldMembership WHERE $titleForeignKey = 1") shouldBe "3"

                migrate()

                scalar("SELECT favorite FROM $newTitle WHERE id = 1") shouldBe "0"
                scalar("SELECT count(*) FROM $libraryView") shouldBe "0"
                scalar("SELECT category_id FROM ${stack}_category WHERE id = 10") shouldBe "3"
                scalar("SELECT hidden FROM category WHERE id = 3") shouldBe "1"
                execute("UPDATE $newTitle SET favorite = 1 WHERE id = 1")
                scalar("SELECT categories FROM $libraryView WHERE id = 1") shouldBe "3"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `duplicate groups outside the library retain all valid category memberships`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = false)
                title(2, favorite = false)
                for (id in 1..2) {
                    seed("categories", mapOf("_id" to id, "sort" to id, "name" to "Category $id"))
                    seed(oldMembership, mapOf("_id" to id, titleForeignKey to id, "category_id" to id))
                }
                seed(oldMembership, mapOf("_id" to 3, titleForeignKey to 2, "category_id" to 1))
                seed(oldMembership, mapOf("_id" to 4, titleForeignKey to 2, "category_id" to 999))
                seed(oldMembership, mapOf("_id" to 5, titleForeignKey to 999, "category_id" to 1))

                migrate()

                scalar("SELECT count(*) FROM $newTitle") shouldBe "1"
                scalar("SELECT id FROM $newTitle") shouldBe "1"
                scalar("SELECT count(*) FROM ${stack}_category") shouldBe "2"
                scalar("SELECT count(*) FROM ${stack}_category WHERE $titleForeignKey = 1") shouldBe "2"
                execute("UPDATE $newTitle SET favorite = 1 WHERE id = 1")
                scalar("SELECT categories FROM $libraryView WHERE id = 1") shouldBe "1,2"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `library duplicate groups merge favorite categories without reviving removed copy categories`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                for (id in 1..3) {
                    title(id, favorite = id != 1)
                    seed("categories", mapOf("_id" to id, "sort" to id, "name" to "Category $id"))
                    seed(oldMembership, mapOf("_id" to id, titleForeignKey to id, "category_id" to id))
                }
                seed(oldMembership, mapOf("_id" to 4, titleForeignKey to 3, "category_id" to 2))

                migrate()

                scalar("SELECT count(*) FROM $newTitle") shouldBe "1"
                scalar("SELECT id FROM $newTitle") shouldBe "2"
                scalar("SELECT categories FROM $libraryView WHERE id = 2") shouldBe "2,3"
                scalar("SELECT count(*) FROM ${stack}_category") shouldBe "2"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `non duplicate titles and items preserve every stored field and related record`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                if (anime) title(30, favorite = false, url = "/parent", fetchType = 0)
                val titleValues = linkedMapOf<String, Any?>(
                    "_id" to 10, "source" to 35, "url" to "/title", "artist" to "Artist",
                    "author" to "Author", "description" to "Description", "genre" to "Action, Drama",
                    "title" to "Preserved title", "status" to 2, "thumbnail_url" to "/cover",
                    "favorite" to 1, "last_update" to 101, "next_update" to 202, "initialized" to 1,
                    "viewer" to 0, "${if (anime) "episode" else "chapter"}_flags" to 252,
                    "cover_last_modified" to 303, "date_added" to 404, "update_strategy" to 1,
                    "calculate_interval" to -7, "last_modified_at" to 505, "favorite_modified_at" to 606,
                    "version" to 17, "is_syncing" to 1,
                )
                if (anime) {
                    titleValues.putAll(
                        mapOf(
                            "fetch_type" to 1,
                            "parent_id" to 30,
                            "season_flags" to 0,
                            "season_number" to 3.5,
                            "season_source_order" to 8,
                            "background_url" to "/background",
                            "background_last_modified" to 707,
                        ),
                    )
                }
                seed(oldTitle, titleValues)
                val itemValues = linkedMapOf<String, Any?>(
                    "_id" to 20, titleForeignKey to 10, "url" to "/item", "name" to "Preserved item",
                    "scanlator" to "Group", (if (anime) "seen" else "read") to 1, "bookmark" to 1,
                    (if (anime) "last_second_seen" else "last_page_read") to 98,
                    "${if (anime) "episode" else "chapter"}_number" to 12.5,
                    "source_order" to 8, "date_fetch" to 808, "date_upload" to 909,
                    "last_modified_at" to 1001, "version" to 19, "is_syncing" to 1,
                )
                if (anime) {
                    itemValues.putAll(
                        mapOf(
                            "total_seconds" to 1440,
                            "summary" to "Episode summary",
                            "preview_url" to "/preview",
                            "fillermark" to 1,
                        ),
                    )
                }
                seed(oldItem, itemValues)
                if (!anime) {
                    execute("UPDATE mangas SET memo = x'7b226b6579223a227469746c65227d' WHERE _id = 10")
                    execute("UPDATE chapters SET memo = x'7b226b6579223a226974656d227d' WHERE _id = 20")
                    seed("excluded_scanlators", mapOf("manga_id" to 10, "scanlator" to "Excluded group"))
                }
                history(40, 20, 1111, 2222)
                seed("categories", mapOf("_id" to 4, "name" to "Hidden", "sort" to 27, "flags" to 77, "hidden" to 1))
                seed(oldMembership, mapOf("_id" to 6, titleForeignKey to 10, "category_id" to 4))
                val oldTrack = if (anime) "anime_sync" else "manga_sync"
                seed(
                    oldTrack,
                    mapOf(
                        "_id" to 50, titleForeignKey to 10, "sync_id" to 2, "remote_id" to 12345,
                        "library_id" to 67890, "title" to "Remote title",
                        (if (anime) "last_episode_seen" else "last_chapter_read") to 9.5,
                        (if (anime) "total_episodes" else "total_chapters") to 120,
                        "status" to 3, "score" to 8.5, "remote_url" to "/remote", "start_date" to 3333,
                        "finish_date" to 4444, "private" to 1,
                    ),
                )
                val oldSource = if (anime) "animesources" else "sources"
                seed(oldSource, mapOf("_id" to 35, "lang" to "es", "name" to "Source"))
                seed(
                    "extension_repos",
                    mapOf(
                        "base_url" to "https://repo.example.test",
                        "name" to "Repository",
                        "short_name" to "Repo",
                        "website" to "https://example.test",
                        "signing_key_fingerprint" to "fingerprint",
                    ),
                )
                if (anime) {
                    seed(
                        "custom_buttons",
                        mapOf(
                            "_id" to 99,
                            "name" to "Custom",
                            "isFavorite" to 1,
                            "sortIndex" to 23,
                            "content" to "print('tap')",
                            "longPressContent" to "print('hold')",
                            "onStartup" to "print('start')",
                        ),
                    )
                }

                val titleFields = titleFieldMapping(anime)
                val itemFields = itemFieldMapping(anime)
                titleFields.keys shouldBe columns(oldTitle).toSet()
                itemFields.keys shouldBe columns(oldItem).toSet()
                val titleBefore = snapshot(oldTitle, titleFields.keys, "_id = 10")
                val itemBefore = snapshot(oldItem, itemFields.keys, "_id = 20")
                val historyFields = columns(oldHistory)
                val historyBefore = snapshot(oldHistory, historyFields, "_id = 40")
                val trackFields = columns(oldTrack)
                val trackBefore = snapshot(oldTrack, trackFields, "_id = 50")
                val repoBefore = snapshot("extension_repos", columns("extension_repos"))
                val buttonBefore = if (anime) {
                    snapshot("custom_buttons", columns("custom_buttons"), "_id = 99")
                } else {
                    null
                }

                migrate()

                snapshot(newTitle, titleFields.values, "id = 10").values.toList() shouldBe titleBefore.values.toList()
                snapshot(newItem, itemFields.values, "id = 20").values.toList() shouldBe itemBefore.values.toList()
                snapshot(newHistory, historyFields.map { if (it == "_id") "id" else it }, "id = 40")
                    .values.toList() shouldBe historyBefore.values.toList()
                snapshot("${stack}_track", trackFields.map { if (it == "_id") "id" else it }, "id = 50")
                    .values.toList() shouldBe trackBefore.values.toList()
                snapshot("extension_repos", columns("extension_repos")) shouldBe repoBefore
                scalar("SELECT name || ':' || flags || ':' || hidden FROM category WHERE id = 4") shouldBe "Hidden:77:1"
                scalar("SELECT sort FROM category WHERE id = 4") shouldBe "0"
                scalar("SELECT category_id FROM ${stack}_category WHERE id = 6 AND $titleForeignKey = 10") shouldBe "4"
                scalar("SELECT lang || ':' || name FROM source WHERE id = 35") shouldBe "es:Source"
                if (anime) {
                    snapshot("custom_buttons", columns("custom_buttons"), "_id = 99") shouldBe buttonBefore
                    scalar("SELECT parent_id FROM anime WHERE id = 10") shouldBe "30"
                } else {
                    scalar("SELECT typeof(remote_memo) FROM manga WHERE id = 10") shouldBe "blob"
                    scalar("SELECT typeof(remote_memo) FROM chapter WHERE id = 20") shouldBe "blob"
                    scalar("SELECT scanlator FROM excluded_scanlator WHERE manga_id = 10") shouldBe "Excluded group"
                }
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `migrated triggers increment versions while restore writes suppress them`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true, version = 7)
                item(1, 1)
                migrate()
                assertIntegrity()
                val seenColumn = if (anime) "user_seen" else "user_read"
                val progressColumn = if (anime) "user_last_second_seen" else "user_last_page_read"
                val initialTitleVersion = scalar("SELECT version FROM $newTitle WHERE id = 1")!!.toLong()
                val initialItemVersion = scalar("SELECT version FROM $newItem WHERE id = 1")!!.toLong()

                execute("UPDATE $newItem SET $seenColumn = 1 WHERE id = 1")
                scalar("SELECT version FROM $newItem WHERE id = 1") shouldBe (initialItemVersion + 1).toString()
                scalar("SELECT version FROM $newTitle WHERE id = 1") shouldBe (initialTitleVersion + 1).toString()
                (scalar("SELECT last_modified_at FROM $newItem WHERE id = 1")!!.toLong() > 0) shouldBe true
                (scalar("SELECT last_modified_at FROM $newTitle WHERE id = 1")!!.toLong() > 0) shouldBe true

                execute("UPDATE $newTitle SET is_syncing = 1 WHERE id = 1")
                execute("UPDATE $newItem SET is_syncing = 1 WHERE id = 1")
                execute("UPDATE $newItem SET $progressColumn = 50, user_bookmark = 1 WHERE id = 1")
                execute("UPDATE $newTitle SET remote_description = 'Restored details', favorite = 0 WHERE id = 1")
                scalar("SELECT version FROM $newItem WHERE id = 1") shouldBe (initialItemVersion + 1).toString()
                scalar("SELECT version FROM $newTitle WHERE id = 1") shouldBe (initialTitleVersion + 1).toString()
                (scalar("SELECT favorite_modified_at FROM $newTitle WHERE id = 1")!!.toLong() > 0) shouldBe true

                seed("category", mapOf("id" to 4, "name" to "Category", "sort" to 0))
                seed("${stack}_category", mapOf("id" to 4, titleForeignKey to 1, "category_id" to 4))
                scalar("SELECT version FROM $newTitle WHERE id = 1") shouldBe (initialTitleVersion + 1).toString()

                execute("UPDATE $newTitle SET is_syncing = 0 WHERE id = 1")
                execute("UPDATE $newItem SET is_syncing = 0 WHERE id = 1")
                execute("UPDATE $newTitle SET remote_description = 'User update' WHERE id = 1")
                scalar("SELECT version FROM $newTitle WHERE id = 1") shouldBe (initialTitleVersion + 2).toString()
                seed("category", mapOf("id" to 5, "name" to "Another category", "sort" to 1))
                seed("${stack}_category", mapOf("id" to 5, titleForeignKey to 1, "category_id" to 5))
                scalar("SELECT version FROM $newTitle WHERE id = 1") shouldBe (initialTitleVersion + 3).toString()
                runCatching { execute("DELETE FROM category WHERE id = 0") }.isFailure shouldBe true
                scalar("SELECT count(*) FROM category WHERE id = 0") shouldBe "1"
                assertIntegrity()
            }
        }
    }

    private fun Fixture.columns(table: String): List<String> = driver.executeQuery(
        null,
        "PRAGMA table_info($table)",
        { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names += cursor.getString(1)!!
            app.cash.sqldelight.db.QueryResult.Value(names)
        },
        0,
    ).value

    private fun Fixture.snapshot(
        table: String,
        fields: Collection<String>,
        where: String = "1",
    ): Map<String, String?> = fields.associateWith { scalar("SELECT quote($it) FROM $table WHERE $where") }

    private fun titleFieldMapping(anime: Boolean): Map<String, String> = linkedMapOf(
        "_id" to "id", "source" to "source_id", "url" to "remote_url", "artist" to "remote_artist",
        "author" to "remote_author", "description" to "remote_description", "genre" to "remote_genre",
        "title" to "remote_title", "status" to "remote_status", "thumbnail_url" to "remote_cover",
        "favorite" to "favorite", "last_update" to "state_last_update", "next_update" to "state_next_update",
        "initialized" to "state_initialized", "viewer" to "user_viewer",
        "${if (anime) "episode" else "chapter"}_flags" to "user_${if (anime) "episode" else "chapter"}_flags",
        "cover_last_modified" to "state_cover_last_modified", "date_added" to "date_added",
        "update_strategy" to "remote_update_strategy", "calculate_interval" to "state_fetch_interval",
        "last_modified_at" to "last_modified_at", "favorite_modified_at" to "favorite_modified_at",
        "version" to "version", "is_syncing" to "is_syncing",
    ) + if (anime) {
        mapOf(
            "fetch_type" to "remote_fetch_type",
            "parent_id" to "parent_id",
            "season_flags" to "user_season_flags",
            "season_number" to "remote_season_number",
            "season_source_order" to "remote_season_source_order",
            "background_url" to "remote_background",
            "background_last_modified" to "state_background_last_modified",
        )
    } else {
        mapOf("memo" to "remote_memo")
    }

    private fun itemFieldMapping(anime: Boolean): Map<String, String> = linkedMapOf(
        "_id" to "id", "${if (anime) "anime" else "manga"}_id" to "${if (anime) "anime" else "manga"}_id",
        "url" to "remote_url", "name" to "remote_name", "scanlator" to "remote_scanlator",
        (if (anime) "seen" else "read") to (if (anime) "user_seen" else "user_read"),
        "bookmark" to "user_bookmark",
        (if (anime) "last_second_seen" else "last_page_read") to
            (if (anime) "user_last_second_seen" else "user_last_page_read"),
        "${if (anime) "episode" else "chapter"}_number" to "remote_${if (anime) "episode" else "chapter"}_number",
        "source_order" to "remote_source_order", "date_fetch" to "state_date_fetch",
        "date_upload" to "remote_date_upload",
        "last_modified_at" to "last_modified_at", "version" to "version", "is_syncing" to "is_syncing",
    ) + if (anime) {
        mapOf(
            "total_seconds" to "remote_total_seconds",
            "summary" to "remote_summary",
            "preview_url" to "remote_preview",
            "fillermark" to "user_fillermark",
        )
    } else {
        mapOf("memo" to "remote_memo")
    }
}
