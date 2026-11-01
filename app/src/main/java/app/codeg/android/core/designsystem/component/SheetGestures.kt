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
 * 批v147 手势穿透修复；批v148 按真机反馈改为单向闸。
 *
 * ModalBottomSheet 自己的拖拽连接挂在 sheet 容器上，是内容的「父级」：
 * 内部列表没能消费的竖向拖拽会沿嵌套滚动链冒泡给 sheet，把整张卡片带着走。
 *
 * 这个连接垫在内容与 sheet 之间，只做一件事：**向上（关闭方向的反向）的
 * 剩余拖拽与全部剩余惯性一律吃掉**——在滚轮/短列表上向上滑不再拽动卡片；
 * **向下的剩余拖拽放行**——内容已在顶部时往下拖，sheet 跟随手指、
 * 拖过阈值正常关闭（标准 bottom-sheet 手感，关闭只吃刻意的下拉）。
 * 点遮罩、返回键两条关闭路径不受影响。
 */
class SheetDragLockConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        // available.y > 0 = 手指向下的剩余（内容已在顶部）：放行给 sheet → 可拖拽关闭。
        // available.y <= 0 = 向上剩余：吞掉，卡片不许被内容拽着走。
        if (available.y > 0f) Offset.Zero else Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Remember a drag-lock connection; pair with [Modifier.nestedScroll] inside the sheet content. */
@Composable
fun rememberSheetDragLock(): NestedScrollConnection = remember { SheetDragLockConnection() }
