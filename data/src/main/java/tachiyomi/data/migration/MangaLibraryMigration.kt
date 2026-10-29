package tachiyomi.data.migration

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object MangaLibraryMigration {
    val mergeMemos = AfterVersion(34, ::mergeDuplicateMemos)

    private fun mergeDuplicateMemos(driver: SqlDriver) {
        val triggers = driver.executeQuery(
            null,
            "SELECT name, sql FROM sqlite_master WHERE type = 'trigger' " +
                "AND name IN ('update_last_modified_at_mangas', 'update_last_modified_at_chapters')",
            { cursor ->
                val definitions = mutableListOf<Pair<String, String>>()
                while (cursor.next().value) {
                    definitions += requireNotNull(cursor.getString(0)) to requireNotNull(cursor.getString(1))
                }
                QueryResult.Value(definitions)
            },
            0,
        ).value
        // Updating migration metadata must preserve the original modification times.
        triggers.forEach { (name, _) -> driver.execute(null, "DROP TRIGGER $name", 0) }

        driver.executeQuery(
            null,
            "SELECT kind, target_id, memo FROM migration_manga_memo ORDER BY kind, target_id, precedence, old_id",
            { cursor ->
                var currentKind = -1L
                var currentId = -1L
                val merged = linkedMapOf<String, JsonElement>()
                fun save() {
                    if (currentKind < 0) return
                    val table = if (currentKind == 0L) "manga" else "chapter"
                    driver.execute(null, "UPDATE $table SET remote_memo = ? WHERE id = ?", 2) {
                        bindBytes(0, JsonObject(merged).toString().encodeToByteArray())
                        bindLong(1, currentId)
                    }
                }
                while (cursor.next().value) {
                    val kind = requireNotNull(cursor.getLong(0))
                    val id = requireNotNull(cursor.getLong(1))
                    if (kind != currentKind || id != currentId) {
                        save()
                        merged.clear()
                        currentKind = kind
                        currentId = id
                    }
                    val memo = requireNotNull(cursor.getBytes(2))
                    val decoded = try {
                        Json.parseToJsonElement(memo.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                            ?: error("Expected a JSON object")
                    } catch (failure: Exception) {
                        throw IllegalStateException(
                            "Cannot merge invalid ${if (kind == 0L) "manga" else "chapter"} memo for id $id",
                            failure,
                        )
                    }
                    decoded.forEach { (key, value) -> merged.putIfAbsent(key, value) }
                }
                save()
                QueryResult.Unit
            },
            0,
        ).value
        triggers.forEach { (_, sql) -> driver.execute(null, sql, 0) }
        driver.execute(null, "DROP TABLE migration_manga_memo", 0)
    }
}
