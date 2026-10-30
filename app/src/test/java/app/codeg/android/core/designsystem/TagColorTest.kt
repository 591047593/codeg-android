package app.codeg.android.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A tag's colour arrives as a string from the server, so the parser is one of the few
 * places where malformed data can reach composition. Every case here is a value the
 * database can actually hold.
 */
class TagColorTest {

    @Test
    fun `parses a six digit hex without the hash`() {
        assertEquals(Color(0xFFD97757), tagColor("D97757"))
    }

    @Test
    fun `parses a six digit hex with the hash`() {
        assertEquals(Color(0xFF1A1A2E), tagColor("#1a1a2e"))
    }

    @Test
    fun `black and white are parsed, not treated as absent`() {
        assertEquals(Color(0xFF000000), tagColor("#000000"))
        assertEquals(Color(0xFFFFFFFF), tagColor("#FFFFFF"))
    }

    @Test
    fun `a short value falls back to grey`() {
        assertEquals(TagFallbackColor, tagColor("#abc"))
    }

    @Test
    fun `non hex characters fall back to grey`() {
        assertEquals(TagFallbackColor, tagColor("#zzzzzz"))
    }

    @Test
    fun `an empty string falls back to grey`() {
        assertEquals(TagFallbackColor, tagColor(""))
        assertEquals(TagFallbackColor, tagColor("#"))
    }

    @Test
    fun `a value longer than six digits falls back rather than overflowing`() {
        // `toLongOrNull(16)` would happily accept this and push the colour out of range.
        assertEquals(TagFallbackColor, tagColor("#11223344"))
    }
}
