package mihon.core.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DatabaseInitializer(
    private val openManga: () -> Unit,
    private val openAnime: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(DatabaseInitializationState())
    val state = _state.asStateFlow()

    suspend fun open() = withContext(dispatcher) {
        mutex.withLock {
            for (database in DatabaseKind.entries) {
                if (_state.value.databases[database] == DatabaseOpenState.Ready) continue
                setState(database, DatabaseOpenState.Opening)
                try {
                    when (database) {
                        DatabaseKind.Manga -> openManga()
                        DatabaseKind.Anime -> openAnime()
                    }
                    setState(database, DatabaseOpenState.Ready)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(database, e)
                    return@withLock
                }
            }
        }
    }

    suspend fun awaitReady() {
        state.first { it.isReady }
    }

    suspend fun awaitReadyOrFailure(): Boolean {
        return state.first { state ->
            state.isReady || state.databases.values.any { it is DatabaseOpenState.Failed }
        }.isReady
    }

    fun migrating(database: DatabaseKind) = setState(database, DatabaseOpenState.Migrating)

    fun failed(database: DatabaseKind, cause: Throwable) = setState(database, DatabaseOpenState.Failed(cause))

    private fun setState(database: DatabaseKind, state: DatabaseOpenState) {
        _state.update { it.copy(databases = it.databases + (database to state)) }
    }
}

enum class DatabaseKind(val filename: String) {
    Manga("tachiyomi.db"),
    Anime("tachiyomi.animedb"),
}

sealed interface DatabaseOpenState {
    data object Opening : DatabaseOpenState
    data object Migrating : DatabaseOpenState
    data object Ready : DatabaseOpenState
    data class Failed(val cause: Throwable) : DatabaseOpenState
}

data class DatabaseInitializationState(
    val databases: Map<DatabaseKind, DatabaseOpenState> = DatabaseKind.entries.associateWith {
        DatabaseOpenState.Opening
    },
) {
    val isReady: Boolean
        get() = databases.values.all { it == DatabaseOpenState.Ready }
}
