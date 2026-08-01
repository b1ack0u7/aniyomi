package eu.kanade.tachiyomi.ui.player.controls.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The spinner shown while the player is waiting on data, wrapped in a translucent panel that
 * explains what is going on.
 *
 * Without the explanation an unplayable server is indistinguishable from a slow one: the user just
 * sees a spinner forever. When the stall watchdog is counting down, this names the server it is
 * about to switch to and how long is left.
 */
@Composable
fun PlayerLoadingIndicator(
    currentVideoLabel: String?,
    stallInfo: PlayerViewModel.StallInfo?,
    panelOpacity: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = panelOpacity / 100f))
            .padding(
                horizontal = MaterialTheme.padding.large,
                vertical = MaterialTheme.padding.medium,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        CircularProgressIndicator(Modifier.size(64.dp))

        Text(
            text = currentVideoLabel?.let { stringResource(AYMR.strings.player_loading_video, it) }
                ?: stringResource(AYMR.strings.player_loading_generic),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (stallInfo != null) {
            Text(
                text = stringResource(AYMR.strings.player_stalled),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )

            Text(
                text = when {
                    stallInfo.nextVideoLabel == null ->
                        stringResource(AYMR.strings.player_stalled_no_alternative)
                    stallInfo.secondsUntilSwitch <= 0 ->
                        stringResource(AYMR.strings.player_stalled_switching_now)
                    else -> stringResource(
                        AYMR.strings.player_stalled_switching_in,
                        stallInfo.nextVideoLabel,
                        stallInfo.secondsUntilSwitch,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
