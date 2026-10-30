package app.codeg.android.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Parse a tag's `#rrggbb` into a Compose colour.
 *
 * Deliberately not a `@Composable` read of the theme: callers resolve a whole row's
 * colours inside a `remember` block, and an unparseable value (a hand-edited database
 * row, a future format) must render as neutral grey rather than throw inside a list.
 */
fun tagColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return TagFallbackColor
    val rgb = cleaned.toLongOrNull(16) ?: return TagFallbackColor
    return Color(0xFF000000L or rgb)
}

/** Grey used when a tag's stored colour cannot be parsed. */
val TagFallbackColor: Color = Color(0xFF8E8E93)
