package app.codeg.android.feature.sessiondetail.rendering

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.core.designsystem.BoundedImageDecoder
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ImageData

/**
 * Longest side to decode at. The image is drawn at <=320dp tall; at the densest common
 * phone that is ~1280px, so 1280 is the most that can ever be visible.
 */
private const val INLINE_MAX_PX = 1280

/** Decodes a base64 [ImageData] and renders it (max height 320dp), with an optional caption. */
@Composable
fun InlineImage(image: ImageData, caption: String?, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    // Bounded decode: the panel draws this at <=320dp, so decoding a 4000px photo at
    // full size only cost heap (48 MB per image) without showing any more detail.
    val bitmap = remember(image.data) {
        BoundedImageDecoder.decode(image.data, INLINE_MAX_PX)?.asImageBitmap()
    }
    Column(modifier.fillMaxWidth()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp)),
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
}
