package app.codeg.android.feature.handoff

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.HandoffSource
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.core.model.AgentType
import app.codeg.android.feature.common.WheelColumn

/**
 * Handoff composer (batch v141): edit the generated package, pick relay mode
 * (A context / B git), pick the target device/project/agent, dispatch via the
 * shared pipeline. Shown from the source session's detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoffSheet(
    source: HandoffSource,
    viewModel: HandoffViewModel,
    onClose: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<Int?>(null) }
    var agent by rememberSaveable { mutableStateOf<AgentType?>(null) }

    androidx.compose.runtime.LaunchedEffect(source.conversationId) {
        viewModel.generate(source)
        viewModel.loadTarget(source.serverId)
        // rememberSaveable restores the picked device on rotation — re-running
        // this effect must not silently snap the selection back to the source.
        if (device == null) device = source.serverId
    }

    ModalBottomSheet(
        onDismissRequest = { viewModel.reset(); onClose() },
        // 批v148：拖拽关闭恢复，手势由内容上的 drag lock 单向闸约束。
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        val pkg = ui.pkg
        if (pkg == null) {
            Column(Modifier.padding(24.dp)) { CircularProgressIndicator(Modifier.size(20.dp)) }
            return@ModalBottomSheet
        }
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.handoff_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    R.string.handoff_source_line,
                    source.serverName, source.folderName, source.agent.displayName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = CodegTheme.colors.textSecondary,
            )

            HandoffField(
                label = stringResource(R.string.handoff_goal),
                value = pkg.goal,
                onChange = { v -> viewModel.update { it.copy(goal = v) } },
            )
            HandoffField(
                label = stringResource(R.string.handoff_completed),
                value = pkg.completed,
                onChange = { v -> viewModel.update { it.copy(completed = v) } },
            )
            HandoffField(
                label = stringResource(R.string.handoff_progress),
                value = pkg.progress,
                onChange = { v -> viewModel.update { it.copy(progress = v) } },
            )
            HandoffField(
                label = stringResource(R.string.handoff_decisions),
                value = pkg.decisions,
                onChange = { v -> viewModel.update { it.copy(decisions = v) } },
            )
            HandoffField(
                label = stringResource(R.string.handoff_known_issues),
                value = pkg.knownIssues,
                onChange = { v -> viewModel.update { it.copy(knownIssues = v) } },
            )
            HandoffField(
                label = stringResource(R.string.handoff_next_steps),
                value = pkg.nextSteps,
                onChange = { v -> viewModel.update { it.copy(nextSteps = v) } },
            )

            // Relay mode: A = context only; B = same + a git precheck note. The
            // package never carries code — B only attaches an honest status line.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = !ui.modeB,
                    onClick = { viewModel.setModeB(false) },
                    label = { Text(stringResource(R.string.handoff_mode_a)) },
                )
                FilterChip(
                    selected = ui.modeB,
                    onClick = { viewModel.setModeB(true) },
                    label = { Text(stringResource(R.string.handoff_mode_b)) },
                )
                if (ui.gitChecking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            if (ui.modeB) {
                val pc = ui.gitPrecheck
                Text(
                    when {
                        pc == null -> stringResource(R.string.handoff_git_checking)
                        pc.error != null -> pc.error
                        else -> stringResource(R.string.handoff_git_result, pc.branch ?: "?", pc.dirtyCount) +
                            (pc.dirtyPreview.takeIf { it.isNotEmpty() }?.let { " ${it.joinToString("、")}" } ?: "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CodegTheme.colors.textSecondary,
                )
            }

            val targets: DispatchTargets = ui.targets
            // 派发默认预选（设置→通用→派发默认选项）：sheet 打开时若未选设备，
            // 自动落到默认设备/项目/Agent。
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            LaunchedEffect(targets.profiles) {
                if (device == null && settings.dispatchDefaultDevice.isNotBlank() &&
                    targets.profiles.any { it.id == settings.dispatchDefaultDevice }
                ) {
                    device = settings.dispatchDefaultDevice
                    folderId = null
                    agent = null
                    viewModel.loadTarget(settings.dispatchDefaultDevice)
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
            if (device != null && targets.foldersLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            targets.error?.let {
                Text(
                    it,
                    color = CodegTheme.colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { device?.let { d -> viewModel.loadTarget(d) } },
                )
            }
            // §6.3: a dead agent list is a degraded state, not a dead panel —
            // short inline hint + tappable retry, raw ACP error stays in the log.
            if (targets.agentsError) {
                Text(
                    stringResource(R.string.dispatch_agents_failed) + " · " +
                        stringResource(R.string.dispatch_retry),
                    color = CodegTheme.colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { device?.let { d -> viewModel.loadTarget(d) } },
                )
            }
            // 三列并排滚轮选择器（手机时钟风格），与派发 sheet 同款交互。
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dispatch_device),
                        style = MaterialTheme.typography.labelSmall,
                        color = CodegTheme.colors.textTertiary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    WheelColumn(
                        items = targets.profiles.map { it.name },
                        selectedIndex = targets.profiles.indexOfFirst { it.id == device }.coerceAtLeast(0),
                        onSelect = { idx ->
                            val profile = targets.profiles.getOrNull(idx) ?: return@WheelColumn
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
                        items = targets.folders.map { it.name },
                        selectedIndex = targets.folders.indexOfFirst { it.id == folderId }.coerceAtLeast(0),
                        onSelect = { idx -> folderId = targets.folders.getOrNull(idx)?.id },
                        emptyText = stringResource(
                            if (targets.foldersLoading) R.string.dispatch_defaults_loading else R.string.dispatch_no_folder,
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
                    val autoAgentLabel = stringResource(R.string.dispatch_agent_auto)
                    val agentOptions = remember(targets.agents, autoAgentLabel) {
                        listOf(autoAgentLabel) + targets.agents.map { it.displayName }
                    }
                    WheelColumn(
                        items = agentOptions,
                        selectedIndex = (if (agent == null) 0 else targets.agents.indexOf(agent) + 1)
                            .coerceIn(0, agentOptions.lastIndex),
                        onSelect = { idx -> agent = if (idx <= 0) null else targets.agents.getOrNull(idx - 1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ui.error?.let {
                Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = {
                    val dev = device ?: return@TextButton
                    val fid = folderId ?: return@TextButton
                    viewModel.dispatch(source, dev, fid, targets.folders.firstOrNull { it.id == fid }?.name.orEmpty(), agent)
                },
                enabled = device != null && folderId != null && !ui.dispatching && ui.unknownRef == null &&
                    (pkg.goal.isNotBlank() || pkg.nextSteps.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.dispatching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(stringResource(R.string.handoff_dispatch))
            }
            Text(
                stringResource(R.string.handoff_no_code_note),
                style = MaterialTheme.typography.labelSmall,
                color = CodegTheme.colors.textSecondary,
            )
        }
    }

    if (ui.createdRef != null) {
        val ref = ui.createdRef!!.ref
        AlertDialog(
            onDismissRequest = { viewModel.reset(); onClose() },
            title = { Text(stringResource(R.string.dispatch_ok, ref.remoteTodoId ?: 0)) },
            text = { Text("${ref.serverName} · ${ref.title}") },
            confirmButton = {
                TextButton(onClick = { viewModel.reset(); onClose() }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

@Composable
private fun HandoffField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = CodegTheme.colors.textSecondary,
    )
}
