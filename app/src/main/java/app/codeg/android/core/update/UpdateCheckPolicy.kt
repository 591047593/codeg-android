package app.codeg.android.core.update

internal object UpdateCheckPolicy {
    fun shouldCheck(now: Long, lastSuccess: Long, lastFailure: Long, manual: Boolean): Boolean {
        if (manual) return true
        fun recent(timestamp: Long, interval: Long) = timestamp > 0 && now >= timestamp && now - timestamp < interval
        return !recent(lastFailure, 60_000L) && !recent(lastSuccess, 6 * 60 * 60 * 1000L)
    }
}
