package app.codeg.android.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
 * The idea inbox + dispatch wizard (batch v140). Lives next to the task board in
 * the Activity tab's mode toggle; ideas are local drafts and dispatch is the only
 * bridge to the servers' native work-task API.
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

@Composable
private fun IdeaCard(
    idea: IdeaEntry,
    onDispatch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = CodegTheme.colors.bgElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                idea.title.ifBlank { stringResource(R.string.inbox_untitled) },
                style = MaterialTheme.typography.titleSmall,
                color = CodegTheme.colors.textPrimary,
            )
            if (idea.description.isNotBlank()) {
                Text(
                    idea.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = CodegTheme.colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                formatStamp(idea.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = CodegTheme.colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onDispatch, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.inbox_dispatch), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.inbox_edit), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("idea", "${idea.title}\n${idea.description}"))
                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(R.string.inbox_copy), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(
                        stringResource(R.string.inbox_delete),
                        style = MaterialTheme.typography.labelMedium,
                        color = CodegTheme.colors.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnknownRefCard(
    ref: DispatchRef,
    probing: Boolean,
    onProbe: () -> Unit,
    onDiscard: () -> Unit,
    onRedispatch: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CodegTheme.colors.danger.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(R.string.dispatch_unknown),
                style = MaterialTheme.typography.titleSmall,
                color = CodegTheme.colors.danger,
            )
            Text(
                "${ref.title} · ${ref.serverName.ifBlank { ref.serverId }} · ${ref.folderName.ifBlank { "#${ref.folderId}" }}",
                style = MaterialTheme.typography.bodySmall,
                color = CodegTheme.colors.textSecondary,
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
                TextButton(onClick = onRedispatch, enabled = !probing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.dispatch_redispatch), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onDiscard, enabled = !probing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.dispatch_unknown_discard), style = MaterialTheme.typography.labelMedium)
                }
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

/** Step selector inside the dispatch sheet — 0 device, 1 project, 2 agent, 3 schedule. */
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
    var title by rememberSaveable("t") { mutableStateOf(idea.title) }
    var description by rememberSaveable("d") { mutableStateOf(idea.description) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var scheduleMillis by rememberSaveable { mutableStateOf(0L) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
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

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose) {
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

            if (device != null && target.foldersLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
            // 三列并排滚轮选择器（手机时钟风格）：每列独立上下滑动、松手自动
            // 吸附，中间项放大高亮、上下项缩小渐隐，纯滚动无按钮样式。
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
            if (device != null && !target.foldersLoading) {
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
                        viewModel.dispatch(
                            idea = idea,
                            serverId = dev,
                            folderId = fid,
                            folderName = target.folders.firstOrNull { it.id == fid }?.name.orEmpty(),
                            agentType = agent,
                            title = title,
                            description = description,
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
