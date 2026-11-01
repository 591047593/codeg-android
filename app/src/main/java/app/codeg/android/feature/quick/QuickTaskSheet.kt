package app.codeg.android.feature.quick

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.feature.dispatch.DispatchForm

/**
 * The ball's 创建任务 sheet (批v150 §1.4): the unified [DispatchForm] in task
 * mode by default, no idea source, hosted by [QuickActionViewModel] (§1.5 宿主
 * B). Reaching the dispatch pipeline WITHOUT entering the inbox tab is
 * acceptance criterion #3 (不进收件箱即可创建任务).
 *
 * v146 B-7: the result dialog renders HERE (sheet-host level), not inside the
 * form, mirroring the inbox host's contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTaskSheet(
    onDismiss: () -> Unit,
    viewModel: QuickActionViewModel = hiltViewModel(),
) {
    var confirmed by remember { mutableStateOf<DispatchRef?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // v148 sheet contract: form scrolls, drag-to-close stays on the
                // handle area only.
                .nestedScroll(rememberSheetDragLock())
                .verticalScroll(rememberScrollState()),
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
                onResult = { ref -> if (ref.state == DispatchState.CONFIRMED) confirmed = ref },
            )
        }
    }

    confirmed?.let { ref ->
        val noun = stringResource(if (ref.sessionMode) R.string.dispatch_mode_session else R.string.dispatch_mode_task)
        AlertDialog(
            onDismissRequest = { confirmed = null },
            title = { Text(stringResource(R.string.dispatch_created_title, noun)) },
            text = {
                Text(
                    stringResource(
                        R.string.dispatch_created_body,
                        ref.serverName.ifBlank { ref.serverId },
                        ref.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmed = null
                    onDismiss()
                }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}
