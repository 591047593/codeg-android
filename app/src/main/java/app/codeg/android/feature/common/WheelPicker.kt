package app.codeg.android.feature.common

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.core.designsystem.theme.CodegTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * One vertical wheel column (clock-app style): drag to spin, snap to the
 * nearest item on release, the centered item enlarged + accent-highlighted
 * while neighbors shrink and fade. Pure scrolling — no button chrome.
 *
 * Sizing contract: [itemHeightDp] × [visibleCount] fixed rows; odd
 * [visibleCount] so one row sits exactly in the middle. External selection
 * changes (e.g. preselected defaults) scroll the wheel; user settles select
 * back through [onSelect].
 */
@Composable
fun WheelColumn(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleCount: Int = 5,
    itemHeightDp: Int = 40,
    emptyText: String? = null,
) {
    val colors = CodegTheme.colors
    val itemHeightPx = with(LocalDensity.current) { itemHeightDp.dp.toPx() }
    val state = rememberLazyListState()
    val fling = rememberSnapFlingBehavior(lazyListState = state)

    // External selection (defaults, device switch reset) drives the wheel.
    LaunchedEffect(items, selectedIndex) {
        if (items.isEmpty()) return@LaunchedEffect
        val target = selectedIndex.coerceIn(0, items.lastIndex)
        val current = state.firstVisibleItemIndex
        if (current != target || state.firstVisibleItemScrollOffset != 0) {
            state.scrollToItem(target)
        }
    }

    // Settled scroll → report the centered item. Fires only when the wheel
    // comes to rest, so dragging never spams onSelect mid-gesture.
    LaunchedEffect(items) {
        if (items.isEmpty()) return@LaunchedEffect
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    val layout = state.layoutInfo
                    val visible = layout.visibleItemsInfo
                    if (visible.isNotEmpty()) {
                        val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                        val nearest = visible.minByOrNull {
                            abs(it.offset + it.size / 2 - viewportCenter)
                        }
                        if (nearest != null && nearest.index in items.indices) {
                            onSelect(nearest.index)
                        }
                    }
                }
            }
    }

    Box(modifier = modifier.height((itemHeightDp * visibleCount).dp)) {
        if (items.isEmpty()) {
            emptyText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(
                state = state,
                flingBehavior = fling,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Half-viewport top/bottom padding lets the first/last item
                // physically reach the center row.
                item(key = "pad-top") { Box(Modifier.height((itemHeightDp * (visibleCount / 2)).dp)) }
                items(items.size) { index ->
                    val info = state.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == index }
                    val viewportCenter = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
                    val distance = info?.let { (it.offset + it.size / 2) - viewportCenter }
                    // 1.0 at the center row, 0.0 at the adjacent rows and beyond.
                    val ratio = distance?.let { d ->
                        (1f - (abs(d) / itemHeightPx).coerceIn(0f, 1f))
                    } ?: 0f
                    val centered = ratio > 0.5f
                    Box(
                        Modifier
                            .height(itemHeightDp.dp)
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = 1f + 0.2f * ratio
                                scaleY = 1f + 0.2f * ratio
                                alpha = 0.35f + 0.65f * ratio
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            items[index],
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (centered) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                            color = if (centered) colors.accent else colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                item(key = "pad-bottom") { Box(Modifier.height((itemHeightDp * (visibleCount / 2)).dp)) }
            }
        }
    }
}
