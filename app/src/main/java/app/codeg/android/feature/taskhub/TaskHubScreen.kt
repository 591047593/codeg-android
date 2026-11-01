package app.codeg.android.feature.taskhub

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.DEFERRABLE_STATUSES
import app.codeg.android.core.data.SEGMENT_ORDER
import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.data.TaskSegment
import app.codeg.android.core.data.TaskStatusTone
import app.codeg.android.core.data.isEffectivelyDeferred
import app.codeg.android.core.data.taskStatusUi
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.theme.CodegTheme
import java.time.Instant

/**
 * The cross-device work-task board (ui重构 阶段2+3): global device scope (the
 * bar above — this page renders no second device row), a compact toolbar
 * (folder / search / filter), the four web-parity segments with counts, and
 * vertical task cards with ONE primary action each. Everything else lives in
 * the detail sheet. Server statuses are shown via [taskStatusUi]; unknown
 * statuses get a neutral section and no state actions (never "done").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHubScreen(viewModel: TaskHubViewModel = hiltViewModel(key = "taskhub")) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val folderOptions by viewModel.folderOptions.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors

    var segmentName by rememberSaveable { mutableStateOf(TaskSegment.TODO.name) }
    val segment = runCatching { TaskSegment.valueOf(segmentName) }.getOrDefault(TaskSegment.TODO)
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var folderSheet by remember { mutableStateOf(false) }
    // The detail sheet holds a KEY, not a row snapshot: the 25s poll keeps
    // mutating the board, and a frozen entry would keep showing stale status
    // and stale actions (e.g. merge buttons on a task someone else already
    // merged). Resolve live from the whole board each recomposition.
    var detailKey by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val detailEntry = detailKey?.let { (sid, tid) ->
        (board.segments.values.flatten() + board.unknown)
            .firstOrNull { it.serverId == sid && it.task.id == tid }
    }
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

    // The 25s poll runs only while the board is actually on screen: entering
    // this composable arms it, leaving the Activity tab (or switching back to
    // the feed page) disposes it and stops the radio. The VM survives tab
    // switches, so without this the poll leaked forever after one visit.
    DisposableEffect(viewModel) {
        viewModel.setActive(true)
        onDispose { viewModel.setActive(false) }
    }

    PullToRefreshBox(
        isRefreshing = ui.deviceStates.any { it.status == DeviceLoadStatus.LOADING } && ui.tasks.isEmpty(),
        onRefresh = { viewModel.refresh(); viewModel.refreshFolders() },
        modifier = Modifier.fillMaxSize(),
    ) {
        val listState = rememberLazyListState()
        // On a long board the failure banner at the top is off-screen when
        // the user acts on a bottom card — pull it into view.
        LaunchedEffect(ui.actionError) {
            if (ui.actionError != null) runCatching { listState.animateScrollToItem(0) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "toolbar") {
                ToolbarRow(
                    folderFilter = ui.folderFilter,
                    searchOpen = searchOpen,
                    showCanceled = ui.showCanceled,
                    showArchived = ui.showArchived,
                    onToggleSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen) viewModel.setSearchQuery("")
                    },
                    onFolderClick = { folderSheet = true },
                    onShowCanceled = viewModel::setShowCanceled,
                    onShowArchived = viewModel::setShowArchived,
                )
            }
            if (searchOpen) {
                item(key = "search") {
                    OutlinedTextField(
                        value = ui.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        placeholder = { Text(stringResource(R.string.task_search_hint)) },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { viewModel.setSearchQuery(""); searchOpen = false }) {
                                Icon(Icons.Rounded.Close, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
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
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { viewModel.clearActionError() },
                    )
                }
            }
            // Non-error outcome note (e.g. merge queued behind the folder's
            // serial merge slot): tap to dismiss, same lifecycle as actionError.
            ui.actionNote?.let { note ->
                item(key = "action-note") {
                    Text(
                        text = note,
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
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
            item(key = "segments") {
                SegmentBar(
                    counts = board.counts,
                    selected = segment,
                    onSelect = { segmentName = it.name },
                )
            }
            val rows = board.segments[segment].orEmpty()
            val unknownHere = segment == TaskSegment.ATTENTION
            val isEmpty = ui.hasLoaded && rows.isEmpty() && (!unknownHere || board.unknown.isEmpty())
            if (isEmpty) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(
                            if (ui.deviceStates.isEmpty()) R.string.task_empty_no_devices else R.string.task_empty_board,
                        ),
                        message = stringResource(R.string.task_empty_hint),
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            }
            items(
                rows,
                key = { "${it.serverId}-${it.task.id}" },
                contentType = { "task" },
            ) { entry ->
                TaskCard(
                    entry = entry,
                    folderName = ui.folderNames[entry.serverId]?.get(entry.task.folderId),
                    busy = ui.busyKey != null,
                    spinning = ui.busyKey == "${entry.serverId}:${entry.task.id}",
                    onOpenDetail = { detailKey = entry.serverId to entry.task.id },
                    onAction = { confirm = it },
                )
            }
            if (unknownHere && board.unknown.isNotEmpty()) {
                item(key = "unknown-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.task_unknown_section),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary,
                        )
                        Text("${board.unknown.size}", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    }
                }
                items(
                    board.unknown,
                    key = { "u-${it.serverId}-${it.task.id}" },
                    contentType = { "task" },
                ) { entry ->
                    TaskCard(
                        entry = entry,
                        folderName = ui.folderNames[entry.serverId]?.get(entry.task.folderId),
                        busy = ui.busyKey != null,
                        spinning = false,
                        onOpenDetail = { detailKey = entry.serverId to entry.task.id },
                        onAction = { confirm = it },
                    )
                }
            }
        }
    }

    if (folderSheet) {
        ModalBottomSheet(onDismissRequest = { folderSheet = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.task_folder_picker),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                FolderOptionRow(
                    label = stringResource(R.string.task_filter_folder),
                    selected = ui.folderFilter == null,
                ) { viewModel.setFolderFilter(null); folderSheet = false }
                folderOptions.forEach { name ->
                    FolderOptionRow(
                        label = name,
                        selected = ui.folderFilter == name,
                    ) { viewModel.setFolderFilter(name); folderSheet = false }
                }
            }
        }
    }

    detailEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { detailKey = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            TaskDetailSheet(
                entry = entry,
                folderName = ui.folderNames[entry.serverId]?.get(entry.task.folderId),
                detail = ui.details[entry.serverId]?.get(entry.task.id),
                busy = ui.busyKey != null,
                spinning = ui.busyKey == "${entry.serverId}:${entry.task.id}",
                onAction = { confirm = it },
                onOpen = { viewModel.loadDetail(entry.serverId, entry.task.id) },
                onLoadDiff = { viewModel.loadDetail(entry.serverId, entry.task.id, withDiff = true) },
                onDismiss = { detailKey = null },
            )
        }
    }

    ConfirmDialogs(confirm, ui.busyKey != null, onRun = { action ->
        val id = action.entry.task.id
        val server = action.entry.serverId
        when (action) {
            is ConfirmAction.START -> viewModel.start(server, id)
            is ConfirmAction.CANCEL -> viewModel.cancel(server, id)
            is ConfirmAction.RETRY -> viewModel.retry(server, id)
            is ConfirmAction.REQUEUE -> viewModel.requeue(server, id)
            is ConfirmAction.MERGE -> viewModel.merge(server, id)
            is ConfirmAction.COMPLETE -> viewModel.complete(server, id)
            is ConfirmAction.RETURN -> viewModel.returnTask(server, id, action.feedback)
            is ConfirmAction.DELIVER_PR -> viewModel.deliverPr(server, id)
            is ConfirmAction.ARCHIVE -> viewModel.setArchived(server, id, true)
            is ConfirmAction.UNARCHIVE -> viewModel.setArchived(server, id, false)
            is ConfirmAction.DEFER -> viewModel.setDeferred(server, id, true)
            is ConfirmAction.UNDEFER -> viewModel.setDeferred(server, id, false)
            is ConfirmAction.DELETE -> viewModel.deleteTask(server, id)
        }
    }, onDismiss = { confirm = null })
}

/** Toolbar: folder picker chip left, search + filter icons right (任务书 §4.1). */
@Composable
private fun ToolbarRow(
    folderFilter: String?,
    searchOpen: Boolean,
    showCanceled: Boolean,
    showArchived: Boolean,
    onToggleSearch: () -> Unit,
    onFolderClick: () -> Unit,
    onShowCanceled: (Boolean) -> Unit,
    onShowArchived: (Boolean) -> Unit,
) {
    val colors = CodegTheme.colors
    var filterMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onFolderClick)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .heightIn(min = 36.dp),
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                folderFilter ?: stringResource(R.string.task_filter_folder),
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleSearch, modifier = Modifier.size(40.dp)) {
            Icon(
                if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = null,
                tint = if (searchOpen) colors.accent else colors.textSecondary,
            )
        }
        Box {
            IconButton(onClick = { filterMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.FilterList,
                    contentDescription = null,
                    // Accent only when the user drifted from the default view
                // (canceled shown, archived hidden) — a "filters active" hint.
                tint = if (!showCanceled || showArchived) colors.accent else colors.textSecondary,
                )
            }
            DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = showCanceled, onCheckedChange = null)
                            Text(stringResource(R.string.task_filter_show_canceled))
                        }
                    },
                    onClick = { onShowCanceled(!showCanceled) },
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = showArchived, onCheckedChange = null)
                            Text(stringResource(R.string.task_filter_show_archived))
                        }
                    },
                    onClick = { onShowArchived(!showArchived) },
                )
            }
        }
    }
}

@Composable
private fun FolderOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .heightIn(min = 44.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.accent else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text("✓", color = colors.accent, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Four web-parity segments with live counts (待办 | 进行中 | 等你处理 | 已完成). */
@Composable
private fun SegmentBar(
    counts: Map<TaskSegment, Int>,
    selected: TaskSegment,
    onSelect: (TaskSegment) -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SEGMENT_ORDER.forEach { seg ->
            val isSel = seg == selected
            val count = counts[seg] ?: 0
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) colors.accentDim else colors.bgElevated)
                    .border(
                        1.dp,
                        if (isSel) colors.accent.copy(alpha = 0.5f) else Color.Transparent,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(seg) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    stringResource(seg.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) colors.accent else colors.textSecondary,
                    maxLines = 1,
                )
                Text(
                    "$count",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSel) colors.textPrimary else colors.textSecondary,
                )
            }
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
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = CodegTheme.colors.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.task_retry_failed))
        }
    }
}

/** Colored status pill; the color IS the tone mapping, the label the raw truth. */
@Composable
private fun StatusTag(task: app.codeg.android.core.model.WorkTaskInfo) {
    val status = taskStatusUi(task)
    val colors = CodegTheme.colors
    val color = when (status.tone) {
        TaskStatusTone.NEUTRAL -> colors.textSecondary
        TaskStatusTone.RUN -> colors.success
        TaskStatusTone.WARN -> colors.warning
        TaskStatusTone.INFO -> colors.accent
        TaskStatusTone.DANGER -> colors.danger
        TaskStatusTone.DONE -> colors.success
    }
    Tag(status.raw ?: stringResource(status.labelRes), color)
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * Compact card (任务书 §4.3): project + status on top, title, snippet, meta row
 * with ONE primary action, 查看详情 affordance at the bottom right.
 */
@Composable
private fun TaskCard(
    entry: TaskBoardEntry,
    folderName: String?,
    busy: Boolean,
    spinning: Boolean,
    onOpenDetail: () -> Unit,
    onAction: (ConfirmAction) -> Unit,
) {
    val task = entry.task
    val colors = CodegTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onOpenDetail),
        colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    folderName
                        ?: task.folderId?.let { stringResource(R.string.task_folder_fallback, it) }
                        ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (spinning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                StatusTag(task)
            }
            Text(
                task.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            task.resultSummary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Tag(entry.serverName, colors.textSecondary)
                task.agentType?.let { Tag(it, colors.textSecondary) }
                if (task.deferred) Tag(stringResource(R.string.task_deferred_tag), colors.warning)
                relativeLabel(task.updatedAt ?: task.createdAt)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                }
                Spacer(Modifier.weight(1f))
                primaryAction(task.status)?.let { action ->
                    // Always composed, only disabled: removing the button on any
                    // in-flight action made every card's row height jump.
                    MiniButton(
                        label = stringResource(action.labelRes),
                        danger = action.danger,
                        enabled = !busy,
                    ) { onAction(action.toConfirm(entry)) }
                }
            }
            Text(
                stringResource(R.string.task_view_detail) + " ›",
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable(onClick = onOpenDetail),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

@Composable
private fun MiniButton(label: String, danger: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val tint = if (danger) colors.danger else colors.accent
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) tint else tint.copy(alpha = 0.4f),
        )
    }
}

private class PrimaryAction(val labelRes: Int, val danger: Boolean, val kind: Kind) {
    enum class Kind { START, CANCEL, RETRY, REQUEUE, MERGE }
    fun toConfirm(entry: TaskBoardEntry): ConfirmAction = when (kind) {
        Kind.START -> ConfirmAction.START(entry)
        Kind.CANCEL -> ConfirmAction.CANCEL(entry)
        Kind.RETRY -> ConfirmAction.RETRY(entry)
        Kind.REQUEUE -> ConfirmAction.REQUEUE(entry)
        Kind.MERGE -> ConfirmAction.MERGE(entry)
    }
}

/** The ONE primary action per status; everything else lives in the detail sheet. */
private fun primaryAction(status: String): PrimaryAction? = when (status) {
    "todo", "queued" -> PrimaryAction(R.string.task_action_start, false, PrimaryAction.Kind.START)
    "preparing", "running", "awaiting_input" -> PrimaryAction(R.string.task_action_cancel, true, PrimaryAction.Kind.CANCEL)
    "review" -> PrimaryAction(R.string.task_action_merge, false, PrimaryAction.Kind.MERGE)
    "failed" -> PrimaryAction(R.string.task_action_retry, false, PrimaryAction.Kind.RETRY)
    "canceled", "cancelled" -> PrimaryAction(R.string.task_action_requeue, false, PrimaryAction.Kind.REQUEUE)
    else -> null // merging/done/unknown: no state action on the card
}

// region Detail sheet

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDetailSheet(
    entry: TaskBoardEntry,
    folderName: String?,
    detail: TaskHubViewModel.TaskDetail?,
    busy: Boolean,
    spinning: Boolean,
    onAction: (ConfirmAction) -> Unit,
    onOpen: () -> Unit,
    onLoadDiff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val task = entry.task
    val colors = CodegTheme.colors
    LaunchedEffect(entry.serverId, task.id) { onOpen() }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (spinning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            StatusTag(task)
            if (task.deferred) {
                Spacer(Modifier.size(6.dp))
                Tag(stringResource(R.string.task_deferred_tag), colors.warning)
            }
        }
        task.resultSummary?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        InfoGrid(entry, folderName)
        // All native actions, confirm-gated (任务书 §4.4 — 以实际 API 能力为准).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
        ) {
            when (task.status) {
                "todo", "queued" -> {
                    SheetAction(R.string.task_action_start, busy) { onAction(ConfirmAction.START(entry)) }
                    SheetAction(R.string.task_action_cancel, busy, danger = true) { onAction(ConfirmAction.CANCEL(entry)) }
                }
                "preparing", "running", "awaiting_input" ->
                    SheetAction(R.string.task_action_cancel, busy, danger = true) { onAction(ConfirmAction.CANCEL(entry)) }
                "review" -> {
                    SheetAction(R.string.task_action_merge, busy) { onAction(ConfirmAction.MERGE(entry)) }
                    SheetAction(R.string.task_action_complete, busy) { onAction(ConfirmAction.COMPLETE(entry)) }
                    SheetAction(R.string.task_action_return, busy) { onAction(ConfirmAction.RETURN(entry)) }
                    SheetAction(R.string.task_action_deliver_pr, busy) { onAction(ConfirmAction.DELIVER_PR(entry)) }
                }
                "failed" -> {
                    SheetAction(R.string.task_action_retry, busy) { onAction(ConfirmAction.RETRY(entry)) }
                    SheetAction(R.string.task_action_requeue, busy) { onAction(ConfirmAction.REQUEUE(entry)) }
                }
                "canceled", "cancelled" ->
                    SheetAction(R.string.task_action_requeue, busy) { onAction(ConfirmAction.REQUEUE(entry)) }
            }
            when {
                // 搁置 only where the server accepts it (set_deferred filters to
                // DEFERRABLE = review/failed elsewhere — the button would always
                // no-op with a 4xx); unshelve shows whenever the flag is set.
                isEffectivelyDeferred(task) ->
                    SheetAction(R.string.task_action_undefer, busy) { onAction(ConfirmAction.UNDEFER(entry)) }
                task.status in DEFERRABLE_STATUSES ->
                    SheetAction(R.string.task_action_defer, busy) { onAction(ConfirmAction.DEFER(entry)) }
            }
            when {
                // status-only archived rows (old server builds) get an exit too:
                // unarchive clears archived_at; the row then shows by its status.
                task.archivedAt != null || task.status == "archived" ->
                    SheetAction(R.string.task_action_unarchive, busy) { onAction(ConfirmAction.UNARCHIVE(entry)) }
                task.status == "done" || task.status == "canceled" || task.status == "cancelled" ->
                    SheetAction(R.string.task_action_archive, busy) { onAction(ConfirmAction.ARCHIVE(entry)) }
            }
            SheetAction(R.string.task_action_delete, busy, danger = true) { onAction(ConfirmAction.DELETE(entry)) }
        }
        TaskDetailBody(detail, onLoadDiff, onRetry = onOpen)
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun InfoGrid(entry: TaskBoardEntry, folderName: String?) {
    val task = entry.task
    val colors = CodegTheme.colors
    val rows = listOf(
        stringResource(R.string.task_detail_device) to entry.serverName,
        stringResource(R.string.task_detail_project) to (folderName ?: "#${task.folderId}"),
        stringResource(R.string.task_detail_agent) to (task.agentType ?: "—"),
        stringResource(R.string.task_detail_branch) to (task.workBranch ?: task.baseBranch ?: "—"),
        stringResource(R.string.task_detail_created) to (relativeLabel(task.createdAt) ?: "—"),
        stringResource(R.string.task_detail_updated) to (relativeLabel(task.updatedAt) ?: "—"),
        task.scheduledAt?.let { stringResource(R.string.task_detail_scheduled) to (relativeLabel(it) ?: it) },
        stringResource(R.string.task_detail_task_id) to "#${task.id}",
    )
    Column(Modifier.padding(top = 10.dp)) {
        rows.filterNotNull().forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(end = 16.dp),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SheetAction(labelRes: Int, busy: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    MiniButton(stringResource(labelRes), danger, enabled = !busy, onClick = onClick)
}

// endregion

@Composable
private fun TaskDetailBody(
    detail: TaskHubViewModel.TaskDetail?,
    onLoadDiff: () -> Unit,
    onRetry: () -> Unit = {},
) {
    val colors = CodegTheme.colors
    if (detail == null) return
    // The load failed: say so and offer the retry, instead of a blank panel
    // that looks like "no events / no files".
    detail.loadError?.let { err ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Text(
                err,
                color = colors.danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.task_action_retry), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
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
    Column(Modifier.padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = CodegTheme.colors.textPrimary)
        content()
    }
}

// region Confirmations — every write goes through one of these (任务书 §4.4).

private sealed interface ConfirmAction {
    val entry: TaskBoardEntry

    data class CANCEL(override val entry: TaskBoardEntry) : ConfirmAction
    data class MERGE(override val entry: TaskBoardEntry) : ConfirmAction
    data class COMPLETE(override val entry: TaskBoardEntry) : ConfirmAction
    data class START(override val entry: TaskBoardEntry) : ConfirmAction
    data class RETRY(override val entry: TaskBoardEntry) : ConfirmAction
    data class REQUEUE(override val entry: TaskBoardEntry) : ConfirmAction
    data class RETURN(override val entry: TaskBoardEntry, val feedback: String = "") : ConfirmAction
    data class DELIVER_PR(override val entry: TaskBoardEntry) : ConfirmAction
    data class ARCHIVE(override val entry: TaskBoardEntry) : ConfirmAction
    data class UNARCHIVE(override val entry: TaskBoardEntry) : ConfirmAction
    data class DEFER(override val entry: TaskBoardEntry) : ConfirmAction
    data class UNDEFER(override val entry: TaskBoardEntry) : ConfirmAction
    data class DELETE(override val entry: TaskBoardEntry) : ConfirmAction
}

@Composable
private fun ConfirmDialogs(
    confirm: ConfirmAction?,
    busy: Boolean,
    onRun: (ConfirmAction) -> Unit,
    onDismiss: () -> Unit,
) {
    confirm?.let { action ->
        // 退回 needs a feedback text (server ReturnParams requires it) — own dialog.
        if (action is ConfirmAction.RETURN) {
            ReturnDialog(action, busy, onRun, onDismiss)
            return
        }
        val (titleRes, msgRes, danger) = when (action) {
            is ConfirmAction.CANCEL -> Triple(R.string.task_confirm_cancel_title, R.string.task_confirm_cancel_msg, true)
            is ConfirmAction.MERGE -> Triple(R.string.task_confirm_merge_title, R.string.task_confirm_merge_msg, true)
            is ConfirmAction.COMPLETE -> Triple(R.string.task_confirm_complete_title, R.string.task_confirm_complete_msg, false)
            is ConfirmAction.START -> Triple(R.string.task_confirm_start_title, R.string.task_confirm_start_msg, false)
            is ConfirmAction.RETRY -> Triple(R.string.task_confirm_retry_title, R.string.task_confirm_retry_msg, false)
            is ConfirmAction.REQUEUE -> Triple(R.string.task_confirm_requeue_title, R.string.task_confirm_requeue_msg, false)
            is ConfirmAction.RETURN -> Triple(0, 0, false) // handled above
            is ConfirmAction.DELIVER_PR -> Triple(R.string.task_confirm_deliver_title, R.string.task_confirm_deliver_msg, false)
            is ConfirmAction.ARCHIVE -> Triple(R.string.task_confirm_archive_title, R.string.task_confirm_archive_msg, false)
            is ConfirmAction.UNARCHIVE -> Triple(R.string.task_confirm_unarchive_title, R.string.task_confirm_unarchive_msg, false)
            is ConfirmAction.DEFER -> Triple(R.string.task_confirm_defer_title, R.string.task_confirm_defer_msg, false)
            is ConfirmAction.UNDEFER -> Triple(R.string.task_confirm_undefer_title, R.string.task_confirm_undefer_msg, false)
            is ConfirmAction.DELETE -> Triple(R.string.task_confirm_delete_title, R.string.task_confirm_delete_msg, true)
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(msgRes)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { onRun(action); onDismiss() }) {
                    Text(
                        stringResource(android.R.string.ok),
                        color = if (danger) CodegTheme.colors.danger else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ReturnDialog(
    action: ConfirmAction.RETURN,
    busy: Boolean,
    onRun: (ConfirmAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var feedback by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_confirm_return_title)) },
        text = {
            Column {
                Text(stringResource(R.string.task_confirm_return_msg))
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    placeholder = { Text(stringResource(R.string.task_return_hint)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && feedback.isNotBlank(),
                onClick = { onRun(action.copy(feedback = feedback.trim())); onDismiss() },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

// endregion

/** RFC3339 → relative label; unparsable strings pass through untouched. */
private fun relativeLabel(iso: String?): String? {
    val raw = iso ?: return null
    // Offset form (…+08:00) throws in Instant.parse — fall back to the
    // tolerant parser the wire layer already ships.
    val instant = runCatching { Instant.parse(raw) }
        .recoverCatching { app.codeg.android.core.model.wire.Rfc3339.parse(raw) }
        .getOrNull() ?: return raw
    return DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
