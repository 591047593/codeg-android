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
 * 批v147 手势穿透修复 → 批v148 单向闸 → 批v151 全锁（真机反馈：列表顶部下拉
 * 想回看最上面的目录时误触关闭，弹卡选择项目目录等所有二级卡片同病）。
 *
 * ModalBottomSheet 自己的拖拽连接挂在 sheet 容器上，是内容的「父级」：内部
 * 列表没能消费的竖向拖拽/惯性会沿嵌套滚动链冒泡给 sheet，把整张卡片带着走。
 *
 * 这个连接垫在内容与 sheet 之间，契约是：
 * - **pre-scroll / pre-fling 全部放行**（返回 Zero）——内容自己正常滚，两个
 *   方向都不受影响；拦截必须发生在 post 阶段，否则列表根本滚不动。
 * - **post-scroll / post-fling 双向剩余一律吃掉**——列表到顶后的下拉剩余到
 *   不了 sheet，卡片不再被误关；向上剩余也吃掉（v148 语义保留）。
 *
 * 代价与补偿：**拖拽关闭从此只认固定头**——标题/拖拽把手不在任何滚动容器内
 * 时，手势原生到达 sheet，拖过阈值正常关闭（标准 bottom-sheet 手感）。凡是
 * 内容自带滚动又想保留拖拽关闭的 sheet，必须把标题挪出滚动区（固定头 +
 * 滚动体）；点遮罩、返回键两条关闭路径不受影响。
 */
class SheetDragLockConnection : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        // 双向剩余全吃：sheet 容器收不到任何来自内容滚动区的竖向剩余。
        Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Remember a drag-lock connection; pair with [Modifier.nestedScroll] inside the sheet content. */
@Composable
fun rememberSheetDragLock(): NestedScrollConnection = remember { SheetDragLockConnection() }

/**
 * 批v151 全锁的标准挂法：内容根布局（含所有滚动容器的那层）套上它即可。
 * 等价于 `Modifier.nestedScroll(rememberSheetDragLock())`——独立成一个
 * Modifier 让调用点不用自己拼，也方便未来在连接里加条件。
 */
@Composable
fun Modifier.sheetContentScrollLock(): Modifier {
    val lock = rememberSheetDragLock()
    return this.nestedScroll(lock)
}
