package app.codeg.android.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's type is the device's own.
 *
 * No bundled family is used anywhere: [CodegSans] is the platform sans and [CodegMono]
 * the platform monospace. That is a deliberate reversal of an earlier choice.
 *
 * The app used to bundle Inter for body text and JetBrains Mono for code. Inter has no
 * CJK glyphs, so a Chinese build fell back per character and mixed two typefaces inside
 * one line; it was replaced by the platform family, which pairs Roboto with Noto Sans
 * CJK. The bundled mono stayed behind — so the body was the device font while every
 * code block, timestamp and model id was not. That reads as the app having "changed
 * fonts", which is exactly how it was reported.
 *
 * One family for the whole surface also means the text follows the user's font settings
 * (size, and any OEM substitution) instead of fighting them.
 *
 * The two TTFs are still in `res/font` so either can be reinstated in one line if the
 * desktop's exact voice is ever wanted again.
 */
/**
 * The app's text family: the platform's own, so Latin and CJK come from a matched pair.
 *
 * A named local rather than a bare `FontFamily.Default` so the intent is explicit at every
 * call site and a future substitution happens in one place.
 */
val CodegSans: FontFamily = FontFamily.Default

/**
 * Monospaced text: code, inline commands, timestamps, model ids.
 *
 * The platform's own monospace face, for the same reason [CodegSans] is the platform
 * sans: a bundled family only matches what the OS draws if every *other* surface uses
 * it too, and it does not. Keeping JetBrains Mono here meant a screen whose body was the
 * device font and whose code blocks, timestamps and chips were a visibly different
 * typeface — the app looked like it had "changed fonts" rather than like it had a code
 * style. The device's monospace is what a system-consistent app uses.
 *
 * The bundled TTF is still in `res/font` and can be reinstated here in one line.
 */
val CodegMono: FontFamily = FontFamily.Monospace

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
        // Regular, matching the Codex client's reading weight: the reply text is the
        // content, and a heavier body made long answers look like UI labels.
        //
        // Android's CJK fallback renders Regular thinner than PingFang does on iOS, so
        // the leading below carries the openness that weight used to: 1.55× here against
        // 1.47× before. Raising leading costs nothing in density and is what actually
        // makes dense CJK paragraphs readable, where a heavier weight only made them
        // look bolder.
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        // Neutral tracking. Negative tracking tightens Latin pleasantly but closes up
        // dense CJK glyphs into a solid block.
        letterSpacing = 0.sp,
    ),
    // 15 / 1.53 — secondary copy and descriptions, the most-used body style. Regular
    // weight with the looser leading, same as `bodyLarge`.
    bodyMedium = TextStyle(
        fontFamily = CodegSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
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
