package app.codeg.android.feature.handoff

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.codeg.android.R
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.HandoffSource
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType

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
        device = source.serverId
    }

    ModalBottomSheet(onDismissRequest = { viewModel.reset(); onClose() }) {
        val pkg = ui.pkg
        if (pkg == null) {
            Column(Modifier.padding(24.dp)) { CircularProgressIndicator(Modifier.size(20.dp)) }
            return@ModalBottomSheet
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
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

            SectionLabel(stringResource(R.string.dispatch_device))
            val targets: DispatchTargets = ui.targets
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                targets.profiles.forEach { profile ->
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
            if (device != null && targets.foldersLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            targets.error?.let {
                Text(it, color = CodegTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
            }
            if (device != null && !targets.foldersLoading) {
                SectionLabel(stringResource(R.string.dispatch_project))
                if (targets.folders.isEmpty()) {
                    Text(
                        stringResource(R.string.dispatch_no_folder),
                        style = MaterialTheme.typography.bodySmall,
                        color = CodegTheme.colors.textSecondary,
                    )
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    targets.folders.forEach { folder ->
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
                    targets.agents.forEach { candidate ->
                        FilterChip(
                            selected = agent == candidate,
                            onClick = { agent = if (agent == candidate) null else candidate },
                            label = { Text(candidate.displayName) },
                        )
                    }
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
