package tachiyomi.data.handlers.manga

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database

class AndroidMangaDatabaseHandlerInitializationTest {

    @Test
    fun `await does not inspect the driver or run queries until initialization completes`() = runBlocking<Unit> {
        val ready = CompletableDeferred<Unit>()
        val driver = mockk<SqlDriver>()
        every { driver.currentTransaction() } returns null
        val handler = AndroidMangaDatabaseHandler(
            mockk(),
            driver,
            queryDispatcher = Dispatchers.Unconfined,
            awaitReady = { ready.await() },
        )
        var queried = false
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            handler.await {
                queried = true
                42
            }
        }

        queried shouldBe false
        result.isCompleted shouldBe false
        verify(exactly = 0) { driver.currentTransaction() }
        ready.complete(Unit)
        result.await() shouldBe 42
        queried shouldBe true
        verify(exactly = 1) { driver.currentTransaction() }
    }

    @Test
    fun `subscriptions resume after readiness without rebuilding their collectors`() = runBlocking<Unit> {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val ready = CompletableDeferred<Unit>()
            val handler = AndroidMangaDatabaseHandler(
                mockk(),
                driver,
                queryDispatcher = Dispatchers.Unconfined,
                awaitReady = { ready.await() },
            )
            val query = Query(1, arrayOf("value"), driver, "test.sql", "value", "SELECT 42") { cursor ->
                requireNotNull(cursor.getLong(0))
            }
            val consumers = listOf(
                async(start = CoroutineStart.UNDISPATCHED) { handler.subscribeToList { query }.first() },
                async(start = CoroutineStart.UNDISPATCHED) { handler.subscribeToOne { query }.first() },
                async(start = CoroutineStart.UNDISPATCHED) { handler.subscribeToOneOrNull { query }.first() },
            )

            consumers.forEach { it.isCompleted shouldBe false }
            ready.complete(Unit)
            consumers.map { it.await() } shouldBe listOf(listOf(42L), 42L, 42L)
        }
    }

    @Test
    fun `all subscriptions defer query construction and cancellation removes the wait`() = runBlocking<Unit> {
        val ready = CompletableDeferred<Unit>()
        val handler = AndroidMangaDatabaseHandler(mockk(), mockk(), awaitReady = { ready.await() })
        val query = mockk<Query<Long>>(relaxed = true)
        var constructed = 0
        val queryBlock: Database.() -> Query<Long> = {
            constructed++
            query
        }
        val subscriptions: List<Flow<*>> = listOf(
            handler.subscribeToList(queryBlock),
            handler.subscribeToOne(queryBlock),
            handler.subscribeToOneOrNull(queryBlock),
        )
        val consumers = subscriptions.map { flow ->
            async(start = CoroutineStart.UNDISPATCHED) { flow.first() }
        }

        constructed shouldBe 0
        consumers.forEach {
            it.isCompleted shouldBe false
            it.cancelAndJoin()
        }
        ready.complete(Unit)
        constructed shouldBe 0
    }
}
