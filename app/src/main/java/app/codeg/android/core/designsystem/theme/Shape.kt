package app.codeg.android.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material3 shape scale mapped onto the codeg desktop radii.
 *
 * The values are the same multiples of the 10dp base that codeg's
 * `globals.css` uses (`--radius-sm` ×0.6, `-md` ×0.8, `-lg` ×1.0, `-xl` ×1.4),
 * so a button, an input, and a dialog are the same shape here as on the
 * desktop. They are written out literally rather than read from
 * [CodegDimens] because [Shapes] is a plain value created once at the theme
 * root and cannot participate in a `@Composable` read.
 */
val CodegShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)
