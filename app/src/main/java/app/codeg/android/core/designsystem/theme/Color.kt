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
enum class AccentPalette(
    val id: String,
    val dark: Color,
    val light: Color,
    /**
     * Explicit label colour, or null to let [onAccentColor] pick by luminance.
     *
     * Almost every accent wants the computed choice — it maximises contrast. The
     * exception is a brand colour whose *identity* is the combination of fill and label:
     * Claude's terracotta buttons carry white text, and a luminance-optimal dark label
     * measures better (6.1:1 against 3.1:1) while making the button look like a warning
     * chip rather than the product's primary action. 3.1:1 clears the WCAG floor for
     * large text, which is what a button label is.
     */
    val label: Color? = null,
) {
    /**
     * The default, matching the desktop client's `DEFAULT_THEME_COLOR = "neutral"`.
     *
     * A near-black accent in light mode and near-white in dark: the interface is then
     * carried entirely by the neutral surfaces, and colour appears only where it means
     * something (destructive actions, status, agent identity). Picking a hue as the
     * default made every button, tab and link the same loud colour, which is not how the
     * desktop client looks on first launch.
     */
    /**
     * The Claude accent: a warm terracotta, not a saturated orange.
     *
     * Claude's brand colour is `#D97757` — an earthy orange-red that reads as clay or
     * paper rather than as a warning. At `#FF9500` (the iOS system orange) the same
     * button looks like a call to action on an alert; one step toward brown is what
     * makes it feel like part of a stationery palette. Dark mode lifts it slightly so
     * it holds up on a dark warm grey.
     */
    CLAUDE("claude", Color(0xFFE08A6B), Color(0xFFD97757), label = Color.White),
    NEUTRAL("neutral", Color(0xFFE5E5EA), Color(0xFF1C1C1E)),
    RED("red", Color(0xFFFF453A), Color(0xFFFF3B30)),
    ROSE("rose", Color(0xFFFF375F), Color(0xFFFF2D55)),
    ORANGE("orange", Color(0xFFFF9F0A), Color(0xFFFF9500)),
    YELLOW("yellow", Color(0xFFFFD60A), Color(0xFFFFCC00)),
    GREEN("green", Color(0xFF30D158), Color(0xFF34C759)),
    BLUE("blue", Color(0xFF0A84FF), Color(0xFF007AFF)),
    VIOLET("violet", Color(0xFFBF5AF2), Color(0xFFAF52DE));

    fun resolve(isDark: Boolean): Color = if (isDark) dark else light

    companion object {
        /** The Claude palette is the app's look; the rest stay selectable. */
        val DEFAULT = CLAUDE
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
 * The palette follows the Claude app rather than Apple's system colours. What carries
 * that is a warm, low-chroma ramp rather than any single hue:
 *
 * 1. **Warm neutrals, not cold greys.** The background is `#F5F4EF` — an off-white with
 *    a little sand in it — and dark mode is `#262624`, a warm charcoal. iOS's
 *    `#F2F2F7` is faintly blue and `#000000` is clinical; against either, warm text and
 *    a terracotta accent look pasted on. Nearly everything else in this file is judged
 *    against that background, so this is the value that decides whether the app reads as
 *    a Claude surface at all.
 * 2. **Charcoal type, not black.** `#1F1E1D` instead of `#000000`. Long agent replies
 *    are the main content of this app, and warm-dark text on warm paper is markedly
 *    easier to read in bulk than maximum contrast.
 * 3. **Separation by luminance.** [surfaceStroke] stays very weak; depth comes from the
 *    surface colour itself, as in the Claude app, where cards are a slightly lighter
 *    paper rather than a bordered box.
 *
 * Accent hues are the user's choice; [AccentPalette.CLAUDE] is the brand default.
 */
fun codegColors(isDark: Boolean, accent: AccentPalette): CodegColors {
    val accentColor = accent.resolve(isDark)
    return if (isDark) {
        CodegColors(
            isDark = true,
            // Warm charcoal rather than pure black. Claude's dark theme is a brown-grey
            // (#262624 territory), not the neutral black iOS uses: on a phone it reads as
            // an ink-washed page, and it keeps the terracotta accent in the same family
            // instead of making it glow against a cold background.
            bg = Color(0xFF262624),
            bgElevated = Color(0xFF30302E),
            surfaceStroke = Color(0xFFFFF8F0).copy(alpha = 0.10f),
            hairline = Color(0xFFFFF8F0).copy(alpha = 0.08f),
            // A slightly lifted well for code, fields and the composer.
            codeSurface = Color(0xFF1F1F1D),
            rail = Color(0xFFFFF8F0).copy(alpha = 0.18f),
            textPrimary = Color(0xFFF5F4EF),
            textSecondary = Color(0xFFF5F4EF).copy(alpha = 0.68f),
            textTertiary = Color(0xFFF5F4EF).copy(alpha = 0.45f),
            danger = Color(0xFFE86A5E),
            accent = accentColor,
            onAccent = accent.label ?: onAccentColor(accentColor),
            accentDim = accentColor.copy(alpha = 0.16f),
        )
    } else {
        CodegColors(
            isDark = false,
            // Claude's paper: a warm off-white with a hint of sand (`#F5F4EF`), not the
            // cool system grey. This single value is most of what makes the app read as
            // Claude rather than as a generic iOS list — every surface above it is judged
            // against it, so a cold grey here makes the whole screen feel like settings.
            bg = Color(0xFFF5F4EF),
            bgElevated = Color(0xFFFDFCFA),
            surfaceStroke = Color(0xFF1F1E1D).copy(alpha = 0.08f),
            hairline = Color(0xFF1F1E1D).copy(alpha = 0.10f),
            // Inset well: one step darker than the paper, as before — the earlier bug was
            // setting this to the same value as `bg`, which made filled controls vanish.
            codeSurface = Color(0xFFEBE9E3),
            rail = Color(0xFF1F1E1D).copy(alpha = 0.18f),
            // Charcoal, not pure black: Claude's type is warm-dark, which is why long
            // passages read softer than on a `#000000` interface.
            textPrimary = Color(0xFF1F1E1D),
            textSecondary = Color(0xFF1F1E1D).copy(alpha = 0.68f),
            textTertiary = Color(0xFF1F1E1D).copy(alpha = 0.45f),
            danger = Color(0xFFC2410C),
            accent = accentColor,
            onAccent = accent.label ?: onAccentColor(accentColor),
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
