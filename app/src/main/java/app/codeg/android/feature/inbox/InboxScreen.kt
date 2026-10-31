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
    var editing by remember { mutableStateOf<IdeaEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var dispatching by remember { mutableStateOf<IdeaEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<IdeaEntry?>(null) }
    // UNKNOWN cards: redispatch/discard are destructive or duplicating actions,
    // so they route through a confirmation dialog first.
    var confirmRef by remember { mutableStateOf<DispatchRef?>(null) }
    var confirmRedispatch by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        },
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
                ui.pendingRefs.forEach { ref ->
                    item(key = "pending-${ref.requestId}", contentType = { "unknown-ref" }) {
                        UnknownRefCard(
                            ref = ref,
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
                        onDispatch = { dispatching = idea },
                        onEdit = { editing = idea },
                        onDelete = { pendingDelete = idea },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        IdeaEditorDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { title, desc ->
                viewModel.saveIdea(editing?.id, title, desc, source = editing)
                creating = false
                editing = null
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
            onClose = { dispatching = null },
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
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onProbe, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.dispatch_unknown_check), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onRedispatch, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.dispatch_redispatch), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onDiscard, contentPadding = PaddingValues(horizontal = 8.dp)) {
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

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel(stringResource(R.string.dispatch_device))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                target.profiles.forEach { profile ->
                    FilterChip(
                        selected = device == profile.id,
                        onClick = {
                            device = profile.id
                            folderId = null
                            agent = null
                            viewModel.loadTarget(profile.id)
                        },
                        label = { Text(profile.name) },
                    )
                }
            }
            if (device != null && target.foldersLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            target.error?.let {
                Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
            }
            if (device != null && !target.foldersLoading) {
                SectionLabel(stringResource(R.string.dispatch_project))
                if (target.folders.isEmpty()) {
                    Text(stringResource(R.string.dispatch_no_folder), style = MaterialTheme.typography.bodySmall, color = CodegTheme.colors.textSecondary)
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    target.folders.forEach { folder ->
                        FilterChip(
                            selected = folderId == folder.id,
                            onClick = { folderId = folder.id },
                            label = { Text(folder.name, maxLines = 1) },
                        )
                    }
                }
                SectionLabel(stringResource(R.string.dispatch_agent))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    target.agents.forEach { candidate ->
                        FilterChip(
                            selected = agent == candidate,
                            onClick = { agent = if (agent == candidate) null else candidate },
                            label = { Text(candidate.displayName) },
                        )
                    }
                }
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
                ui.dispatchError?.let {
                    Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
                }
                val canDispatch = device != null && folderId != null && title.isNotBlank() && !ui.dispatching
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
            text = { Text("${ref.serverName} · ${ref.title}") },
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
