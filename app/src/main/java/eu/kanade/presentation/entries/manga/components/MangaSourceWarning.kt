package eu.kanade.presentation.entries.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SourceAccessWarningBody
import eu.kanade.presentation.components.SourceAccessWarningTitle
import eu.kanade.presentation.components.rememberSourceAccessWarning
import eu.kanade.tachiyomi.network.SourceAccessRestriction
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MangaSourceWarning(
    sourceName: String,
    restriction: SourceAccessRestriction?,
    isRetrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (restriction == null) return
    val state = rememberSourceAccessWarning(sourceName, restriction)

    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceAccessWarningTitle(state)
            SourceAccessWarningBody(state)
            Button(
                modifier = Modifier.align(Alignment.End),
                enabled = !isRetrying && state.canRetry,
                onClick = onRetry,
            ) {
                Text(
                    stringResource(AYMR.strings.action_retry_source),
                )
            }
        }
    }
}
