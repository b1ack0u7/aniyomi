package mihon.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import tachiyomi.data.migration.MangaLibraryMigration

class DatabaseMigrationCallback(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    private val database: DatabaseKind,
    private val initializer: () -> DatabaseInitializer,
) : AndroidSqliteDriver.Callback(
    schema,
    *if (database == DatabaseKind.Manga) arrayOf(MangaLibraryMigration.mergeMemos) else emptyArray(),
) {

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        // The helper starts the upgrade transaction after onConfigure; rebuilding tables needs FK checks off.
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        initializer().migrating(database)
        try {
            super.onUpgrade(db, oldVersion, newVersion)
            db.query("PRAGMA foreign_key_check").use { cursor ->
                check(!cursor.moveToFirst()) { "Foreign key check failed after migrating ${database.filename}" }
            }
        } catch (e: Exception) {
            initializer().failed(database, e)
            throw e
        }
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        db.setForeignKeyConstraintsEnabled(true)
        for (pragma in listOf("journal_mode = WAL", "synchronous = NORMAL")) {
            db.query("PRAGMA $pragma").use { it.moveToFirst() }
        }
    }
}
