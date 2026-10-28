package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * ANSI SGR (Select Graphic Rendition) parsing for terminal output.
 *
 * Agent tool calls routinely return text that was written for a terminal: `ls --color`,
 * `git diff --color`, `npm test`, `docker compose`, and anything that detects a TTY.
 * Without this the transcripts showed the escape bytes verbatim — a failed build read
 * `\u001B[31mERROR\u001B[0m: …` instead of a red ERROR — which is both ugly and actively
 * harder to read than plain text.
 *
 * Scope is deliberately SGR only (colour, bold, dim, italic, underline, inverse). Cursor
 * movement, erase, and the rest of the control repertoire are stripped and ignored: this
 * renders a static buffer, so a cursor-up cannot mean anything.
 */

/** Colour slot referenced by an SGR code, resolved against the theme at render time. */
enum class AnsiColor {
    BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE, DEFAULT;

    /**
     * Index into the theme's `ansi` palette, or -1 for [DEFAULT].
     *
     * The enum order is the palette order: codes 30–37 and 90–97 both map onto these
     * eight slots, so the ordinal *is* the index.
     */
    val paletteIndex: Int get() = if (this == DEFAULT) -1 else ordinal
}

/**
 * One run of text sharing a style. [fg]/[bg] index into the theme's ANSI palette rather
 * than carrying a [Color], so the same parsed output re-colours correctly when the theme
 * (or light/dark) changes without re-parsing.
 */
data class AnsiSpan(
    val text: String,
    val fg: AnsiColor? = null,
    val bg: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

object AnsiText {

    /** True when [raw] contains at least one SGR sequence — the fast path checks this
     *  before parsing, so ordinary output never pays for the tokenizer. */
    fun hasAnsi(raw: String): Boolean = raw.indexOf('\u001B') >= 0

    /**
     * Strip every escape sequence, keeping the text.
     *
     * Used for copying and for classification: `classify` must not decide a run is a
     * diff or markdown based on bytes the user will never see.
     */
    fun strip(raw: String): String {
        if (!hasAnsi(raw)) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\u001B' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    // CSI: ESC [ params letter — the only family this app produces.
                    '[' -> {
                        i += 2
                        while (i < raw.length && !isCsiFinal(raw[i])) i++
                        if (i < raw.length) i++ // consume the final byte
                        continue
                    }
                    // OSC: ESC ] … BEL (or ST). Titles, hyperlinks.
                    ']' -> {
                        i += 2
                        while (i < raw.length && raw[i] != '\u0007') i++
                        if (i < raw.length) i++
                        continue
                    }
                    else -> { i += 2; continue } // two-byte escape
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    /**
     * Split [raw] into styled runs.
     *
     * Returns a single unstyled span (the input) when there is no ANSI, so the common
     * case allocates nothing extra.
     */
    fun parse(raw: String): List<AnsiSpan> {
        if (!hasAnsi(raw)) return listOf(AnsiSpan(raw))

        val spans = ArrayList<AnsiSpan>()
        var fg: AnsiColor? = null
        var bg: AnsiColor? = null
        var bold = false
        var dim = false
        var italic = false
        var underline = false
        val buf = StringBuilder()

        fun flush() {
            if (buf.isEmpty()) return
            spans += AnsiSpan(
                text = buf.toString(),
                fg = fg, bg = bg, bold = bold, dim = dim, italic = italic, underline = underline,
            )
            buf.setLength(0)
        }

        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\u001B' && i + 1 < raw.length && raw[i + 1] == '[') {
                val start = i + 2
                var end = start
                while (end < raw.length && !isCsiFinal(raw[end])) end++
                if (end < raw.length && raw[end] == 'm') {
                    // Commit everything before this SGR with the style that applied to it.
                    flush()
                    parseSgr(raw.substring(start, end)) { code ->
                        when (code) {
                            0 -> { fg = null; bg = null; bold = false; dim = false; italic = false; underline = false }
                            1 -> bold = true
                            2 -> dim = true
                            3 -> italic = true
                            4 -> underline = true
                            22 -> { bold = false; dim = false }
                            23 -> italic = false
                            24 -> underline = false
                            in 30..37 -> fg = basicColor(code - 30)
                            39 -> fg = null
                            in 40..47 -> bg = basicColor(code - 40)
                            49 -> bg = null
                            in 90..97 -> fg = brightColor(code - 90)
                            in 100..107 -> bg = brightColor(code - 100)
                            // 38/48 extended colour (256 / truecolor) is intentionally
                            // ignored: the palette is a fixed 8-colour theme, and
                            // approximating a truecolor value into it would be a guess.
                            else -> Unit
                        }
                    }
                    i = end + 1
                    continue
                }
                // A non-SGR CSI (cursor move, erase): drop the whole sequence.
                i = if (end < raw.length) end + 1 else end
                continue
            }
            if (ch == '\u001B' && i + 1 < raw.length) {
                // Other escape forms carry no visible text.
                i += 2
                continue
            }
            buf.append(ch)
            i++
        }
        flush()
        // A styled run that ended up empty (e.g. a trailing reset) leaves nothing behind.
        return spans.ifEmpty { listOf(AnsiSpan(raw)) }
    }

    /** CSI parameter bytes end at the first byte in the final-byte range `0x40..0x7E`. */
    private fun isCsiFinal(c: Char): Boolean = c.code in 0x40..0x7E

    /**
     * Expand one SGR parameter list into codes, honouring both the `;` and the legacy
     * `:` separators and the empty-parameter-means-0 rule (`ESC[m` == `ESC[0m`).
     */
    private inline fun parseSgr(params: String, onCode: (Int) -> Unit) {
        if (params.isEmpty()) { onCode(0); return }
        for (part in params.split(';', ':')) {
            val code = part.toIntOrNull()
            onCode(code ?: 0)
        }
    }

    private fun basicColor(index: Int): AnsiColor = when (index) {
        0 -> AnsiColor.BLACK
        1 -> AnsiColor.RED
        2 -> AnsiColor.GREEN
        3 -> AnsiColor.YELLOW
        4 -> AnsiColor.BLUE
        5 -> AnsiColor.MAGENTA
        6 -> AnsiColor.CYAN
        else -> AnsiColor.WHITE
    }

    private fun brightColor(index: Int): AnsiColor = basicColor(index)

    /**
     * Recover the coloured form of [plain] from [raw], which contains the same text with
     * escape sequences interleaved.
     *
     * Used by the command-output cleaner: it has to work on plain text (a fence or an
     * `exit code:` header would not match with escapes inside it), but the *display* form
     * should keep its colour. [plain] is always a contiguous tail of the stripped [raw] —
     * the cleaner only removes whole leading lines and a wrapping fence — so this walks
     * one line at a time down both strings in step and returns the raw remainder once the
     * plain one is exhausted.
     *
     * Returns null when the shapes disagree, and the caller then uses [plain]: losing
     * colour is acceptable, showing text that does not match the cleaned body is not.
     */
    fun tailAfterDroppedPrefix(raw: String, plain: String): String? {
        if (plain.isEmpty()) return null
        // Compare on line counts: the cleaner drops a whole number of leading lines.
        val rawLines = raw.split('\n')
        val plainLines = plain.split('\n')
        val skip = rawLines.size - plainLines.size
        if (skip < 0) return null
        // Rebuild the tail from the raw lines, then verify it strips down to `plain`.
        val tail = rawLines.subList(skip, rawLines.size).joinToString("\n")
        return if (strip(tail) == plain) tail else null
    }

    /**
     * Build the styled text for Compose from [spans], resolving palette slots through
     * [color].
     */
    fun toAnnotatedString(
        spans: List<AnsiSpan>,
        color: (AnsiColor) -> Color,
        defaultColor: Color,
    ): AnnotatedString = buildAnnotatedString {
        for (span in spans) {
            if (span.fg == null && span.bg == null && !span.bold && !span.dim &&
                !span.italic && !span.underline
            ) {
                append(span.text)
                continue
            }
            val fgColor = span.fg?.let(color) ?: defaultColor
            withStyle(
                SpanStyle(
                    color = if (span.dim) fgColor.copy(alpha = 0.6f) else fgColor,
                    background = span.bg?.let(color) ?: Color.Unspecified,
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) {
                        androidx.compose.ui.text.font.FontStyle.Italic
                    } else {
                        null
                    },
                    textDecoration = if (span.underline) {
                        androidx.compose.ui.text.style.TextDecoration.Underline
                    } else {
                        null
                    },
                ),
            ) { append(span.text) }
        }
    }
}
