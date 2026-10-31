package app.codeg.android.core.common

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash recording: stack traces are appended to a file in `cacheDir`, and the
 * System settings page can show and clear them.
 *
 * This exists because there was no way at all to find out why the app died. A report of
 * "it closed" cannot be acted on, and on a sideloaded build there is no Play Console to
 * read the trace from. The file lives in the cache directory — the system may evict it,
 * the user can clear it from the settings page, and nothing leaves the device.
 *
 * The trace is written before the process dies, so the write is deliberately synchronous
 * and bounded: an exception thrown *inside* the handler must not replace the original
 * crash in a loop, so everything here is wrapped and capped.
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_ENTRIES = 20
    private const val MAX_BYTES = 512 * 1024
    private const val TAG = "CodegCrash"

    /** Installs the handler once. Safe to call more than once. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            // Hand the crash to the platform unchanged: swallowing it would leave the
            // process in an undefined state rather than letting Android tear it down.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.cacheDir, FILE_NAME)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("=== ").append(timestamp).append(" · ").append(thread.name).append('\n')
            append(Log.getStackTraceString(throwable))
            append("\n\n")
        }
        // Rotate rather than grow without bound: this file is a debugging aid, not a
        // history, and an unconditional append would eventually fill the cache partition.
        val existing = if (file.exists()) {
            runCatching { file.readText() }.getOrDefault("")
        } else ""
        val combined = entry + existing
        val trimmed = if (combined.toByteArray().size > MAX_BYTES || combined.count { it == '\n' } > MAX_ENTRIES * 40) {
            combined.take(MAX_BYTES / 2)
        } else {
            combined
        }
        file.writeText(trimmed)
    }

    /** Everything recorded so far, or null when there is nothing. */
    fun read(context: Context): String? {
        val file = File(context.cacheDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { File(context.cacheDir, FILE_NAME).delete() }
    }
}
