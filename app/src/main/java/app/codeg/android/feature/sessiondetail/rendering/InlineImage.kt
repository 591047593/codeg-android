package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.BoundedImageDecoder
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ImageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Longest side to decode at. The image is drawn at <=320dp tall; at the densest common
 * phone that is ~1280px, so 1280 is the most that can ever be visible.
 */
private const val INLINE_MAX_PX = 1280

/** Decodes a base64 [ImageData] and renders it (max height 320dp), with an optional caption. */
@Composable
fun InlineImage(image: ImageData, caption: String?, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    // Decoded off the main thread.
    //
    // This used to run inside `remember`, so the base64 decode plus two BitmapFactory
    // passes (bounds, then the bounded decode) happened on the composition thread the
    // first time the image scrolled into view — a ~6 MB bitmap for a 1280px photo, and a
    // visible stall on every one of them. `produceState` keeps the frame clean and shows
    // the placeholder until the bitmap is ready, which is also what makes scrolling past
    // a transcript with images smooth.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, image.data) {
        value = withContext(Dispatchers.Default) {
            BoundedImageDecoder.decode(image.data, INLINE_MAX_PX)?.asImageBitmap()
        }
    }
    val ready = bitmap
    Column(modifier.fillMaxWidth()) {
        if (ready != null) {
            Image(
                bitmap = ready,
                contentDescription = caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            // Placeholder while decoding: same footprint so the layout does not jump when
            // the bitmap lands.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.codeSurface),
                contentAlignment = Alignment.Center,
            ) {
                Text("[image]", fontSize = 12.sp, color = colors.textTertiary)
            }
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
}
