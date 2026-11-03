package app.codeg.android.core.designsystem.component

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codeg.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

/**
 * 批v155 段3: the fullscreen image viewer (pinch zoom, double-tap zoom, pan,
 * multi-image swipe). Zero external dependencies — pages decode with
 * [BitmapFactory] (base64 blobs or app-private files), downsampled to a
 * viewer-friendly bound so twenty attachments cannot OOM the dialog.
 *
 * Wire points: composer attachment strip, idea editor/card attachment rows,
 * dispatch form attachment rows, chat inline images.
 */

/** One viewer page: an inline base64 blob or a local file path. */
sealed interface ImageViewerSource {
    data class Base64(val data: String) : ImageViewerSource
    data class File(val path: String) : ImageViewerSource
}

/** Max decoded dimension for a viewer page — ~2k keeps zoom usable at a
 *  phone-typical density without ever holding a full-camera image. */
private const val VIEWER_MAX_DIM = 2048

@Composable
fun ImageViewerDialog(
    images: List<ImageViewerSource>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                val pagerState = rememberPagerState(
                    initialPage = initialIndex.coerceIn(0, images.lastIndex),
                    pageCount = { images.size },
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { it },
                ) { page ->
                    ViewerPage(
                        source = images[page],
                        // Gesture gate read at EVENT time (not composition): reading
                        // currentPageOffsetFraction here would recompose every page
                        // on every swipe frame.
                        tapAllowed = {
                            pagerState.currentPage == page &&
                                pagerState.currentPageOffsetFraction.absoluteValue < 0.1f
                        },
                        onDismiss = onDismiss,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .statusBarsPadding()
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = Color.White,
                    )
                }
                if (images.size > 1) {
                    Text(
                        "${pagerState.currentPage + 1} / ${images.size}",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .statusBarsPadding()
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp),
                    )
                }
            }
        }
    }
}

/**
 * One zoomable page. Transform state is per-page (the pager disposes
 * off-screen pages by default), so swiping back returns to a fresh 1x view —
 * deliberate: a kept zoom/pan on return is more confusing than a reset.
 *
 * Gesture split (the pager coexistence rule): at 1x a single finger is NEVER
 * consumed — the pager owns horizontal swipes and taps pass through. Only a
 * second finger (pinch) or an already-zoomed page consumes drag events, which
 * naturally also stops the pager from swiping while the user is panning.
 */
@Composable
private fun ViewerPage(
    source: ImageViewerSource,
    tapAllowed: () -> Boolean,
    onDismiss: () -> Unit,
) {
    // (decoded, bitmap): decoded flips once the decode pass has run, so a
    // corrupt/unreadable source shows a failure note instead of a forever spinner.
    val decodeState by produceState(Pair(false, null as ImageBitmap?), source) {
        value = true to withContext(Dispatchers.Default) {
            runCatching { decodeForViewer(source) }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }

    fun clamp() {
        if (container == IntSize.Zero || scale <= 1f) {
            offset = Offset.Zero
            return
        }
        val maxX = container.width * (scale - 1f) / 2f
        val maxY = container.height * (scale - 1f) / 2f
        offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { container = it; clamp() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomed = scale > 1f
                        val pinching = event.changes.size > 1
                        if (pinching || zoomed) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f) {
                                scale = (scale * zoomChange).coerceIn(1f, 5f)
                            }
                            if (zoomed || pinching) {
                                offset += panChange
                            }
                            clamp()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // Dismiss only from a restful 1x page: while zoomed, taps are
                    // ambiguous with the double-tap zoom-out window (and every
                    // photo viewer suppresses it when zoomed).
                    onTap = { if (scale <= 1f && tapAllowed()) onDismiss() },
                    onDoubleTap = { center ->
                        val target = if (scale > 1.5f) 1f else 2.5f
                        // Zoom toward the tapped point: with
                        // screen = center + (point - center) * scale + offset,
                        // keeping that point fixed gives
                        // offset' = offset - (point - center) * (target - scale).
                        if (target == 1f || container == IntSize.Zero) {
                            offset = Offset.Zero
                        } else {
                            val fromCenter = Offset(
                                center.x - container.width / 2f,
                                center.y - container.height / 2f,
                            )
                            offset -= fromCenter * (target - scale)
                        }
                        scale = target
                        clamp()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val (decoded, bmp) = decodeState
        when {
            !decoded ->
                CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(24.dp))
            bmp == null ->
                Text(
                    stringResource(R.string.image_viewer_failed),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            else ->
                Image(
                    bitmap = bmp,
                    contentDescription = stringResource(R.string.sessiondetail_attachment),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
        }
    }
}

/** Decode an image off the caller's thread, downsampled to [maxDim]. The loop
 *  compares the CURRENT sample (not the next one): the classic
 *  `w / (sample*2) >= max` form exits one rung early and lets a 4032px phone
 *  photo decode at full size (~48 MB ARGB_8888) — the exact OOM this exists
 *  to prevent. Internal so InlineImage (chat-rendered base64) shares the same
 *  off-main-thread downsample path (R2D#3) instead of decoding full-res on the
 *  UI thread; the function itself does no threading — callers wrap it. */
internal fun decodeForViewer(source: ImageViewerSource, maxDim: Int = VIEWER_MAX_DIM): ImageBitmap? {
    val bytes = when (source) {
        is ImageViewerSource.Base64 -> android.util.Base64.decode(source.data, android.util.Base64.DEFAULT)
        is ImageViewerSource.File -> java.io.File(source.path).takeIf { it.exists() }?.readBytes()
    } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample >= maxDim || bounds.outHeight / sample >= maxDim) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/**
 * Small async thumbnail of a local image file (idea/attachment rows). Decode
 * is downsampled to ~320px — a row thumbnail never needs more.
 */
@Composable
fun LocalImageThumbnail(
    path: String,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 8,
) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                if (!java.io.File(path).exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample >= 320 || bounds.outHeight / sample >= 320) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = bitmap
    Box(
        modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(cornerRadius.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text("[image]", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.4f))
        }
    }
}
