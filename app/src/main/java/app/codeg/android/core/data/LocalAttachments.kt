package app.codeg.android.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One file the user attached to an idea / dispatch, copied into app-private
 * storage so it survives a restart, a re-dispatch or a device switch (批v154).
 *
 * The bytes stay LOCAL until dispatch: the target device is not known when the
 * idea is written (an idea may sit in the inbox for days and be dispatched to any
 * device), and a workspace path is device-local. [DispatchService] uploads the
 * file into the chosen device's `uploads/` right before creating the task /
 * conversation, so the prompt can point at a real path there.
 */
@Serializable
data class LocalAttachment(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    /** Absolute path inside the app's private files dir (never a SAF uri). */
    val localPath: String,
    val size: Long = 0,
    val isImage: Boolean = false,
)

/**
 * A file that already exists on the TARGET device (the result of
 * `upload_workspace_file`): [absolutePath] is what the prompt hands the agent.
 */
data class UploadedAttachment(
    val name: String,
    val absolutePath: String,
)

/**
 * Copies SAF picks into app-private storage (批v154). An idea's attachment must
 * outlive the picker's uri grant (which dies with the screen/process), so the
 * bytes are staged immediately and only the app-private path is persisted.
 */
@Singleton
class AttachmentStager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Copy [uri] into `filesDir/idea_attachments/<id>/<name>`; null if unreadable. */
    suspend fun stage(uri: Uri, name: String?, mimeType: String?, isImage: Boolean): LocalAttachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val displayName = name?.takeIf { it.isNotBlank() }
                    ?: displayNameOf(resolver, uri)
                    ?: "file"
                val dir = File(File(context.filesDir, ROOT), UUID.randomUUID().toString())
                    .apply { mkdirs() }
                val target = uniqueFile(dir, sanitize(displayName))
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                } ?: return@runCatching null
                if (target.length() <= 0) {
                    target.delete()
                    return@runCatching null
                }
                LocalAttachment(
                    id = target.parentFile?.name ?: UUID.randomUUID().toString(),
                    name = target.name,
                    mimeType = mimeType,
                    localPath = target.absolutePath,
                    size = target.length(),
                    isImage = isImage,
                )
            }.getOrNull()
        }

    /** Delete the staged copy (idea deleted / attachment removed). */
    fun discard(att: LocalAttachment) {
        runCatching { File(att.localPath).parentFile?.deleteRecursively() }
    }

    private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    /** No path separators, no `.`/`..`, bounded length — the name reaches a shell-free
     *  multipart header and a server-side path, so keep it boring. */
    private fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f]"), "")
            .trim()
            .trimStart('.')
            .take(120)
        return cleaned.ifBlank { "file" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = File(dir, "$stem-$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private companion object {
        const val ROOT = "idea_attachments"
    }
}