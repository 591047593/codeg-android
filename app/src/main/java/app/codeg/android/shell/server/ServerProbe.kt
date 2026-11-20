package app.codeg.android.shell.server

internal enum class ServerProbeOutcome { READY, AUTH_REQUIRED, NOT_FOUND, SERVER_ERROR, UNRECOGNIZED }

internal fun classifyServerProbe(code: Int, contentType: String, sample: String): ServerProbeOutcome = when {
    code == 401 || code == 403 || code in 300..399 -> ServerProbeOutcome.AUTH_REQUIRED
    code == 404 -> ServerProbeOutcome.NOT_FOUND
    code !in 200..299 -> ServerProbeOutcome.SERVER_ERROR
    contentType.contains("text/html", ignoreCase = true) &&
        Regex("<title[^>]*>[^<]*codeg[^<]*</title>", RegexOption.IGNORE_CASE).containsMatchIn(sample) -> ServerProbeOutcome.READY
    else -> ServerProbeOutcome.UNRECOGNIZED
}
