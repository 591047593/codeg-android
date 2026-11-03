package app.codeg.android.core.designsystem.component

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 批v154 — keep a Dialog's window from reacting to the keyboard.
 *
 * Any dialog that sizes itself with `imePadding()` must NOT also let the window
 * pan or resize: the two fight each other and the content bounces (or the field
 * ends up behind the IME anyway, since the window pan and the padding offset are
 * applied independently). `ADJUST_NOTHING` leaves the insets to Compose, so the
 * single animation source is the padding.
 *
 * androidx only re-stamps the dialog's soft input mode on API < 31, so one set is
 * stable on the main flavor (minSdk 31); the legacy flavor (minSdk 28) re-applies
 * the platform default on later focus changes and simply degrades to the stock
 * behaviour. Call this from the dialog's content:
 *
 * ```
 * Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
 *     holdDialogWindowStill()
 *     ...
 * }
 * ```
 */
@Composable
fun holdDialogWindowStill() {
    // `.parent` — the DialogLayout, not the inner compose view, implements
    // DialogWindowProvider.
    val view = LocalView.current
    LaunchedEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
        )
    }
}