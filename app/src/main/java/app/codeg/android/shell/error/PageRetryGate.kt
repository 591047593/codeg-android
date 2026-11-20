package app.codeg.android.shell.error

/** UI-thread gate shared by automatic, manual and foreground recovery requests. */
internal class PageRetryGate {
    var isLoading: Boolean = false
        private set

    fun begin(): Boolean {
        if (isLoading) return false
        isLoading = true
        return true
    }

    fun finish() { isLoading = false }
}
