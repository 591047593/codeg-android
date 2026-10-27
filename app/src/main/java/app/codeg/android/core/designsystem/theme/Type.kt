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
 * Type scale aligned to the codeg web client's Tailwind scale
 * (`--text-xs: 12px`, `-sm: 14px`, `-base: 16px`, `-lg: 18px`, `-xl: 20px`,
 * `-2xl: 24px`) and set in Inter.
 *
 * Line heights follow codeg's `leading-*` usage — about 1.5× for body copy and
 * 1.35× for titles. The previous scale used Material's default line heights
 * with iOS sizes, which left titles too tight and labels too loose next to the
 * desktop client.
 */
val CodegTypography = Typography(
    // 24px / 1.33 — codeg `text-2xl`. Screen titles.
    headlineLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = -0.46.sp,
    ),
    // 20px / 1.4 — codeg `text-xl`. Section headers.
    headlineMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = -0.34.sp,
    ),
    // 18px / 1.44 — codeg `text-lg`. Dialog and sheet titles.
    titleLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = -0.25.sp,
    ),
    // 16px / 1.5 — codeg `text-base`. Prominent labels, list rows.
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = -0.18.sp,
    ),
    // 14px / 1.43 — codeg `text-sm` at medium weight.
    titleSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = -0.08.sp,
    ),
    // 16px / 1.5 — codeg `text-base`. Message body copy.
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = -0.18.sp,
    ),
    // 14px / 1.43 — codeg `text-sm`. Secondary copy, descriptions.
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = -0.08.sp,
    ),
    // 12px / 1.33 — codeg `text-xs`. Metadata, timestamps, captions.
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    // 14px / 1.43 at medium — codeg `text-sm font-medium`. Buttons, tabs.
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = -0.08.sp,
    ),
    // 12px / 1.33 — codeg `text-xs font-medium`. Chips, badges.
    labelMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    // 11px / 1.45 — codeg `text-2xs`. The tightest badge text.
    labelSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)
