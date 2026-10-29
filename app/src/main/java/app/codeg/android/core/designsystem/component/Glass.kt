package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * A 0.75dp hairline border (iOS `hairlineBorder`). Defaults to the surface stroke.
 */
@Composable
fun Modifier.hairlineBorder(
    shape: Shape,
    color: Color = CodegTheme.colors.surfaceStroke,
    width: Dp = CodegTheme.dimens.hairlineWidth,
): Modifier = this.border(width, color, shape)

/**
 * A grouped-content card: rounded, an opaque elevated fill, and a hairline stroke.
 *
 * Named for the original iOS "glass" surface, which was translucent so the backdrop's
 * colour glows could show through. iOS actually draws these cards opaque — translucency
 * is reserved for chrome (tab bars, toolbars) that content scrolls beneath — so with the
 * flat backdrop the fill is now solid and only the hairline separates it.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CodegTheme.dimens.radiusLg,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier
            .clip(shape)
            // Opaque, like an iOS grouped-content card. The translucency existed so the
            // background's colour glows could bleed through; with a flat backdrop it only
            // muddied the fill, and a card that shows the canvas behind it is a web idiom.
            .background(colors.bgElevated)
            .hairlineBorder(shape, colors.surfaceStroke)
            .padding(padding),
        content = content,
    )
}

/**
 * A single glass row (iOS `GlassRow`): 14dp radius, used for list rows. Supports
 * a selected wash (accent tint + accent stroke).
 */
@Composable
fun GlassRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(CodegTheme.dimens.radiusMd)
    val fill = if (selected) colors.accent.copy(alpha = 0.22f) else colors.bgElevated
    val stroke = if (selected) colors.accent.copy(alpha = 0.45f) else colors.surfaceStroke
    Column(
        modifier
            .clip(shape)
            .background(fill)
            .hairlineBorder(shape, stroke)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}
