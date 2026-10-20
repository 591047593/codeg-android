package app.codeg.android.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Magnitude thresholds for the compact list-row timestamp.
 *
 * The label is rendered per conversation row, so an off-by-one at a boundary is
 * visible everywhere at once. Also covers clock skew: a server timestamp a few
 * seconds ahead of the phone must read as "just now", never a negative magnitude.
 */
class RelativeTimeTest {

    private val now: Instant = Instant.parse("2026-03-05T12:00:00Z")

    private fun ago(seconds: Long): Instant = now.minusSeconds(seconds)

    @Test
    fun `under a minute is just now`() {
        assertEquals("just now", RelativeTime.compact(ago(0), now, justNow = "just now"))
        assertEquals("just now", RelativeTime.compact(ago(59), now, justNow = "just now"))
    }

    @Test
    fun `a future timestamp reads as just now rather than negative`() {
        // Phone/server clock skew: the server stamped the row slightly ahead.
        assertEquals("just now", RelativeTime.compact(now.plusSeconds(30), now, justNow = "just now"))
    }

    @Test
    fun `minutes hours and days use the compact unit form`() {
        assertEquals("1m", RelativeTime.compact(ago(60), now))
        assertEquals("59m", RelativeTime.compact(ago(59 * 60), now))
        assertEquals("1h", RelativeTime.compact(ago(60 * 60), now))
        assertEquals("23h", RelativeTime.compact(ago(23 * 3600), now))
        assertEquals("1d", RelativeTime.compact(ago(24 * 3600), now))
        assertEquals("6d", RelativeTime.compact(ago(6 * 24 * 3600), now))
    }

    @Test
    fun `a week or more falls back to the month-day date`() {
        // 7 days crosses the boundary, so it is a date, not "7d".
        val result = RelativeTime.compact(ago(7 * 24 * 3600), now)
        assertEquals(false, result.endsWith("d"))
        assertEquals(false, result == "just now")
    }

    @Test
    fun `justNow is the only localized word`() {
        // Units stay as digits+suffix regardless of locale; only the sub-minute word
        // is caller-supplied, which is what the resource-backed call sites rely on.
        assertEquals("刚刚", RelativeTime.compact(ago(10), now, justNow = "刚刚"))
        assertEquals("5m", RelativeTime.compact(ago(5 * 60), now, justNow = "刚刚"))
    }
}
