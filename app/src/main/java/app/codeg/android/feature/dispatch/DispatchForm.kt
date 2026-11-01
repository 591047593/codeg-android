package app.codeg.android.feature.dispatch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.designsystem.component.AgentChipRow
import app.codeg.android.core.designsystem.component.DeviceChipRow
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What [DispatchForm] reads from its host, beyond [DispatchTargets]. Hosts are
 * ViewModels with DIFFERENT dispatch pipelines (§1.5 双宿主):
 *
 *  - 宿主 A [app.codeg.android.feature.inbox.InboxViewModel] — idea dispatch with
 *    the UNKNOWN ledger (probe / redispatch machinery stays there).
 *  - 宿主 B [app.codeg.android.feature.quick.QuickActionViewModel] — the ball's
 *    quick task path; an ambiguous submit lands in the SAME global ledger and is
 *    resolved from the inbox tab's cards.
 *
 * The form is state+callback driven and holds NO pipeline of its own: it never
 * talks to [app.codeg.android.core.data.DispatchService] directly.
 */
interface DispatchFormHost {
    val targets: StateFlow<DispatchTargets>
    val formUi: StateFlow<DispatchFormUi>
    val settings: StateFlow<AppSettings>

    fun loadTarget(serverId: String)
    fun loadBranches(serverId: String, folder: FolderDetail)

    /** Fire the pipeline; [onDone] delivers the ledger ref (PENDING included). */
    fun dispatch(submit: DispatchFormSubmit, onDone: (DispatchRef) -> Unit)
}

/** Form-level host state: dispatch progress, errors, and the branch list. */
data class DispatchFormUi(
    val dispatching: Boolean = false,
    val error: String? = null,
    val branches: List<String> = emptyList(),
    val branchesLoading: Boolean = false,
    val branchesError: Boolean = false,
    /** serverId:folderPath key the branch list belongs to — a stale list must
     *  never be offered for a different project (v146 invariant). */
    val branchFolderKey: String? = null,
)

/** Everything the form submits, resolved. The host owns guards beyond this. */
data class DispatchFormSubmit(
    val ideaId: String? = null,
    val serverId: String,
    val folderId: Int,
    val folderName: String,
    val agentType: AgentType?,
    val title: String,
    val description: String,
    val baseBranch: String?,
    val scheduleLocalMillis: Long?,
    val sessionMode: Boolean,
    val autoStart: Boolean = true,
)

/**
 * The unified dispatch form (ui重构 v2 §1.5, doc1 §6.1 field order):
 * 执行方式 → 设备 chips → Agent chips → 项目 → 分支(任务) → 标题 → 描述 →
 * 定时(任务) → 派发摘要卡 → 底部主按钮（创建会话 / 创建任务）。
 *
 * All selectors are chips + BottomSheet (§六); the wheel era is gone. The form
 * is self-contained but the SHEET hosting it must keep the v148 drag lock, and
 * the RESULT dialog renders at screen level (v146 B-7 — it must survive the
 * sheet unmounting when a confirmed dispatch deletes the idea).
 *
 * v146 invariant carry-over (each marked at its site below): process-death
 * restore LaunchedEffects, branchFresh keying, past-schedule 30s ticker,
 * rememberSaveable keys, defaults preseed, canDispatch gate. The stale-folder
 * dispatch check and the UNKNOWN triple gate live in the HOSTS (both re-check
 * against real state at send time).
 */
@Composable
fun DispatchForm(
    host: DispatchFormHost,
    modifier: Modifier = Modifier,
    /** Host A (inbox) opens in session mode; host B (ball) opens in task mode. */
    defaultSessionMode: Boolean = true,
    /** Seed values for the title/description fields (host A prefills from the
     *  idea being dispatched; host B starts blank). Sheet-lifetime state — the
     *  sheet is disposed between opens, so a plain init matches the old sheet. */
    initialTitle: String = "",
    initialDescription: String = "",
    /** Ledger ref of an accepted submit — the screen renders the result dialog. */
    onResult: (DispatchRef) -> Unit = {},
) {
    val colors = CodegTheme.colors
    val targets by host.targets.collectAsStateWithLifecycle()
    val ui by host.formUi.collectAsStateWithLifecycle()
    val settings by host.settings.collectAsStateWithLifecycle()

    // rememberSaveable ids only (never objects): a rotation/process death keeps
    // the selection while the target lists reload from the host (v146).
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<Int?>(null) }
    var agent by rememberSaveable { mutableStateOf<AgentType?>(null) }
    var branch by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionMode by rememberSaveable { mutableStateOf(defaultSessionMode) }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var scheduleMillis by rememberSaveable { mutableStateOf(0L) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var folderSheet by remember { mutableStateOf(false) }
    var branchSheet by remember { mutableStateOf(false) }
    // Result of THIS composition's submit; suppresses the error line once the
    // screen-level dialog is up (same rule as the pre-rebuild sheet).
    var result by remember { mutableStateOf<DispatchRef?>(null) }

    // 30s ticker (v146 invariant): "schedule in the past" goes stale as the wall
    // clock advances with no state change — the button must not stay enabled.
    var clockTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            clockTick = System.currentTimeMillis()
        }
    }

    val folder = targets.folders.firstOrNull { it.id == folderId }
    // branchFresh (v146 invariant): a picked branch only counts while it belongs
    // to the CURRENT device+repo — function-scope so the branch sheet sees it too.
    val branchFresh = ui.branchFolderKey == (device?.let { d -> folder?.let { f -> "$d:${f.path}" } })
    // Project (or device) switched → the picked branch no longer describes the
    // new repo; reset and reload. Session mode never sends base_branch — reload
    // only on the task path so toggling the mode leaks no stale list either way.
    LaunchedEffect(device, folderId, sessionMode) {
        branch = null
        val d = device
        if (!sessionMode && d != null && folder != null) host.loadBranches(d, folder)
    }
    // Process-death restore (v146 invariant): saved device/folder/agent come back
    // but the loaded folders/agents died with the process — re-request once.
    LaunchedEffect(device) {
        val d = device ?: return@LaunchedEffect
        if (targets.folders.isEmpty() && !targets.foldersLoading && targets.error == null) {
            host.loadTarget(d)
        }
    }
    // A restored/default agent must actually exist on the device: once the real
    // agent list lands, drop a selection it doesn't contain.
    LaunchedEffect(targets.agents) {
        val a = agent
        if (a != null && targets.agents.isNotEmpty() && a !in targets.agents) agent = null
    }
    // Same for a restored folderId the loaded project list doesn't contain.
    LaunchedEffect(targets.folders) {
        val fid = folderId
        if (fid != null && targets.folders.isNotEmpty() && targets.folders.none { it.id == fid }) {
            folderId = null
        }
    }

    // 派发默认预选 (v146 invariant): settings-stored device/project/agent
    // (设置→通用→派发默认选项) auto-select when the form opens; switching
    // devices resets project/agent to the defaults.
    LaunchedEffect(targets.profiles) {
        if (device == null && settings.dispatchDefaultDevice.isNotBlank() &&
            targets.profiles.any { it.id == settings.dispatchDefaultDevice }
        ) {
            device = settings.dispatchDefaultDevice
            folderId = null
            agent = null
            host.loadTarget(settings.dispatchDefaultDevice)
        }
    }
    LaunchedEffect(targets.folders, targets.agents) {
        if (device == null) return@LaunchedEffect
        if (folderId == null && settings.dispatchDefaultFolderId > 0 &&
            targets.folders.any { it.id == settings.dispatchDefaultFolderId }
        ) {
            folderId = settings.dispatchDefaultFolderId
        }
        if (agent == null && settings.dispatchDefaultAgent.isNotBlank()) {
            val wire = AgentType.fromWire(settings.dispatchDefaultAgent)
            if (wire != AgentType.UNKNOWN && (targets.agents.isEmpty() || wire in targets.agents)) {
                agent = wire
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ① 执行方式（会话 / 任务卡）— doc1 §6.1 first field.
        FormSectionLabel(stringResource(R.string.dispatch_mode))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeCard(
                label = stringResource(R.string.dispatch_mode_session),
                note = stringResource(R.string.dispatch_mode_session_note),
                selected = sessionMode,
                onClick = { sessionMode = true },
                modifier = Modifier.weight(1f),
            )
            ModeCard(
                label = stringResource(R.string.dispatch_mode_task),
                note = stringResource(R.string.dispatch_mode_task_note),
                selected = !sessionMode,
                onClick = { sessionMode = false },
                modifier = Modifier.weight(1f),
            )
        }
        if (sessionMode) {
            Text(
                stringResource(R.string.dispatch_session_note),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }

        // ② 设备 chips — real profiles; load errors mark the chip (never fake
        // online dots — liveness comes only from the host's health precheck).
        FormSectionLabel(stringResource(R.string.dispatch_section_device_agent))
        DeviceChipRow(
            devices = targets.profiles.map { PickerOption(id = it.id, label = it.name) },
            selectedId = device,
            loadingDeviceId = if (targets.foldersLoading) device else null,
            errorDeviceIds = if (targets.error != null || targets.agentsError || targets.foldersError) {
                setOfNotNull(device)
            } else emptySet(),
            onSelect = { id ->
                if (id != device) {
                    device = id
                    folderId = null
                    agent = null
                    host.loadTarget(id)
                }
            },
        )
        if (targets.foldersError || targets.error != null || targets.agentsError) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    targets.error
                        ?: stringResource(if (targets.foldersError) R.string.dispatch_folders_failed else R.string.dispatch_agents_failed),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { device?.let { host.loadTarget(it) } }) {
                    Text(stringResource(R.string.dispatch_retry))
                }
            }
        }

        // ③ Agent chips — first chip is 自动 (no override; project default).
        val autoAgentLabel = stringResource(R.string.dispatch_agent_auto)
        AgentChipRow(
            autoLabel = autoAgentLabel,
            agents = targets.agents.map { PickerOption(id = it.name, label = it.displayName) },
            selectedId = agent?.name,
            onSelect = { name -> agent = AgentType.entries.firstOrNull { it.name == name } },
        )
        if (agent == null) {
            Text(
                stringResource(R.string.dispatch_agent_auto_note),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }

        // ④ 项目 PickerField → OptionListSheet.
        PickerField(
            icon = Icons.Rounded.Folder,
            label = stringResource(R.string.dispatch_project),
            value = folder?.name,
            placeholder = stringResource(
                when {
                    targets.foldersLoading -> R.string.dispatch_defaults_loading
                    targets.foldersError -> R.string.dispatch_folders_failed
                    device == null -> R.string.dispatch_no_device
                    else -> R.string.dispatch_no_folder
                },
            ),
            onClick = { if (targets.folders.isNotEmpty()) folderSheet = true },
        )

        // ⑤ 分支（仅任务模式）— real git_list_all_branches; failure keeps the
        // project default dispatchable (上游不支持的字段绝不假装支持).
        if (!sessionMode) {
            PickerField(
                icon = Icons.Rounded.AccountTree,
                label = stringResource(R.string.dispatch_branch),
                value = if (branch != null && branchFresh) branch else null,
                valueNote = if (branch == null || !branchFresh) {
                    folder?.gitBranch
                        ?.let { stringResource(R.string.dispatch_branch_follow, it) }
                        ?: stringResource(R.string.dispatch_branch_follow_unknown)
                } else null,
                placeholder = stringResource(
                    when {
                        ui.branchesLoading -> R.string.dispatch_branch_loading
                        else -> R.string.dispatch_branch_follow_unknown
                    },
                ),
                onClick = {
                    val f = folder
                    val d = device
                    when {
                        ui.branchesLoading -> Unit
                        ui.branchesError && d != null && f != null -> host.loadBranches(d, f)
                        else -> branchSheet = true
                    }
                },
            )
            if (ui.branchesError && !ui.branchesLoading) {
                Text(
                    stringResource(R.string.dispatch_branch_failed),
                    color = colors.danger,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // ⑥ 标题 + 描述. 会话模式的描述就是首条指令，空描述派出去只会得到
        // 一个空会话 — the guard repeats at canDispatch.
        FormSectionLabel(stringResource(R.string.dispatch_section_description))
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
        if (sessionMode && description.isBlank()) {
            Text(
                stringResource(R.string.dispatch_session_desc_required),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }

        // ⑦ 定时（仅任务模式）— 会话模式无定时（定时拉起的是 work_task）。
        if (!sessionMode) {
            FormSectionLabel(stringResource(R.string.dispatch_schedule))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = scheduled, onCheckedChange = { scheduled = it })
                Text(
                    stringResource(if (scheduled) R.string.dispatch_schedule_set else R.string.dispatch_schedule_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(enabled = scheduled) { showDatePicker = true },
                )
                if (scheduled && scheduleMillis > 0) {
                    val pattern = stringResource(R.string.dispatch_schedule_pattern)
                    Text(
                        remember(scheduleMillis, pattern) {
                            Instant.ofEpochMilli(scheduleMillis).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern(pattern))
                        } + " (" + stringResource(R.string.dispatch_schedule_note) + ")",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary,
                    )
                }
            }
            // Murphy guard: a schedule switch without a time would silently
            // dispatch IMMEDIATE, a past time would fire at once (v146).
            val scheduleNotSet = scheduled && scheduleMillis <= 0L
            val scheduleInPast = scheduled && scheduleMillis in 1..clockTick
            if (scheduleNotSet) {
                Text(
                    stringResource(R.string.dispatch_schedule_not_set),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (scheduleInPast) {
                Text(
                    stringResource(R.string.dispatch_schedule_past),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ⑧ 派发摘要卡 — full echo of what will be sent (doc1 §6.6).
        DispatchSummaryCard(
            sessionMode = sessionMode,
            device = targets.profiles.firstOrNull { it.id == device },
            folderName = folder?.name,
            agent = agent,
            folderDefaultAgent = folder?.defaultAgentType,
            branch = if (!sessionMode && branch != null && branchFresh) branch else null,
            followBranch = if (!sessionMode) folder?.gitBranch else null,
            scheduledAt = if (!sessionMode && scheduled && scheduleMillis > 0) scheduleMillis else null,
        )

        // Suppressed once the result dialog is up: a schedule/auto-start note
        // rides the same field and would render in error styling behind it.
        if (result == null) ui.error?.let {
            Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall)
        }

        // ⑨ 底部主按钮 — 创建会话 / 创建任务 (doc1 §6.7). canDispatch gate (v146).
        val scheduleNotSetNow = !sessionMode && scheduled && scheduleMillis <= 0L
        val scheduleInPastNow = !sessionMode && scheduled && scheduleMillis in 1..clockTick
        val canDispatch = device != null && folderId != null && title.isNotBlank() &&
            (!sessionMode || description.isNotBlank()) &&
            !ui.dispatching && !scheduleNotSetNow && !scheduleInPastNow
        PrimaryButton(
            text = stringResource(if (sessionMode) R.string.dispatch_button_session else R.string.dispatch_button_task),
            onClick = {
                val dev = device ?: return@PrimaryButton
                val fid = folderId ?: return@PrimaryButton
                // A branch picked against a stale folder list must never ride
                // along — branchFresh pins it to the current repo.
                val effectiveBranch = if (branch != null && branchFresh) branch else null
                // 会话模式的"自动"落到项目默认 Agent（没有则客户端默认）——
                // 服务端 create_conversation 需要具体 agent_type。
                val effectiveAgent = if (sessionMode) {
                    agent ?: folder?.defaultAgentType?.takeIf { it != AgentType.UNKNOWN }
                } else agent
                host.dispatch(
                    DispatchFormSubmit(
                        serverId = dev,
                        folderId = fid,
                        folderName = targets.folders.firstOrNull { it.id == fid }?.name.orEmpty(),
                        agentType = effectiveAgent,
                        title = title,
                        description = description,
                        baseBranch = if (sessionMode) null else effectiveBranch,
                        scheduleLocalMillis = if (!sessionMode && scheduled && scheduleMillis > 0) scheduleMillis else null,
                        sessionMode = sessionMode,
                    ),
                ) { ref ->
                    result = ref
                    onResult(ref)
                }
            },
            enabled = canDispatch,
            loading = ui.dispatching,
        )
    }

    if (folderSheet) {
        OptionListSheet(
            title = stringResource(R.string.dispatch_project),
            options = targets.folders.map { PickerOption(id = it.id.toString(), label = it.name, note = it.path) },
            selectedId = folderId?.toString(),
            onSelect = { option -> folderId = option.id.toIntOrNull() },
            onDismiss = { folderSheet = false },
        )
    }
    if (branchSheet) {
        // First row is the project default (跟随分支); picked branches only
        // count while branchFresh — a stale list can never be submitted.
        val defaultId = "__default__"
        OptionListSheet(
            title = stringResource(R.string.dispatch_pick_branch),
            options = listOf(
                PickerOption(
                    id = defaultId,
                    label = folder?.gitBranch
                        ?.let { stringResource(R.string.dispatch_branch_follow, it) }
                        ?: stringResource(R.string.dispatch_branch_follow_unknown),
                ),
            ) + ui.branches.map { PickerOption(id = it, label = it) },
            selectedId = if (branch != null && branchFresh) branch else defaultId,
            onSelect = { option -> branch = if (option.id == defaultId) null else option.id },
            onDismiss = { branchSheet = false },
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
            title = { Text(stringResource(R.string.dispatch_pick_time)) },
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

/** Small section label above a field group. */
@Composable
private fun FormSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = CodegTheme.colors.textTertiary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** One selectable 执行方式 card (会话 / 任务). */
@Composable
private fun ModeCard(
    label: String,
    note: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentDim else colors.bgElevated, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.accent else colors.textPrimary,
        )
        Text(note, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

/**
 * 派发摘要卡 (doc1 §6.6): every field echoed once, exactly what the host will
 * submit — no hidden defaults. Unknown optional fields show —.
 */
@Composable
private fun DispatchSummaryCard(
    sessionMode: Boolean,
    device: ServerProfile?,
    folderName: String?,
    agent: AgentType?,
    folderDefaultAgent: AgentType?,
    branch: String?,
    followBranch: String?,
    scheduledAt: Long?,
) {
    val colors = CodegTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bgElevated, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.dispatch_summary_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
        )
        SummaryRow(stringResource(R.string.dispatch_mode), stringResource(if (sessionMode) R.string.dispatch_mode_session else R.string.dispatch_mode_task))
        SummaryRow(stringResource(R.string.dispatch_device), device?.name)
        SummaryRow(stringResource(R.string.dispatch_project), folderName)
        SummaryRow(
            stringResource(R.string.dispatch_agent),
            agent?.displayName
                ?: folderDefaultAgent?.takeIf { it != AgentType.UNKNOWN }?.displayName,
        )
        if (!sessionMode) {
            SummaryRow(
                stringResource(R.string.dispatch_branch),
                branch ?: followBranch?.let { stringResource(R.string.dispatch_branch_follow, it) },
            )
            SummaryRow(
                stringResource(R.string.dispatch_schedule),
                scheduledAt?.let { millis ->
                    val pattern = java.time.format.DateTimeFormatter.ofPattern(
                        stringResource(R.string.dispatch_schedule_pattern),
                    )
                    remember(millis, pattern) {
                        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(pattern)
                    }
                },
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String?) {
    val colors = CodegTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        Text(
            value ?: "—",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
