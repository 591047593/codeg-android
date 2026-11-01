package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the v149.2 attachment-prep contracts (pure logic only — no Android
 * graphics): the adaptive re-encode routing that replaced the hard 1.3 MB
 * image rejection, the step-down ladder invariants, and the 409 auto-rename
 * that unblocks the "retry the same upload forever" loop.
 */
class AttachmentPrepRouteTest {

    // ── prepRoute ──────────────────────────────────────────────────────────

    @Test
    fun `small image within budget and dimension keeps its original bytes`() {
        assertEquals(-1, AttachmentPrep.prepRoute(originalBytes = 300_000, longestSide = 1200))
    }

    @Test
    fun `oversize original starts the ladder even when dimensions are small`() {
        // The regression: a ~1.5 MB screenshot at ≤1568px kept its original
        // bytes, blew the composer's aggregate cap, and blocked every later
        // image. It must now re-encode instead.
        assertEquals(0, AttachmentPrep.prepRoute(originalBytes = 1_500_000, longestSide = 1500))
    }

    @Test
    fun `oversize dimensions start the ladder even when bytes fit`() {
        assertEquals(0, AttachmentPrep.prepRoute(originalBytes = 300_000, longestSide = 4000))
    }

    @Test
    fun `exactly at the budget keeps the original`() {
        assertEquals(-1, AttachmentPrep.prepRoute(originalBytes = AttachmentPrep.TARGET_BYTES, longestSide = 1568))
    }

    @Test
    fun `empty or degenerate input rejects`() {
        assertEquals(-2, AttachmentPrep.prepRoute(originalBytes = 0, longestSide = 100))
        assertEquals(-2, AttachmentPrep.prepRoute(originalBytes = 100, longestSide = 0))
    }

    // ── LADDER invariants ──────────────────────────────────────────────────

    @Test
    fun `ladder is monotonically shrinking in dimension and quality`() {
        val ladder = AttachmentPrep.LADDER
        assertTrue(ladder.size >= 2)
        for (i in 1 until ladder.size) {
            assertTrue(ladder[i].longestSide < ladder[i - 1].longestSide)
            assertTrue(ladder[i].jpegQuality < ladder[i - 1].jpegQuality)
        }
        // First rung matches the historical caps (1568px, q75).
        assertEquals(1568, ladder.first().longestSide)
        assertEquals(75, ladder.first().jpegQuality)
        // Budget: two prepared images must coexist under the aggregate cap.
        assertTrue(2 * AttachmentPrep.TARGET_BYTES * 4 / 3 <= AttachmentPrep.MAX_TOTAL_BYTES * 4 / 3)
    }

    // ── autoRenamedFileName ────────────────────────────────────────────────

    @Test
    fun `rename inserts the counter before the extension`() {
        assertEquals("a (1).png", autoRenamedFileName("a.png"))
        assertEquals("codeg-diag-big (1).bin", autoRenamedFileName("codeg-diag-big.bin"))
    }

    @Test
    fun `rename handles extensionless and dotfile names`() {
        assertEquals("Makefile (1)", autoRenamedFileName("Makefile"))
        assertEquals(".gitignore (1)", autoRenamedFileName(".gitignore"))
    }
}
