package app.codeg.android.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii and spacing tokens, aligned to the codeg desktop client.
 *
 * Source of truth: `src/app/globals.css` in the codeg web app, where every
 * radius is a multiple of `--radius: 10px` (`--radius-sm` ×0.6, `-md` ×0.8,
 * `-lg` ×1.0, `-xl` ×1.4, `-2xl` ×1.8). Mirroring the same multipliers keeps a
 * card on the phone and a card in the desktop client visibly the same shape;
 * the previous values (26/20/14/10) were ported from the iOS app and read far
 * rounder than anything in the desktop product.
 *
 * Spacing follows codeg's `--spacing: 4px` base, so every step here is a
 * multiple of 4dp.
 */
@Immutable
data class CodegDimens(
    /** ×2.2 of the 10dp base — hero surfaces, sheets. */
    val radius2xl: Dp = 22.dp,
    /** ×1.8 — dialogs, large cards. */
    val radiusXl: Dp = 18.dp,
    /** ×1.4 — the codeg `rounded-xl`: message bubbles, panels. */
    val radiusLg: Dp = 14.dp,
    /** ×1.0 — the codeg `rounded-lg`: buttons, inputs, list rows. */
    val radiusMd: Dp = 10.dp,
    /** ×0.8 — the codeg `rounded-md`: chips, tags, small controls. */
    val radiusSm: Dp = 8.dp,
    /** ×0.6 — the codeg `rounded-sm`: the tightest affordance. */
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
