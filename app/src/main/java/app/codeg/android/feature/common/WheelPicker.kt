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
import kotlinx.coroutines.flow.drop
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Display row a settled LazyColumn index reports as, or null when the settle
 * landed on one of the half-viewport pads (LazyColumn indices 0 and
 * [itemCount] + 1) with no row centered. Real rows sit at LazyColumn indices
 * 1..[itemCount], so the display row is always lazyIndex − 1 — v149.1: the raw
 * index was reported/validated directly, which silently dropped the last
 * row's settle (the dispatch agent wheel's last entry) and committed one row
 * below every other scroll selection. Pure so the geometry is unit-testable.
 */
internal fun wheelDisplayIndex(lazyColumnIndex: Int, itemCount: Int): Int? =
    (lazyColumnIndex - 1).takeIf { it in 0 until itemCount }

/**
 * One vertical wheel column (clock-app style): drag to spin, snap to the
 * nearest item on release, the centered item enlarged + accent-highlighted
 * while neighbors shrink and fade. Pure scrolling — no button chrome.
 *
 * Sizing contract: [itemHeightDp] × [visibleCount] fixed rows; odd
 * [visibleCount] so one row sits exactly in the middle. External selection
 * changes (e.g. preselected defaults) scroll the wheel; user settles select
 * back through [onSelect]. [onSelect] reports DISPLAY indices (0-based into
 * [items]) — never raw LazyColumn indices, which the internal half-viewport
 * pads shift by one.
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
    // Centering display row [target] takes a content scroll of exactly
    // itemHeight × target: the half-viewport pad-top is the runway that lets
    // row 0 reach the center band, and pad-bottom clamps the last row there
    // too. (Scrolling by LazyColumn ITEM index was off by one row for every
    // target > 0.)
    LaunchedEffect(items, selectedIndex) {
        if (items.isEmpty()) return@LaunchedEffect
        val target = selectedIndex.coerceIn(0, items.lastIndex)
        state.scrollToItem(0, (itemHeightPx * target).roundToInt())
    }

    // Settled scroll → report the centered item. Fires only when the wheel
    // comes to rest, so dragging never spams onSelect mid-gesture. drop(1)
    // skips the flow's initial "not scrolling" emission, which would report
    // the pre-positioning row and clobber an external preselect at composition.
    LaunchedEffect(items) {
        if (items.isEmpty()) return@LaunchedEffect
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .drop(1)
            .collect { scrolling ->
                if (!scrolling) {
                    val layout = state.layoutInfo
                    val visible = layout.visibleItemsInfo
                    if (visible.isNotEmpty()) {
                        val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                        val nearest = visible.minByOrNull {
                            abs(it.offset + it.size / 2 - viewportCenter)
                        }
                        if (nearest != null) {
                            // See [wheelDisplayIndex]: report the display row,
                            // never the pad-shifted LazyColumn index.
                            wheelDisplayIndex(nearest.index, items.size)?.let(onSelect)
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
