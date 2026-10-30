package app.codeg.android.feature.sessiondetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegMono
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.SessionStats

/**
 * How full the model's context window is, as a small ring.
 *
 * The desktop client puts this above the composer, and the app has carried the numbers
 * (`SessionStats.contextWindow*`) since the usage event was first handled — but nothing
 * ever drew them, so the one signal that tells you "this conversation is about to be
 * compacted" was invisible. Tap opens the exact counts, because a ring alone cannot say
 * whether 40% is 8k or 800k tokens.
 *
 * Renders nothing until the server has reported a window: a ring at 0% on a fresh session
 * would read as "the context is empty" rather than "no data yet".
 */
@Composable
fun ContextUsageRing(stats: SessionStats?, modifier: Modifier = Modifier) {
    val used = stats?.contextWindowUsedTokens ?: return
    val max = stats?.contextWindowMaxTokens ?: return
    if (max <= 0) return

    val colors = CodegTheme.colors
    val fraction = (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    // Green under half, accent approaching three quarters, danger once compaction is
    // imminent. The ring is the only always-visible signal, so its colour is the alert.
    val tint = when {
        fraction >= 0.9f -> colors.danger
        fraction >= 0.7f -> colors.accent
        else -> colors.textTertiary
    }
    val animated by animateFloatAsState(targetValue = fraction, animationSpec = tween(400), label = "ctx")
    var showDetail by remember { mutableStateOf(false) }

    Canvas(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(50))
            .clickable(
                onClickLabel = stringResource(R.string.session_context_usage),
                role = Role.Button,
            ) { showDetail = true },
    ) {
        val stroke = 2.2.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = colors.hairline,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke),
        )
        if (animated > 0f) {
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }

    if (showDetail) {
        ContextUsageDialog(used = used, max = max, percent = fraction, onDismiss = { showDetail = false })
    }
}

@Composable
private fun ContextUsageDialog(used: Int, max: Int, percent: Float, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = { Text(stringResource(R.string.session_context_usage), color = colors.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.session_context_used), fontSize = 14.sp, color = colors.textSecondary)
                    Text(
                        groupThousands(used),
                        fontFamily = CodegMono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.session_context_max), fontSize = 14.sp, color = colors.textSecondary)
                    Text(
                        groupThousands(max),
                        fontFamily = CodegMono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.session_context_percent), fontSize = 14.sp, color = colors.textSecondary)
                    Text(
                        "${(percent * 100).toInt()}%",
                        fontFamily = CodegMono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (percent >= 0.9f) colors.danger else colors.textPrimary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close), color = colors.accent) }
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** Thousands separators in the user's locale, so 200000 reads as 200,000. */
private fun groupThousands(value: Int): String =
    String.format(java.util.Locale.getDefault(), "%,d", value)
