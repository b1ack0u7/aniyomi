package tachiyomi.data.migration

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.Database
import tachiyomi.mi.data.AnimeDatabase

class LibrarySchemaMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `migration preserves survivor settings and combines progress without multiplying history`(stack: String) {
        Fixture(stack, damagedConstraints = true).use { f ->
            with(f) {
                title(1, favorite = false, viewer = 7, artist = "Author")
                title(2, favorite = true, viewer = 0, version = 9)
                item(1, 1, read = true, bookmark = true, progress = 12)
                item(2, 2, read = false, bookmark = false, progress = 3)
                history(10, 1, 1, 10)
                history(11, 1, 2, 20)
                history(12, 2, 3, 30)
                seed("categories", mapOf("_id" to 1, "name" to "Hidden", "sort" to 9, "flags" to 14, "hidden" to 1))
                seed(oldMembership, mapOf("_id" to 1, titleForeignKey to 2, "category_id" to 1))
                seed(oldMembership, mapOf("_id" to 2, titleForeignKey to 2, "category_id" to 1))
                track(1, 1, 100, 100.0)
                track(2, 2, 200, 2.0)
                if (!anime) {
                    seed("excluded_scanlators", mapOf("manga_id" to 1, "scanlator" to "Excluded"))
                    seed("excluded_scanlators", mapOf("manga_id" to 2, "scanlator" to "Excluded"))
                }

                val expectedVersion = scalar("SELECT version FROM $oldTitle WHERE _id = 2")
                migrate()

                scalar("SELECT count(*) FROM $newTitle") shouldBe "1"
                scalar("SELECT id FROM $newTitle") shouldBe "2"
                scalar("SELECT user_viewer FROM $newTitle") shouldBe "0"
                scalar("SELECT version FROM $newTitle") shouldBe expectedVersion
                scalar("SELECT remote_artist FROM $newTitle") shouldBe "Author"
                scalar("SELECT count(*) FROM $newItem") shouldBe "1"
                scalar("SELECT id FROM $newItem") shouldBe "1"
                scalar("SELECT $titleForeignKey FROM $newItem") shouldBe "2"
                scalar("SELECT ${if (anime) "user_seen" else "user_read"} FROM $newItem") shouldBe "1"
                scalar("SELECT user_bookmark FROM $newItem") shouldBe "1"
                scalar("SELECT ${if (anime) "user_last_second_seen" else "user_last_page_read"} FROM $newItem") shouldBe
                    "12"
                scalar("SELECT count(*) FROM $newHistory") shouldBe "1"
                scalar("SELECT ${if (anime) "last_seen" else "last_read"} FROM $newHistory") shouldBe "3"
                if (!anime) scalar("SELECT time_read FROM history") shouldBe "60"
                scalar("SELECT count(*) FROM ${stack}_category") shouldBe "1"
                scalar("SELECT hidden FROM category WHERE id = 1") shouldBe "1"
                scalar("SELECT sort FROM category WHERE id = 1") shouldBe "0"
                scalar("SELECT remote_id FROM ${stack}_track") shouldBe "200"
                if (!anime) scalar("SELECT count(*) FROM excluded_scanlator") shouldBe "1"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `library has one row per title and keeps all categories`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true)
                title(2, favorite = true, url = "/other")
                for (id in 1..2) {
                    seed("categories", mapOf("_id" to id, "sort" to id, "name" to "Category $id"))
                    seed(oldMembership, mapOf("_id" to id, titleForeignKey to 1, "category_id" to id))
                }
                item(1, 1, read = true)
                migrate()

                scalar("SELECT count(*) FROM $libraryView") shouldBe "2"
                scalar("SELECT categories FROM $libraryView WHERE id = 1") shouldBe "1,2"
                scalar("SELECT categories FROM $libraryView WHERE id = 2") shouldBe "0"
                scalar("SELECT ${if (anime) "seenCount" else "readCount"} FROM $libraryView WHERE id = 1") shouldBe "1"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `failed migration rolls back all tables and can be reopened`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true)
                item(1, 1)
                val failing = object : SqlDriver by driver {
                    override fun execute(
                        identifier: Int?,
                        sql: String,
                        parameters: Int,
                        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
                    ): QueryResult<Long> {
                        if (sql.startsWith("INSERT INTO $newItem(")) error("injected migration failure")
                        return driver.execute(identifier, sql, parameters, binders)
                    }
                }

                runCatching { migrate(failing) }.exceptionOrNull()?.message shouldBe "injected migration failure"
                scalar("SELECT count(*) FROM $oldTitle") shouldBe "1"
                scalar("SELECT count(*) FROM $oldItem") shouldBe "1"
                scalar("PRAGMA user_version") shouldBe oldVersion.toString()
                scalar("SELECT count(*) FROM sqlite_temp_master WHERE type = 'table'") shouldBe "0"

                migrate()
                scalar("SELECT count(*) FROM $newTitle") shouldBe "1"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `orphan rows are removed while independent tables survive`(stack: String) {
        Fixture(stack).use { f ->
            with(f) {
                title(1, favorite = true)
                item(1, 999)
                history(1, 999, 1, 1)
                seed(oldMembership, mapOf("_id" to 1, titleForeignKey to 999, "category_id" to 99))
                track(1, 999, 5, 1.0)
                if (!anime) seed("excluded_scanlators", mapOf("manga_id" to 999, "scanlator" to "Orphan"))
                seed("extension_repos", mapOf("base_url" to "https://example.test", "name" to "Repo"))
                migrate()
                scalar("SELECT count(*) FROM $newItem") shouldBe "0"
                scalar("SELECT count(*) FROM $newHistory") shouldBe "0"
                scalar("SELECT count(*) FROM ${stack}_track") shouldBe "0"
                scalar("SELECT count(*) FROM ${stack}_category") shouldBe "0"
                scalar("SELECT count(*) FROM extension_repos") shouldBe "1"
                assertIntegrity()
            }
        }
    }

    @Test
    fun `season children outside the library contribute after their parents merge`() {
        Fixture("anime").use { f ->
            with(f) {
                title(1, favorite = true, fetchType = 0)
                title(2, favorite = false, fetchType = 0)
                title(3, favorite = false, url = "/season", parentId = 2)
                item(1, 3, read = true, bookmark = true, filler = true)
                migrate()
                scalar("SELECT parent_id FROM anime WHERE id = 3") shouldBe "1"
                scalar("SELECT totalCount FROM animelibView WHERE id = 1") shouldBe "1"
                scalar("SELECT seenCount FROM animelibView WHERE id = 1") shouldBe "1"
                scalar("SELECT bookmarkCount FROM animelibView WHERE id = 1") shouldBe "1"
                scalar("SELECT fillermarkCount FROM animelibView WHERE id = 1") shouldBe "1"
                scalar("SELECT count(*) FROM animeseasonsView WHERE parent_id = 1") shouldBe "1"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["type", "parents", "self", "cycle"])
    fun `structural anime conflicts keep the previous database intact`(conflict: String) {
        Fixture("anime").use { f ->
            with(f) {
                when (conflict) {
                    "type" -> {
                        title(1, favorite = true, fetchType = 0)
                        title(2, favorite = false, fetchType = 1)
                    }
                    "parents" -> {
                        title(1, favorite = true, parentId = 3)
                        title(2, favorite = false, parentId = 4)
                        title(3, favorite = false, url = "/parent1")
                        title(4, favorite = false, url = "/parent2")
                    }
                    "self" -> {
                        title(1, favorite = true)
                        title(2, favorite = false, parentId = 1)
                    }
                    "cycle" -> {
                        title(1, favorite = true, parentId = 2)
                        title(2, favorite = false, url = "/other", parentId = 1)
                    }
                }
                val before = scalar("SELECT count(*) FROM animes")
                val failure = runCatching { migrate() }.exceptionOrNull()
                (failure != null) shouldBe true
                failure!!.message!!.contains("anime_") shouldBe true
                scalar("SELECT count(*) FROM animes") shouldBe before
                scalar("PRAGMA user_version") shouldBe "136"
                scalar("SELECT count(*) FROM sqlite_master WHERE name = 'anime'") shouldBe "0"
            }
        }
    }

    @Test
    fun `missing season parents are cleared and an empty season keeps its existing seen semantics`() {
        Fixture("anime").use { f ->
            with(f) {
                title(1, favorite = true, fetchType = 0)
                title(2, favorite = false, url = "/empty-season", parentId = 1)
                title(3, favorite = false, url = "/orphan-parent", parentId = 999)
                migrate()
                scalar("SELECT parent_id IS NULL FROM anime WHERE id = 3") shouldBe "1"
                scalar("SELECT seenCount FROM animelibView WHERE id = 1") shouldBe "1"
                assertIntegrity()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `migrated schema matches a fresh installation`(stack: String) {
        Fixture(stack).use { fixture ->
            fixture.migrate()
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { fresh ->
                if (stack == "anime") AnimeDatabase.Schema.create(fresh) else Database.Schema.create(fresh)
                fixture.schemaObjects(fixture.driver) shouldBe fixture.schemaObjects(fresh)
            }
        }
    }

    @Test
    fun `empty binary memos do not hide a nonempty duplicate memo`() {
        Fixture("manga").use { f ->
            with(f) {
                title(1, favorite = true)
                title(2, favorite = false)
                execute("UPDATE mangas SET memo = X'7b7d' WHERE _id = 1")
                execute("UPDATE mangas SET memo = CAST('{\"key\":\"value\"}' AS BLOB) WHERE _id = 2")
                item(1, 1)
                item(2, 2)
                execute("UPDATE chapters SET memo = X'7b7d' WHERE _id = 1")
                execute("UPDATE chapters SET memo = CAST('{\"key\":\"value\"}' AS BLOB) WHERE _id = 2")
                migrate()
                scalar("SELECT CAST(remote_memo AS TEXT) FROM manga") shouldBe "{\"key\":\"value\"}"
                scalar("SELECT typeof(remote_memo) FROM manga") shouldBe "blob"
                scalar("SELECT typeof(remote_memo) FROM chapter") shouldBe "blob"
                scalar("SELECT CAST(remote_memo AS TEXT) FROM chapter") shouldBe "{\"key\":\"value\"}"
            }
        }
    }

    @Test
    fun `long season chains migrate without materializing every ancestor pair`() {
        Fixture("anime").use { f ->
            with(f) {
                execute("BEGIN")
                for (id in 1..10_000) {
                    title(id, favorite = id == 1, url = "/$id", parentId = if (id == 1) null else id - 1)
                }
                execute("COMMIT")
                migrate()
                scalar("SELECT count(*) FROM anime") shouldBe "10000"
                scalar("SELECT parent_id FROM anime WHERE id = 10000") shouldBe "9999"
                assertIntegrity()
            }
        }
    }

    internal class Fixture(stack: String, damagedConstraints: Boolean = false) : AutoCloseable {
        val anime = stack == "anime"
        val oldTitle = if (anime) "animes" else "mangas"
        val oldItem = if (anime) "episodes" else "chapters"
        val oldMembership = if (anime) "animes_categories" else "mangas_categories"
        val oldHistory = if (anime) "animehistory" else "history"
        val newTitle = if (anime) "anime" else "manga"
        val newItem = if (anime) "episode" else "chapter"
        val newHistory = if (anime) "anime_history" else "history"
        val titleForeignKey = if (anime) "anime_id" else "manga_id"
        val itemForeignKey = if (anime) "episode_id" else "chapter_id"
        val libraryView = if (anime) "animelibView" else "libraryView"
        val oldVersion = if (anime) 136L else 34L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        init {
            var schema = javaClass.getResource("/migrations/$stack-before.sql")!!.readText()
            if (damagedConstraints) {
                schema = schema.replace("$itemForeignKey INTEGER NOT NULL UNIQUE", "$itemForeignKey INTEGER NOT NULL")
                    .replace(Regex("UNIQUE \\($titleForeignKey, sync_id\\) ON CONFLICT REPLACE,"), "")
            }
            schema.split("-- statement-break").filter { it.isNotBlank() }.forEach(::execute)
            execute("PRAGMA user_version = $oldVersion")
        }

        fun execute(sql: String) {
            driver.execute(null, sql, 0)
        }

        fun scalar(sql: String): String? = driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            0,
        ).value

        fun seed(table: String, values: Map<String, Any?>) {
            val defaults = driver.executeQuery(
                null,
                "PRAGMA table_info($table)",
                { cursor ->
                    val required = linkedMapOf<String, Any?>()
                    while (cursor.next().value) {
                        if (cursor.getLong(3) == 1L && cursor.getString(4) == null && cursor.getLong(5) == 0L) {
                            val name = cursor.getString(1)!!
                            required[name] = when {
                                name == "memo" -> "{}"
                                cursor.getString(2) == "TEXT" -> ""
                                else -> 0
                            }
                        }
                    }
                    QueryResult.Value(required)
                },
                0,
            ).value
            defaults.putAll(values)
            val encoded = defaults.values.joinToString { value ->
                when (value) {
                    null -> "NULL"
                    is Number -> value.toString()
                    is Boolean -> if (value) "1" else "0"
                    else -> "'${value.toString().replace("'", "''")}'"
                }
            }
            execute("INSERT INTO $table (${defaults.keys.joinToString()}) VALUES ($encoded)")
        }

        fun title(
            id: Int,
            favorite: Boolean,
            url: String = "/same",
            viewer: Int = 0,
            artist: String? = null,
            version: Int = 0,
            fetchType: Int = 1,
            parentId: Int? = null,
        ) {
            val fields = mutableMapOf<String, Any?>(
                "_id" to id,
                "source" to 3,
                "url" to url,
                "favorite" to favorite,
                "viewer" to viewer,
                "artist" to artist,
                "version" to version,
            )
            if (anime) fields.putAll(mapOf("fetch_type" to fetchType, "parent_id" to parentId))
            seed(oldTitle, fields)
        }

        fun item(
            id: Int,
            titleId: Int,
            read: Boolean = false,
            bookmark: Boolean = false,
            progress: Int = 0,
            filler: Boolean = false,
        ) {
            val fields = mutableMapOf<String, Any?>(
                "_id" to id,
                titleForeignKey to titleId,
                "url" to "/item",
                (if (anime) "seen" else "read") to read,
                "bookmark" to bookmark,
                (if (anime) "last_second_seen" else "last_page_read") to progress,
            )
            if (anime) fields["fillermark"] = filler
            seed(oldItem, fields)
        }

        fun history(id: Int, itemId: Int, date: Int, duration: Int) {
            val fields = mutableMapOf<String, Any?>(
                "_id" to id,
                itemForeignKey to itemId,
                (if (anime) "last_seen" else "last_read") to date,
            )
            if (!anime) fields["time_read"] = duration
            seed(oldHistory, fields)
        }

        fun track(id: Int, titleId: Int, remoteId: Int, progress: Double) {
            seed(
                if (anime) "anime_sync" else "manga_sync",
                mapOf(
                    "_id" to id,
                    titleForeignKey to titleId,
                    "sync_id" to 1,
                    "remote_id" to remoteId,
                    (if (anime) "last_episode_seen" else "last_chapter_read") to progress,
                ),
            )
        }

        fun migrate(migrationDriver: SqlDriver = driver) {
            execute("BEGIN EXCLUSIVE")
            try {
                if (anime) {
                    AnimeDatabase.Schema.migrate(migrationDriver, oldVersion, AnimeDatabase.Schema.version)
                    execute("PRAGMA user_version = ${AnimeDatabase.Schema.version}")
                } else {
                    Database.Schema.migrate(
                        migrationDriver,
                        oldVersion,
                        Database.Schema.version,
                        MangaLibraryMigration.mergeMemos,
                    )
                    execute("PRAGMA user_version = ${Database.Schema.version}")
                }
                execute("COMMIT")
            } catch (failure: Throwable) {
                execute("ROLLBACK")
                throw failure
            }
        }

        fun schemaObjects(sqlDriver: SqlDriver): List<String> = sqlDriver.executeQuery(
            null,
            "SELECT type || ':' || name || ':' || sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
            { cursor ->
                val objects = mutableListOf<String>()
                while (cursor.next().value) {
                    objects += cursor.getString(0)!!.replace(Regex("--[^\\n]*"), "")
                        .replace(Regex("\\s+"), " ").trim().lowercase()
                }
                QueryResult.Value(objects)
            },
            0,
        ).value

        fun assertIntegrity() {
            scalar("PRAGMA integrity_check") shouldBe "ok"
            scalar("PRAGMA foreign_key_check") shouldBe null
            scalar("SELECT count(*) FROM sqlite_temp_master WHERE type = 'table'") shouldBe "0"
            execute("PRAGMA foreign_keys = ON")
        }

        override fun close() = driver.close()
    }
}
