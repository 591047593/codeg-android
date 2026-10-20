package app.codeg.android.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a single-emphasis span closes.
 *
 * The inline parser used a plain `indexOf`, which pairs the opening `*` of
 * `*a **b** c*` with the first `*` of `**b` — severing the bold span and leaving the
 * rest of the line as literal asterisks. Pinned as a free function so the rule is
 * testable without Compose's text types.
 */
class InlineDelimiterTest {

    @Test
    fun `a simple pair closes at the next single marker`() {
        assertEquals(4, findClosingSingleDelimiter("*abc*", 0, '*'))
    }

    @Test
    fun `a doubled run is skipped when looking for the closing marker`() {
        // *a **b** c*  -> the opening `*` must pair with the final `*`, not with `**`.
        val raw = "*a **b** c*"
        assertEquals(raw.lastIndex, findClosingSingleDelimiter(raw, 0, '*'))
        assertEquals(10, findClosingSingleDelimiter(raw, 0, '*'))
    }

    @Test
    fun `a doubled run immediately after the opener is skipped`() {
        val raw = "****"
        // Every position is part of a doubled run, so there is no single closing marker.
        assertEquals(-1, findClosingSingleDelimiter(raw, 0, '*'))
    }

    @Test
    fun `an unmatched opener yields no closing index`() {
        assertEquals(-1, findClosingSingleDelimiter("*abc", 0, '*'))
        assertEquals(-1, findClosingSingleDelimiter("a *b", 2, '*'))
    }

    @Test
    fun `underscore behaves the same as asterisk`() {
        assertEquals(4, findClosingSingleDelimiter("_abc_", 0, '_'))
        val raw = "_a __b__ c_"
        assertEquals(raw.lastIndex, findClosingSingleDelimiter(raw, 0, '_'))
    }

    @Test
    fun `a marker of the other kind does not close`() {
        // `*a_b*` — the underscore is literal here.
        assertEquals(4, findClosingSingleDelimiter("*a_b*", 0, '*'))
    }

    @Test
    fun `scanning starts after the opener`() {
        // The opener itself must never be returned as its own close.
        assertEquals(2, findClosingSingleDelimiter("*a*", 0, '*'))
    }
}
