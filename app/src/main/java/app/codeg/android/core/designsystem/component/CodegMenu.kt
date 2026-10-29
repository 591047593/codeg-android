package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The app's overflow / long-press menu.
 *
 * Material's [DropdownMenu] defaults are a different design language from the rest of
 * this app: a near-square popup with a hard elevation shadow and a dense 8dp-per-side
 * item inset, sitting on a card that uses 14dp radii, hairline strokes, and a translucent
 * elevated surface. Next to a session row it read as a foreign widget — visibly "not from
 * this app" in exactly the place the user is least forgiving about it.
 *
 * This wraps it into the product's language: the large radius, the elevated card colour
 * from the theme, a single soft shadow instead of Material's stacked tonal+ambient pair,
 * and roomier item padding so a touch target is comfortable. Every menu in the app goes
 * through here so the shape can never drift back.
 *
 * Usage is a drop-in replacement — the content lambda receives the same [RowScope] that
 * [DropdownMenuItem] expects.
 */
@Composable
fun CodegMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = CodegTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        containerColor = colors.bgElevated,
        tonalElevation = 0.dp,
        // One soft, wide shadow. Material's default pair (tonal + 3dp ambient) reads as a
        // hard edge on the light theme; a single diffused one sits closer to the card
        // language used elsewhere.
        shadowElevation = 8.dp,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
            content()
        }
    }
}

/**
 * One row inside a [CodegMenu].
 *
 * Sits here (rather than using [DropdownMenuItem] directly at each call site) so every
 * menu in the app shares the same type, colour and destructive-red treatment. [danger]
 * marks a destructive action — delete, in practice — and uses the theme's danger colour
 * for both the label and its leading icon.
 */
@Composable
fun CodegMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    danger: Boolean = false,
) {
    val colors = CodegTheme.colors
    val tint = if (danger) colors.danger else colors.textPrimary
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) tint else colors.textTertiary,
            )
        },
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        leadingIcon = leadingIcon?.let { icon ->
            {
                CompositionLocalProvider(LocalContentColor provides if (danger) colors.danger else colors.textSecondary) {
                    icon()
                }
            }
        },
    )
}

/** The colour a [CodegMenuItem] label uses, for callers drawing their own content. */
@Composable
fun codegMenuItemColor(danger: Boolean): Color {
    val colors = CodegTheme.colors
    return if (danger) colors.danger else colors.textPrimary
}
