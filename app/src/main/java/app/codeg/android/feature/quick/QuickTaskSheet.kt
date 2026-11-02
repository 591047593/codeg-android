package app.codeg.android.feature.quick

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.feature.dispatch.DispatchForm

/**
 * The ball's 创建任务 sheet (批v150 §1.4): the unified [DispatchForm] in task
 * mode by default, no idea source, hosted by [QuickActionViewModel] (§1.5 宿主
 * B). Reaching the dispatch pipeline WITHOUT entering the inbox tab is
 * acceptance criterion #3 (不进收件箱即可创建任务).
 *
 * v146 B-7（审查轮1 W2 修正上移）：CONFIRMED 回执对话框渲染在 shell 层
 * （MainShell 消费 [QuickActionViewModel.confirmedRef]）——sheet 中途被关
 * （旋转/误滑）也不吞回执；这里只负责表单与关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTaskSheet(
    onDismiss: () -> Unit,
    viewModel: QuickActionViewModel = hiltViewModel(),
) {
    // VM 是 activity 作用域、比 sheet 长寿——上次打开残留的错误行不能迎人。
    LaunchedEffect(Unit) { viewModel.clearError() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // v148 sheet contract: the form scrolls internally (it has its
                // own scroll column); drag-to-close stays on the handle area.
                // No outer scroll wrapper — same-direction nested scroll would
                // muddy the drag-lock (round-3 review W3).
                .nestedScroll(rememberSheetDragLock()),
        ) {
            Text(
                stringResource(R.string.quick_task_title),
                style = MaterialTheme.typography.titleMedium,
                color = app.codeg.android.core.designsystem.theme.CodegTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            DispatchForm(
                host = viewModel,
                defaultSessionMode = false,
                onResult = { ref ->
                    if (ref.state == DispatchState.CONFIRMED) {
                        viewModel.confirmCreated(ref)
                        onDismiss()
                    }
                },
            )
        }
    }
}
