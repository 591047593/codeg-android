package app.codeg.android.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.feature.common.WheelColumn
import app.codeg.android.R
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The idea inbox + dispatch wizard (batch v140, rebuilt 批v145 阶段4+5). Ideas
 * are local drafts in a lightweight memo-style card (time + ⋯ menu + one 派发
 * action); dispatch runs the shared pipeline with the 任务书 §6.2 form order:
 * 设备与 Agent → 任务描述 → 目标（项目 + 分支） → 创建. An idea leaves the
 * inbox only when the server CONFIRMS the create; an UNKNOWN ref may only
 * re-dispatch after a probe verified "not created".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(viewModel: InboxViewModel = hiltViewModel(key = "inbox")) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val target by viewModel.target.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    // Saveable (ids, not objects): a rotation must not close the editor/sheet —
    // the text inside is saveable too, and losing it mid-typing loses work.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var dispatchingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<IdeaEntry?>(null) }
    val editing = editingId?.let { id -> ui.ideas.firstOrNull { it.id == id } }
    val dispatching = dispatchingId?.let { id -> ui.ideas.firstOrNull { it.id == id } }
    // UNKNOWN cards: redispatch/discard are destructive or duplicating actions,
    // so they route through a confirmation dialog first.
    var confirmRef by remember { mutableStateOf<DispatchRef?>(null) }
    var confirmRedispatch by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.inbox_new))
            }
        },
    ) { padding ->
        if (ui.ideas.isEmpty() && ui.pendingRefs.isEmpty() && ui.confirmedRefs.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    icon = Icons.Rounded.Add,
                    title = stringResource(R.string.inbox_empty),
                    message = "",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // probe/redispatch fire from the cards below with no sheet open —
                // without this line their failures ("检查失败…勿直接重派") would
                // be set on the VM but never rendered anywhere.
                ui.dispatchError?.takeIf { dispatching == null }?.let { error ->
                    item(key = "dispatch-error") {
                        Text(
                            error,
                            color = colors.danger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ui.inFlightRefs.forEach { ref ->
                    item(key = "inflight-${ref.requestId}", contentType = { "pending-ref" }) {
                        // Display only: the request may still be on the wire —
                        // offering redispatch/discard here would invite a double
                        // create. The age-gated sweep promotes it to UNKNOWN if
                        // the receipt never lands.
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(
                                    stringResource(R.string.dispatch_pending_note),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textSecondary,
                                )
                                Text(
                                    "${ref.title} · ${ref.serverName.ifBlank { ref.serverId }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
                ui.pendingRefs.forEach { ref ->
                    item(key = "pending-${ref.requestId}", contentType = { "unknown-ref" }) {
                        UnknownRefCard(
                            ref = ref,
                            probing = ref.requestId in ui.probingIds,
                            canRedispatch = ref.requestId in ui.probeClearedIds,
                            onProbe = { viewModel.probe(ref.requestId) },
                            onDiscard = { confirmRef = ref; confirmRedispatch = false },
                            onRedispatch = { confirmRef = ref; confirmRedispatch = true },
                        )
                    }
                }
                ui.confirmedRefs.forEach { ref ->
                    item(key = "done-${ref.requestId}", contentType = { "ref" }) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Text(
                                stringResource(R.string.dispatch_ok, ref.remoteTodoId ?: 0) +
                                    " · ${ref.serverName} · ${ref.title}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                            )
                        }
                    }
                }
                items(ui.ideas, key = { it.id }, contentType = { "idea" }) { idea ->
                    IdeaCard(
                        idea = idea,
                        onDispatch = { dispatchingId = idea.id },
                        onEdit = { editingId = idea.id },
                        onCopy = { context ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("idea", "${idea.title}\n${idea.description}"))
                        },
                        onDelete = { pendingDelete = idea },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        IdeaEditorDialog(
            initial = editing,
            onDismiss = { creating = false; editingId = null },
            onSave = { title, desc ->
                viewModel.saveIdea(editing?.id, title, desc, source = editing)
                creating = false
                editingId = null
            },
        )
    }

    pendingDelete?.let { idea ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.inbox_delete)) },
            text = { Text(idea.title) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteIdea(idea.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.inbox_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    dispatching?.let { idea ->
        // Stale errors from a previous session must not greet the next dispatch.
        LaunchedEffect(idea.id) { viewModel.clearDispatchError() }
        DispatchSheet(
            idea = idea,
            viewModel = viewModel,
            target = target,
            ui = ui,
            onClose = { dispatchingId = null },
        )
    }

    confirmRef?.let { ref ->
        AlertDialog(
            onDismissRequest = { confirmRef = null },
            title = { Text(stringResource(R.string.dispatch_unknown)) },
            text = {
                Text(
                    stringResource(
                        if (confirmRedispatch) R.string.dispatch_unknown_confirm_redispatch
                        else R.string.dispatch_unknown_confirm_discard,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmRedispatch) viewModel.redispatch(ref, null)
                    else viewModel.discard(ref.requestId)
                    confirmRef = null
                }) { Text(stringResource(R.string.dispatch_unknown_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRef = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/**
 * Memo-style idea card (任务书 §5.2): time top-left, ⋯ menu top-right
 * (编辑/复制/删除, delete confirmed by the caller), one prominent 派发 action
 * at the bottom. Description truncates at 3 lines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeaCard(
    idea: IdeaEntry,
    onDispatch: () -> Unit,
    onEdit: () -> Unit,
    onCopy: (Context) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatStamp(idea.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_edit)) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        val context = LocalContext.current
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_copy)) },
                            onClick = { menuOpen = false; onCopy(context) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_delete), color = colors.danger) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            Text(
                idea.title.ifBlank { stringResource(R.string.inbox_untitled) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (idea.description.isNotBlank()) {
                Text(
                    idea.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDispatch)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.inbox_dispatch),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent,
                )
            }
        }
    }
}

/**
 * UNKNOWN dispatch card: 检查服务器 always available; 重新派发 unlocks ONLY
 * after a probe verified "not created" (任务书 §5.4 未核实不能再次创建).
 */
@Composable
private fun UnknownRefCard(
    ref: DispatchRef,
    probing: Boolean,
    canRedispatch: Boolean,
    onProbe: () -> Unit,
    onDiscard: () -> Unit,
    onRedispatch: () -> Unit,
) {
    val colors = CodegTheme.colors
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.danger.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(R.string.dispatch_unknown),
                style = MaterialTheme.typography.titleSmall,
                color = colors.danger,
            )
            Text(
                "${ref.title} · ${ref.serverName.ifBlank { ref.serverId }} · ${ref.folderName.ifBlank { "#${ref.folderId}" }}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (probing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                TextButton(onClick = onProbe, enabled = !probing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.dispatch_unknown_check), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onRedispatch,
                    enabled = !probing && canRedispatch,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.dispatch_redispatch),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!probing && canRedispatch) colors.textPrimary else colors.textTertiary,
                    )
                }
                TextButton(onClick = onDiscard, enabled = !probing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.dispatch_unknown_discard), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!canRedispatch) {
                Text(
                    stringResource(R.string.dispatch_unknown_need_probe),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun IdeaEditorDialog(
    initial: IdeaEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by rememberSaveable(initial?.id ?: "new") { mutableStateOf(initial?.title.orEmpty()) }
    var description by rememberSaveable(initial?.id ?: "new") { mutableStateOf(initial?.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.inbox_new else R.string.inbox_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.inbox_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.inbox_desc_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, description) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * The unified dispatch panel, reordered to the 任务书 §6.2 flow:
 * ① 设备与 Agent（读取目标设备真实 Agent 列表；获取失败只降级不白屏）
 * ② 任务描述（想法自动填充）
 * ③ 目标：项目 + 分支（项目切换后分支重载；上游不支持的字段绝不假装支持——
 *    分支走真实 git_list_all_branches + base_branch，加载失败可仍按项目默认派发）
 * ④ 创建：防重复点击 + 明确结果反馈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DispatchSheet(
    idea: IdeaEntry,
    viewModel: InboxViewModel,
    target: DispatchTargets,
    ui: InboxViewModel.UiState,
    onClose: () -> Unit,
) {
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<Int?>(null) }
    var agent by rememberSaveable { mutableStateOf<AgentType?>(null) }
    var branch by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable("t") { mutableStateOf(idea.title) }
    var description by rememberSaveable("d") { mutableStateOf(idea.description) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var scheduleMillis by rememberSaveable { mutableStateOf(0L) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var branchSheet by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DispatchRef?>(null) }
    // Composition-time "is the schedule in the past" goes stale as the wall
    // clock advances (no state change → no recompose → button stays enabled).
    // A 30s ticker keeps the guard honest for as long as the sheet is open.
    var clockTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            clockTick = System.currentTimeMillis()
        }
    }

    val folder = target.folders.firstOrNull { it.id == folderId }
    // Function-scope so the branch picker sheet (outside the panel) sees it too:
    // a picked branch only counts while it belongs to the CURRENT device+repo.
    val branchFresh = ui.branchFolderKey == (device?.let { d -> folder?.let { f -> "$d:${f.path}" } })
    // Project (or device) switched → the picked branch no longer describes the
    // new repo; reset and reload (§6.2 项目切换后重新加载分支).
    LaunchedEffect(device, folderId) {
        branch = null
        val d = device
        if (d != null && folder != null) viewModel.loadBranches(d, folder)
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 派发默认预选：settings 里存好的设备/项目/Agent（设置→通用→派发
            // 默认选项）在 sheet 打开时自动选中；设备切换后项目/Agent 回到默认。
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            LaunchedEffect(target.profiles) {
                if (device == null && settings.dispatchDefaultDevice.isNotBlank() &&
                    target.profiles.any { it.id == settings.dispatchDefaultDevice }
                ) {
                    device = settings.dispatchDefaultDevice
                    folderId = null
                    agent = null
                    viewModel.loadTarget(settings.dispatchDefaultDevice)
                }
            }
            LaunchedEffect(target.folders, target.agents) {
                if (device == null) return@LaunchedEffect
                if (folderId == null && settings.dispatchDefaultFolderId > 0 &&
                    target.folders.any { it.id == settings.dispatchDefaultFolderId }
                ) {
                    folderId = settings.dispatchDefaultFolderId
                }
                if (agent == null && settings.dispatchDefaultAgent.isNotBlank()) {
                    val wire = AgentType.fromWire(settings.dispatchDefaultAgent)
                    if (wire != AgentType.UNKNOWN && (target.agents.isEmpty() || wire in target.agents)) {
                        agent = wire
                    }
                }
            }

            // ① 设备与 Agent — the agent list IS the device's real one
            // (acp_list_agents filtered to available+enabled); a failed list
            // degrades to a short inline hint + retry, never a white panel
            // (任务书 §6.3: raw ACP errors stay out of the UI).
            SectionLabel(stringResource(R.string.dispatch_section_device_agent))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dispatch_device),
                        style = MaterialTheme.typography.labelSmall,
                        color = CodegTheme.colors.textTertiary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    WheelColumn(
                        items = target.profiles.map { it.name },
                        selectedIndex = target.profiles.indexOfFirst { it.id == device }.coerceAtLeast(0),
                        onSelect = { idx ->
                            val profile = target.profiles.getOrNull(idx) ?: return@WheelColumn
                            if (profile.id != device) {
                                device = profile.id
                                folderId = null
                                agent = null
                                viewModel.loadTarget(profile.id)
                            }
                        },
                        emptyText = stringResource(R.string.dispatch_no_device),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dispatch_agent),
                        style = MaterialTheme.typography.labelSmall,
                        color = CodegTheme.colors.textTertiary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    // First wheel row = 自动 (no agent override; folder default applies).
                    val autoAgentLabel = stringResource(R.string.dispatch_agent_auto)
                    val agentOptions = remember(target.agents, autoAgentLabel) {
                        listOf(autoAgentLabel) + target.agents.map { it.displayName }
                    }
                    WheelColumn(
                        items = agentOptions,
                        selectedIndex = (if (agent == null) 0 else target.agents.indexOf(agent) + 1)
                            .coerceIn(0, agentOptions.lastIndex),
                        onSelect = { idx -> agent = if (idx <= 0) null else target.agents.getOrNull(idx - 1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (agent == null) {
                Text(
                    stringResource(R.string.dispatch_agent_auto_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = CodegTheme.colors.textTertiary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (device != null && target.foldersLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                if (target.agentsError) {
                    Text(
                        stringResource(R.string.dispatch_agents_failed),
                        color = CodegTheme.colors.danger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { device?.let { viewModel.loadTarget(it) } }) {
                        Text(stringResource(R.string.dispatch_retry))
                    }
                }
            }
            target.error?.let {
                Text(
                    it,
                    color = CodegTheme.colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    // Tappable retry: the only alternative was re-tapping the
                    // already-selected device chip, which reads as a no-op.
                    modifier = Modifier.clickable { device?.let { d -> viewModel.loadTarget(d) } },
                )
            }

            // ② 任务描述 — prefilled from the idea; multi-line, scrollable.
            SectionLabel(stringResource(R.string.dispatch_section_description))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.inbox_title_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.inbox_desc_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            // ③ 目标：项目 + 分支.
            SectionLabel(stringResource(R.string.dispatch_section_target))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dispatch_project),
                        style = MaterialTheme.typography.labelSmall,
                        color = CodegTheme.colors.textTertiary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    WheelColumn(
                        items = target.folders.map { it.name },
                        selectedIndex = target.folders.indexOfFirst { it.id == folderId }.coerceAtLeast(0),
                        onSelect = { idx -> folderId = target.folders.getOrNull(idx)?.id },
                        emptyText = stringResource(
                            if (target.foldersLoading) R.string.dispatch_defaults_loading else R.string.dispatch_no_folder,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // Branch selector: real branch list for the picked project's repo
            // path; failure keeps dispatching possible on the project default.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CodegTheme.colors.bgElevated, RoundedCornerShape(10.dp))
                    .clickable(enabled = folder != null && !ui.branchesLoading) {
                        val f = folder
                        val d = device
                        if (ui.branchesError && d != null && f != null) viewModel.loadBranches(d, f)
                        else branchSheet = true
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .heightIn(min = 40.dp),
            ) {
                Text(
                    stringResource(R.string.dispatch_branch),
                    style = MaterialTheme.typography.labelMedium,
                    color = CodegTheme.colors.textTertiary,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    when {
                        ui.branchesLoading -> stringResource(R.string.dispatch_branch_loading)
                        branch != null && branchFresh -> branch!!
                        folder?.gitBranch != null ->
                            stringResource(R.string.dispatch_branch_follow, folder.gitBranch!!)
                        else -> stringResource(R.string.dispatch_branch_follow_unknown)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (branch != null && branchFresh) CodegTheme.colors.textPrimary else CodegTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (ui.branchesLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = CodegTheme.colors.textSecondary,
                    )
                }
            }
            if (ui.branchesError && !ui.branchesLoading) {
                Text(
                    stringResource(R.string.dispatch_branch_failed),
                    color = CodegTheme.colors.danger,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            // ④ 创建 — schedule, guards, result feedback (unchanged semantics).
            SectionLabel(stringResource(R.string.dispatch_schedule))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = scheduled, onCheckedChange = { scheduled = it })
                Text(
                    stringResource(if (scheduled) R.string.dispatch_schedule_set else R.string.dispatch_schedule_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = CodegTheme.colors.textSecondary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(enabled = scheduled) { showDatePicker = true },
                )
                if (scheduled && scheduleMillis > 0) {
                    Text(
                        remember(scheduleMillis) {
                            Instant.ofEpochMilli(scheduleMillis).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        } + " (" + stringResource(R.string.dispatch_schedule_note) + ")",
                        style = MaterialTheme.typography.labelSmall,
                        color = CodegTheme.colors.textPrimary,
                    )
                }
            }
            // Suppressed once the success dialog is up: a schedule/auto-start
            // note rides the same field and would render in error styling
            // behind the dialog.
            if (result == null) ui.dispatchError?.let {
                Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
            }
            // Murphy guard: a schedule switch without a time would silently
            // dispatch as IMMEDIATE, and a past time would fire right away —
            // both look "scheduled" to the user. Block the button and say why.
            val scheduleNotSet = scheduled && scheduleMillis <= 0L
            val scheduleInPast = scheduled && scheduleMillis in 1..clockTick
            if (scheduleNotSet) {
                Text(
                    stringResource(R.string.dispatch_schedule_not_set),
                    color = CodegTheme.colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (scheduleInPast) {
                Text(
                    stringResource(R.string.dispatch_schedule_past),
                    color = CodegTheme.colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val canDispatch = device != null && folderId != null && title.isNotBlank() &&
                !ui.dispatching && !scheduleNotSet && !scheduleInPast
            TextButton(
                onClick = {
                    val dev = device ?: return@TextButton
                    val fid = folderId ?: return@TextButton
                    // A branch picked against a stale folder list must never ride
                    // along — the branchFresh key pins it to the current repo.
                    val effectiveBranch = if (branch != null && branchFresh) branch else null
                    viewModel.dispatch(
                        idea = idea,
                        serverId = dev,
                        folderId = fid,
                        folderName = target.folders.firstOrNull { it.id == fid }?.name.orEmpty(),
                        agentType = agent,
                        title = title,
                        description = description,
                        baseBranch = effectiveBranch,
                        scheduleLocalMillis = if (scheduled && scheduleMillis > 0) scheduleMillis else null,
                        onDone = { ref -> result = ref },
                    )
                },
                enabled = canDispatch,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.dispatching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.dispatch_button))
            }
        }
    }

    if (branchSheet) {
        ModalBottomSheet(onDismissRequest = { branchSheet = false }) {
            val listState = rememberLazyListState()
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.dispatch_pick_branch),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                LazyColumn(state = listState) {
                    item(key = "default") {
                        BranchOptionRow(
                            label = folder?.gitBranch
                                ?.let { stringResource(R.string.dispatch_branch_follow, it) }
                                ?: stringResource(R.string.dispatch_branch_follow_unknown),
                            selected = branch == null,
                        ) { branch = null; branchSheet = false }
                    }
                    items(ui.branches, key = { "b-$it" }) { name ->
                        BranchOptionRow(
                            label = name,
                            selected = branch == name && branchFresh,
                        ) { branch = name; branchSheet = false }
                    }
                }
            }
        }
    }

    result?.let { ref ->
        AlertDialog(
            onDismissRequest = { result = null; onClose() },
            title = { Text(stringResource(R.string.dispatch_ok, ref.remoteTodoId ?: 0)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${ref.serverName} · ${ref.title}")
                    ui.dispatchError?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = CodegTheme.colors.textSecondary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { result = null; onClose() }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        showDatePicker = false
                        showTimePicker = true
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }
    if (showTimePicker) {
        val now = LocalTime.now()
        val state = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.dispatch_schedule)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickedDate ?: return@TextButton
                    scheduleMillis = date.atTime(LocalTime.of(state.hour, state.minute))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    showTimePicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun BranchOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = CodegTheme.colors.textPrimary,
    )
}

private fun formatStamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
