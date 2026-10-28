package app.codeg.android.core.network

import android.content.Context
import app.codeg.android.R

/**
 * Maps a thrown error (typically an [ApiError]) to a short, *actionable*, localized
 * message.
 *
 * This used to return hardcoded English, which meant a Chinese build showed English for
 * every failure the app could produce — the one place localization mattered most. It also
 * forwarded [ApiError.Transport]'s raw exception text straight to the screen, so a failed
 * connection surfaced as `java.net.ConnectException: Failed to connect to /192.168.1.5:3080`
 * instead of something a user can act on.
 *
 * Every branch now names what failed and what to try next. A [Context] is required so the
 * strings resolve through the resource system and follow the app language.
 */
fun Throwable.displayMessage(context: Context): String = when (this) {
    is ApiError.Unauthorized -> context.getString(R.string.err_unauthorized)
    is ApiError.TurnInProgress -> context.getString(R.string.err_turn_in_progress)
    is ApiError.Decoding -> context.getString(R.string.err_decoding)
    is ApiError.ResponseTooLarge -> context.getString(R.string.err_response_too_large)
    is ApiError.Transport -> transportMessage(context)
    is ApiError.Server -> context.getString(R.string.err_server, status, serverMessage)
    // Anything else reaching the UI is unexpected, so the message is shown — but as a
    // quoted detail under a localized prefix rather than as the whole error text.
    else -> message?.takeIf { it.isNotBlank() }
        ?.let { context.getString(R.string.err_unknown, it) }
        ?: context.getString(R.string.err_network)
}

/**
 * Fold a socket/HTTP client failure into one of two messages the user can act on.
 *
 * The distinction that matters is "you cannot reach it" versus "it took too long"; the
 * underlying exception class is what tells them apart, and its `message` is never shown
 * (it is JVM English and names internal addresses).
 */
private fun ApiError.Transport.transportMessage(context: Context): String {
    val text = detail.lowercase()
    val timedOut = text.contains("timeout") ||
        text.contains("timed out") ||
        text.contains("connecttimeout") ||
        text.contains("sockettimeout")
    return context.getString(if (timedOut) R.string.err_timeout else R.string.err_network)
}
