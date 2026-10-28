package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.tachiyomi.network.SourceAccessRestriction
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

@Immutable
internal data class SourceAccessWarningState(
    val title: String,
    val code: String,
    val message: String,
    val waitMessage: String?,
    val canRetry: Boolean,
)

@Composable
internal fun rememberSourceAccessWarning(
    sourceName: String,
    restriction: SourceAccessRestriction,
): SourceAccessWarningState {
    var now by remember(restriction) { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(restriction, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                now = System.currentTimeMillis()
                if (restriction.canRetry(now)) break
                delay(1_000)
            } while (true)
        }
    }
    val seconds = restriction.remainingSeconds(now)
    val waiting = seconds > 0
    val combined = restriction.forbidden && restriction.rateLimited
    val title = when {
        combined && waiting -> AYMR.strings.source_access_denied_rate_limited_title
        restriction.forbidden -> AYMR.strings.source_access_denied_title
        else -> AYMR.strings.source_rate_limited_title
    }
    val code = when {
        combined && waiting -> AYMR.strings.source_access_denied_rate_limited_code
        combined -> AYMR.strings.source_access_denied_rate_limited_finished_code
        restriction.forbidden -> AYMR.strings.source_access_denied_code
        else -> AYMR.strings.source_rate_limited_code
    }
    val message = when {
        combined && waiting -> AYMR.strings.source_access_denied_rate_limited_summary
        restriction.forbidden -> AYMR.strings.source_access_denied_summary
        waiting -> AYMR.strings.source_rate_limited_summary
        else -> AYMR.strings.source_rate_limited_ready_summary
    }
    val waitMessage = when {
        waiting -> {
            val remaining = "${stringResource(MR.strings.minute_short, seconds / 60)} " +
                stringResource(MR.strings.seconds_short, seconds % 60)
            stringResource(AYMR.strings.source_rate_limited_countdown, remaining)
        }
        restriction.forbidden && restriction.retryAt != null ->
            stringResource(AYMR.strings.source_access_denied_wait_finished)
        restriction.rateLimited -> stringResource(AYMR.strings.source_rate_limited_ready)
        else -> null
    }
    return SourceAccessWarningState(
        title = stringResource(title),
        code = stringResource(code),
        message = stringResource(message, sourceName),
        waitMessage = waitMessage,
        canRetry = restriction.canRetry(now),
    )
}

@Composable
internal fun SourceAccessWarningTitle(state: SourceAccessWarningState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Warning, contentDescription = null)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.title, style = MaterialTheme.typography.titleMedium)
            Text(state.code, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun SourceAccessWarningBody(state: SourceAccessWarningState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        state.waitMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(AYMR.strings.source_cached_content_available), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SourceAccessWarningDialog(
    sourceName: String,
    restriction: SourceAccessRestriction,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberSourceAccessWarning(sourceName, restriction)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { SourceAccessWarningTitle(state) },
        text = { SourceAccessWarningBody(state) },
        confirmButton = {
            Button(onClick = onRetry, enabled = state.canRetry) {
                Text(stringResource(AYMR.strings.action_retry_source))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(AYMR.strings.source_action_back)) }
        },
    )
}
