package app.codeg.android.core.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Compact relative-time formatting for dense list rows, ported from the iOS
 * `RelativeTime`. No third-party dependency — plain `java.time` arithmetic.
 *
 * The magnitude words ("now", "5m", "2h", "6d") are supplied by the caller rather
 * than baked in: they are user-visible text and had been hardcoded English, so a
 * Chinese build showed "now" / "5m" next to translated labels. The colon-free
 * numeric forms (5m, 2h, 6d) stay as-is — they are units, not prose — while "now"
 * and the month-day date are localized at the call site.
 */
object RelativeTime {
    /**
     * "Mar 5"-style date for anything older than a week.
     *
     * Resolved from the *current* default locale on each call rather than cached in a
     * `val`: this object is initialised once per process, but the app can change its
     * display language at runtime (`LocaleManager.wrap` calls `Locale.setDefault` and
     * recreates the activity). A cached formatter would keep printing month names in
     * the language that happened to be active when the object was first touched, for
     * the remaining life of the process.
     */
    private fun monthDayFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    /**
     * Ultra-compact magnitude: [justNow], "5m", "2h", "6d", then a short date
     * ("Mar 5") past a week. No "ago"/"in" suffix (iOS `RelativeTime.compact`).
     */
    fun compact(
        instant: Instant,
        now: Instant = Instant.now(),
        justNow: String = "now",
    ): String {
        val seconds = now.epochSecond - instant.epochSecond
        // A timestamp slightly in the future (clock skew between phone and server)
        // reads as "just now" rather than a negative magnitude.
        if (seconds < 60) return justNow
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h"
        val days = hours / 24
        if (days < 7) return "${days}d"
        return monthDayFormatter().format(instant.atZone(ZoneId.systemDefault()))
    }
}

/**
 * A ticking "now" for relative timestamps, refreshed at the coarsest useful cadence.
 *
 * Without this, a list row computed `Instant.now()` inside composition: the label was
 * frozen at whatever it read the last time something *else* forced a recomposition, so
 * a conversation that said "now" could keep saying "now" for hours, and every
 * recomposition produced a slightly different string (non-idempotent composition).
 *
 * Minute granularity matches the coarsest unit [RelativeTime.compact] shows below a
 * week ("5m"), so a tick is never more precise than the label needs.
 */
@Composable
fun rememberNowInstant(): State<Instant> {
    val now = remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now.value = Instant.now()
        }
    }
    return now
}
