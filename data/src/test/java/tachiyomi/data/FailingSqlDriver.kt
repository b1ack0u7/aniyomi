package tachiyomi.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

internal class FailingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var insertFailure: Exception? = null

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (sql.startsWith("INSERT INTO chapters") || sql.startsWith("INSERT INTO episodes")) {
            insertFailure?.let { throw it }
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
