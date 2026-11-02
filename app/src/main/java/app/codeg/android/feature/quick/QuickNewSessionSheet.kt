package app.codeg.android.feature.quick

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.dispatch.DispatchForm

/**
 * The ball's 全局新建会话 sheet (批v151 §六): the unified [DispatchForm] with
 * session mode preselected, hosted by [QuickActionViewModel] (宿主 B) — pick the
 * device first, then the folder (the form's directory picker is device-scoped),
 * and the created session opens automatically.
 *
 * CONFIRMED session results skip the shell receipt dialog and jump straight to
 * the conversation (same rule as the inbox's 批v149 session dispatch: the first
 * message is composed on the detail page). Any other outcome — task mode
 * results, UNKNOWN corpses — falls back to the standard receipt dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickNewSessionSheet(
    /** Session-mode CONFIRMED result → close + jump (shell owns the dance). */
    onConfirmedSession: (serverId: String, conversationId: Int) -> Unit,
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
                // v151 sheet contract: the form scrolls internally; the lock eats
                // all bidirectional content leftover, so drag-to-close lives on
                // the fixed title above (the sheet itself sets dragHandle = null).
                .nestedScroll(rememberSheetDragLock()),
        ) {
            Text(
                stringResource(R.string.quick_new_session_title),
                style = MaterialTheme.typography.titleMedium,
                color = CodegTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            DispatchForm(
                host = viewModel,
                defaultSessionMode = true,
                onResult = { ref ->
                    val convId = ref.remoteTodoId
                    if (ref.sessionMode && ref.state == DispatchState.CONFIRMED && convId != null) {
                        onConfirmedSession(ref.serverId, convId)
                    } else {
                        viewModel.confirmCreated(ref)
                        onDismiss()
                    }
                },
            )
        }
    }
}
