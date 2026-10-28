package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANSI SGR handling for terminal output.
 *
 * Agent tool calls return text written for a terminal (`ls --color`, `git diff
 * --color`, test runners, docker), so the escape bytes are common. Before this the
 * renderer showed them literally and the copy button handed them to the clipboard.
 */
class AnsiTextTest {

    private val ESC = '\u001B'

    @Test
    fun `plain text is passed through untouched`() {
        val text = "hello\nworld"
        assertFalse(AnsiText.hasAnsi(text))
        assertEquals(text, AnsiText.strip(text))
        val spans = AnsiText.parse(text)
        assertEquals(1, spans.size)
        assertEquals(text, spans[0].text)
        assertNull(spans[0].fg)
    }

    @Test
    fun `strip removes colour codes and keeps the text`() {
        val raw = "${ESC}[31mERROR${ESC}[0m: build failed"
        assertTrue(AnsiText.hasAnsi(raw))
        assertEquals("ERROR: build failed", AnsiText.strip(raw))
    }

    @Test
    fun `strip removes multi-parameter and empty codes`() {
        assertEquals("bold red", AnsiText.strip("${ESC}[1;31mbold red${ESC}[m"))
        assertEquals("x", AnsiText.strip("${ESC}[m x".trim()))
    }

    @Test
    fun `strip removes non-SGR control sequences too`() {
        // Cursor move, erase line, and an OSC title — none of these carry visible text
        // and all of them must disappear rather than leak into the transcript.
        assertEquals("abc", AnsiText.strip("${ESC}[2A${ESC}[Kabc"))
        assertEquals("abc", AnsiText.strip("${ESC}]0;title\u0007abc"))
    }

    @Test
    fun `parse splits into styled runs`() {
        val raw = "${ESC}[31mred${ESC}[0m plain ${ESC}[1;34mboldblue${ESC}[0m"
        val spans = AnsiText.parse(raw)

        assertEquals(4, spans.size)
        assertEquals("red", spans[0].text)
        assertEquals(AnsiColor.RED, spans[0].fg)

        assertEquals(" plain ", spans[1].text)
        assertNull(spans[1].fg)

        assertEquals("boldblue", spans[2].text)
        assertEquals(AnsiColor.BLUE, spans[2].fg)
        assertTrue(spans[2].bold)

        assertEquals("", spans[3].text.ifEmpty { "" })
    }

    @Test
    fun `parse handles background, dim, italic and underline`() {
        val spans = AnsiText.parse(
            "${ESC}[42mgreen bg${ESC}[0m${ESC}[2mdim${ESC}[22m${ESC}[3mit${ESC}[23m${ESC}[4mul${ESC}[24m",
        )
        assertEquals(AnsiColor.GREEN, spans[0].bg)
        assertTrue(spans[1].dim)
        assertTrue(spans[2].italic)
        assertTrue(spans[3].underline)
        // 22/23/24 clear the attribute again.
        assertFalse(spans[1].bold)
    }

    @Test
    fun `parse maps bright codes onto the same eight slots`() {
        val spans = AnsiText.parse("${ESC}[91m${ESC}[101mx")
        assertEquals(AnsiColor.RED, spans[0].fg)
        assertEquals(AnsiColor.RED, spans[0].bg)
    }

    @Test
    fun `tailAfterDroppedPrefix recovers colour after the envelope is removed`() {
        // The cleaner drops the CLI envelope (exit code / wall time) before display; the
        // surviving body must come back coloured, not as plain text.
        val raw = "exit code: 0\nwall time: 1.2s\n${ESC}[32mOK${ESC}[0m done"
        val plain = "OK done"
        assertEquals(plain, AnsiText.strip(raw).substringAfterLast("\n"))
        val tail = AnsiText.tailAfterDroppedPrefix(raw, plain)
        assertEquals("${ESC}[32mOK${ESC}[0m done", tail)
        assertEquals(plain, AnsiText.strip(tail!!))
    }

    @Test
    fun `tailAfterDroppedPrefix refuses when the shapes disagree`() {
        // The plain form is not a tail of the raw one (the cleaner transformed content),
        // so the caller must fall back to plain text rather than show something else.
        val raw = "${ESC}[31maaa${ESC}[0m bbb"
        assertNull(AnsiText.tailAfterDroppedPrefix(raw, "something else entirely"))
        assertNull(AnsiText.tailAfterDroppedPrefix("short", "longer than the source"))
    }

    @Test
    fun `an unterminated escape does not swallow the rest`() {
        // Truncated output (a killed process) can end mid-sequence; the parser must not
        // drop the visible text or loop forever.
        val raw = "${ESC}[31mred text"
        assertEquals("red text", AnsiText.strip(raw))
        val spans = AnsiText.parse(raw)
        assertEquals("red text", spans.joinToString("") { it.text })
    }
}
