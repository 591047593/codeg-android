package app.codeg.android.core.designsystem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlin.math.max
import kotlin.math.min

/**
 * Size-bounded decoding for the base64 images the server sends.
 *
 * `BitmapFactory.decodeByteArray` allocates `width x height x 4` bytes for the full
 * image: a 4000x3000 photo is ~48 MB in one allocation, and a transcript with a handful
 * of screenshots exhausted the 192 MB heap this app runs in. The renderer never shows an
 * image above a few hundred dp, so decoding at full resolution only ever wasted memory.
 *
 * Two passes are needed because the scale factor is only known after reading the bounds.
 * [maxDimension] bounds the longest side; the caller picks a value at or above the size it
 * will actually draw, so nothing is upscaled.
 */
object BoundedImageDecoder {

    /** Decode [base64] with its longest side at most [maxDimension]. Null when undecodable. */
    fun decode(base64: String, maxDimension: Int): Bitmap? {
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        return decode(bytes, maxDimension)
    }

    /**
     * The same bounds, for callers that already hold the encoded bytes.
     *
     * Avoids a base64 round trip on the attachment path, where the bytes come straight
     * from the content resolver and the result is re-encoded again.
     */
    fun decode(bytes: ByteArray, maxDimension: Int): Bitmap? {
        if (bytes.isEmpty()) return null

        // Pass 1: bounds only — this allocates nothing for the pixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        // Pass 2: decode at a power-of-two subsample. `inSampleSize` rounds down to the
        // nearest power of two, so this lands at or below `maxDimension` rather than over.
        val longest = max(w, h)
        var sample = 1
        while (longest / sample > maxDimension) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    /** Decode [base64] scaled to at most [targetWidth] px wide, preserving aspect ratio. */
    fun decodeForWidth(base64: String, targetWidth: Int): Bitmap? =
        decode(base64, max(1, targetWidth))
}
