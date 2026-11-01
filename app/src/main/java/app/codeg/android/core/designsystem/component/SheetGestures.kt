package app.codeg.android.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * 批v147 手势穿透修复。ModalBottomSheet 自己的拖拽连接挂在 sheet 容器上，
 * 是内容的「父级」：内部列表滚到头（或滚不到头）时，没被消费的竖向拖拽
 * 会沿着嵌套滚动链冒泡给 sheet，把整张卡片带着走。
 *
 * 这个连接垫在内容与 sheet 之间，把所有冒泡上来的竖向剩余拖拽/惯性全部
 * 吃掉——内部列表独立滚动，卡片纹丝不动。关闭手势只保留：点遮罩、返回键
 * （外层一律配合 `confirmValueChange` 拒绝 Hidden，拖拽连关闭都关不掉）。
 */
class SheetDragLockConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(0f, available.y)

    override fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Remember a drag-lock connection; pair with [Modifier.nestedScroll] inside the sheet content. */
@Composable
fun rememberSheetDragLock(): NestedScrollConnection = remember { SheetDragLockConnection() }
