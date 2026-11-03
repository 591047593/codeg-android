package app.codeg.android.feature.dispatch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.LocalAttachment
import app.codeg.android.core.designsystem.component.AgentChipRow
import app.codeg.android.core.designsystem.component.FullscreenTextEditorDialog
import app.codeg.android.core.designsystem.component.DeviceChipRow
import app.codeg.android.core.designsystem.component.OptionListSheet
import app.codeg.android.core.designsystem.component.PickerField
import app.codeg.android.core.designsystem.component.PickerOption
import app.codeg.android.core.designsystem.component.PrimaryButton
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    /** 批v154: copies picked files into app-private storage. The form only stages
     *  LOCAL copies; [DispatchService] uploads them to the chosen device. */
    val attachmentStager: app.codeg.android.core.data.AttachmentStager

    fun loadTarget(serverId: String)
    fun loadBranches(serverId: String, folder: FolderDetail)

    /** 批v155: the agent's mode list for the 模型 row — cache-first probe via
     *  [app.codeg.android.core.data.AgentOptionsProber] (one CLI spawn per
     *  server+agent+dir, shared across forms). null = probe failed. */
    suspend fun probeAgentOptions(serverId: String, agentType: AgentType, workingDir: String?): app.codeg.android.core.model.AgentOptionsSnapshot?

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

/** Cap per dispatch (image + file picks share it) — raised 10 → 20 in 批v155. */
private val MAX_DISPATCH_ATTACHMENTS = app.codeg.android.core.data.AttachmentLimits.MAX_IMAGES

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
    /** 批v155: the picked agent mode (模型 row), list-validated by the form. */
    val modeId: String? = null,
    val scheduleLocalMillis: Long?,
    val sessionMode: Boolean,
    val autoStart: Boolean = true,
    /** 批v154 — files to upload to the target device before the create. */
    val attachments: List<app.codeg.android.core.data.LocalAttachment> = emptyList(),
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DispatchForm(
    host: DispatchFormHost,
    modifier: Modifier = Modifier,
    /** Host A (inbox) opens in session mode; host B (ball) opens in task mode. */
    defaultSessionMode: Boolean = true,
    /** 收件箱宿主传入草稿 id——CONFIRMED 后删草稿、UNKNOWN 卡片绑定都靠它（v140 契约）。 */
    ideaId: String? = null,
    /** Seed values for the title/description fields (host A prefills from the
     *  idea being dispatched; host B starts blank). Sheet-lifetime state — the
     *  sheet is disposed between opens, so a plain init matches the old sheet. */
    initialTitle: String = "",
    initialDescription: String = "",
    /** 批v155 任务板新建模式 (方案 A): task mode locked (no 执行方式 toggle),
     *  branch row dropped — the server bases the worktree on the repo's current
     *  HEAD and the APP never surfaces the worktree — and schedule row dropped
     *  (定时 lives in the long-press menu after creation). */
    newTaskMode: Boolean = false,
    /** Ledger ref of an accepted submit — the screen renders the result dialog. */
    onResult: (DispatchRef) -> Unit = {},
    /** 批v154: files already owned by the source (an idea's attachments). The form
     *  shows them as removable chips; it only DELETES copies it staged itself. */
    initialAttachments: List<app.codeg.android.core.data.LocalAttachment> = emptyList(),
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
    var sessionMode by rememberSaveable { mutableStateOf(!newTaskMode && defaultSessionMode) }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var scheduled by rememberSaveable { mutableStateOf(false) }
    var scheduleMillis by rememberSaveable { mutableStateOf(0L) }
    // 批v155 模型行（仅任务模式 + 具体 Agent）。modelId survives process death,
    // the OPTIONS list does not — a restored id must revalidate against the
    // fresh probe result before it can ride along on a submit.
    var modelId by rememberSaveable { mutableStateOf<String?>(null) }
    var modelOptions by remember { mutableStateOf<List<app.codeg.android.core.model.SessionModeInfo>>(emptyList()) }
    var modelLoading by remember { mutableStateOf(false) }
    var modelFailed by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    // 批v154 attachments. A plain remember (not saveable): the staged model is not
    // Bundle-able, and the sheet closes on rotation anyway — the private copies
    // simply outlive the chip list. Locally staged copies are ours to delete on
    // removal; the ones handed in by the host (an idea's attachments) are not.
    val attachments = remember {
        androidx.compose.runtime.mutableStateListOf<LocalAttachment>().apply {
            addAll(initialAttachments)
        }
    }
    var stagingAttachments by remember { mutableStateOf(false) }
    var showDescFullscreen by rememberSaveable { mutableStateOf(false) }
    // 批v155 段3: which image attachment the fullscreen viewer shows (null = closed).
    // rememberSaveable: rotation no longer closes the viewer (R2D#4).
    var imageViewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val stagedOwners = remember { mutableSetOf<String>() }
    val attachmentScope = rememberCoroutineScope()
    // Stage picked uris off the main thread; the private copy is what the idea /
    // dispatch persists (a SAF grant dies with the screen).
    fun stage(uris: List<android.net.Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        stagingAttachments = true
        attachmentScope.launch {
            val staged = uris.mapNotNull { uri ->
                host.attachmentStager.stage(uri, null, null, images)
            }
            val room = (MAX_DISPATCH_ATTACHMENTS - attachments.size).coerceAtLeast(0)
            val accepted = staged.take(room)
            staged.drop(room).forEach(host.attachmentStager::discard)
            attachments.addAll(accepted)
            accepted.forEach { stagedOwners += it.id }
            stagingAttachments = false
        }
    }
    val pickDispatchImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_DISPATCH_ATTACHMENTS),
    ) { uris -> stage(uris, images = true) }
    val pickDispatchFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> stage(uris, images = false) }
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
        if (!sessionMode && !newTaskMode && d != null && folder != null) host.loadBranches(d, folder)
    }
    // 模型探测 (批v155): task mode + a concrete agent on a picked device+project
    // → describeAgentOptions for the mode list. Cache-first on the prober, so a
    // re-opened form answers instantly; a key change resets the picked id
    // (a fresh list may differ) and re-resolves. Cancellation-safe: the store
    // keeps the probe running, only the await is cancelled.
    LaunchedEffect(sessionMode, device, agent, folder?.path) {
        modelId = null
        modelOptions = emptyList()
        modelLoading = false
        modelFailed = false
        val d = device
        val a = agent
        val f = folder
        if (sessionMode || d == null || a == null || f == null) return@LaunchedEffect
        modelLoading = true
        val snapshot = try {
            host.probeAgentOptions(d, a, f.path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        modelLoading = false
        if (snapshot == null) {
            modelFailed = true
        } else {
            modelOptions = snapshot.modes?.availableModes.orEmpty()
            snapshot.modes?.currentModeId
                ?.takeIf { current -> modelOptions.any { it.id == current } }
                ?.let { modelId = it }
        }
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
        // ① 执行方式（会话 / 任务卡）— doc1 §6.1 first field. 任务板新建模式
        // (批v155) locks task mode: no toggle, no session note.
        if (!newTaskMode) {
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

        // ⑤b 模型（仅任务模式 + 具体 Agent）— the agent's live mode list via
        // describeAgentOptions (批v155). 自动 agent can't be probed (no concrete
        // CLI), so the row hides; an agent without modes dispatches mode-less.
        if (!sessionMode && agent != null) {
            PickerField(
                icon = Icons.Rounded.Tune,
                label = stringResource(R.string.dispatch_model),
                value = modelOptions.firstOrNull { it.id == modelId }
                    ?.let { it.name.ifBlank { it.id } },
                placeholder = stringResource(
                    when {
                        modelLoading -> R.string.dispatch_model_loading
                        modelFailed -> R.string.dispatch_model_failed
                        // R3 P3-6: no project selected → the probe hasn't run;
                        // "无模型" reads as a verdict, not a pending state.
                        folder == null -> R.string.dispatch_model_pending
                        else -> R.string.dispatch_model_none
                    },
                ),
                onClick = { if (modelOptions.isNotEmpty()) modelSheet = true },
            )
            if (modelFailed && !modelLoading) {
                Text(
                    stringResource(R.string.dispatch_model_failed_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }

        // ⑤ 分支（仅任务模式）— real git_list_all_branches; failure keeps the
        // project default dispatchable (上游不支持的字段绝不假装支持).
        // 任务板新建模式不显示 (批v155 方案 A): base follows HEAD, worktree invisible.
        if (!sessionMode && !newTaskMode) {
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
            // 批v154: long descriptions get the same near-fullscreen editor as the
            // composer — a 3-line box on a phone hides the tail while typing.
            trailingIcon = {
                IconButton(onClick = { showDescFullscreen = true }) {
                    Icon(
                        Icons.Rounded.OpenInFull,
                        contentDescription = stringResource(R.string.compose_edit_fullscreen),
                        tint = colors.textSecondary,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // 批v154 附件：图片 / 任意文件 → 应用私有目录落盘，派发时上传到目标设备。
        if (stagingAttachments) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (attachments.isNotEmpty()) {
            attachments.forEach { att ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        // 批v155 段3: image rows open the fullscreen viewer
                        // across this form's images.
                        .then(
                            if (att.isImage) {
                                Modifier.clickable {
                                    imageViewerIndex = attachments.filter { it.isImage }
                                        .indexOfFirst { it.id == att.id }
                                        .takeIf { idx -> idx >= 0 }
                                }
                            } else Modifier,
                        ),
                ) {
                    if (att.isImage) {
                        app.codeg.android.core.designsystem.component.LocalImageThumbnail(
                            path = att.localPath,
                            modifier = Modifier.size(width = 52.dp, height = 36.dp),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.AttachFile,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        att.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    IconButton(
                        onClick = {
                            attachments.removeAll { it.id == att.id }
                            if (att.id in stagedOwners) {
                                stagedOwners -= att.id
                                host.attachmentStager.discard(att)
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.attach_remove),
                            tint = colors.textTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { pickDispatchImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !stagingAttachments && attachments.size < MAX_DISPATCH_ATTACHMENTS,
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.attach_add_image), modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(
                onClick = { pickDispatchFiles.launch(arrayOf("*/*")) },
                enabled = !stagingAttachments && attachments.size < MAX_DISPATCH_ATTACHMENTS,
            ) {
                Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.attach_add_file), modifier = Modifier.padding(start = 4.dp))
            }
        }
        // R3 P2-1: task mode needs it too — the server's validate_draft rejects
        // an empty prompt with 422 ("prompt is required"); a title-only create
        // used to pass the gate and die server-side.
        if (description.isBlank() && attachments.isEmpty()) {
            Text(
                stringResource(
                    if (sessionMode) R.string.dispatch_session_desc_required
                    else R.string.dispatch_task_desc_required,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
        // 批v155 段3: the form's fullscreen image viewer (attachment rows above).
        imageViewerIndex?.let { idx ->
            app.codeg.android.core.designsystem.component.ImageViewerDialog(
                images = attachments.filter { it.isImage }
                    .map { app.codeg.android.core.designsystem.component.ImageViewerSource.File(it.localPath) },
                initialIndex = idx,
                onDismiss = { imageViewerIndex = null },
            )
        }

        // ⑦ 定时（仅任务模式）— 会话模式无定时（定时拉起的是 work_task）。
        // 任务板新建模式同样无定时 (批v155)：定时走卡片长按菜单。
        if (!sessionMode && !newTaskMode) {
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
            modelName = modelOptions.firstOrNull { it.id == modelId }?.let { it.name.ifBlank { it.id } },
            branch = if (!sessionMode && !newTaskMode && branch != null && branchFresh) branch else null,
            followBranch = if (!sessionMode && !newTaskMode) folder?.gitBranch else null,
            scheduledAt = if (!sessionMode && !newTaskMode && scheduled && scheduleMillis > 0) scheduleMillis else null,
        )

        // Suppressed once the result dialog is up: a schedule/auto-start note
        // rides the same field and would render in error styling behind it.
        if (result == null) ui.error?.let {
            Text(it, color = colors.danger, style = MaterialTheme.typography.bodySmall)
        }

        // ⑨ 底部主按钮 — 创建会话 / 创建任务 (doc1 §6.7). canDispatch gate (v146).
        val scheduleNotSetNow = !sessionMode && scheduled && scheduleMillis <= 0L
        val scheduleInPastNow = !sessionMode && scheduled && scheduleMillis in 1..clockTick
        // R3 P2-1: validate_draft rejects an empty prompt in BOTH modes — the
        // form must never dispatch a title-only request (session mode already
        // required this; task mode used to slip through to a 422).
        val canDispatch = device != null && folderId != null && title.isNotBlank() &&
            (description.isNotBlank() || attachments.isNotEmpty()) &&
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
                // 模型行只在任务模式出现；restored id 必须能在当前列表里找到
                // 才放行（probe 未回来时宁可 mode-less，不发未校验的 mode_id）。
                val effectiveModeId = modelOptions.firstOrNull { it.id == modelId }?.id
                host.dispatch(
                    DispatchFormSubmit(
                        ideaId = ideaId,
                        serverId = dev,
                        folderId = fid,
                        folderName = targets.folders.firstOrNull { it.id == fid }?.name.orEmpty(),
                        agentType = effectiveAgent,
                        title = title,
                        description = description,
                        baseBranch = if (sessionMode || newTaskMode) null else effectiveBranch,
                        modeId = if (sessionMode) null else effectiveModeId,
                        scheduleLocalMillis = if (!sessionMode && !newTaskMode && scheduled && scheduleMillis > 0) scheduleMillis else null,
                        sessionMode = sessionMode,
                        attachments = attachments.toList(),
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

    if (showDescFullscreen) {
        FullscreenTextEditorDialog(
            text = description,
            onTextChange = { description = it },
            onDismiss = { showDescFullscreen = false },
            label = stringResource(R.string.inbox_desc_hint),
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
        // \u0000 不可能出现在 git 分支名里——防止真实同名分支把 LazyColumn key 撞重复。
        val defaultId = "\u0000default"
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
    if (modelSheet) {
        OptionListSheet(
            title = stringResource(R.string.dispatch_model),
            options = modelOptions.map { PickerOption(id = it.id, label = it.name.ifBlank { it.id }) },
            selectedId = modelId,
            onSelect = { option -> modelId = option.id },
            onDismiss = { modelSheet = false },
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
    /** 批v155: the picked agent mode (模型 row), already resolved to a name. */
    modelName: String? = null,
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
            SummaryRow(stringResource(R.string.dispatch_model), modelName)
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
