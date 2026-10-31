package app.codeg.android.feature.taskhub

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.data.TaskColumn
import app.codeg.android.core.data.activeColumns
import app.codeg.android.core.data.taskColumnOf
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.theme.CodegTheme
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The cross-device work-task board (batch v139): one screen, every participating
 * device, the server's own statuses grouped into PRD §8.2's columns. Rendered
 * inside the Activity tab's "任务" toggle — see [TaskHubViewModel] for the
 * fetch/projection split.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHubScreen(viewModel: TaskHubViewModel = hiltViewModel(key = "taskhub")) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

    PullToRefreshBox(
        isRefreshing = ui.deviceStates.any { it.status == DeviceLoadStatus.LOADING } && ui.tasks.isEmpty(),
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "filters") {
                DeviceFilterRow(
                    ui = ui,
                    onSelect = viewModel::setDeviceFilter,
                    showArchived = ui.showArchived,
                    onToggleArchived = viewModel::setShowArchived,
                )
            }
            if (ui.failedDevices > 0) {
                item(key = "banner") {
                    FailedBanner(
                        ui = ui,
                        onRetry = viewModel::retryFailedDevices,
                    )
                }
            }
            ui.actionError?.let { error ->
                item(key = "action-error") {
                    Text(
                        text = stringResource(R.string.task_action_failed) + " · " + error,
                        color = colors.danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { viewModel.clearActionError() },
                    )
                }
            }
            if (ui.archivedHidden > 0) {
                item(key = "archived-note") {
                    Text(
                        text = stringResource(R.string.task_archived_hidden, ui.archivedHidden),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
            val columns = activeColumns(board)
            if (columns.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(R.string.task_empty_board),
                        message = "",
                        modifier = Modifier.padding(top = 64.dp),
                    )
                }
            }
            columns.forEach { column ->
                item(key = "col-${column.name}") {
                    ColumnHeader(column, board[column].orEmpty().size)
                }
                items(
                    board[column].orEmpty(),
                    key = { "${it.serverId}-${it.task.id}" },
                    contentType = { "task" },
                ) { entry ->
                    TaskCard(
                        entry = entry,
                        detail = ui.details[entry.serverId]?.get(entry.task.id),
                        busy = ui.busyKey == "${entry.serverId}:${entry.task.id}",
                        onExpand = { viewModel.loadDetail(entry.serverId, entry.task.id) },
                        onLoadDiff = { viewModel.loadDetail(entry.serverId, entry.task.id, withDiff = true) },
                        onStart = { viewModel.start(entry.serverId, entry.task.id) },
                        onCancel = { confirm = ConfirmAction.CANCEL(entry) },
                        onRetry = { viewModel.retry(entry.serverId, entry.task.id) },
                        onRequeue = { viewModel.requeue(entry.serverId, entry.task.id) },
                        onMerge = { confirm = ConfirmAction.MERGE(entry) },
                        onComplete = { confirm = ConfirmAction.COMPLETE(entry) },
                    )
                }
            }
        }
    }

    confirm?.let { action ->
        val (titleRes, msgRes, run) = when (action) {
            is ConfirmAction.CANCEL -> Triple(
                R.string.task_confirm_cancel_title,
                R.string.task_confirm_cancel_msg,
                { viewModel.cancel(action.entry.serverId, action.entry.task.id) },
            )
            is ConfirmAction.MERGE -> Triple(
                R.string.task_confirm_merge_title,
                R.string.task_confirm_merge_msg,
                { viewModel.merge(action.entry.serverId, action.entry.task.id) },
            )
            is ConfirmAction.COMPLETE -> Triple(
                R.string.task_confirm_complete_title,
                R.string.task_confirm_complete_msg,
                { viewModel.complete(action.entry.serverId, action.entry.task.id) },
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(msgRes)) },
            confirmButton = {
                TextButton(onClick = { run(); confirm = null }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private sealed interface ConfirmAction {
    val entry: TaskBoardEntry

    data class CANCEL(override val entry: TaskBoardEntry) : ConfirmAction
    data class MERGE(override val entry: TaskBoardEntry) : ConfirmAction
    data class COMPLETE(override val entry: TaskBoardEntry) : ConfirmAction
}

@Composable
private fun DeviceFilterRow(
    ui: TaskHubViewModel.UiState,
    onSelect: (String?) -> Unit,
    showArchived: Boolean,
    onToggleArchived: (Boolean) -> Unit,
) {
    val devices = ui.deviceStates
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = ui.deviceFilter == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.task_filter_all)) },
        )
        devices.forEach { device ->
            FilterChip(
                selected = ui.deviceFilter == device.serverId,
                onClick = { onSelect(device.serverId) },
                label = { Text(device.name) },
                leadingIcon = {
                    when (device.status) {
                        DeviceLoadStatus.LOADING -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        DeviceLoadStatus.FAILED -> Icon(
                            Icons.Rounded.CloudOff, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = CodegTheme.colors.danger,
                        )
                        DeviceLoadStatus.OK -> null
                    }
                },
            )
        }
        Spacer(Modifier.width(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.task_show_archived),
                style = MaterialTheme.typography.labelSmall,
                color = CodegTheme.colors.textSecondary,
            )
            Switch(checked = showArchived, onCheckedChange = onToggleArchived, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
private fun FailedBanner(ui: TaskHubViewModel.UiState, onRetry: () -> Unit) {
    val names = ui.deviceStates
        .filter { it.status == DeviceLoadStatus.FAILED }
        .joinToString("、") { it.name }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(CodegTheme.colors.danger.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(
            stringResource(R.string.task_banner_failed, names),
            style = MaterialTheme.typography.bodySmall,
            color = CodegTheme.colors.danger,
        )
        ui.deviceStates.firstOrNull { it.status == DeviceLoadStatus.FAILED }?.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = CodegTheme.colors.textSecondary)
        }
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.task_retry_failed))
        }
    }
}

@Composable
private fun ColumnHeader(column: TaskColumn, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(columnLabel(column)),
            style = MaterialTheme.typography.titleSmall,
            color = CodegTheme.colors.textPrimary,
        )
        Text("$count", style = MaterialTheme.typography.labelMedium, color = CodegTheme.colors.textSecondary)
    }
}

private fun columnLabel(column: TaskColumn): Int = when (column) {
    TaskColumn.PENDING -> R.string.task_column_pending
    TaskColumn.RUNNING -> R.string.task_column_running
    TaskColumn.ATTENTION -> R.string.task_column_attention
    TaskColumn.DONE -> R.string.task_column_done
    TaskColumn.ENDED -> R.string.task_column_ended
    TaskColumn.UNKNOWN -> R.string.task_column_unknown
}

@Composable
private fun TaskCard(
    entry: TaskBoardEntry,
    detail: TaskHubViewModel.TaskDetail?,
    busy: Boolean,
    onExpand: () -> Unit,
    onLoadDiff: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRequeue: () -> Unit,
    onMerge: () -> Unit,
    onComplete: () -> Unit,
) {
    val task = entry.task
    val colors = CodegTheme.colors
    var expanded by rememberSaveable(key = "${entry.serverId}-${task.id}-expanded") { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (expanded && detail == null) onExpand() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Tag(entry.serverName)
                task.agentType?.let { Tag(it) }
                if (task.status == "merging") Tag(stringResource(R.string.task_merging_tag))
                if (taskColumnOf(task.status) == TaskColumn.UNKNOWN) {
                    Tag(stringResource(R.string.task_unknown_status, task.status), danger = true)
                }
                task.scheduledAt?.let { Tag(stringResource(R.string.task_scheduled_at, relativeLabel(it))) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                task.additions?.let { Text("+$it", color = colors.accent, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                task.deletions?.let { Text("-$it", color = colors.danger, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                relativeLine(task.updatedAt)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
            task.resultSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (expanded) {
                TaskActions(task, onLoadDiff, onStart, onCancel, onRetry, onRequeue, onMerge, onComplete)
                TaskDetailBody(detail, onLoadDiff)
            }
        }
    }
}

@Composable
private fun Tag(text: String, danger: Boolean = false) {
    Box(
        Modifier
            .background(
                (if (danger) CodegTheme.colors.danger else CodegTheme.colors.textSecondary).copy(alpha = 0.14f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (danger) CodegTheme.colors.danger else CodegTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TaskActions(
    task: app.codeg.android.core.model.WorkTaskInfo,
    onLoadDiff: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRequeue: () -> Unit,
    onMerge: () -> Unit,
    onComplete: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
    ) {
        when (task.status) {
            "todo", "queued" -> {
                ActionButton(R.string.task_action_start, onStart)
                ActionButton(R.string.task_action_cancel, onCancel, danger = true)
            }
            "preparing", "running", "awaiting_input" -> ActionButton(R.string.task_action_cancel, onCancel, danger = true)
            "review" -> {
                ActionButton(R.string.task_action_merge, onMerge)
                ActionButton(R.string.task_action_complete, onComplete)
                ActionButton(R.string.task_action_cancel, onCancel, danger = true)
            }
            "failed" -> {
                ActionButton(R.string.task_action_retry, onRetry)
                ActionButton(R.string.task_action_requeue, onRequeue)
            }
            "canceled" -> ActionButton(R.string.task_action_requeue, onRequeue)
        }
    }
}

@Composable
private fun ActionButton(labelRes: Int, onClick: () -> Unit, danger: Boolean = false) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = if (danger) CodegTheme.colors.danger else CodegTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun TaskDetailBody(detail: TaskHubViewModel.TaskDetail?, onLoadDiff: () -> Unit) {
    val colors = CodegTheme.colors
    if (detail == null) return
    detail.changedFiles?.takeIf { it.isNotEmpty() }?.let { files ->
        DetailSection(stringResource(R.string.task_detail_files)) {
            files.take(20).forEach { file ->
                Text(
                    file.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    detail.events?.takeIf { it.isNotEmpty() }?.let { events ->
        DetailSection(stringResource(R.string.task_detail_events)) {
            events.take(10).forEach { event ->
                Text(
                    listOfNotNull(
                        event.createdAt?.let { relativeLabel(it) },
                        event.actor,
                        event.kind,
                        event.summary,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                )
            }
        }
    }
    DetailSection(stringResource(R.string.task_detail_diff)) {
        when {
            detail.diff != null -> {
                if (detail.diff.isBlank()) {
                    Text(stringResource(R.string.task_diff_empty), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                } else {
                    Text(
                        detail.diff.take(8_000),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            detail.diffLoading -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            detail.diffError != null -> Text(detail.diffError, color = colors.danger, style = MaterialTheme.typography.labelSmall)
            else -> TextButton(onClick = onLoadDiff, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.task_load_diff), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = CodegTheme.colors.textPrimary)
        content()
    }
}

/** RFC3339 → relative label; unparsable strings pass through untouched. */
private fun relativeLine(iso: String?): String? = iso?.let { "· " + relativeLabel(it) }

private fun relativeLabel(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    return DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
