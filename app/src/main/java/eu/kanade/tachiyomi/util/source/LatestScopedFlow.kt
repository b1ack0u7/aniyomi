package eu.kanade.tachiyomi.util.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

internal fun <T, R> Flow<T>.mapWithLatestScope(transform: suspend CoroutineScope.(T) -> R): Flow<R> =
    transformLatest { value ->
        coroutineScope {
            emit(transform(value))
            awaitCancellation()
        }
    }
