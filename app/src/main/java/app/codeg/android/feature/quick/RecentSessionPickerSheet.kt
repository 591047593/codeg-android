package app.codeg.android.feature.quick

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * 快捷会话交接 first step (批v150 §1.4): a REAL aggregate list of the most
 * recently updated sessions across all visible devices ([RecentSessionsViewModel]
 * fetches each device's conversation list with the aggregate timeout family —
 * no fake data, no read-mark-only ids). Picking one hands off to the owning
 * device and auto-opens the HandoffSheet there via [HandoffAutoOpenBus].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentSessionPickerSheet(
    onPick: (serverId: String, conversationId: Int) -> Unit,
    onDismiss: () -> Unit,
    viewModel: RecentSessionsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock())
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.recent_pick_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            when {
                ui.loading -> Column(Modifier.heightIn(min = 160.dp)) {
                    LoadingView(stringResource(R.string.common_loading))
                }
                ui.failed && ui.sessions.isEmpty() -> Text(
                    stringResource(R.string.recent_pick_failed),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.retry() }
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                )
                ui.sessions.isEmpty() -> Text(
                    stringResource(R.string.recent_pick_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ui.sessions, key = { it.key }) { row ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(row.serverId, row.conversationId) }
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    row.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    row.timeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textTertiary,
                                )
                            }
                            Text(
                                row.serverName,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.accent,
                            )
                        }
                        HorizontalDivider(color = colors.hairline)
                    }
                }
            }
        }
    }
}
