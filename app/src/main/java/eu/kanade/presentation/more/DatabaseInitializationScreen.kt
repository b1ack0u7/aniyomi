package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import mihon.core.database.DatabaseInitializationState
import mihon.core.database.DatabaseOpenState
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun DatabaseInitializationScreen(
    state: DatabaseInitializationState,
    onRetry: () -> Unit,
) {
    val failure = state.databases.entries.firstOrNull { it.value is DatabaseOpenState.Failed }
    val clipboard = LocalClipboardManager.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            if (failure == null) {
                CircularProgressIndicator()
                Text(
                    stringResource(
                        if (state.databases.values.any { it == DatabaseOpenState.Migrating }) {
                            AYMR.strings.database_migrating
                        } else {
                            AYMR.strings.database_opening
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                val cause = (failure.value as DatabaseOpenState.Failed).cause
                val diagnostic = "${failure.key.filename}\n${cause.stackTraceToString()}"
                Text(
                    stringResource(AYMR.strings.database_open_failed),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(AYMR.strings.database_open_failed_summary))
                TextButton(onClick = onRetry) {
                    Text(stringResource(MR.strings.action_retry))
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(diagnostic)) }) {
                    Text(stringResource(MR.strings.action_copy_to_clipboard))
                }
                SelectionContainer {
                    Text(diagnostic, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
