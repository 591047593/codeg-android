package app.codeg.android.core.designsystem.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.CodegMono

/**
 * Inline-Markdown → [AnnotatedString] (the Compose analogue of iOS
 * `MarkdownText.attributed(from:)`, which used Apple's inline parser). Supports
 * `**bold**` / `__bold__`, `*italic*` / `_italic_`, `~~strike~~`, `` `code` ``,
 * and `[text](url)` links. Nesting is handled by recursion; code spans are
 * literal. Link URLs are attached as a string annotation under tag `URL`.
 */
const val URL_TAG = "URL"

/** Cached inline-markdown for the current theme colors (recomputed if colors change).
 *  Slot-scoped `remember` only — deliberately NOT a shared cache, so the growing tail of
 *  a streaming block can't churn/evict persisted spans. */
@Composable
fun rememberInlineMarkdown(raw: String): AnnotatedString {
    val colors = CodegTheme.colors
    return remember(raw, colors.accent, colors.codeSurface, colors.textPrimary) {
        inlineMarkdown(
            raw = raw,
            linkColor = colors.accent,
            codeBg = colors.codeSurface,
            codeColor = colors.textPrimary,
        )
    }
}

fun inlineMarkdown(
    raw: String,
    linkColor: Color,
    codeBg: Color,
    codeColor: Color,
): AnnotatedString = buildAnnotatedString {
    parseInline(this, raw, linkColor, codeBg, codeColor)
}

private fun parseInline(
    builder: AnnotatedString.Builder,
    raw: String,
    linkColor: Color,
    codeBg: Color,
    codeColor: Color,
) {
    var i = 0
    val n = raw.length
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            builder.append(plain.toString())
            plain.clear()
        }
    }

    while (i < n) {
        val c = raw[i]
        when {
            // Inline code — literal content, no recursion.
            c == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    builder.withStyle(
                        SpanStyle(fontFamily = CodegMono, background = codeBg, color = codeColor),
                    ) { append(raw.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            // Bold (** or __).
            (c == '*' || c == '_') && i + 1 < n && raw[i + 1] == c && !isIntraword(raw, i, c) -> {
                val marker = "$c$c"
                val end = raw.indexOf(marker, i + 2)
                if (end > i + 1 && !isIntraword(raw, end, c)) {
                    flushPlain()
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        parseInline(builder, raw.substring(i + 2, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            // Strikethrough (~~).
            c == '~' && i + 1 < n && raw[i + 1] == '~' -> {
                val end = raw.indexOf("~~", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        parseInline(builder, raw.substring(i + 2, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 2
                } else {
                    plain.append(c); i++
                }
            }

            // Italic (single * or _).
            (c == '*' || c == '_') && !isIntraword(raw, i, c) -> {
                val end = findClosingSingleDelimiter(raw, i, c)
                if (end > i) {
                    flushPlain()
                    builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        parseInline(builder, raw.substring(i + 1, end), linkColor, codeBg, codeColor)
                    }
                    i = end + 1
                } else {
                    plain.append(c); i++
                }
            }

            // Link [text](url).
            c == '[' -> {
                val close = raw.indexOf(']', i + 1)
                if (close > i && close + 1 < n && raw[close + 1] == '(') {
                    val urlEnd = raw.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        val text = raw.substring(i + 1, close)
                        val url = raw.substring(close + 2, urlEnd)
                        flushPlain()
                        builder.pushStringAnnotation(URL_TAG, url)
                        builder.withStyle(SpanStyle(color = linkColor)) {
                            parseInline(builder, text, linkColor, codeBg, codeColor)
                        }
                        builder.pop()
                        i = urlEnd + 1
                    } else {
                        plain.append(c); i++
                    }
                } else {
                    plain.append(c); i++
                }
            }

            else -> {
                plain.append(c); i++
            }
        }
    }
    flushPlain()
}

/**
 * Index of the delimiter that closes a *single* emphasis run opened at [open], or -1.
 *
 * A naive `indexOf` pairs the opening `*` of `*a **b** c*` with the first `*` of `**b`,
 * so the bold span is severed and the rest of the line renders as literal asterisks.
 * Runs of two or more belong to the bold branch, whether they open or close, so the
 * whole run is skipped here.
 *
 * Kept as a free function (rather than inline in the parser) so it can be unit-tested
 * without pulling Compose's text types into a JVM test.
 */
internal fun findClosingSingleDelimiter(raw: String, open: Int, marker: Char): Int {
    var scan = open + 1
    while (scan < raw.length) {
        if (raw[scan] == marker) {
            val startsRun = scan + 1 < raw.length && raw[scan + 1] == marker
            val endsRun = scan > 0 && raw[scan - 1] == marker
            if (startsRun || endsRun) {
                while (scan < raw.length && raw[scan] == marker) scan++
                continue
            }
            // An underscore inside a word is literal, so it cannot close a span either.
            if (isIntraword(raw, scan, marker)) { scan++; continue }
            return scan
        }
        scan++
    }
    return -1
}

/**
 * True when the delimiter at [index] sits *inside* a word, which CommonMark treats as
 * literal rather than an emphasis marker.
 *
 * This matters for identifiers, which is most of what an agent writes: without the rule
 * a prompt like `STREAMTEST_OK_12345` loses its underscores — `_OK_` is taken as an
 * italic run and the delimiter characters are swallowed, so the bubble renders
 * `STREAMTESTOK12345`. The rule applies to `_` only; `*` keeps its usual meaning
 * (`a*b*c` still italicises in this renderer, matching the previous behaviour).
 */
internal fun isIntraword(raw: String, index: Int, marker: Char): Boolean {
    if (marker != '_') return false
    val before = if (index > 0) raw[index - 1] else ' '
    val after = if (index + 1 < raw.length) raw[index + 1] else ' '
    return before.isLetterOrDigit() && after.isLetterOrDigit()
}
