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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.data.DEFERRABLE_STATUSES
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.SEGMENT_ORDER
import app.codeg.android.core.data.TaskAccentTone
import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.data.TaskSegment
import app.codeg.android.core.data.archiveAllCandidates
import app.codeg.android.core.data.isEffectivelyDeferred
import app.codeg.android.core.data.segmentAccentTone
import app.codeg.android.core.data.segmentStepIndex
import app.codeg.android.core.data.taskStatusLabelRes
import app.codeg.android.core.data.taskStatusUi
import app.codeg.android.core.data.TaskStatusUi
import app.codeg.android.core.data.TaskSegmentBus
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.designsystem.component.AgentChipRow
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FullscreenTextEditorDialog
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.CodegColors
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.WorkTaskChangedFile
import app.codeg.android.core.model.WorkTaskEvent
import app.codeg.android.core.model.WorkTaskInfo
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

    // 批v160 W5: the board is a four-column kanban now — no selected segment,
    // just the LazyRow and a scroll target for the programmatic jumps.
    val columnState = rememberLazyListState()
    var pendingColumn by remember { mutableStateOf<Int?>(null) }
    // 批v152: the workbench stat cards / 需要处理 preview ask for a SPECIFIC
    // column. Consumed once on entry (the request is read-then-cleared in the
    // repository), so a later plain tab return keeps the columns where the user
    // left them.
    LaunchedEffect(viewModel) {
        viewModel.consumeSegmentRequest()?.let { seg ->
            pendingColumn = SEGMENT_ORDER.indexOf(seg).coerceAtLeast(0)
        }
    }
    // 批v156: after an action (启动/取消/重试…) the card moves columns — follow
    // it so it never "vanishes" one swipe away (the VM emits the landed segment
    // once per action).
    val actedLanded by viewModel.actedLandedSegment.collectAsStateWithLifecycle()
    LaunchedEffect(actedLanded) {
        val landed = actedLanded ?: return@LaunchedEffect
        pendingColumn = SEGMENT_ORDER.indexOf(landed).coerceAtLeast(0)
        viewModel.consumeActedLandedSegment()
    }
    // 批v160 W5: the 「切任务状态」 horizontal swipe — step between the four
    // columns, clamped at the ends (segmentStepIndex; no wraparound).
    LaunchedEffect(viewModel) {
        viewModel.segmentBus.steps.collect { step ->
            pendingColumn = segmentStepIndex(columnState.firstVisibleItemIndex, step)
        }
    }
    LaunchedEffect(pendingColumn) {
        val target = pendingColumn ?: return@LaunchedEffect
        pendingColumn = null
        if (target in SEGMENT_ORDER.indices) {
            runCatching { columnState.animateScrollToItem(target) }
        }
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
        Column(Modifier.fillMaxSize()) {
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
            if (searchOpen) {
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
            if (ui.failedDevices > 0) {
                FailedBanner(ui = ui, onRetry = viewModel::retryFailedDevices)
            }
            ui.actionError?.let { error ->
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
            // Non-error outcome note (e.g. merge queued behind the folder's
            // serial merge slot): tap to dismiss, same lifecycle as actionError.
            ui.actionNote?.let { note ->
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
            if (ui.archivedHidden > 0) {
                Text(
                    text = stringResource(R.string.task_archived_hidden, ui.archivedHidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (ui.hasLoaded && board.segments.values.all { it.isEmpty() } && board.unknown.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(
                        if (ui.deviceStates.isEmpty()) R.string.task_empty_no_devices else R.string.task_empty_board,
                    ),
                    message = stringResource(R.string.task_empty_hint),
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
            // 批v160 W5 kanban: four side-by-side columns (web board parity).
            // Column width = max(240dp, 72% of the screen) — a phone never sees
            // two full columns at once, and the 240dp floor keeps every card
            // footer's buttons reachable (施工图 W5 风险条目).
            val configuration = LocalConfiguration.current
            val columnWidth = maxOf(240.dp, configuration.screenWidthDp.dp * 0.72f)
            LazyRow(
                state = columnState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SEGMENT_ORDER, key = { it.name }, contentType = { "column" }) { seg ->
                    val rows = board.segments[seg].orEmpty()
                    KanbanColumn(
                        segment = seg,
                        entries = rows,
                        // Unknown statuses surface under 等你处理 (the old
                        // single-column view showed them in the same section).
                        unknown = if (seg == TaskSegment.ATTENTION) board.unknown else emptyList(),
                        modifier = Modifier.width(columnWidth).fillParentMaxHeight(),
                        folderNames = ui.folderNames,
                        busy = ui.busyKey != null,
                        spinningKey = ui.busyKey,
                        canArchiveAll = seg == TaskSegment.DONE &&
                            archiveAllCandidates(rows).isNotEmpty(),
                        onArchiveAll = viewModel::archiveAllDone,
                        onOpenDetail = { entry -> detailKey = entry.serverId to entry.task.id },
                        onOpenSession = onOpenSession,
                        onAction = { action ->
                            // 编辑 opens a sheet, not a confirm dialog.
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

/**
 * 批v160 W5: one kanban column (web board parity) — a fixed header (accent
 * bar + name + count badge, and 全部归档 on DONE) over a vertical list of
 * cards. Unknown statuses ride at the bottom of 等你处理 like the old
 * single-column view did.
 */
@Composable
private fun KanbanColumn(
    segment: TaskSegment,
    entries: List<TaskBoardEntry>,
    unknown: List<TaskBoardEntry>,
    modifier: Modifier,
    folderNames: Map<String, Map<Int, String>>,
    busy: Boolean,
    spinningKey: String?,
    canArchiveAll: Boolean,
    onArchiveAll: () -> Unit,
    onOpenDetail: (TaskBoardEntry) -> Unit,
    onOpenSession: (serverId: String, conversationId: Int) -> Unit,
    onAction: (ConfirmAction) -> Unit,
) {
    Column(modifier) {
        KanbanColumnHeader(
            segment = segment,
            count = entries.size,
            showArchiveAll = segment == TaskSegment.DONE,
            canArchiveAll = canArchiveAll,
            busy = busy,
            onArchiveAll = onArchiveAll,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                entries,
                key = { "${it.serverId}-${it.task.id}" },
                contentType = { "task" },
            ) { entry ->
                TaskCardWeb(
                    entry = entry,
                    folderName = folderNames[entry.serverId]?.get(entry.task.folderId),
                    busy = busy,
                    spinning = spinningKey == "${entry.serverId}:${entry.task.id}",
                    onOpenDetail = { onOpenDetail(entry) },
                    onOpenSession = onOpenSession,
                    onAction = onAction,
                )
            }
            if (unknown.isNotEmpty()) {
                item(key = "unknown-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.task_unknown_section),
                            style = MaterialTheme.typography.titleSmall,
                            color = CodegTheme.colors.textPrimary,
                        )
                        Text(
                            "${unknown.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CodegTheme.colors.textSecondary,
                        )
                    }
                }
                items(
                    unknown,
                    key = { "u-${it.serverId}-${it.task.id}" },
                    contentType = { "task" },
                ) { entry ->
                    TaskCardWeb(
                        entry = entry,
                        folderName = folderNames[entry.serverId]?.get(entry.task.folderId),
                        busy = busy,
                        spinning = false,
                        onOpenDetail = { onOpenDetail(entry) },
                        onOpenSession = onOpenSession,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

/** Column header: the 4dp accent bar + name + live count (+ 全部归档 on DONE). */
@Composable
private fun KanbanColumnHeader(
    segment: TaskSegment,
    count: Int,
    showArchiveAll: Boolean,
    canArchiveAll: Boolean,
    busy: Boolean,
    onArchiveAll: () -> Unit,
) {
    val colors = CodegTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .wrapContentHeight(Alignment.CenterVertically),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 18.dp)
                .background(segmentAccentTone(segment).composeColor(colors), RoundedCornerShape(2.dp)),
        )
        Text(
            stringResource(segment.labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 8.dp),
        )
        Box(
            Modifier
                .padding(start = 6.dp)
                .background(colors.textSecondary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (showArchiveAll) {
            // 批v160 W5: one-tap sweep of every finished row on the board.
            TextButton(
                onClick = onArchiveAll,
                enabled = canArchiveAll && !busy,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    stringResource(R.string.task_archive_all_done),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** 批v160 W5: [TaskAccentTone] → the theme colour it reads as on this surface. */
private fun TaskAccentTone.composeColor(
    colors: app.codeg.android.core.designsystem.theme.CodegColors,
): Color = when (this) {
    TaskAccentTone.ARCHIVED -> colors.textSecondary.copy(alpha = 0.20f)
    TaskAccentTone.SHELVED, TaskAccentTone.QUEUED -> colors.textSecondary.copy(alpha = 0.35f)
    TaskAccentTone.LIVE -> colors.accent
    TaskAccentTone.ATTENTION -> colors.warning
    TaskAccentTone.FAILED -> colors.danger
    TaskAccentTone.DONE -> colors.success
    TaskAccentTone.CANCELED -> colors.textSecondary.copy(alpha = 0.25f)
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

/**
 * Web StatusChip (task-card.tsx 84-156), 1:1: live statuses are spinner text in
 * the accent colour, `awaiting_input`/`review` an outlined amber pill, `done` a
 * green pill with a check, `failed` a red pill, the rest muted — and a shelved
 * task reads as exactly that (muted + clock) whatever status is underneath.
 * An interrupted failure reads differently from an agent failure.
 */
@Composable
private fun WebStatusChip(task: WorkTaskInfo) {
    val colors = CodegTheme.colors
    val status = taskStatusUi(task)
    val deferred = isEffectivelyDeferred(task)
    val label = when {
        deferred -> stringResource(R.string.task_deferred_tag)
        task.status == "failed" && task.failureReason == "interrupted" ->
            stringResource(R.string.task_status_interrupted)
        status.raw != null -> status.raw
        else -> stringResource(status.labelRes)
    }
    val base = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium)
    when {
        deferred -> ChipPill(
            bg = colors.textSecondary.copy(alpha = 0.14f), fg = colors.textSecondary,
            label = label, base = base, icon = Icons.Rounded.AccessTime,
        )
        task.status in LIVE_CHIP_STATUSES -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = colors.accent)
            Text(label, style = base, color = colors.accent, maxLines = 1)
        }
        task.status == "awaiting_input" || task.status == "review" -> ChipPill(
            bg = colors.warning.copy(alpha = 0.05f), fg = colors.warning,
            label = label, base = base, border = colors.warning.copy(alpha = 0.45f),
        )
        task.status == "done" -> ChipPill(
            bg = colors.success.copy(alpha = 0.10f), fg = colors.success,
            label = label, base = base, icon = Icons.Rounded.Check,
        )
        task.status == "failed" -> ChipPill(
            bg = colors.danger.copy(alpha = 0.10f), fg = colors.danger, label = label, base = base,
        )
        else -> ChipPill(
            bg = colors.textSecondary.copy(alpha = 0.14f), fg = colors.textSecondary, label = label, base = base,
        )
    }
}

/** Statuses whose chip is bare spinner text in the accent colour (web 108-116). */
private val LIVE_CHIP_STATUSES = setOf("queued", "preparing", "running", "merging")

@Composable
private fun ChipPill(
    bg: Color,
    fg: Color,
    label: String,
    base: androidx.compose.ui.text.TextStyle,
    border: Color? = null,
    icon: ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(50)) else Modifier)
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(10.dp))
        }
        Text(label, style = base, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The planned start of a to-do card — the one thing on it that says something
 *  WILL happen (web ScheduleChip 375-389). */
@Composable
private fun SchedulePill(scheduledAt: String) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(colors.accent.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = colors.accent, modifier = Modifier.size(10.dp))
        Text(
            relativeLabel(scheduledAt) ?: scheduledAt,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
            color = colors.accent,
            maxLines = 1,
        )
    }
}

/**
 * One card action. The web `buildTaskActions` matrix minus what this wire
 * doesn't carry (merge_queued / preflight / worktree state → no unqueue, no
 * preflight/worktree chips); OPEN_SESSION is dispatched by the screen, it is
 * not a ConfirmAction.
 */
private class CardAction(
    val labelRes: Int,
    val icon: ImageVector,
    val kind: Kind,
    val danger: Boolean = false,
    val amber: Boolean = false,
) {
    enum class Kind {
        START, CANCEL, RETRY, REQUEUE, MERGE, DELIVER_PR, COMPLETE, RETURN,
        EDIT, SCHEDULE, ARCHIVE, UNARCHIVE, DEFER, UNDEFER, OPEN_SESSION,
    }

    fun toConfirm(entry: TaskBoardEntry): ConfirmAction = when (kind) {
        Kind.START -> ConfirmAction.START(entry)
        Kind.CANCEL -> ConfirmAction.CANCEL(entry)
        Kind.RETRY -> ConfirmAction.RETRY(entry)
        Kind.REQUEUE -> ConfirmAction.REQUEUE(entry)
        Kind.MERGE -> ConfirmAction.MERGE(entry)
        Kind.DELIVER_PR -> ConfirmAction.DELIVER_PR(entry)
        Kind.COMPLETE -> ConfirmAction.COMPLETE(entry)
        Kind.RETURN -> ConfirmAction.RETURN(entry)
        Kind.EDIT -> ConfirmAction.EDIT(entry)
        Kind.SCHEDULE -> ConfirmAction.SCHEDULE(entry)
        Kind.ARCHIVE -> ConfirmAction.ARCHIVE(entry)
        Kind.UNARCHIVE -> ConfirmAction.UNARCHIVE(entry)
        Kind.DEFER -> ConfirmAction.DEFER(entry)
        Kind.UNDEFER -> ConfirmAction.UNDEFER(entry)
        Kind.OPEN_SESSION -> error("OPEN_SESSION is dispatched by the screen, not ConfirmAction")
    }
}

private class CardActions(val primary: CardAction?, val secondaries: List<CardAction>)

/** 「以后处理」, or — on a task already shelved — the way back (web deferToggle). */
private fun cardDeferToggle(task: WorkTaskInfo): CardAction? = when {
    isEffectivelyDeferred(task) ->
        CardAction(R.string.task_action_undefer, Icons.Rounded.Inbox, CardAction.Kind.UNDEFER)
    task.status in DEFERRABLE_STATUSES && !task.isArchivedLike ->
        CardAction(R.string.task_action_defer, Icons.Rounded.AccessTime, CardAction.Kind.DEFER)
    else -> null
}

/**
 * The action set a card offers (web task-actions.ts buildTaskActions): exactly
 * one primary per status on the left, secondaries on the right, 查看会话 always
 * last when a session exists.
 */
private fun buildCardActions(task: WorkTaskInfo): CardActions {
    val secondaries = mutableListOf<CardAction>()
    var primary: CardAction? = null
    if (task.isArchivedLike) {
        // An archived row offers exactly one way back.
        primary = CardAction(R.string.task_action_unarchive, Icons.Rounded.Unarchive, CardAction.Kind.UNARCHIVE)
    } else {
        when (task.status) {
            "todo" -> {
                primary = CardAction(R.string.task_action_start, Icons.Rounded.PlayArrow, CardAction.Kind.START)
                secondaries += CardAction(R.string.task_action_edit, Icons.Rounded.Edit, CardAction.Kind.EDIT)
                secondaries += CardAction(R.string.task_action_schedule, Icons.Rounded.Schedule, CardAction.Kind.SCHEDULE)
            }
            "queued", "preparing", "running", "awaiting_input" ->
                primary = CardAction(R.string.task_action_cancel, Icons.Rounded.Block, CardAction.Kind.CANCEL, danger = true)
            "review" -> {
                if (isEffectivelyDeferred(task)) {
                    // Picking it back up is what this card is for now (web 163-171).
                    primary = CardAction(
                        R.string.task_action_follow_up, Icons.AutoMirrored.Rounded.Undo,
                        CardAction.Kind.RETURN, amber = true,
                    )
                    secondaries += CardAction(R.string.task_action_undefer, Icons.Rounded.Inbox, CardAction.Kind.UNDEFER)
                } else {
                    primary = when {
                        // Nothing changed ⟹ nothing to merge: accept outright (web 176-181).
                        (task.changedFileCount ?: -1) == 0 ->
                            CardAction(R.string.task_action_complete, Icons.Rounded.CheckCircle, CardAction.Kind.COMPLETE, amber = true)
                        // forge_pr: the local merge is refused server-side (web 182-191).
                        task.fromPullRequest ->
                            CardAction(R.string.task_action_deliver_pr, Icons.Rounded.CallMade, CardAction.Kind.DELIVER_PR, amber = true)
                        else ->
                            CardAction(R.string.task_action_merge, Icons.Rounded.CallMerge, CardAction.Kind.MERGE, amber = true)
                    }
                    cardDeferToggle(task)?.let { secondaries += it }
                }
            }
            "merging" -> {} // no primary: it cannot be canceled
            "failed" -> {
                primary = CardAction(R.string.task_action_retry, Icons.Rounded.Replay, CardAction.Kind.RETRY)
                secondaries += CardAction(R.string.task_action_edit, Icons.Rounded.Edit, CardAction.Kind.EDIT)
                cardDeferToggle(task)?.let { secondaries += it }
                secondaries += CardAction(R.string.task_action_archive, Icons.Rounded.Archive, CardAction.Kind.ARCHIVE)
            }
            "done" ->
                primary = CardAction(R.string.task_action_archive, Icons.Rounded.Archive, CardAction.Kind.ARCHIVE)
            "canceled", "cancelled" -> {
                primary = CardAction(R.string.task_action_requeue, Icons.Rounded.Replay, CardAction.Kind.REQUEUE)
                secondaries += CardAction(R.string.task_action_archive, Icons.Rounded.Archive, CardAction.Kind.ARCHIVE)
            }
        }
    }
    // The session is offered in every status once one exists; always last.
    if (task.conversationId != null) {
        secondaries += CardAction(R.string.task_open_session, Icons.Rounded.Chat, CardAction.Kind.OPEN_SESSION)
    }
    return CardActions(primary, secondaries)
}

private fun dispatchCardAction(
    action: CardAction,
    entry: TaskBoardEntry,
    onOpenSession: (serverId: String, conversationId: Int) -> Unit,
    onAction: (ConfirmAction) -> Unit,
) {
    if (action.kind == CardAction.Kind.OPEN_SESSION) {
        entry.task.conversationId?.let { onOpenSession(entry.serverId, it) }
    } else {
        onAction(action.toConfirm(entry))
    }
}

/**
 * 批v160 W5: the web TaskCard (task-card.tsx), 1:1 where the Android wire
 * carries the data — rounded 16dp bordered card, agent mark + 2-line title +
 * status chip on top, the folder/branch · ±stat · when meta line, a review
 * card's 2-line verdict, and a footer of one primary pill on the left with
 * round icon secondaries on the right. Long-press keeps the v155 context menu
 * (the full per-status action matrix, server-honest).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskCardWeb(
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
    val actions = buildCardActions(task)
    val metaStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
    val monoStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (task.isArchivedLike) Modifier.alpha(0.6f) else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.bgElevated)
            .border(1.dp, colors.textPrimary.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onOpenDetail, onLongClick = { menuOpen = true })
            .padding(12.dp),
    ) {
        // Header: agent mark rides the first title line, chip pinned right.
        Row(verticalAlignment = Alignment.Top) {
            if (spinning) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(6.dp))
            }
            Icon(
                Icons.Rounded.SmartToy,
                contentDescription = null,
                tint = if (task.agentType != null || task.configAgentType != null) {
                    colors.textSecondary
                } else {
                    colors.textTertiary.copy(alpha = 0.4f)
                },
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(14.dp),
            )
            Text(
                task.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp,
                ),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            WebStatusChip(task)
        }
        // Meta line (web 468-506). The server name is Android-only — this board
        // aggregates across devices, the web board never does.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text(
                entry.serverName,
                style = metaStyle, color = colors.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            folderName?.let {
                Text(
                    it,
                    style = metaStyle, color = colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
            task.workBranch?.let { branch ->
                Text(
                    branch,
                    style = monoStyle, color = colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
            if ((task.changedFileCount ?: 0) > 0) {
                Text("+${task.additions ?: 0}", style = monoStyle, color = colors.success)
                Text("-${task.deletions ?: 0}", style = monoStyle, color = colors.danger)
            }
            (task.finishedAt ?: task.settledAt ?: task.startedAt ?: task.createdAt)?.let { whenIso ->
                relativeLabel(whenIso)?.let {
                    Text(it, style = metaStyle, color = colors.textTertiary, maxLines = 1)
                }
            }
            if (task.status == "todo" && task.scheduledAt != null) {
                SchedulePill(task.scheduledAt)
            }
        }
        // Review cards quote the agent's verdict, two lines (web 546-550).
        if (task.status == "review") {
            task.resultSummary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // Footer: divider, then primary pill left / round icon secondaries right
        // (web 560-597). Always composed, only disabled — removing buttons on an
        // in-flight action made every card's row height jump.
        if (actions.primary != null || actions.secondaries.isNotEmpty()) {
            Column(Modifier.padding(top = 12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.textPrimary.copy(alpha = 0.08f)),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    actions.primary?.let { primary ->
                        CardPrimaryPill(
                            action = primary,
                            enabled = !busy,
                            modifier = Modifier.weight(1f, fill = false),
                        ) { dispatchCardAction(primary, entry, onOpenSession, onAction) }
                    }
                    if (actions.secondaries.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            actions.secondaries.forEach { secondary ->
                                CardIconAction(action = secondary, enabled = !busy) {
                                    dispatchCardAction(secondary, entry, onOpenSession, onAction)
                                }
                            }
                        }
                    }
                }
            }
        }
        TaskContextMenu(
            entry = entry,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onOpenSession = onOpenSession,
            onAction = onAction,
        )
    }
}

/** The one filled primary pill of a card footer; danger/amber keep their tints. */
@Composable
private fun CardPrimaryPill(
    action: CardAction,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    val bg = when {
        action.danger -> colors.danger.copy(alpha = 0.14f)
        action.amber -> colors.warning.copy(alpha = 0.16f)
        else -> colors.accent
    }
    val fg = when {
        action.danger -> colors.danger
        action.amber -> colors.warning
        else -> colors.onAccent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(action.icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        Text(
            stringResource(action.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One round icon-only secondary of a card footer (web CardIconAction). */
@Composable
private fun CardIconAction(action: CardAction, enabled: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val tint = if (action.danger) colors.danger else colors.textSecondary
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(1.dp, colors.textPrimary.copy(alpha = 0.18f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            action.icon,
            contentDescription = stringResource(action.labelRes),
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
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

// region Detail sheet

/**
 * 批v160 W5: the web task-detail-sheet (task-detail-sheet.tsx), 1:1 where the
 * wire carries the data — agent-marked header with chip + ✕, a scroll body
 * (任务描述 → 结果 → zone panel → InfoGrid → 变更文件 → 推进记录 → diff) and a
 * FIXED footer of 查看会话 / 编辑 / 删除. Merging rows hide destructive actions
 * (server refuses them anyway); an archived row keeps exactly one way back.
 */
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
    // 批v151 全锁 + 固定头；W5 加固定底栏：滚动体 weight(1f)，头尾都钉死，
    // 推进记录再长也只滚中间。
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.94f)
            .sheetContentScrollLock()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Header (web 90-120): agent mark in a bordered tile, title, spinner,
        // chip, ✕ — the drag handle is this whole row.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.textPrimary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.SmartToy,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            if (spinning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            WebStatusChip(task)
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = colors.textSecondary,
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // 批v155: the task's own description (config.prompt_blocks) — what
            // the agent actually launches with.
            task.configPromptText?.takeIf { it.isNotBlank() }?.let { prompt ->
                DetailSection(stringResource(R.string.task_detail_prompt)) {
                    Text(
                        prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                    )
                }
            }
            // 结果 (web result block): the agent's own verdict text.
            task.resultSummary?.takeIf { it.isNotBlank() }?.let {
                DetailSection(stringResource(R.string.task_detail_summary)) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                    )
                }
            }
            DetailZonePanel(entry, busy, onAction)
            InfoGrid(entry, folderName)
            // The load failed: say so and offer the retry, instead of a blank
            // panel that looks like "no events / no files".
            detail?.loadError?.let { err ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        err,
                        color = colors.danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.task_action_retry), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            detail?.changedFiles?.takeIf { it.isNotEmpty() }?.let { files ->
                ChangedFilesSection(files)
            }
            detail?.events?.takeIf { it.isNotEmpty() }?.let { events ->
                DetailSection(stringResource(R.string.task_detail_events)) {
                    TaskTimeline(events)
                }
            }
            if (detail != null) {
                DiffSection(detail, onLoadDiff)
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
        // Fixed footer (web bottom bar): divider, 查看会话 + 编辑 left, 删除
        // right. 批v160 W5: 编辑 moves here from the action grid (web canEdit
        // = todo/failed — Android adds canceled, the server accepts it); 删除
        // keeps the server's no-merging rule and R5 P3-1's archived gate.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.textPrimary.copy(alpha = 0.08f)),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            task.conversationId?.let { convId ->
                TextButton(onClick = { onOpenSession(entry.serverId, convId) }) {
                    Icon(
                        Icons.Rounded.Chat,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.task_open_session), style = MaterialTheme.typography.labelMedium)
                }
            }
            val canEdit = !task.isArchivedLike &&
                (task.status == "todo" || task.status == "failed" || task.status == "canceled" || task.status == "cancelled")
            if (canEdit) {
                TextButton(onClick = { onAction(ConfirmAction.EDIT(entry)) }) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.task_action_edit), style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            // R3 P2-5: the server refuses to delete a merging row — wait for the
            // transient state to resolve instead of a guaranteed 4xx.
            if (task.status != "merging" && !task.isArchivedLike) {
                TextButton(onClick = { onAction(ConfirmAction.DELETE(entry)) }) {
                    Text(
                        stringResource(R.string.task_action_delete),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.danger,
                    )
                }
            }
        }
    }
}

/**
 * The zone panel (web detail-sheet zone): a bordered block that turns AMBER on
 * review — "the agent is waiting on YOU" — and carries the per-status actions
 * the wide sheet can afford. One filled primary per row; a trailing ghost rides
 * the same row pushed right (web filled/trailing split), the rest wrap below.
 */
@Composable
private fun DetailZonePanel(
    entry: TaskBoardEntry,
    busy: Boolean,
    onAction: (ConfirmAction) -> Unit,
) {
    val task = entry.task
    // merging: transient, nothing to offer — the panel vanishes for a beat.
    if (task.status == "merging") return
    val colors = CodegTheme.colors
    val amber = task.status == "review" && !isEffectivelyDeferred(task) && !task.isArchivedLike
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (amber) colors.warning.copy(alpha = 0.05f) else colors.textPrimary.copy(alpha = 0.03f))
            .border(
                1.dp,
                if (amber) colors.warning.copy(alpha = 0.30f) else colors.textPrimary.copy(alpha = 0.10f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            // R5 P3-1: an archived row's ONLY action is 取消归档 — the server
            // would happily retry/requeue it behind the user's back.
            task.isArchivedLike -> ZoneRow {
                ZoneFilled(
                    R.string.task_action_unarchive, Icons.Rounded.Unarchive, busy,
                    modifier = Modifier.weight(1f),
                ) { onAction(ConfirmAction.UNARCHIVE(entry)) }
            }
            task.status == "review" && isEffectivelyDeferred(task) -> ZoneRow {
                // Shelved review: picking it back up is the whole point.
                ZoneFilled(
                    R.string.task_action_follow_up, Icons.AutoMirrored.Rounded.Undo, busy,
                    modifier = Modifier.weight(1f),
                ) { onAction(ConfirmAction.RETURN(entry)) }
                ZoneGhost(R.string.task_action_undefer, Icons.Rounded.Inbox, busy) {
                    onAction(ConfirmAction.UNDEFER(entry))
                }
            }
            task.status == "review" -> {
                // Primary = the way out this task actually has: nothing changed
                // ⟹ nothing to merge (完成); a forge_pr task's merge is refused
                // server-side ⟹ 交付 PR (R3 P2-3); otherwise 合并. Trailing
                // 放弃 rides the same row pushed right (web parity).
                ZoneRow {
                    val primary = when {
                        (task.changedFileCount ?: -1) == 0 ->
                            Triple(R.string.task_action_complete, Icons.Rounded.CheckCircle, ConfirmAction.COMPLETE(entry))
                        task.fromPullRequest ->
                            Triple(R.string.task_action_deliver_pr, Icons.Rounded.CallMade, ConfirmAction.DELIVER_PR(entry))
                        else ->
                            Triple(R.string.task_action_merge, Icons.Rounded.CallMerge, ConfirmAction.MERGE(entry))
                    }
                    ZoneFilled(primary.first, primary.second, busy, modifier = Modifier.weight(1f)) {
                        onAction(primary.third)
                    }
                    ZoneGhost(R.string.task_action_abandon, Icons.Rounded.Block, busy, danger = true) {
                        onAction(ConfirmAction.CANCEL(entry))
                    }
                }
                ZoneRow {
                    // The alternative delivery path: a normal task can still be
                    // turned into a PR instead of merging (web offers both).
                    if (!task.fromPullRequest && (task.changedFileCount ?: -1) != 0) {
                        ZoneGhost(R.string.task_action_deliver_pr, Icons.Rounded.CallMade, busy) {
                            onAction(ConfirmAction.DELIVER_PR(entry))
                        }
                    }
                    ZoneGhost(R.string.task_action_follow_up, Icons.AutoMirrored.Rounded.Undo, busy) {
                        onAction(ConfirmAction.RETURN(entry))
                    }
                    ZoneGhost(R.string.task_action_defer, Icons.Rounded.AccessTime, busy) {
                        onAction(ConfirmAction.DEFER(entry))
                    }
                }
            }
            task.status == "todo" -> ZoneRow {
                // R1A: schedule/edit are todo-only on the server (CAS on
                // Status::Todo); web parity drops todo-取消 from the zone.
                ZoneFilled(
                    R.string.task_action_start, Icons.Rounded.PlayArrow, busy,
                    modifier = Modifier.weight(1f),
                ) { onAction(ConfirmAction.START(entry)) }
                ZoneGhost(R.string.task_action_schedule, Icons.Rounded.Schedule, busy) {
                    onAction(ConfirmAction.SCHEDULE(entry))
                }
            }
            task.status == "queued" || task.status == "preparing" ||
                task.status == "running" || task.status == "awaiting_input" -> ZoneRow {
                ZoneFilled(
                    R.string.task_action_cancel, Icons.Rounded.Block, busy,
                    danger = true, modifier = Modifier.weight(1f),
                ) { onAction(ConfirmAction.CANCEL(entry)) }
            }
            task.status == "failed" -> {
                // R1A: no requeue — the server requeue path accepts canceled
                // rows only; failed's way out is 重试.
                ZoneRow {
                    ZoneFilled(
                        R.string.task_action_retry, Icons.Rounded.Replay, busy,
                        modifier = Modifier.weight(1f),
                    ) { onAction(ConfirmAction.RETRY(entry)) }
                    if (isEffectivelyDeferred(task)) {
                        ZoneGhost(R.string.task_action_undefer, Icons.Rounded.Inbox, busy) {
                            onAction(ConfirmAction.UNDEFER(entry))
                        }
                    } else {
                        ZoneGhost(R.string.task_action_defer, Icons.Rounded.AccessTime, busy) {
                            onAction(ConfirmAction.DEFER(entry))
                        }
                    }
                }
                ZoneRow {
                    ZoneGhost(R.string.task_action_archive, Icons.Rounded.Archive, busy) {
                        onAction(ConfirmAction.ARCHIVE(entry))
                    }
                }
            }
            task.status == "done" -> ZoneRow {
                ZoneFilled(
                    R.string.task_action_archive, Icons.Rounded.Archive, busy,
                    modifier = Modifier.weight(1f),
                ) { onAction(ConfirmAction.ARCHIVE(entry)) }
            }
            task.status == "canceled" || task.status == "cancelled" -> ZoneRow {
                ZoneFilled(
                    R.string.task_action_requeue, Icons.Rounded.Replay, busy,
                    modifier = Modifier.weight(1f),
                ) { onAction(ConfirmAction.REQUEUE(entry)) }
                ZoneGhost(R.string.task_action_archive, Icons.Rounded.Archive, busy) {
                    onAction(ConfirmAction.ARCHIVE(entry))
                }
            }
        }
    }
}

@Composable
private fun ZoneRow(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

/** The zone's solid primary (web `filled`): accent on-accent, 40dp tall. */
@Composable
private fun ZoneFilled(
    labelRes: Int,
    icon: ImageVector,
    enabled: Boolean,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    val bg = if (danger) colors.danger else colors.accent
    val fg = if (danger) colors.onAccent else colors.onAccent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
        )
    }
}

/** The zone's outlined secondary (web `ghost`); danger keeps a red label. */
@Composable
private fun ZoneGhost(
    labelRes: Int,
    icon: ImageVector,
    enabled: Boolean,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    val tint = if (danger) colors.danger else colors.textSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.textPrimary.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * Metadata grid (web InfoGrid): 补齐 web 的 分支 `work ← base`、变更计数、
 * 开始/完成时间 — the lifecycle timestamps this wire only started carrying in
 * W5. Grid stays string-based; the colored ±stats live on the card/footer.
 */
@Composable
private fun InfoGrid(entry: TaskBoardEntry, folderName: String?) {
    val task = entry.task
    val colors = CodegTheme.colors
    val branchValue = when {
        task.workBranch != null && task.baseBranch != null -> "${task.workBranch} ← ${task.baseBranch}"
        else -> task.workBranch ?: task.baseBranch ?: "—"
    }
    val changesValue = if ((task.changedFileCount ?: 0) > 0) {
        "${task.changedFileCount} · +${task.additions ?: 0} -${task.deletions ?: 0}"
    } else {
        null
    }
    val rows = listOfNotNull(
        stringResource(R.string.task_detail_device) to entry.serverName,
        stringResource(R.string.task_detail_project) to (folderName ?: "#${task.folderId}"),
        stringResource(R.string.task_detail_agent) to (task.agentType ?: "—"),
        stringResource(R.string.task_detail_branch) to branchValue,
        changesValue?.let { stringResource(R.string.task_detail_changes) to it },
        stringResource(R.string.task_detail_created) to (relativeLabel(task.createdAt) ?: "—"),
        task.startedAt?.let { stringResource(R.string.task_detail_started) to (relativeLabel(it) ?: it) },
        (task.finishedAt ?: task.settledAt)?.let {
            stringResource(R.string.task_detail_finished) to (relativeLabel(it) ?: it)
        },
        stringResource(R.string.task_detail_updated) to (relativeLabel(task.updatedAt) ?: "—"),
        task.scheduledAt?.let { stringResource(R.string.task_detail_scheduled) to (relativeLabel(it) ?: it) },
        stringResource(R.string.task_detail_task_id) to "#${task.id}",
    )
    Column(Modifier.padding(top = 10.dp)) {
        rows.forEach { (label, value) ->
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

// endregion

/** 变更文件 (web file list): mono path, +adds green / -dels red, capped at 20. */
@Composable
private fun ChangedFilesSection(files: List<WorkTaskChangedFile>) {
    val colors = CodegTheme.colors
    DetailSection(stringResource(R.string.task_detail_files)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            files.take(20).forEach { file ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        file.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    file.additions?.takeIf { it > 0 }?.let {
                        Text("+$it", style = MaterialTheme.typography.labelSmall, color = colors.success)
                    }
                    file.deletions?.takeIf { it > 0 }?.let {
                        Text("-$it", style = MaterialTheme.typography.labelSmall, color = colors.danger)
                    }
                }
            }
        }
    }
}

/** status_changed head dot color (web timeline): live=accent, attention=amber,
 *  failed=red, done=green, the rest muted. */
private fun statusDotColor(status: String?, colors: CodegColors): Color = when (status) {
    "queued", "preparing", "running" -> colors.accent
    "awaiting_input", "review", "merging" -> colors.warning
    "failed" -> colors.danger
    "done" -> colors.success
    else -> colors.textSecondary.copy(alpha = 0.5f)
}

/** Localized label for an event kind; the raw kind falls back as-is. */
@Composable
private fun taskEventLabel(kind: String): String = when (kind) {
    "created" -> stringResource(R.string.task_event_created)
    "status_changed" -> stringResource(R.string.task_event_status_changed)
    "config_effective" -> stringResource(R.string.task_event_config_effective)
    "init_command" -> stringResource(R.string.task_event_init_command)
    "agent_progress" -> stringResource(R.string.task_event_agent_progress)
    "agent_verdict" -> stringResource(R.string.task_event_agent_verdict)
    "merge_attempt" -> stringResource(R.string.task_event_merge_attempt)
    "merge_queued" -> stringResource(R.string.task_event_merge_queued)
    "deliver_attempt" -> stringResource(R.string.task_event_deliver_attempt)
    "delivered_pr" -> stringResource(R.string.task_event_delivered_pr)
    "merge_conflict" -> stringResource(R.string.task_event_merge_conflict)
    "preflight_result" -> stringResource(R.string.task_event_preflight)
    "cleanup_failed" -> stringResource(R.string.task_event_cleanup_failed)
    "resume_fallback" -> stringResource(R.string.task_event_resume_fallback)
    "context_compact" -> stringResource(R.string.task_event_context_compact)
    "user_action" -> stringResource(R.string.task_event_user_action)
    "diff_stat" -> stringResource(R.string.task_event_diff_stat)
    "forge_writeback" -> stringResource(R.string.task_event_forge_writeback)
    "forge_writeback_failed" -> stringResource(R.string.task_event_forge_writeback_failed)
    else -> kind
}

/**
 * 推进记录 (web timeline, 1:1 where the wire carries it): status_changed reads
 * as a colored dot head (target status + its note), every other event a rail
 * row with a localized kind label, summary, and the time on the right.
 */
@Composable
private fun TaskTimeline(events: List<WorkTaskEvent>) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        events.take(20).forEach { event ->
            if (event.kind == "status_changed") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusDotColor(event.to, colors)),
                    )
                    Text(
                        event.to?.let { to ->
                            taskStatusLabelRes(to)?.let { stringResource(it) } ?: to
                        } ?: (event.summary ?: taskEventLabel(event.kind)),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    event.summary?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } ?: Spacer(Modifier.weight(1f))
                    Text(
                        relativeLabel(event.createdAt) ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colors.textPrimary.copy(alpha = 0.10f)),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            taskEventLabel(event.kind),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        event.summary?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        relativeLabel(event.createdAt) ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

/** Diff block — unchanged from 批v155, lifted out of the old TaskDetailBody. */
@Composable
private fun DiffSection(detail: TaskHubViewModel.TaskDetail, onLoadDiff: () -> Unit) {
    val colors = CodegTheme.colors
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
