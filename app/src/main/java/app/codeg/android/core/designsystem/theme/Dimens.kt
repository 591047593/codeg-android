package app.codeg.android.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii and spacing tokens.
 *
 * ### iOS geometry
 *
 * Apple's surfaces are rounder and roomier than a Material/Tailwind scale. The
 * reference points are the platform's own: a grouped list row uses a 10pt radius inside
 * a 16pt inset card, a sheet is 10pt at the top with 16pt side padding, and a filled
 * button is a 12pt-radius capsule-ish block. These tokens approximate that with whole
 * numbers at Android densities:
 *
 * - 8 / 12 / 16 / 20 / 28 rather than the desktop client's 6 / 8 / 10 / 14 / 18.
 *   The old scale was ported from the web app's `--radius: 10px` multiples, which is
 *   visibly tighter than anything iOS draws.
 * - Spacing steps stay on the 4dp grid (iOS uses 4pt too) but the vertical rhythm opens
 *   up: 12dp between blocks, 16dp screen insets.
 */
@Immutable
data class CodegDimens(
    /** ×2.8 — hero surfaces, sheets, the composer. */
    val radius2xl: Dp = 28.dp,
    /** ×2.0 — dialogs, large cards, grouped list containers. */
    val radiusXl: Dp = 20.dp,
    /** ×1.6 — message bubbles, panels, tool cards. */
    val radiusLg: Dp = 16.dp,
    /** ×1.2 — buttons, inputs, list rows. */
    val radiusMd: Dp = 12.dp,
    /** ×0.8 — chips, tags, small controls. */
    val radiusSm: Dp = 8.dp,
    /** ×0.6 — the tightest affordance. */
    val radiusXs: Dp = 6.dp,

    /** Screen edge inset, 4 × 4dp. */
    val screenHMargin: Dp = 16.dp,
    /** Vertical gap between message blocks. */
    val blockSpacing: Dp = 12.dp,
    /** Vertical gap between stacked list rows. */
    val rowSpacing: Dp = 8.dp,
    /** Inner padding of a card / row, 3 × 4dp. */
    val cardPadding: Dp = 12.dp,
    /** Minimum touch target; Material and iOS both use 48dp / 44pt. */
    val touchTarget: Dp = 48.dp,

    val hairlineWidth: Dp = 0.75.dp,
) {
    /**
     * A hairline that stays visible at any density. A raw [hairlineWidth] can
     * round down to zero pixels on a 1x display and vanish entirely; flooring
     * it at one physical pixel keeps every divider and border present.
     */
    fun hairline(density: androidx.compose.ui.unit.Density): Dp =
        maxOf(hairlineWidth, 1.dp / density.density)
}
