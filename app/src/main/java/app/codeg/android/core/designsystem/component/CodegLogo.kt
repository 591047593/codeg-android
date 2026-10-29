package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Codeg mark, drawn to match the project logo.
 *
 * A `</>` in the bracket grey with three coloured dots between the angles — magenta,
 * yellow, cyan. That trio is the only colour in the brand; everything else in the
 * artwork is neutral, which is also why the app's default accent is `neutral` rather
 * than a hue.
 *
 * Drawn rather than shipped as a vector so it scales cleanly at any size and picks up
 * the surrounding theme for the bracket stroke.
 */
@Composable
fun CodegLogo(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    bracket: Color = Color(0xFFDCDFE9),
) {
    Canvas(modifier.size(size)) {
        // Authored on the logo's 512-unit grid, scaled to whatever size is asked for.
        fun n(v: Float) = v / 512f * this.size.width

        val stroke = Stroke(
            width = n(32f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val left = Path().apply {
            moveTo(n(180f), n(186f))
            lineTo(n(100f), n(256f))
            lineTo(n(180f), n(326f))
        }
        val right = Path().apply {
            moveTo(n(332f), n(186f))
            lineTo(n(412f), n(256f))
            lineTo(n(332f), n(326f))
        }
        drawPath(left, bracket, style = stroke)
        drawPath(right, bracket, style = stroke)

        drawCircle(Color(0xFFE91E63), radius = n(30f), center = Offset(n(206f), n(256f)))
        drawCircle(Color(0xFFFDD835), radius = n(36f), center = Offset(n(256f), n(256f)))
        drawCircle(Color(0xFF03A9F4), radius = n(30f), center = Offset(n(306f), n(256f)))
    }
}
