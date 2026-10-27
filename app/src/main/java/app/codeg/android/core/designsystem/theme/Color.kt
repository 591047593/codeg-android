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
    RED("red", Color(0xFFFB2C36), Color(0xFFFB2C36)),
    ROSE("rose", Color(0xFFFF2056), Color(0xFFFF2056)),
    ORANGE("orange", Color(0xFFF54900), Color(0xFFFF6900)),
    YELLOW("yellow", Color(0xFFF0B100), Color(0xFFF0B100)),
    GREEN("green", Color(0xFF00BC7D), Color(0xFF00C950)),
    BLUE("blue", Color(0xFF2B7FFF), Color(0xFF155DFC)),
    VIOLET("violet", Color(0xFF7F22FE), Color(0xFF8E51FF));

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
)

/** Build the colour set for the given mode + accent (iOS `Theme.swift` values). */
fun codegColors(isDark: Boolean, accent: AccentPalette): CodegColors {
    val accentColor = accent.resolve(isDark)
    return if (isDark) {
        CodegColors(
            isDark = true,
            bg = Color(0xFF0A0B0D),
            bgElevated = Color(0xFF141619),
            surfaceStroke = Color.White.copy(alpha = 0.09f),
            hairline = Color.White.copy(alpha = 0.055f),
            codeSurface = Color.Black.copy(alpha = 0.30f),
            rail = Color.White.copy(alpha = 0.16f),
            textPrimary = Color(0xFFF7F7F7),
            textSecondary = Color(0xFFA3A3A3),
            textTertiary = Color(0xFF707070),
            danger = Color(0xFFF57575),
            accent = accentColor,
            onAccent = onAccentColor(accentColor),
            accentDim = accentColor.copy(alpha = 0.16f),
        )
    } else {
        CodegColors(
            isDark = false,
            bg = Color(0xFFF2F4F6),
            bgElevated = Color(0xFFFFFFFF),
            surfaceStroke = Color.Black.copy(alpha = 0.10f),
            hairline = Color.Black.copy(alpha = 0.07f),
            codeSurface = Color.Black.copy(alpha = 0.045f),
            rail = Color.Black.copy(alpha = 0.20f),
            textPrimary = Color(0xFF1C1C1C),
            textSecondary = Color(0xFF616161),
            textTertiary = Color(0xFF8C8C8C),
            danger = Color(0xFFCC2E2E),
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

/** The cool indigo glow used in the app background (iOS `CodegBackground`). */
val CodegGlowCool = Color(0xFF4D6BF2)
