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
 * Bundled Inter — the codeg desktop client's default interface font
 * (`--font-sans: "Inter Variable", system-ui, …` in its `globals.css`).
 *
 * This is the highest-impact typography change available on Android. Roboto
 * and Inter have similar metrics but very different voice at the small sizes
 * that make up most of this UI, and every screen is set in one or the other.
 *
 * ### Why each weight is a separate [Font] entry
 *
 * The fonts are variable files, and Compose applies a weight to a variable
 * font only through `FontVariation.Settings`. The four-argument `Font(resId,
 * weight, style, loadingStrategy)` factory passes an *empty* `Settings()`, and
 * on Android that empty value is what reaches
 * `Typeface.Builder.setFontVariationSettings` — so every declared weight would
 * silently render at 400 and nothing in the app would ever look bold.
 * `variationSettings` is therefore passed explicitly, which is what requires
 * the [ExperimentalTextApi] opt-in below.
 *
 * One variable file covers 100–900, so the whole range costs 860 KB instead of
 * four separate ~300 KB static faces.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int): Font = Font(
    resId = R.font.inter_variable,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontWeight(weight), FontStyle.Normal),
)

val InterFamily: FontFamily = FontFamily(inter(400), inter(500), inter(600), inter(700))

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
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = -0.5.sp,
    ),
    // 22 / 1.27 — iOS title1. Section headers.
    headlineMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = -0.4.sp,
    ),
    // 20 / 1.25 — iOS title2. Dialog and sheet titles.
    titleLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = -0.35.sp,
    ),
    // 17 / 1.29 — iOS title3. Prominent labels, list rows.
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = -0.24.sp,
    ),
    // 15 / 1.33 — iOS headline at semibold.
    titleSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = -0.16.sp,
    ),
    // 17 / 1.41 — iOS body. Message copy. The extra point over 16 is what makes a
    // phone paragraph read comfortably rather than dense.
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = -0.2.sp,
    ),
    // 15 / 1.4 — iOS subheadline. Secondary copy, descriptions.
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = -0.12.sp,
    ),
    // 13 / 1.38 — iOS footnote. Metadata, timestamps, captions.
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = -0.06.sp,
    ),
    // 16 / 1.25 at medium — iOS callout. Buttons, tabs.
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = -0.16.sp,
    ),
    // 13 / 1.23 at medium — iOS caption1. Chips, badges.
    labelMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = -0.04.sp,
    ),
    // 11 / 1.27 at medium — iOS caption2 with the tracking iOS gives small text.
    labelSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.06.sp,
    ),
)
