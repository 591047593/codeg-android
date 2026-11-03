package app.codeg.android.feature.taskhub

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.designsystem.component.AgentChipRow
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FullscreenTextEditorDialog
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.feature.dispatch.DispatchForm
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
fun TaskHubScreen(
    viewModel: TaskHubViewModel = hiltViewModel(key = "taskhub"),
    /** 批v155: jump to the task's attached session (查看会话) — the shell does
     *  the owning-device handoff dance, same as the inbox tab's jump. */
    onOpenSession: (serverId: String, conversationId: Int) -> Unit = { _, _ -> },
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val folderOptions by viewModel.folderOptions.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors

    var segmentName by rememberSaveable { mutableStateOf(TaskSegment.TODO.name) }
    val segment = runCatching { TaskSegment.valueOf(segmentName) }.getOrDefault(TaskSegment.TODO)
    // 批v152: the workbench stat cards / 需要处理 preview ask for a SPECIFIC segment.
    // Consumed once on entry (the request is read-then-cleared in the repository),
    // so a later plain tab return keeps whatever segment the user picked himself.
    LaunchedEffect(viewModel) {
        viewModel.consumeSegmentRequest()?.let { segmentName = it.name }
    }
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
    // 批v155: the 新建任务 sheet (host C form) + its screen-level result dialog
    // (the sheet unmounts on confirm — the dialog must survive it, B-7 rule).
    var showNewTask by remember { mutableStateOf(false) }
    var newTaskResult by remember { mutableStateOf<DispatchRef?>(null) }
    // 编辑任务 opens a sheet (not an AlertDialog) — intercepted before ConfirmDialogs.
    var editEntry by remember { mutableStateOf<TaskBoardEntry?>(null) }

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
                    onNewTask = { showNewTask = true },
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
                    onOpenSession = onOpenSession,
                    onAction = { action ->
                        // 编辑 opens a sheet, not a confirm dialog.
                        if (action is ConfirmAction.EDIT) editEntry = action.entry else confirm = action
                    },
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
                        onOpenSession = onOpenSession,
                        onAction = { action ->
                            if (action is ConfirmAction.EDIT) editEntry = action.entry else confirm = action
                        },
                    )
                }
            }
        }
    }

    if (folderSheet) {
        ModalBottomSheet(
            onDismissRequest = { folderSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = null,
        ) {
            Column(
                Modifier
                    .nestedScroll(rememberSheetDragLock())
                    .padding(top = 12.dp, bottom = 24.dp),
            ) {
                // dragHandle=null：标题条就是拖拽关闭区——撑到 48dp（第3轮扫尾修正）。
                Text(
                    stringResource(R.string.task_folder_picker),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
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
            dragHandle = null,
        ) {
            TaskDetailSheet(
                entry = entry,
                folderName = ui.folderNames[entry.serverId]?.get(entry.task.folderId),
                detail = ui.details[entry.serverId]?.get(entry.task.id),
                busy = ui.busyKey != null,
                spinning = ui.busyKey == "${entry.serverId}:${entry.task.id}",
                onAction = { action ->
                    if (action is ConfirmAction.EDIT) editEntry = action.entry else confirm = action
                },
                onOpenSession = onOpenSession,
                onOpen = { viewModel.loadDetail(entry.serverId, entry.task.id) },
                onLoadDiff = { viewModel.loadDetail(entry.serverId, entry.task.id, withDiff = true) },
                onDismiss = { detailKey = null },
            )
        }
    }

    // 批v155 新建任务: host C form in a task-locked sheet (方案 A — no branch,
    // no schedule; the task parks in 待办 with autoStart=false).
    if (showNewTask) {
        ModalBottomSheet(
            onDismissRequest = { showNewTask = false; viewModel.clearFormError() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = null,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .nestedScroll(rememberSheetDragLock())
                    .padding(top = 12.dp),
            ) {
                Text(
                    stringResource(R.string.task_new_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
                DispatchForm(
                    host = viewModel,
                    newTaskMode = true,
                    defaultSessionMode = false,
                    onResult = { ref ->
                        newTaskResult = ref
                        if (ref.state == DispatchState.CONFIRMED) showNewTask = false
                    },
                )
            }
        }
    }
    newTaskResult?.let { ref ->
        NewTaskResultDialog(
            ref = ref,
            onDismiss = { newTaskResult = null; viewModel.clearFormError() },
        )
    }

    // 批v155 编辑任务: title + content (+ agent), config rebuilt from the row's
    // rawConfig so the full-overwrite update never drops agent/mode/branch.
    editEntry?.let { entry ->
        EditTaskSheet(
            entry = entry,
            viewModel = viewModel,
            busy = ui.busyKey != null,
            onDone = { editEntry = null },
            onDismiss = { editEntry = null },
        )
    }

    ConfirmDialogs(confirm, ui.busyKey != null, onRun = { action ->
        val id = action.entry.task.id
        val server = action.entry.serverId
        when (action) {
            is ConfirmAction.START -> viewModel.start(server, id)
            is ConfirmAction.CANCEL -> viewModel.cancel(server, id, action.deleteWorktree, action.reason)
            is ConfirmAction.RETRY -> viewModel.retry(server, id, action.note)
            is ConfirmAction.REQUEUE -> viewModel.requeue(server, id, action.note)
            is ConfirmAction.MERGE -> viewModel.merge(server, id)
            is ConfirmAction.COMPLETE -> viewModel.complete(server, id)
            is ConfirmAction.RETURN -> viewModel.returnTask(server, id, action.feedback)
            is ConfirmAction.DELIVER_PR -> viewModel.deliverPr(server, id)
            is ConfirmAction.ARCHIVE -> viewModel.setArchived(server, id, true)
            is ConfirmAction.UNARCHIVE -> viewModel.setArchived(server, id, false)
            is ConfirmAction.DEFER -> viewModel.setDeferred(server, id, true)
            is ConfirmAction.UNDEFER -> viewModel.setDeferred(server, id, false)
            is ConfirmAction.DELETE -> viewModel.deleteTask(server, id)
            is ConfirmAction.SCHEDULE -> viewModel.schedule(server, id, action.scheduledAtIsoUtc)
            is ConfirmAction.EDIT -> Unit // handled by the edit sheet, never routed here
        }
    }, onDismiss = { confirm = null })
}

/** Toolbar: folder picker chip left, search + filter + 新建 icons right (任务书 §4.1). */
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
    onNewTask: () -> Unit,
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
        // 批v155: the board's own 新建任务 entry (web parity) — opens the
        // task-locked dispatch form sheet.
        IconButton(onClick = onNewTask, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.task_new_title),
                tint = colors.accent,
            )
        }
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
    // 批v147 视觉规范：加载失败只允许柔和文字行，严禁红底大卡片。
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.task_banner_failed, names),
                style = MaterialTheme.typography.bodySmall,
                color = CodegTheme.colors.danger,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.task_retry_failed), style = MaterialTheme.typography.labelMedium)
            }
        }
        ui.deviceStates.firstOrNull { it.status == DeviceLoadStatus.FAILED }?.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = CodegTheme.colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
 * with ONE primary action (plus 查看会话 when the task has a session — 批v155
 * web parity), 查看详情 affordance at the bottom right. Long-press opens the
 * per-state context menu (web task-actions matrix, server-honest subset).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(
    entry: TaskBoardEntry,
    folderName: String?,
    busy: Boolean,
    spinning: Boolean,
    onOpenDetail: () -> Unit,
    onOpenSession: (serverId: String, conversationId: Int) -> Unit,
    onAction: (ConfirmAction) -> Unit,
) {
    val task = entry.task
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onOpenDetail, onLongClick = { menuOpen = true }),
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
                // 批v155: 查看会话 — jump into the task's live session (continue
                // typing / steer / resend), exactly the web card's session button.
                // R3 P3-1: web shows it whenever a conversation exists (a retried
                // failed→queued task keeps its session); todo rows simply have
                // no conversationId yet, so no extra status filter.
                val convId = task.conversationId
                if (convId != null) {
                    MiniButton(
                        label = stringResource(R.string.task_open_session),
                        danger = false,
                        enabled = true,
                    ) { onOpenSession(entry.serverId, convId) }
                }
                primaryAction(task)?.let { action ->
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
            TaskContextMenu(
                entry = entry,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onOpenSession = onOpenSession,
                onAction = onAction,
            )
        }
    }
}

/**
 * 批v155: the long-press context menu — the web task-actions matrix projected
 * onto what this server build actually accepts (archive only on done/canceled,
 * schedule only on todo, edit only on todo/failed/canceled).
 */
@Composable
private fun TaskContextMenu(
    entry: TaskBoardEntry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenSession: (serverId: String, conversationId: Int) -> Unit,
    onAction: (ConfirmAction) -> Unit,
) {
    val task = entry.task
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // R3 P2-4: the menu used to ignore archived state — an archived done row
        // still offered 归档 (server CAS ArchivedAt.is_null → always 4xx) and had
        // no way out. Web parity: archived rows offer ONLY 取消归档.
        if (task.archivedAt != null || task.status == "archived") {
            MenuAction(R.string.task_action_unarchive) { onDismiss(); onAction(ConfirmAction.UNARCHIVE(entry)) }
            return@DropdownMenu
        }
        task.conversationId?.let { convId ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.task_open_session)) },
                onClick = {
                    onDismiss()
                    onOpenSession(entry.serverId, convId)
                },
            )
        }
        when (task.status) {
            // R1A: queued split from todo — the server schedules/edits ONLY
            // todo rows (set_schedule/update CAS on Status::Todo), and a task
            // can sit in queued for a long time when concurrency is maxed.
            // R3 P1-1: no START for queued either (claim CAS is todo-only).
            "todo" -> {
                MenuAction(R.string.task_action_start) { onDismiss(); onAction(ConfirmAction.START(entry)) }
                MenuAction(R.string.task_action_edit) { onDismiss(); onAction(ConfirmAction.EDIT(entry)) }
                MenuAction(R.string.task_action_schedule) { onDismiss(); onAction(ConfirmAction.SCHEDULE(entry)) }
                MenuAction(R.string.task_action_cancel, danger = true) { onDismiss(); onAction(ConfirmAction.CANCEL(entry)) }
                MenuAction(R.string.task_action_delete, danger = true) { onDismiss(); onAction(ConfirmAction.DELETE(entry)) }
            }
            "queued" -> {
                MenuAction(R.string.task_action_cancel, danger = true) { onDismiss(); onAction(ConfirmAction.CANCEL(entry)) }
                MenuAction(R.string.task_action_delete, danger = true) { onDismiss(); onAction(ConfirmAction.DELETE(entry)) }
            }
            "preparing", "running", "awaiting_input" -> {
                MenuAction(R.string.task_action_cancel, danger = true) { onDismiss(); onAction(ConfirmAction.CANCEL(entry)) }
            }
            "review", "merging" -> {
                if (task.status == "review" && task.status in DEFERRABLE_STATUSES) {
                    MenuAction(R.string.task_action_defer) { onDismiss(); onAction(ConfirmAction.DEFER(entry)) }
                }
                // R3 P2-5: work_task_delete_core rejects a merging row outright.
                if (task.status != "merging") {
                    MenuAction(R.string.task_action_delete, danger = true) { onDismiss(); onAction(ConfirmAction.DELETE(entry)) }
                }
            }
            "failed" -> {
                // R1A: no 重新排队 here — requeue_canceled CAS-accepts canceled
                // rows only; failed's way out is 重试.
                // R3 P2-2: 归档 is a failed exit too (set_archived accepts
                // done/failed/canceled; web shows it as failed's second action).
                MenuAction(R.string.task_action_retry) { onDismiss(); onAction(ConfirmAction.RETRY(entry)) }
                MenuAction(R.string.task_action_edit) { onDismiss(); onAction(ConfirmAction.EDIT(entry)) }
                MenuAction(R.string.task_action_defer) { onDismiss(); onAction(ConfirmAction.DEFER(entry)) }
                MenuAction(R.string.task_action_archive) { onDismiss(); onAction(ConfirmAction.ARCHIVE(entry)) }
                MenuAction(R.string.task_action_delete, danger = true) { onDismiss(); onAction(ConfirmAction.DELETE(entry)) }
            }
            "canceled", "cancelled" -> {
                MenuAction(R.string.task_action_requeue) { onDismiss(); onAction(ConfirmAction.REQUEUE(entry)) }
                MenuAction(R.string.task_action_edit) { onDismiss(); onAction(ConfirmAction.EDIT(entry)) }
                MenuAction(R.string.task_action_archive) { onDismiss(); onAction(ConfirmAction.ARCHIVE(entry)) }
                MenuAction(R.string.task_action_delete, danger = true) { onDismiss(); onAction(ConfirmAction.DELETE(entry)) }
            }
            "done" -> {
                MenuAction(R.string.task_action_archive) { onDismiss(); onAction(ConfirmAction.ARCHIVE(entry)) }
                MenuAction(R.string.task_action_delete, danger = true) { onDismiss(); onAction(ConfirmAction.DELETE(entry)) }
            }
        }
    }
}

@Composable
private fun MenuAction(labelRes: Int, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(labelRes),
                color = if (danger) CodegTheme.colors.danger else CodegTheme.colors.textPrimary,
            )
        },
        onClick = onClick,
    )
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
    enum class Kind { START, CANCEL, RETRY, REQUEUE, MERGE, DELIVER_PR }
    fun toConfirm(entry: TaskBoardEntry): ConfirmAction = when (kind) {
        Kind.START -> ConfirmAction.START(entry)
        Kind.CANCEL -> ConfirmAction.CANCEL(entry)
        Kind.RETRY -> ConfirmAction.RETRY(entry)
        Kind.REQUEUE -> ConfirmAction.REQUEUE(entry)
        Kind.MERGE -> ConfirmAction.MERGE(entry)
        Kind.DELIVER_PR -> ConfirmAction.DELIVER_PR(entry)
    }
}

/** The ONE primary action per status; everything else lives in the detail sheet. */
private fun primaryAction(task: app.codeg.android.core.model.WorkTaskInfo): PrimaryAction? {
    // R5 P3-1: an archived row shows no card primary — the server would happily
    // retry/requeue it behind the user's back (CAS sees only status, not the
    // archived flag); web parity keeps archived rows out of the action matrix.
    if (task.isArchivedLike) return null
    return when (task.status) {
    "todo" -> PrimaryAction(R.string.task_action_start, false, PrimaryAction.Kind.START)
    // R3 P1-1: queued rows can never START — the server's claim CAS only
    // matches Status::Todo, so the button always failed with "task is not in
    // todo"; queued's primary (web parity) is 取消.
    "queued" -> PrimaryAction(R.string.task_action_cancel, true, PrimaryAction.Kind.CANCEL)
    "preparing", "running", "awaiting_input" -> PrimaryAction(R.string.task_action_cancel, true, PrimaryAction.Kind.CANCEL)
    // R3 P2-3: a forge_pr task's merge is rejected server-side ("came from a
    // pull request") — its review way out is 交付 PR (web's mustDeliverToPr).
    "review" ->
        if (task.fromPullRequest) {
            PrimaryAction(R.string.task_action_deliver_pr, false, PrimaryAction.Kind.DELIVER_PR)
        } else {
            PrimaryAction(R.string.task_action_merge, false, PrimaryAction.Kind.MERGE)
        }
    "failed" -> PrimaryAction(R.string.task_action_retry, false, PrimaryAction.Kind.RETRY)
    "canceled", "cancelled" -> PrimaryAction(R.string.task_action_requeue, false, PrimaryAction.Kind.REQUEUE)
    else -> null // merging/done/unknown: no state action on the card
    }
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
    onOpenSession: (serverId: String, conversationId: Int) -> Unit,
    onOpen: () -> Unit,
    onLoadDiff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val task = entry.task
    val colors = CodegTheme.colors
    LaunchedEffect(entry.serverId, task.id) { onOpen() }
    // 批v151 全锁 + 固定头：标题行在滚动区外（拖拽关闭只认它），其余进滚动体。
    Column(
        Modifier
            .fillMaxWidth()
            .sheetContentScrollLock()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // 批v153（第3轮可及性审查）：dragHandle=null 后标题行是唯一拖拽关闭区，
        // 1 行标题时 ≈42.6dp——补 48dp（与 8 张姊妹 sheet 标题条同法）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        ) {
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
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            task.resultSummary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // 批v155: the task's own description (config.prompt_blocks) — what the
            // agent actually launches with, above the metadata grid.
            task.configPromptText?.takeIf { it.isNotBlank() }?.let { prompt ->
                DetailSection(stringResource(R.string.task_detail_prompt)) {
                    Text(
                        prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                    )
                }
            }
            InfoGrid(entry, folderName)
            // All native actions, confirm-gated (任务书 §4.4 — 以实际 API 能力为准).
            // R5 P3-1: an archived row's ONLY action is 取消归档 (web parity) —
            // retry/requeue/delete on a shelved row either resurrect it behind
            // the user's back or just 4xx.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            ) {
                if (task.isArchivedLike) {
                    SheetAction(R.string.task_action_unarchive, busy) { onAction(ConfirmAction.UNARCHIVE(entry)) }
                } else {
                    task.conversationId?.let { convId ->
                        SheetAction(R.string.task_open_session, busy = false) {
                            onOpenSession(entry.serverId, convId)
                        }
                    }
                    when (task.status) {
                    // R1A: queued split from todo — schedule/edit are todo-only
                    // on the server (CAS on Status::Todo); queued waits can be long.
                    "todo" -> {
                        SheetAction(R.string.task_action_start, busy) { onAction(ConfirmAction.START(entry)) }
                        SheetAction(R.string.task_action_edit, busy) { onAction(ConfirmAction.EDIT(entry)) }
                        SheetAction(R.string.task_action_schedule, busy) { onAction(ConfirmAction.SCHEDULE(entry)) }
                        SheetAction(R.string.task_action_cancel, busy, danger = true) { onAction(ConfirmAction.CANCEL(entry)) }
                    }
                    "queued" ->
                        SheetAction(R.string.task_action_cancel, busy, danger = true) { onAction(ConfirmAction.CANCEL(entry)) }
                    "preparing", "running", "awaiting_input" ->
                        SheetAction(R.string.task_action_cancel, busy, danger = true) { onAction(ConfirmAction.CANCEL(entry)) }
                    "review" -> {
                        // R3 P2-3: a forge_pr task's merge is rejected server-side —
                        // hide 合并 the way the web's mustDeliverToPr does; 交付 PR
                        // stays (the client can't see the forge remote to gate it).
                        if (!task.fromPullRequest) {
                            SheetAction(R.string.task_action_merge, busy) { onAction(ConfirmAction.MERGE(entry)) }
                        }
                        SheetAction(R.string.task_action_complete, busy) { onAction(ConfirmAction.COMPLETE(entry)) }
                        SheetAction(R.string.task_action_return, busy) { onAction(ConfirmAction.RETURN(entry)) }
                        SheetAction(R.string.task_action_deliver_pr, busy) { onAction(ConfirmAction.DELIVER_PR(entry)) }
                    }
                    "failed" -> {
                        // R1A: no requeue — the server requeue path accepts
                        // canceled rows only; failed's way out is 重试.
                        SheetAction(R.string.task_action_retry, busy) { onAction(ConfirmAction.RETRY(entry)) }
                        SheetAction(R.string.task_action_edit, busy) { onAction(ConfirmAction.EDIT(entry)) }
                    }
                    "canceled", "cancelled" -> {
                        SheetAction(R.string.task_action_requeue, busy) { onAction(ConfirmAction.REQUEUE(entry)) }
                        SheetAction(R.string.task_action_edit, busy) { onAction(ConfirmAction.EDIT(entry)) }
                    }
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
                        // R3 P2-2: failed archives too (set_archived: done/failed/canceled).
                        task.status == "done" || task.status == "failed" ||
                            task.status == "canceled" || task.status == "cancelled" ->
                            SheetAction(R.string.task_action_archive, busy) { onAction(ConfirmAction.ARCHIVE(entry)) }
                    }
                    // R3 P2-5: the server refuses to delete a merging row — wait for
                    // the transient state to resolve instead of a guaranteed 4xx.
                    if (task.status != "merging") {
                        SheetAction(R.string.task_action_delete, busy, danger = true) { onAction(ConfirmAction.DELETE(entry)) }
                    }
                }
            }
            TaskDetailBody(detail, onLoadDiff, onRetry = onOpen)
            Spacer(Modifier.padding(bottom = 24.dp))
        }
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
                        // R2D#7: code-point-safe cap — a raw take(8_000) can split
                        // a surrogate pair and render a dangling half character.
                        app.codeg.android.core.data.DispatchService.cap(detail.diff, 8_000),
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

    /** 批v155: carries the dialog's reason + worktree checkbox (web parity). */
    data class CANCEL(
        override val entry: TaskBoardEntry,
        val reason: String? = null,
        val deleteWorktree: Boolean = false,
    ) : ConfirmAction
    data class MERGE(override val entry: TaskBoardEntry) : ConfirmAction
    data class COMPLETE(override val entry: TaskBoardEntry) : ConfirmAction
    data class START(override val entry: TaskBoardEntry) : ConfirmAction

    /** 批v155: carries the dialog's optional note (web parity). */
    data class RETRY(override val entry: TaskBoardEntry, val note: String? = null) : ConfirmAction
    data class REQUEUE(override val entry: TaskBoardEntry, val note: String? = null) : ConfirmAction
    data class RETURN(override val entry: TaskBoardEntry, val feedback: String = "") : ConfirmAction
    data class DELIVER_PR(override val entry: TaskBoardEntry) : ConfirmAction
    data class ARCHIVE(override val entry: TaskBoardEntry) : ConfirmAction
    data class UNARCHIVE(override val entry: TaskBoardEntry) : ConfirmAction
    data class DEFER(override val entry: TaskBoardEntry) : ConfirmAction
    data class UNDEFER(override val entry: TaskBoardEntry) : ConfirmAction
    data class DELETE(override val entry: TaskBoardEntry) : ConfirmAction

    /** 批v155: 定时运行 (todo only) — null clears an existing schedule. */
    data class SCHEDULE(override val entry: TaskBoardEntry, val scheduledAtIsoUtc: String? = null) : ConfirmAction

    /** 批v155: 编辑任务 (todo/failed/canceled only) — opens the edit sheet. */
    data class EDIT(override val entry: TaskBoardEntry) : ConfirmAction
}

@Composable
private fun ConfirmDialogs(
    confirm: ConfirmAction?,
    busy: Boolean,
    onRun: (ConfirmAction) -> Unit,
    onDismiss: () -> Unit,
) {
    confirm?.let { action ->
        when (action) {
            // Text-input dialogs (each owns its fields + gates).
            is ConfirmAction.RETURN -> ReturnDialog(action, busy, onRun, onDismiss)
            is ConfirmAction.CANCEL -> CancelDialog(action, busy, onRun, onDismiss)
            is ConfirmAction.RETRY -> NoteDialog(
                titleRes = R.string.task_confirm_retry_title,
                msgRes = R.string.task_confirm_retry_msg,
                hintRes = R.string.task_note_hint,
                busy = busy,
                onConfirm = { note -> onRun(action.copy(note = note)); onDismiss() },
                onDismiss = onDismiss,
            )
            is ConfirmAction.REQUEUE -> NoteDialog(
                titleRes = R.string.task_confirm_requeue_title,
                msgRes = R.string.task_confirm_requeue_msg,
                hintRes = R.string.task_note_hint,
                busy = busy,
                onConfirm = { note -> onRun(action.copy(note = note)); onDismiss() },
                onDismiss = onDismiss,
            )
            is ConfirmAction.SCHEDULE -> ScheduleDialog(action, busy, onRun, onDismiss)
            is ConfirmAction.EDIT -> Unit // never routed here (screen intercepts into the sheet)
            else -> {
                val (titleRes, msgRes, danger) = when (action) {
                    is ConfirmAction.MERGE -> Triple(R.string.task_confirm_merge_title, R.string.task_confirm_merge_msg, true)
                    is ConfirmAction.COMPLETE -> Triple(R.string.task_confirm_complete_title, R.string.task_confirm_complete_msg, false)
                    is ConfirmAction.START -> Triple(R.string.task_confirm_start_title, R.string.task_confirm_start_msg, false)
                    is ConfirmAction.DELIVER_PR -> Triple(R.string.task_confirm_deliver_title, R.string.task_confirm_deliver_msg, false)
                    is ConfirmAction.ARCHIVE -> Triple(R.string.task_confirm_archive_title, R.string.task_confirm_archive_msg, false)
                    is ConfirmAction.UNARCHIVE -> Triple(R.string.task_confirm_unarchive_title, R.string.task_confirm_unarchive_msg, false)
                    is ConfirmAction.DEFER -> Triple(R.string.task_confirm_defer_title, R.string.task_confirm_defer_msg, false)
                    is ConfirmAction.UNDEFER -> Triple(R.string.task_confirm_undefer_title, R.string.task_confirm_undefer_msg, false)
                    is ConfirmAction.DELETE -> Triple(R.string.task_confirm_delete_title, R.string.task_confirm_delete_msg, true)
                    else -> return
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
    }
}

/** 批v155 取消任务: optional reason + delete-worktree checkbox (web parity).
 *  The checkbox only shows when a worktree plausibly exists (a started task). */
@Composable
private fun CancelDialog(
    action: ConfirmAction.CANCEL,
    busy: Boolean,
    onRun: (ConfirmAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember(action.entry.task.id) { mutableStateOf("") }
    var deleteWorktree by rememberSaveable(key = "cancel-wt-${action.entry.task.id}") { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_confirm_cancel_title)) },
        text = {
            Column {
                Text(stringResource(R.string.task_confirm_cancel_msg))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = { Text(stringResource(R.string.task_cancel_reason_hint)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                if (action.entry.task.workBranch != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteWorktree = !deleteWorktree }
                            .padding(top = 4.dp),
                    ) {
                        Checkbox(checked = deleteWorktree, onCheckedChange = { deleteWorktree = it })
                        Text(
                            stringResource(R.string.task_cancel_delete_worktree),
                            style = MaterialTheme.typography.bodySmall,
                            color = CodegTheme.colors.textSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                onRun(action.copy(reason = reason.trim().takeIf(String::isNotEmpty), deleteWorktree = deleteWorktree))
                onDismiss()
            }) {
                Text(stringResource(android.R.string.ok), color = CodegTheme.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** 批v155: shared optional-note dialog for 重试 / 重新排队 (web parity). */
@Composable
private fun NoteDialog(
    titleRes: Int,
    msgRes: Int,
    hintRes: Int,
    busy: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(titleRes) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(stringResource(msgRes))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text(stringResource(hintRes)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onConfirm(note.trim().takeIf(String::isNotEmpty)) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * 批v155 定时运行 (web parity): quick chips + custom date/time pickers, and a
 * clear-schedule path when one is already set. The server's scheduler polls
 * every 15s; a past time would fire immediately, so the confirm gate mirrors
 * the dispatch form's rule.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleDialog(
    action: ConfirmAction.SCHEDULE,
    busy: Boolean,
    onRun: (ConfirmAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val task = action.entry.task
    var picked by remember { mutableStateOf<LocalDateTime?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showTime by remember { mutableStateOf(false) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    // 30s ticker (v146 invariant, R1A): "in the past" goes stale as the wall
    // clock advances with no state change — without this, a dialog left open
    // across a minute boundary could confirm an already-past schedule.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowTick = System.currentTimeMillis()
        }
    }

    fun chip(millis: Long) { picked = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_schedule_title)) },
        text = {
            Column {
                task.scheduledAt?.let { current ->
                    Text(
                        stringResource(R.string.task_schedule_current, relativeLabel(current) ?: current),
                        style = MaterialTheme.typography.bodySmall,
                        color = CodegTheme.colors.textSecondary,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    QuickChip(stringResource(R.string.task_schedule_chip_1h)) { chip(nowTick + 3_600_000L) }
                    QuickChip(stringResource(R.string.task_schedule_chip_tonight)) {
                        chip(LocalDate.now().atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                    QuickChip(stringResource(R.string.task_schedule_chip_tomorrow)) {
                        chip(LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                    QuickChip(stringResource(R.string.task_schedule_chip_custom)) { showDate = true }
                }
                picked?.let { dt ->
                    val pattern = stringResource(R.string.dispatch_schedule_pattern)
                    Text(
                        remember(dt, pattern) { dt.format(DateTimeFormatter.ofPattern(pattern)) },
                        style = MaterialTheme.typography.titleSmall,
                        color = CodegTheme.colors.textPrimary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    val inPast = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() <= nowTick
                    if (inPast) {
                        Text(
                            stringResource(R.string.dispatch_schedule_past),
                            color = CodegTheme.colors.danger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                // 清除定时 only when one exists (workTaskSchedule(id, null)).
                if (task.scheduledAt != null) {
                    TextButton(enabled = !busy, onClick = {
                        onRun(action.copy(scheduledAtIsoUtc = null)); onDismiss()
                    }) { Text(stringResource(R.string.task_schedule_clear), color = CodegTheme.colors.danger) }
                }
                TextButton(
                    enabled = !busy && picked != null &&
                        picked!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() > nowTick,
                    onClick = {
                        val iso = picked!!
                            .atZone(ZoneId.systemDefault()).toInstant().toString()
                        onRun(action.copy(scheduledAtIsoUtc = iso)); onDismiss()
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        showDate = false
                        showTime = true
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }
    if (showTime) {
        val now = LocalTime.now()
        val state = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.task_schedule_pick_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickedDate ?: return@TextButton
                    picked = date.atTime(LocalTime.of(state.hour, state.minute))
                    showTime = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bgElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    }
}

/**
 * 批v155 新建任务 result dialog — screen-level so it survives the sheet
 * unmounting (the same rule the inbox's dispatch result follows).
 */
@Composable
private fun NewTaskResultDialog(ref: DispatchRef, onDismiss: () -> Unit) {
    val (titleRes, message) = when (ref.state) {
        DispatchState.CONFIRMED -> R.string.task_new_created_title to
            stringResource(R.string.task_new_created_msg, ref.title)
        DispatchState.UNKNOWN -> R.string.task_new_unknown_title to
            stringResource(R.string.task_new_unknown_msg)
        DispatchState.PENDING -> R.string.task_new_unknown_title to
            stringResource(R.string.task_new_unknown_msg)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

/**
 * 批v155 编辑任务 (todo/failed/canceled): title + content + agent. The server
 * overwrite is WHOLE — the config is rebuilt from the row's own rawConfig
 * (prompt text re-derived from prompt_blocks, mode/base/config_values passed
 * through untouched), so an edit can never silently drop a pinned mode or
 * branch it didn't show a field for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTaskSheet(
    entry: TaskBoardEntry,
    viewModel: TaskHubViewModel,
    busy: Boolean,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val task = entry.task
    val colors = CodegTheme.colors
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    var title by rememberSaveable(key = "edit-title-${task.id}") { mutableStateOf(task.title) }
    var description by rememberSaveable(key = "edit-desc-${task.id}") {
        mutableStateOf(task.configPromptText.orEmpty())
    }
    var showDescFullscreen by remember { mutableStateOf(false) }
    // Agent chips: current config agent preselected; 自动 = drop the override
    // (project default) — same semantics the dispatch form's chips carry.
    // R1B #2: chips are keyed by the WIRE name (configAgentType stores the
    // server's `agent_type` value, e.g. "claude_code"), not the enum name —
    // keying by it.name would never preselect and would write "CLAUDE_CODE"
    // back into the config on save.
    var agentWire by rememberSaveable(key = "edit-agent-${task.id}") { mutableStateOf(task.configAgentType.orEmpty()) }
    // R1B #8 / R4 P2-1: also re-run when a previous load FAILED (the sheet is
    // the only surface that can recover the agent list from here). The error
    // clause used to gate the retry out of existence — error != null is exactly
    // the case that needs a re-run. A repeat failure with the SAME message
    // doesn't re-emit the StateFlow, so this can't become a retry loop.
    // R5 P2-1: also reload when the lists belong to ANOTHER device — the sheet
    // has no device selector, and saving the stale agent wire would write a
    // foreign agent into this task's config.
    LaunchedEffect(entry.serverId, targets.error) {
        val foreign = targets.loadedForServerId != null && targets.loadedForServerId != entry.serverId
        if (!targets.foldersLoading && (targets.folders.isEmpty() || foreign)) {
            viewModel.loadTarget(entry.serverId)
        }
    }
    // R2C#4: settle-gated submit — the sheet stays mounted (draft intact, the
    // rememberSaveable states above alive) until the server answers; a
    // rejection shows inline and the user can fix/retry. Closing on send used
    // to destroy a long edit on a stale-status 4xx with only a board-top
    // banner as a trace.
    var pending by remember { mutableStateOf(false) }
    var inlineError by remember { mutableStateOf<String?>(null) }
    val folderId = task.folderId
    // R1A #6: the server rejects an empty prompt (validate_draft) — block the
    // save locally instead of surfacing a raw 4xx banner.
    val canSave = title.isNotBlank() && description.isNotBlank() && folderId != null && !busy && !pending

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.task_edit_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.inbox_title_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.inbox_desc_hint)) },
                minLines = 4,
                trailingIcon = {
                    IconButton(onClick = { showDescFullscreen = true }) {
                        Icon(
                            Icons.Rounded.OpenInFull,
                            contentDescription = stringResource(R.string.compose_edit_fullscreen),
                            tint = colors.textSecondary,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.dispatch_section_device_agent),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 12.dp),
            )
            AgentChipRow(
                autoLabel = stringResource(R.string.dispatch_agent_auto),
                agents = targets.agents.map { app.codeg.android.core.designsystem.component.PickerOption(id = it.wire, label = it.displayName) },
                selectedId = agentWire.ifBlank { null },
                onSelect = { wire -> agentWire = wire ?: "" },
            )
            Text(
                stringResource(R.string.task_edit_note),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )
            // R2C#7: same 120-code-point cap the create path applies — the edit
            // sheet used to send the title uncapped.
            inlineError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(android.R.string.cancel))
                }
                app.codeg.android.core.designsystem.component.PrimaryButton(
                    text = stringResource(R.string.task_edit_save),
                    onClick = {
                        val fid = folderId ?: return@PrimaryButton
                        pending = true
                        inlineError = null
                        val agent = agentWire.takeIf { it.isNotBlank() }
                        // NOTE (R1A #7): the config is rebuilt from the row's own
                        // rawConfig, but a multi-block prompt_blocks (e.g. forge's
                        // instruction+untrusted envelope) is flattened into ONE
                        // text block by configPromptText — content survives, block
                        // structure does not. The server revives omitted keys like
                        // deliverable; engine consumers of label_snapshot: none.
                        val config = app.codeg.android.core.data.DispatchPipeline.taskConfig(
                            description = description.trim(),
                            agentTypeWire = agent,
                            modeId = task.configModeId,
                            baseBranch = task.configBaseBranch,
                            configValues = task.configValues,
                        )
                        viewModel.updateTask(
                            entry.serverId,
                            task.id,
                            fid,
                            app.codeg.android.core.data.DispatchService.cap(title.trim(), 120),
                            config,
                        ) { ok, error ->
                            pending = false
                            // Keep the sheet open on a rejection — the draft and
                            // the error stay; onDone (close) only on a verified
                            // save. busyKey≠null pre-empts via onSettled(false).
                            if (ok) onDone() else inlineError = error
                        }
                    },
                    enabled = canSave,
                    loading = pending,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (showDescFullscreen) {
        FullscreenTextEditorDialog(
            text = description,
            onTextChange = { description = it },
            onDismiss = { showDescFullscreen = false },
            label = stringResource(R.string.inbox_desc_hint),
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
