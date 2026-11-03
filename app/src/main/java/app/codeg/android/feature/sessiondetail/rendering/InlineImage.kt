package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.component.ImageViewerDialog
import app.codeg.android.core.designsystem.component.ImageViewerSource
import app.codeg.android.core.designsystem.component.decodeForViewer
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ImageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a base64 [ImageData] and renders it (max height 320dp), with an
 * optional caption. 批v155 段3: a tap opens the fullscreen viewer — the dialog
 * state lives here (its own window, unconstrained by parent bounds), so the
 * whole rendering stack needs no new callback threading.
 *
 * 批v155(R2D#3): the decode moved off the composition — it used to be a
 * synchronous `remember {}` on the main thread, decoding a full-res screenshot
 * (a base64 chat image is routinely 2-8 MP) mid-scroll; worst case jank, at
 * the limit an ANR. Now: produceState + Dispatchers.Default + the same
 * downsample the viewer uses (inline display caps at 320dp height, so 1440px
 * is already 4x oversampled).
 */
@Composable
fun InlineImage(image: ImageData, caption: String?, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val bitmap by produceState<ImageBitmap?>(null, image.data) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                decodeForViewer(ImageViewerSource.Base64(image.data), maxDim = 1440)
            }.getOrNull()
        }
    }
    // rememberSaveable: rotation/proc-death used to silently close the viewer.
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    // Local copy: a delegated property can't be smart-cast, so the null check
    // below would refuse to compile against `bitmap` directly.
    val decoded = bitmap
    Column(modifier.fillMaxWidth()) {
        if (decoded != null) {
            Image(
                bitmap = decoded,
                contentDescription = caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { viewerOpen = true },
            )
        } else {
            Text(
                "[image]",
                fontSize = 12.sp,
                color = colors.textTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeSurface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (viewerOpen) {
        ImageViewerDialog(
            images = listOf(ImageViewerSource.Base64(image.data)),
            onDismiss = { viewerOpen = false },
        )
    }
}
