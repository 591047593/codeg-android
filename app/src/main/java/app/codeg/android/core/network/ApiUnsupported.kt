package app.codeg.android.core.network

/**
 * Old-server gate shared by the per-device capability surfaces (fleet M2
 * automations, M3 token usage): a 404 or a "no such route/method" server
 * error means the surface doesn't exist there — not a transient failure.
 * The generic `not_found` code is deliberately NOT matched (it also fires
 * for missing rows and would misread a healthy server as unsupported).
 */
fun isUnsupportedMethod(e: Throwable): Boolean {
    val server = e as? ApiError.Server ?: return false
    if (server.status == 404) return true
    val code = server.code?.lowercase().orEmpty()
    if (code in UNSUPPORTED_METHOD_CODES) return true
    val msg = server.serverMessage.lowercase()
    return UNSUPPORTED_METHOD_MSGS.any { msg.contains(it) }
}

private val UNSUPPORTED_METHOD_CODES = setOf(
    "unknown_method", "method_not_found", "invalid_method", "no_such_method",
)

private val UNSUPPORTED_METHOD_MSGS = listOf("unknown method", "no such method", "method not found")
