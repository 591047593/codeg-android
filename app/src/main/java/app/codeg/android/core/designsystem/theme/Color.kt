package app.codeg.android.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * User-selectable accent palettes.
 *
 * The ids and colours are taken from the codeg web client's theme presets
 * (`src/lib/theme-presets.ts` + `[data-theme="..."]` in `globals.css`, converted from
 * OKLCH), so a user moving between the desktop client and this app sees the same
 * accent under the same name.
 *
 * The web client's five *neutral* presets (neutral / zinc / slate / stone / gray) are
 * deliberately absent: their `--primary` is near-black or near-white and their
 * `--accent` is a flat grey, which works as a page tint on desktop but leaves every
 * button, selected tab and link indistinguishable on a phone. Only the presets that
 * carry an actual hue are offered here.
 */
enum class AccentPalette(val id: String, val dark: Color, val light: Color) {
    // Hues are the iOS system colours, which are less saturated than the web presets
    // they replace. The web values (`#FF6900` orange, `#FB2C36` red) are tuned to pop
    // against a page on a large display; at phone size on a near-white surface the same
    // saturation dominates every screen it appears on. The system values keep the same
    // hue identity at a weight that reads as a control colour rather than as an alert.
    RED("red", Color(0xFFFF453A), Color(0xFFFF3B30)),
    ROSE("rose", Color(0xFFFF375F), Color(0xFFFF2D55)),
    ORANGE("orange", Color(0xFFFF9F0A), Color(0xFFFF9500)),
    YELLOW("yellow", Color(0xFFFFD60A), Color(0xFFFFCC00)),
    GREEN("green", Color(0xFF30D158), Color(0xFF34C759)),
    BLUE("blue", Color(0xFF0A84FF), Color(0xFF007AFF)),
    VIOLET("violet", Color(0xFFBF5AF2), Color(0xFFAF52DE));

    fun resolve(isDark: Boolean): Color = if (isDark) dark else light

    companion object {
        val DEFAULT = ORANGE
        val all: List<AccentPalette> = entries
        fun from(id: String?): AccentPalette = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * The full set of semantic colours used across the app, ported from the iOS
 * `Theme`. Provided via `LocalCodegColors`; resolved for the active mode +
 * accent by [codegColors].
 */
@Immutable
data class CodegColors(
    val isDark: Boolean,
    val bg: Color,
    val bgElevated: Color,
    val surfaceStroke: Color,
    val hairline: Color,
    val codeSurface: Color,
    val rail: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val danger: Color,
    val accent: Color,
    val onAccent: Color,
    val accentDim: Color,
) {
    /**
     * Palette for ANSI SGR colour codes in terminal output.
     *
     * These are deliberately *not* literal VGA values. A pure `#FF0000` on the light
     * background fails contrast, and a pure `#FFFFFF` on dark is invisible against the
     * code surface, so each slot is a tonally-shifted pair chosen to stay legible in its
     * own mode — the same approach the desktop client takes with its `--ansi-*` tokens.
     * Indexed by [app.codeg.android.feature.sessiondetail.rendering.AnsiColor].
     */
    val ansi: List<Color>
        get() = if (isDark) ANSI_DARK else ANSI_LIGHT
}

/** Dark-mode ANSI palette: light, slightly desaturated so text stays readable. */
private val ANSI_DARK = listOf(
    Color(0xFF6B7280), // black  — a dark terminal's "black" is really grey
    Color(0xFFF87171), // red
    Color(0xFF4ADE80), // green
    Color(0xFFFBBF24), // yellow
    Color(0xFF60A5FA), // blue
    Color(0xFFC084FC), // magenta
    Color(0xFF22D3EE), // cyan
    Color(0xFFE5E7EB), // white
)

/** Light-mode ANSI palette: the same hues darkened to survive a pale background. */
private val ANSI_LIGHT = listOf(
    Color(0xFF374151), // black
    Color(0xFFB91C1C), // red
    Color(0xFF15803D), // green
    Color(0xFFA16207), // yellow
    Color(0xFF1D4ED8), // blue
    Color(0xFF7E22CE), // magenta
    Color(0xFF0E7490), // cyan
    Color(0xFF4B5563), // white — a light terminal's "white" is really grey
)

/**
 * Build the colour set for the given mode + accent.
 *
 * ### Why these values
 *
 * The palette follows Apple's platform colours rather than a web theme. Three
 * properties carry that:
 *
 * 1. **A neutral, slightly warm grey scale.** iOS surfaces are not blue-tinted
 *    (`#F2F4F6`) and not pure black. Light mode sits on `#F2F2F7` — the system
 *    grouped-background grey — with `#FFFFFF` cards. Dark mode is `#000000` with
 *    `#1C1C1E` cards, which is what makes the elevated surface read as *above* the
 *    background instead of merely lighter than it.
 * 2. **Separation by luminance, not by strokes.** iOS draws a border only when
 *    content would otherwise float ambiguously. [surfaceStroke] is therefore
 *    much weaker than a web card border; most separation comes from the surface
 *    colour itself.
 * 3. **System semantic colours** for danger (`#FF3B30` / `#FF453A`) and a text
 *    ramp taken from `UIColor.label` / `.secondaryLabel` / `.tertiaryLabel`, so
 *    the greys stay in one family instead of drifting.
 *
 * Accent hues are left as the user's choice; only the neutrals are opinionated.
 */
fun codegColors(isDark: Boolean, accent: AccentPalette): CodegColors {
    val accentColor = accent.resolve(isDark)
    return if (isDark) {
        CodegColors(
            isDark = true,
            // Pure black is the iOS dark background; content layers stack upward from it
            // in lighten steps rather than sitting on a slate wash.
            bg = Color(0xFF000000),
            bgElevated = Color(0xFF1C1C1E),
            surfaceStroke = Color.White.copy(alpha = 0.10f),
            hairline = Color.White.copy(alpha = 0.08f),
            // A filled, slightly lifted panel: iOS secondary system fill.
            codeSurface = Color(0xFF2C2C2E),
            rail = Color.White.copy(alpha = 0.18f),
            textPrimary = Color(0xFFFFFFFF),
            textSecondary = Color(0xFFEBEBF5).copy(alpha = 0.72f),
            textTertiary = Color(0xFFEBEBF5).copy(alpha = 0.48f),
            danger = Color(0xFFFF453A),
            accent = accentColor,
            onAccent = onAccentColor(accentColor),
            accentDim = accentColor.copy(alpha = 0.16f),
        )
    } else {
        CodegColors(
            isDark = false,
            // System grouped background; cards are pure white on top of it.
            bg = Color(0xFFF2F2F7),
            bgElevated = Color(0xFFFFFFFF),
            surfaceStroke = Color.Black.copy(alpha = 0.08f),
            hairline = Color.Black.copy(alpha = 0.10f),
            // A fill that reads as inset *against the grouped background*. Taking
            // `secondarySystemBackground` literally here made it identical to `bg`
            // (`#F2F2F7` on `#F2F2F7`), so every filled control — code blocks, text
            // fields, the composer — became invisible; iOS only gets away with that
            // value because its fields sit on white cards. One step darker than the
            // background keeps the same relationship without the vanishing act.
            codeSurface = Color(0xFFE5E5EA),
            rail = Color.Black.copy(alpha = 0.18f),
            textPrimary = Color(0xFF000000),
            textSecondary = Color(0xFF3C3C43).copy(alpha = 0.72f),
            textTertiary = Color(0xFF3C3C43).copy(alpha = 0.48f),
            danger = Color(0xFFFF3B30),
            accent = accentColor,
            onAccent = onAccentColor(accentColor),
            accentDim = accentColor.copy(alpha = 0.16f),
        )
    }
}

/**
 * Black or white text/icon on a filled accent.
 *
 * The threshold is the luminance at which white and near-black read equally
 * well, not an arbitrary midpoint: solving `1.05 / (L + 0.05) = (L + 0.05) /
 * 0.0548` (where 0.0548 is the relative luminance of `#0F0F0F`) gives
 * `L ≈ 0.19`. Any higher cutoff puts white text on light accents — the
 * previous 0.6 chose white on the yellow accent, which is a 1.9:1
 * white-on-yellow that is genuinely unreadable, and under-picked dark text on
 * orange and green as well.
 *
 * With 0.19 every one of the seven bundled accents clears the 4.5:1 WCAG AA
 * body-text ratio except violet-on-light, which lands at 4.4:1 and is still
 * well above the 3:1 large-text floor.
 */
private fun onAccentColor(accent: Color): Color =
    if (accent.luminance() > 0.19f) Color(0xFF0F0F0F) else Color.White

// The cool indigo glow that used to paint the backdrop is gone with the two-glow
// background; iOS-style surfaces are flat, so nothing references it any more.
