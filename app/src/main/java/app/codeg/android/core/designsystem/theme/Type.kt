package app.codeg.android.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.codeg.android.R

/**
 * The platform UI font — Roboto for Latin, Noto Sans CJK for Chinese.
 *
 * ### Why the bundled Inter is no longer the body face
 *
 * Inter was chosen to match the desktop client, and for an English-only UI it is the
 * better grotesque. It has **no CJK glyphs**, though. In a Chinese build every ideograph
 * fell back to the system font *per character*, so a single line mixed two typefaces with
 * different stroke weights, widths and vertical metrics — which is exactly the "ragged,
 * cheap-looking" text a mixed-script fallback produces, and it is most visible at the
 * small sizes this UI uses most.
 *
 * The platform family is a matched pair: Roboto and Noto Sans CJK are drawn to sit
 * together, with compatible x-heights and stroke contrast. Dropping Inter costs a little
 * Latin personality and buys consistent text in the language most of this app is read in.
 *
 * Inter is still bundled and still available as [InterFamily] for a caller that wants the
 * desktop's exact look in a Latin-only string.
 *
 * ### Why each weight is a separate [Font] entry
 *
 * Compose only applies a weight to a *variable* font through `FontVariation.Settings`;
 * the four-argument factory passes an empty `Settings()`, which reaches
 * `Typeface.Builder.setFontVariationSettings` and silently renders every declared weight
 * at 400. Hence the explicit settings and the [ExperimentalTextApi] opt-in.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int): Font = Font(
    resId = R.font.inter_variable,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontWeight(weight), FontStyle.Normal),
)

/** Bundled Inter, for Latin-only surfaces that want the desktop's voice. */
val InterFamily: FontFamily = FontFamily(inter(400), inter(500), inter(600), inter(700))

/**
 * The app's text family: the platform's own, so Latin and CJK come from a matched pair.
 *
 * Kept as a named local rather than `FontFamily.Default` so the intent is explicit at
 * every call site and a future substitution happens in one place.
 */
val CodegSans: FontFamily = FontFamily.Default

/**
 * Bundled JetBrains Mono for code, inline commands, and monospaced values.
 *
 * The platform `Monospace` alias resolves to Droid Sans Mono on most devices,
 * which has weak punctuation and no programming ligatures. codeg's own code
 * surfaces use a dedicated mono stack for exactly that reason, so this is the
 * one family every code surface in the app should reference — never
 * `CodegMono` directly.
 */
@OptIn(ExperimentalTextApi::class)
private fun mono(weight: Int): Font = Font(
    resId = R.font.jetbrains_mono_variable,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontWeight(weight), FontStyle.Normal),
)

val CodegMono: FontFamily = FontFamily(mono(400), mono(500), mono(700))

/** Shared style for inline code / commands / monospaced values (iOS `Font.mono(13)`). */
val CodeTextStyle = TextStyle(
    fontFamily = CodegMono,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

/**
 * Type scale modelled on iOS's text styles, set in Inter.
 *
 * Inter is the closest bundled match to SF Pro — same grotesque skeleton, similar
 * widths — and Apple's *metrics* are what make text feel like iOS: body copy is 17pt,
 * not 16; line heights are generous (≈1.3–1.4×) because the platform expects multiline
 * paragraphs; and large titles are tracked in tightly (-0.4pt) while small labels are
 * tracked out, which is why iOS labels look airy rather than cramped.
 *
 * The previous scale was Tailwind's (`text-base: 16px`, `text-sm: 14px`) — a web scale
 * that runs a step small on a phone and set every line a little tight.
 *
 * Mapping (iOS text style → this scale):
 * - largeTitle / title1 → [headlineLarge] / [headlineMedium]
 * - title2 / title3 → [titleLarge] / [titleMedium]
 * - headline → [titleSmall]
 * - body (17) → [bodyLarge]
 * - subheadline (15) → [bodyMedium]
 * - footnote (13) → [bodySmall]
 * - callout (16, medium) → [labelLarge]
 */
val CodegTypography = Typography(
    // 28 / 1.21 — iOS largeTitle. Screen titles.
    headlineLarge = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    // 22 / 1.27 — iOS title1. Section headers.
    headlineMedium = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    // 20 / 1.25 — iOS title2. Dialog and sheet titles.
    titleLarge = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    // 17 / 1.29 — iOS title3. Prominent labels, list rows.
    titleMedium = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    // 15 / 1.33 — iOS headline at semibold.
    titleSmall = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // 17 / 1.41 — iOS body. Message copy. The extra point over 16 is what makes a
    // phone paragraph read comfortably rather than dense.
    bodyLarge = TextStyle(
        fontFamily = CodegSans,
        // Medium, not Normal. Android's CJK fallback renders Regular far lighter than
        // PingFang does on iOS — the strokes come out thin and washed at body size — so
        // the whole scale is nudged one step heavier to match the weight iOS body text
        // actually has. Latin gains a little weight too, which keeps the pair consistent.
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        // 1.47× — CJK glyphs are square and need more leading than Latin at the same
        // point size; iOS sets Chinese a touch looser than English for this reason.
        lineHeight = 25.sp,
        // Neutral tracking. Negative tracking tightens Latin pleasantly but closes up
        // dense CJK glyphs into a solid block.
        letterSpacing = 0.sp,
    ),
    // 15 / 1.47 — iOS subheadline. Secondary copy, descriptions. The most-used body
    // style in the app, so it carries the same CJK-motivated weight and leading as
    // `bodyLarge`.
    bodyMedium = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    // 13 / 1.46 — iOS footnote. Metadata, timestamps, captions. Stays Regular: these
    // are secondary labels, and at Medium the small CJK glyphs blur together.
    bodySmall = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    // 16 / 1.25 at medium — iOS callout. Buttons, tabs.
    labelLarge = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // 13 / 1.23 at medium — iOS caption1. Chips, badges.
    labelMedium = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    // 11 / 1.27 at medium — iOS caption2 with the tracking iOS gives small text.
    labelSmall = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.06.sp,
    ),
)
