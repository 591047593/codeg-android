package app.codeg.android.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material3 shape scale, aligned with [CodegDimens]' iOS-style radii.
 *
 * Written out literally rather than read from [CodegDimens] because [Shapes] is a plain
 * value created once at the theme root and cannot participate in a `@Composable` read.
 * Keep these in step with the tokens: a stock M3 control (dialog, menu, sheet) has to
 * look like it came from the same family as the hand-drawn surfaces.
 */
val CodegShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
