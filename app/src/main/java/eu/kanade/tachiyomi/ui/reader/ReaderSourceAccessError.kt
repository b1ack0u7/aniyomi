package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceAccessRestriction
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import tachiyomi.core.common.util.lang.causeChain

internal fun Throwable?.isSourceAccessError(): Boolean {
    val causes = this?.causeChain() ?: return false
    return causes.none { it is CloudflareChallengeException } &&
        causes.any {
            when (it) {
                is SourceRequestPausedException -> it.forbidden || it.rateLimited
                is HttpException -> it.code in setOf(403, 429)
                else -> false
            }
        }
}

internal suspend fun ReaderPage.collectSourceAccessWarnings(
    restrictions: Flow<SourceAccessRestriction?>,
    dialogAvailable: Flow<Boolean>,
    onWarning: (SourceAccessRestriction) -> Boolean,
) {
    var presentedError: Throwable? = null
    combine(statusFlow, errorFlow, restrictions, dialogAvailable) { status, error, restriction, available ->
        if (status == Page.State.ERROR && error.isSourceAccessError() && restriction != null) {
            Triple(error, restriction, available)
        } else {
            null
        }
    }.collect { candidate ->
        if (candidate == null) {
            presentedError = null
        } else if (candidate.third && candidate.first !== presentedError) {
            currentCoroutineContext().ensureActive()
            if (onWarning(candidate.second)) presentedError = candidate.first
        }
    }
}
