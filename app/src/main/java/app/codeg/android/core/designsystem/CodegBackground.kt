package app.codeg.android.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.codeg.android.core.designsystem.theme.CodegGlowCool
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The app backdrop: the base [CodegTheme] background with two soft glows — an
 * accent-tinted one in the upper-left and a cool indigo one in the lower-right —
 * ported from the iOS `CodegBackground`. Implemented with radial gradients
 * (cheap, no render passes) rather than blurred circles.
 */
@Composable
fun CodegBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = CodegTheme.colors
    // 任务书 §8 克制原则: the glows stay as the app's signature but at half
    // strength — a faint canvas tint, not a decoration competing with content.
    // (0.20/0.18 → 0.10/0.09 dark, proportionally in light mode.)
    val accentGlow = colors.accent.copy(alpha = if (colors.isDark) 0.10f else 0.09f)
    val coolGlow = CodegGlowCool.copy(alpha = if (colors.isDark) 0.09f else 0.08f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(colors.bg)
                val w = size.width
                val h = size.height

                // Accent glow, anchored in the upper-left and spread wide.
                val c1 = Offset(w * 0.20f, h * 0.15f)
                val r1 = w * 0.90f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accentGlow, Color.Transparent),
                        center = c1,
                        radius = r1,
                    ),
                    radius = r1,
                    center = c1,
                )

                // Cool indigo glow in the lower-right, with a radius wide enough to wash
                // across the whole bottom edge — so the (transparent) bottom navigation
                // bar sits on a visibly tinted canvas instead of a flat-white strip.
                val c2 = Offset(w * 0.84f, h * 0.96f)
                val r2 = w * 1.15f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(coolGlow, Color.Transparent),
                        center = c2,
                        radius = r2,
                    ),
                    radius = r2,
                    center = c2,
                )
            },
        content = { content() },
    )
}
