package app.codeg.android.shell.error

/** UI-thread gate shared by automatic, manual and foreground recovery requests. */
internal class PageRetryGate {
    var isLoading: Boolean = false
        private set
    var activeUrl: String? = null
        private set
    var generation: Long = 0
        private set

    /** Reserve one recovery load; page starts independently advance ownership. */
    fun begin(): Boolean {
        if (isLoading) return false
        generation += 1 // Invalidate pending completion reads before reload starts.
        isLoading = true
        return true
    }

    fun start(url: String): Long {
        activeUrl = url.substringBefore('#')
        generation += 1
        isLoading = true
        return generation
    }

    fun accepts(url: String): Boolean = activeUrl != null && activeUrl == url.substringBefore('#')

    fun owns(generation: Long): Boolean = isLoading && this.generation == generation

    // Native callbacks have no unique ID for identical-URL retries. Completion
    // also needs current document readiness; errors must remain reportable.
    fun acceptsCompletion(url: String, currentUrl: String?, progress: Int): Boolean =
        accepts(url) && currentUrl != null && accepts(currentUrl) && progress == 100

    fun finish() { isLoading = false }
}
