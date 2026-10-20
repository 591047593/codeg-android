package app.codeg.android.feature.sessiondetail

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import app.codeg.android.core.model.ImageData
import app.codeg.android.core.model.PromptInputBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * A prepared image attachment ready to send: base64 payload + mime type. Mirrors
 * iOS `Attachment` — the bytes are already downscaled/encoded by [AttachmentPrep].
 */
data class Attachment(
    val id: String,
    val base64: String,
    val mimeType: String,
    /** Raw (pre-base64) payload size, so the aggregate budget is measured directly. */
    val rawBytes: Int = 0,
) {
    val promptBlock: PromptInputBlock get() = PromptInputBlock.Image(data = base64, mimeType = mimeType)
    val imageData: ImageData get() = ImageData(data = base64, mimeType = mimeType)
}

/**
 * Downscales + encodes a picked image to keep prompts under the server's JSON body
 * limit. Port of iOS `AttachmentPrep`: longest side ≤ 1568 px, JPEG quality 0.75.
 *
 * Two separate budgets, previously conflated into a contradictory pair:
 *
 * - [MAX_TOTAL_BYTES] is the aggregate *raw* budget for one prompt. It is the number
 *   that actually decides whether an attachment can be sent, so it is also what a
 *   single image is measured against — an image larger than the whole budget can
 *   never be accepted as-is and must be re-encoded.
 * - [MAX_INPUT_BYTES] is only a safety valve: refuse to even decode an absurdly large
 *   pick rather than risk decoding it into a multi-hundred-megabyte bitmap.
 *
 * The old code re-encoded above 4 MB but rejected above 1.3 MB, so any image between
 * those sizes was silently dropped without ever being re-encoded.
 */
object AttachmentPrep {
    const val MAX_DIMENSION = 1568
    private const val JPEG_QUALITY = 75

    /** Aggregate raw-byte budget for all attachments on one prompt. */
    const val MAX_TOTAL_BYTES = 1_300_000

    /** Refuse to decode a picked file larger than this (OOM guard, not a send limit). */
    const val MAX_INPUT_BYTES = 64_000_000

    const val MAX_COUNT = 10

    suspend fun fromUri(uri: Uri, resolver: ContentResolver): Attachment? = withContext(Dispatchers.Default) {
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return@withContext null
        if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return@withContext null

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        val longest = max(bitmap.width, bitmap.height)
        // Re-encode when the image alone would blow the prompt budget, or when its
        // dimensions exceed what the server renders.
        val needsReencode = bytes.size > MAX_TOTAL_BYTES || longest > MAX_DIMENSION
        val (outBytes, mime) = if (needsReencode) {
            val scale = if (longest > MAX_DIMENSION) MAX_DIMENSION.toFloat() / longest else 1f
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            baos.toByteArray() to "image/jpeg"
        } else {
            bytes to sniffMime(bytes)
        }
        // A re-encode that still does not fit the budget is genuinely unsendable.
        if (outBytes.isEmpty() || outBytes.size > MAX_TOTAL_BYTES) return@withContext null

        Attachment(
            id = UUID.randomUUID().toString(),
            base64 = Base64.encodeToString(outBytes, Base64.NO_WRAP),
            mimeType = mime,
            rawBytes = outBytes.size,
        )
    }

    /** Sniff a mime type from magic bytes; default to PNG. */
    private fun sniffMime(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "image/png"
        b.size >= 4 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() -> "image/gif"
        b.size >= 12 && b[0] == 0x52.toByte() && b[8] == 0x57.toByte() && b[9] == 0x45.toByte() -> "image/webp"
        else -> "image/png"
    }
}
