package eu.kanade.presentation.entries.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

const val COLLAPSED_ITEM_COUNT = 5

// Separators (missing-count markers) count as decoration, not as entries, so a gap in the
// numbering never eats into the chapters the user is meant to see.
fun <T> List<T>.collapsedTo(limit: Int, isEntry: (T) -> Boolean): List<T> {
    var kept = 0
    val result = ArrayList<T>(minOf(size, limit * 2))
    for (item in this) {
        if (isEntry(item)) {
            if (kept >= limit) break
            kept++
        }
        result += item
    }
    return result
}

// [totalCount] is the size of the *full* list, so the label tells the user what they would
// get rather than what they can already see.
@Composable
fun ShowAllItemsButton(
    expanded: Boolean,
    totalCount: Int,
    isManga: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onClick) {
            Text(
                text = if (expanded) {
                    stringResource(AYMR.strings.action_show_less)
                } else {
                    val plural = if (isManga) {
                        AYMR.plurals.action_show_all_chapters
                    } else {
                        AYMR.plurals.action_show_all_episodes
                    }
                    pluralStringResource(plural, count = totalCount, totalCount)
                },
            )
        }
    }
}
