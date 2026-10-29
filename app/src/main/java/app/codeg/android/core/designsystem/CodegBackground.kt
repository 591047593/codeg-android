package app.codeg.android.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The app backdrop.
 *
 * A flat system background. It used to paint two large radial glows — an
 * accent-tinted one upper-left and an indigo one lower-right — over the base colour.
 * That was the web client's signature, and it is the single most "not iOS" thing the
 * app did: on a phone those washes read as a coloured page, fight every card laid on
 * top of them, and make the accent-coloured controls blend into the canvas behind.
 *
 * iOS separates chrome from content by *surface colour and elevation*, not by
 * atmosphere, so the backdrop is now a single flat fill (`systemGroupedBackground` in
 * light, black in dark) and all depth comes from the cards themselves.
 *
 * Kept as a composable rather than inlined at the call site so the backdrop can
 * change again in one place.
 */
@Composable
fun CodegBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = CodegTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
        content = { content() },
    )
}
