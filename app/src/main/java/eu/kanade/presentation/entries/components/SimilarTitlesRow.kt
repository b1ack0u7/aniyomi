package eu.kanade.presentation.entries.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionState
import eu.kanade.tachiyomi.data.suggestions.suggestionCoverModel
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private val CardWidth = 96.dp

@Composable
fun SimilarTitlesRow(
    state: SuggestionState,
    onRequestLoad: () -> Unit,
    onItemClick: (SuggestionItem) -> Unit,
    onSeeAllClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = (state as? SuggestionState.Success)?.items.orEmpty()
    when (state) {
        SuggestionState.Disabled, SuggestionState.Empty -> return
        is SuggestionState.Success -> if (items.isEmpty()) return
        else -> Unit
    }

    // The tablet layout puts this row in a plain scrolling column, so visibility has to be
    // measured rather than inferred from composition. A sliver of the row peeks above the
    // fold on most entries, hence the fraction rather than any overlap at all.
    var loadRequested by remember { mutableStateOf(false) }
    val visibilityModifier = if (state is SuggestionState.Idle && !loadRequested) {
        Modifier.onGloballyPositioned { coordinates ->
            if (loadRequested) return@onGloballyPositioned
            val height = coordinates.size.height
            if (height > 0 && coordinates.boundsInWindow().height >= height * MIN_VISIBLE_FRACTION) {
                loadRequested = true
                onRequestLoad()
            }
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(visibilityModifier)
            // Asymmetric on purpose: the tag chips above carry 12dp of their own bottom
            // padding and the item header below only 4dp; this evens both gaps to 20dp.
            .padding(top = MaterialTheme.padding.small, bottom = MaterialTheme.padding.medium),
    ) {
        SimilarTitlesHeader(
            showSeeAll = items.isNotEmpty(),
            onSeeAllClick = onSeeAllClick,
        )

        when (state) {
            SuggestionState.Idle, SuggestionState.Loading -> SimilarTitlesPlaceholders()
            is SuggestionState.Error -> SimilarTitlesMessage(
                message = stringResource(AYMR.strings.similar_titles_error),
                actionLabel = stringResource(MR.strings.action_retry),
                onActionClick = onRetryClick,
            )
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(items, key = { it.providerId ?: it.providerUrl }) { item ->
                    SimilarTitleCard(item = item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

@Composable
private fun SimilarTitlesHeader(
    showSeeAll: Boolean,
    onSeeAllClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.padding.medium,
                end = MaterialTheme.padding.small,
                bottom = MaterialTheme.padding.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(AYMR.strings.similar_titles),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (showSeeAll) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onSeeAllClick)
                    .padding(
                        horizontal = MaterialTheme.padding.small,
                        vertical = MaterialTheme.padding.extraSmall,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                Text(
                    text = stringResource(AYMR.strings.similar_titles_see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SimilarTitleCard(
    item: SuggestionItem,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.width(CardWidth)) {
        ItemCover.Book(
            data = suggestionCoverModel(item),
            contentDescription = item.title,
            onClick = onClick,
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MaterialTheme.padding.extraSmall),
        )
    }
}

@Composable
private fun SimilarTitlesPlaceholders() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        userScrollEnabled = false,
    ) {
        items(PLACEHOLDER_COUNT) {
            Box(
                modifier = Modifier
                    .width(CardWidth)
                    .aspectRatio(ItemCover.Book.ratio)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun SimilarTitlesMessage(
    message: String,
    actionLabel: String,
    onActionClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onActionClick)
                .padding(MaterialTheme.padding.extraSmall),
        )
    }
}

private const val PLACEHOLDER_COUNT = 5

private const val MIN_VISIBLE_FRACTION = 0.5f
