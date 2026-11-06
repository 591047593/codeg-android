package app.codeg.android.feature.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client's ONLY cron job is emitting the five fields and recognizing
 * which preset a stored cron came from — evaluation/next-run/field-range
 * validity is always the server's `automation_compute_next_run`. These tests
 * pin the canonical wire forms so a refactor can't silently change what we send.
 */
class AutomationCronPresetsTest {

    // --- buildFor: canonical wire forms -----------------------------------

    @Test
    fun `buildFor emits canonical five-field crons`() {
        assertEquals("0 9 * * *", CronPreset.DAILY.buildFor(0, 9, ""))
        assertEquals("30 9 * * 1-5", CronPreset.WORKDAYS.buildFor(30, 9, ""))
        assertEquals("0 9 * * 1", CronPreset.MONDAYS.buildFor(0, 9, ""))
        assertEquals("0 9 1 * *", CronPreset.MONTHLY_FIRST.buildFor(0, 9, ""))
        assertEquals("0 * * * *", CronPreset.HOURLY.buildFor(37, 21, ""))
        assertEquals("*/30 * * * *", CronPreset.EVERY_30_MIN.buildFor(0, 0, ""))
    }

    @Test
    fun `buildFor keeps minute and hour verbatim`() {
        assertEquals("5 7 * * *", CronPreset.DAILY.buildFor(5, 7, ""))
    }

    @Test
    fun `buildFor custom returns trimmed expression and null when blank`() {
        assertEquals("15 8 * * 0", CronPreset.CUSTOM.buildFor(0, 0, "  15 8 * * 0  "))
        assertNull(CronPreset.CUSTOM.buildFor(0, 0, "   "))
    }

    @Test
    fun `buildFor ignores time-of-day for interval presets`() {
        assertEquals("0 * * * *", CronPreset.HOURLY.buildFor(59, 23, ""))
        assertEquals("*/30 * * * *", CronPreset.EVERY_30_MIN.buildFor(59, 23, ""))
    }

    // --- matchCronPreset: round-trips --------------------------------------

    @Test
    fun `matchCronPreset round-trips every preset`() {
        assertEquals(CronPreset.DAILY, matchCronPreset("0 9 * * *"))
        assertEquals(CronPreset.WORKDAYS, matchCronPreset("0 9 * * 1-5"))
        assertEquals(CronPreset.MONDAYS, matchCronPreset("0 9 * * 1"))
        assertEquals(CronPreset.MONTHLY_FIRST, matchCronPreset("0 9 1 * *"))
        assertEquals(CronPreset.HOURLY, matchCronPreset("0 * * * *"))
        assertEquals(CronPreset.EVERY_30_MIN, matchCronPreset("*/30 * * * *"))
    }

    @Test
    fun `matchCronPreset recovers preset from a stored cron with any time-of-day`() {
        assertEquals(CronPreset.DAILY, matchCronPreset("30 21 * * *"))
        assertEquals(CronPreset.WORKDAYS, matchCronPreset("45 8 * * 1-5"))
    }

    @Test
    fun `matchCronPreset falls back to CUSTOM for non-canonical shapes`() {
        assertEquals(CronPreset.CUSTOM, matchCronPreset("0 12 1,15 * *"))
        assertEquals(CronPreset.CUSTOM, matchCronPreset("15 8 * * 0"))
        assertEquals(CronPreset.CUSTOM, matchCronPreset("not a cron at all"))
        assertEquals(CronPreset.CUSTOM, matchCronPreset(""))
    }

    @Test
    fun `matchCronPreset null cron falls back to DAILY editor default`() {
        assertEquals(CronPreset.DAILY, matchCronPreset(null))
    }

    // --- cronTimeOfDay ------------------------------------------------------

    @Test
    fun `cronTimeOfDay extracts minute and hour`() {
        assertEquals(0 to 9, cronTimeOfDay("0 9 * * *"))
        assertEquals(37 to 21, cronTimeOfDay("37 21 * * *"))
        assertEquals(30 to 8, cronTimeOfDay("30 8 * * 1-5"))
    }

    @Test
    fun `cronTimeOfDay returns null for interval and malformed crons`() {
        assertNull(cronTimeOfDay("0 * * * *"))
        assertNull(cronTimeOfDay("*/30 * * * *"))
        assertNull(cronTimeOfDay("garbage"))
        assertNull(cronTimeOfDay(null))
    }

    // --- isPlausibleCron: UI shape pre-check only, server is the judge ------

    @Test
    fun `plausible crons pass`() {
        assertTrue(isPlausibleCron("0 9 * * *"))
        assertTrue(isPlausibleCron("*/30 * * * *"))
        assertTrue(isPlausibleCron("15 8 1,15 * 0"))
        assertTrue(isPlausibleCron("0 9 * * 1-5"))
    }

    @Test
    fun `out-of-range fields still pass the shape check`() {
        // Range validity (60分钟/25点) is deliberately NOT checked here —
        // automation_compute_next_run is the only judge and surfaces
        // next_run=null, which the editor shows as invalid.
        assertTrue(isPlausibleCron("60 25 * * *"))
        assertTrue(isPlausibleCron("0 9 * * 8"))
    }

    @Test
    fun `malformed crons fail the shape check`() {
        assertFalse(isPlausibleCron(""))
        assertFalse(isPlausibleCron("0 9 * *"))
        assertFalse(isPlausibleCron("0 9 * * * *"))
        assertFalse(isPlausibleCron("hello world"))
        assertFalse(isPlausibleCron("a b c d e"))
        assertFalse(isPlausibleCron("* * * *"))
        assertFalse(isPlausibleCron("0 9 * * @"))
    }
}
