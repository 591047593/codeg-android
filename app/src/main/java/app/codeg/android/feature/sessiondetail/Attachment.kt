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
) {
    val promptBlock: PromptInputBlock get() = PromptInputBlock.Image(data = base64, mimeType = mimeType)
    val imageData: ImageData get() = ImageData(data = base64, mimeType = mimeType)
}

/**
 * Downscales + encodes a picked image to keep prompts under Axum's JSON body
 * limit. Port of iOS `AttachmentPrep`: longest side ≤ 1568 px, JPEG quality 0.75,
 * ≤ 4 MB per image hard ceiling. Originals already within limits keep their
 * bytes + sniffed mime.
 *
 * v149.2 adaptive re-encode: the composer's aggregate cap is
 * [AttachmentPrep.MAX_TOTAL_BYTES] × 4/3 base64 chars (the 2 MiB axum prompt
 * ceiling), so an original that skipped re-encode (small dimensions, up to
 * 4 MB) was attached and then REJECTED there — a ~1.5 MB screenshot filled the
 * whole budget and every later image (even a few KB) was skipped as "too
 * large". Prep now walks [LADDER] step-down (dimension × quality) until the
 * JPEG fits [TARGET_BYTES], so any attachable image lands small enough for
 * 2–3 of them to coexist; only an image failing every step returns null.
 */
object AttachmentPrep {
    const val MAX_DIMENSION = 1568
    private const val JPEG_QUALITY = 75
    const val MAX_BYTES = 4_000_000
    const val MAX_TOTAL_BYTES = 1_300_000
    const val MAX_COUNT = 10

    /** Per-image byte budget after prep — see the object KDoc. */
    const val TARGET_BYTES = 550_000

    /**
     * Step-down re-encode ladder: (longest side px, JPEG quality). Monotonic;
     * the last step yields ≲100 KB even for noise-heavy photos. Pure data so
     * the schedule is unit-testable without Android graphics.
     */
    internal val LADDER = listOf(
        PrepStep(MAX_DIMENSION, JPEG_QUALITY),
        PrepStep(1280, 60),
        PrepStep(1024, 50),
        PrepStep(768, 40),
    )

    /**
     * Pure prep routing: -1 keeps the original (already within the byte budget
     * and the dimension cap — no quality loss), otherwise the [LADDER] index to
     * start from. Returns -2 for a non-image (no bytes). Unit-tested.
     */
    internal fun prepRoute(originalBytes: Int, longestSide: Int, targetBytes: Int = TARGET_BYTES): Int =
        when {
            originalBytes <= 0 || longestSide <= 0 -> -2
            originalBytes <= targetBytes && longestSide <= MAX_DIMENSION -> -1
            else -> 0
        }

    suspend fun fromUri(uri: Uri, resolver: ContentResolver): Attachment? = withContext(Dispatchers.Default) {
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        val longest = max(bitmap.width, bitmap.height)
        when (val start = prepRoute(bytes.size, longest)) {
            -1 -> Attachment(
                id = UUID.randomUUID().toString(),
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                mimeType = sniffMime(bytes),
            )
            -2 -> null
            else -> {
                var out: ByteArray? = null
                for (step in LADDER.drop(start)) {
                    val scale = if (longest > step.longestSide) step.longestSide.toFloat() / longest else 1f
                    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, step.jpegQuality, baos)
                    val encoded = baos.toByteArray()
                    if (encoded.isEmpty()) continue
                    if (encoded.size <= TARGET_BYTES) { out = encoded; break }
                    if (out == null || encoded.size < out.size) out = encoded
                }
                val best = out
                if (best == null || best.isEmpty() || best.size > MAX_BYTES) {
                    return@withContext null
                }
                Attachment(
                    id = UUID.randomUUID().toString(),
                    base64 = Base64.encodeToString(best, Base64.NO_WRAP),
                    mimeType = "image/jpeg",
                )
            }
        }
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

/** One rung of the [AttachmentPrep.LADDER]: re-encode to this longest side at this JPEG quality. */
internal data class PrepStep(val longestSide: Int, val jpegQuality: Int)

/**
 * Auto-rename for a workspace upload that hit "already exists" (409): the
 * v149.2 fix for "retrying the same file keeps failing" — a previous attempt
 * may have landed the bytes even though the client saw an error, and the
 * server refuses to overwrite. "a.png" → "a (1).png"; extensionless names get
 * the suffix too; a leading-dot name keeps its dot. Pure — unit-tested.
 */
internal fun autoRenamedFileName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} (1)${name.substring(dot)}" else "$name (1)"
}
