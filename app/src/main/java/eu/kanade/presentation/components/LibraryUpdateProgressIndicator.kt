package eu.kanade.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.tachiyomi.data.library.LibraryUpdateProgress
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun LibraryUpdateProgressIndicator(
    progress: LibraryUpdateProgress,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = if (progress.title != null) {
                stringResource(
                    AYMR.strings.library_update_progress_entry,
                    progress.current,
                    progress.total,
                    progress.title,
                )
            } else {
                stringResource(AYMR.strings.library_update_progress, progress.current, progress.total)
            },
            fontStyle = FontStyle.Italic,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { progress.current.toFloat() / progress.total },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaterialTheme.padding.extraSmall),
        )
    }
}

/**
 * Variant that expands and collapses itself instead of popping in and out of the layout, for screens
 * where the indicator sits above content that would otherwise jump.
 *
 * [modifier] is applied to the indicator rather than to the wrapper so that its padding collapses too.
 */
@Composable
fun AnimatedLibraryUpdateProgressIndicator(
    progress: LibraryUpdateProgress?,
    modifier: Modifier = Modifier,
) {
    val visible = progress != null && progress.total > 0
    var lastProgress by remember { mutableStateOf(progress) }
    if (visible) lastProgress = progress

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        // Kept around so the bar doesn't blank out while collapsing
        lastProgress?.let { LibraryUpdateProgressIndicator(progress = it, modifier = modifier) }
    }
}
