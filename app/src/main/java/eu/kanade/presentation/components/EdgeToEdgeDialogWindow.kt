package eu.kanade.presentation.components

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Lays the dialog window out over the whole display instead of letting the window manager shrink it
 * to the area between the system bars.
 *
 * A dialog that disables `usePlatformDefaultWidth` has its content measured against
 * `Configuration.screenHeightDp`, which since targetSdk 35 no longer excludes the system bars. That
 * leaves the content measured taller than the window it is placed in, pushing whatever is aligned to
 * the bottom past the edge of the screen. Making the window match what was measured keeps the two in
 * sync, so the content can inset itself through the padding modifiers.
 *
 * Note that the system bar insets aren't dispatched to a dialog's own composition — they all resolve
 * to zero in there — so they have to be read outside of the dialog and passed into it.
 */
@Composable
fun EdgeToEdgeDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    }
}
