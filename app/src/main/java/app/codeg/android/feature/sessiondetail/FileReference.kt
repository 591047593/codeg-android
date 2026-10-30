package app.codeg.android.feature.sessiondetail

/**
 * The `@file` reference the desktop composer inserts, as plain Markdown.
 *
 * The backend resolves a file mention from the link destination, not from the label, so
 * this has to be a `[name](file:///abs/path)` link with every segment percent-encoded —
 * a path with a space or a `#` in it would otherwise truncate or mis-resolve. Digits and
 * the RFC 3986 unreserved set stay literal so ordinary paths remain readable.
 *
 * Pure and free of Android types so it can be unit-tested against the same cases the
 * desktop's `buildFileUri` covers.
 */
object FileReference {

    /** The Markdown to splice into the composer for [absolutePath]. */
    fun markdown(absolutePath: String, label: String? = null): String {
        val normalized = absolutePath.replace('\\', '/')
        val text = (label ?: normalized.substringAfterLast('/')).replace("[", "\\[").replace("]", "\\]")
        return "[$text](${uri(normalized)})"
    }

    /** `file://` + percent-encoded path, with a leading slash for POSIX paths. */
    fun uri(absolutePath: String): String {
        val normalized = absolutePath.replace('\\', '/')
        val encoded = normalized.split('/').joinToString("/") { encodeSegment(it) }
        return when {
            // UNC share: `//server/share` → `file://server/share`.
            normalized.startsWith("//") -> "file://" + encoded.removePrefix("//")
            normalized.startsWith("/") -> "file://$encoded"
            else -> "file:///$encoded"
        }
    }

    /**
     * Percent-encode one path segment.
     *
     * Only the unreserved set survives. `+` and space both have to be encoded: a literal
     * `+` is legal in a path but is read as a space by enough tools that leaving it raw
     * breaks round-tripping.
     */
    private fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
                append(c)
            } else {
                append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/**
 * Insert a file reference into [draft], separated from the existing text by one space.
 *
 * Mirrors [ComposeInsert.appendMessage] rather than appending blindly, so repeatedly
 * inserting files does not build up runs of whitespace.
 */
fun appendFileReference(draft: String, absolutePath: String): String {
    val ref = FileReference.markdown(absolutePath)
    return if (draft.isBlank()) "$ref " else "${draft.trimEnd()} $ref "
}
