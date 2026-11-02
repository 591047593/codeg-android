package app.codeg.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.feature.floatingball.FONT_STEPS
import app.codeg.android.feature.floatingball.stepFontScale
import kotlin.math.roundToInt

@Composable
fun GeneralContent(
    viewModel: GeneralViewModel = hiltViewModel(),
    onOpenBallSettings: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val appSettings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Section(stringResource(R.string.general_shell_section)) {
            // 批v150: a persisted five-tab-era startTab ("chats") is not a tab
            // route any more — display the MIGRATED value so the start marker
            // lands on the workbench instead of vanishing.
            TabOrderEditor(
                savedOrder = appSettings.homeTabOrder,
                startTab = app.codeg.android.feature.main.HomeTab.byRoute(appSettings.startTab).route,
                onReorder = viewModel::setHomeTabOrder,
                onStartTab = viewModel::setStartTab,
            )
            ToggleRow(
                stringResource(R.string.general_activity_swipe_title),
                stringResource(R.string.general_activity_swipe_subtitle),
                appSettings.activityDeviceSwipe,
                viewModel::setActivityDeviceSwipe,
            )
            // 批v151: inbox "new idea" button placement (corner FAB vs bottom-center
            // half-width pill) — same chip idiom as the ball-size row above.
            ListItem(
                headlineContent = { Text(stringResource(R.string.inbox_idea_style_title)) },
                supportingContent = { Text(stringResource(R.string.inbox_idea_style_subtitle)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "corner" to R.string.inbox_idea_style_corner,
                            "centerWide" to R.string.inbox_idea_style_center,
                        ).forEach { (id, labelRes) ->
                            val selected = appSettings.inboxNewIdeaStyle == id
                            Text(
                                stringResource(labelRes),
                                fontSize = 13.sp,
                                color = if (selected) colors.onAccent else colors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) colors.accent else colors.textPrimary.copy(alpha = 0.08f),
                                    )
                                    .clickable { viewModel.setInboxNewIdeaStyle(id) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
            )
        }
        Section(stringResource(R.string.general_floating_ball_section)) {
            ToggleRow(
                stringResource(R.string.ball_enable_title),
                stringResource(R.string.ball_enable_subtitle),
                appSettings.floatBallEnabled,
                viewModel::setFloatBallEnabled,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.ball_size_title)) },
                supportingContent = { Text(stringResource(R.string.ball_size_subtitle)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "small" to R.string.ball_size_small,
                            "medium" to R.string.ball_size_medium,
                            "large" to R.string.ball_size_large,
                        ).forEach { (id, labelRes) ->
                            val selected = appSettings.floatBallSize == id
                            Text(
                                stringResource(labelRes),
                                fontSize = 13.sp,
                                color = if (selected) colors.onAccent else colors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) colors.accent else colors.textPrimary.copy(alpha = 0.08f),
                                    )
                                    .clickable { viewModel.setFloatBallSize(id) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.ball_bindings_title)) },
                supportingContent = { Text(stringResource(R.string.ball_bindings_subtitle)) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
                modifier = Modifier.clickable { onOpenBallSettings() },
            )
        }
        Section(stringResource(R.string.general_dispatch_defaults_section)) {
            DispatchDefaultsSection()
        }
        Section(stringResource(R.string.general_font_section)) {
            val stepIndex = FONT_STEPS.indexOfFirst { kotlin.math.abs(it - appSettings.fontScale) < 0.01f }
            ListItem(
                headlineContent = { Text(stringResource(R.string.general_font_scale_title)) },
                supportingContent = { Text(stringResource(R.string.general_font_scale_subtitle)) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        StepButton(
                            Icons.Rounded.Remove,
                            enabled = stepIndex > 0,
                            onClick = { viewModel.setFontScale(stepFontScale(appSettings.fontScale, -1)) },
                        )
                        Text(
                            "${(appSettings.fontScale * 100).roundToInt()}%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        StepButton(
                            Icons.Rounded.Add,
                            enabled = stepIndex in 0 until FONT_STEPS.lastIndex,
                            onClick = { viewModel.setFontScale(stepFontScale(appSettings.fontScale, +1)) },
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
            )
        }
        Section(stringResource(R.string.general_new_session_section)) {
            var showFolderPicker by rememberSaveable { mutableStateOf(false) }
            // Summary line: the pin's name, or the last-used folder's name when
            // following memory, so the row reads as a fact not a mode switch.
            val lastUsedName = ui.lastUsedFolderId?.let { id ->
                ui.draftFolders.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
            }
            val followLabel = lastUsedName
                ?.let { stringResource(R.string.general_default_folder_follow_last, it) }
                ?: stringResource(R.string.general_default_folder_follow)
            // A pin whose folder was closed/deleted server-side is NOT a pin any more:
            // the new-task resolver skips it, so showing "#12" (and an option list with
            // nothing selected) would describe behaviour that no longer happens.
            val activePin = ui.pinnedFolderId?.takeIf { pin -> ui.draftFolders.any { it.id == pin } }
            val currentLabel = when (val pin = activePin) {
                null -> followLabel
                else -> ui.draftFolders.firstOrNull { it.id == pin }?.name ?: followLabel
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.general_default_folder_title)) },
                supportingContent = { Text(currentLabel) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = colors.textPrimary,
                    supportingColor = colors.textTertiary,
                ),
                // No server loaded (or its load failed): the list is empty and every
                // option would be a no-op — don't open a dead dialog.
                modifier = Modifier.clickable { if (ui.serverId != null) showFolderPicker = true },
            )
            ui.error?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.load() }) {
                        Text(stringResource(R.string.common_retry), color = colors.accent)
                    }
                }
            }
            if (showFolderPicker) {
                AlertDialog(
                    onDismissRequest = { showFolderPicker = false },
                    title = { Text(stringResource(R.string.general_default_folder_title)) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PickerRow(
                                label = followLabel,
                                selected = activePin == null,
                                onClick = {
                                    viewModel.setPinnedFolder(null)
                                    showFolderPicker = false
                                },
                            )
                            ui.draftFolders.forEach { f ->
                                PickerRow(
                                    label = f.name.ifBlank { f.path },
                                    selected = activePin == f.id,
                                    onClick = {
                                        viewModel.setPinnedFolder(f.id)
                                        showFolderPicker = false
                                    },
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showFolderPicker = false }) {
                            Text(stringResource(R.string.common_ok), color = colors.accent)
                        }
                    },
                )
            }
        }
        Section(stringResource(R.string.general_conversation_tools)) {
            ToggleRow(stringResource(R.string.general_live_feedback_title), stringResource(R.string.general_live_feedback_subtitle), ui.feedbackEnabled, viewModel::setFeedback)
            ToggleRow(stringResource(R.string.general_ask_questions_title), stringResource(R.string.general_ask_questions_subtitle), ui.questionEnabled, viewModel::setQuestion)
        }
        Section(stringResource(R.string.general_multi_agent_delegation)) {
            ToggleRow(stringResource(R.string.general_enable_delegation_title), stringResource(R.string.general_enable_delegation_subtitle), ui.delegationEnabled, viewModel::setDelegationEnabled)
            if (ui.delegationEnabled) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.general_max_depth_title)) },
                    supportingContent = { Text(stringResource(R.string.general_max_depth_subtitle)) },
                    trailingContent = {
                        Stepper(ui.depthLimit, onDec = { viewModel.setDepth(ui.depthLimit - 1) }, onInc = { viewModel.setDepth(ui.depthLimit + 1) })
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = colors.textPrimary,
                        supportingColor = colors.textTertiary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, fontSize = 15.sp, color = colors.textPrimary, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = CodegTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.textTertiary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.bgElevated.copy(alpha = 0.5f))) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = CodegTheme.colors
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = colors.textPrimary,
            supportingColor = colors.textTertiary,
        ),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun Stepper(value: Int, onDec: () -> Unit, onInc: () -> Unit) {
    val colors = CodegTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton(Icons.Rounded.Remove, onClick = onDec)
        Text("$value", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 8.dp))
        StepButton(Icons.Rounded.Add, onClick = onInc)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = CodegTheme.colors
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = colors.textPrimary.copy(alpha = 0.06f),
            contentColor = colors.textPrimary,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/**
 * Bottom-tab order + cold-start tab editor. Rows render in the user's saved order
 * (enum order as fallback); ▲▼ swap adjacent rows and persist immediately. The
 * start-tab row is marked with a check — tapping a row's name (not the arrows)
 * sets it as the tab the app opens on.
 */
@Composable
private fun TabOrderEditor(
    savedOrder: List<String>,
    startTab: String,
    onReorder: (List<String>) -> Unit,
    onStartTab: (String) -> Unit,
) {
    val colors = CodegTheme.colors
    val tabs = remember(savedOrder) { app.codeg.android.feature.main.HomeTab.orderedBy(savedOrder) }
    Column {
        Text(stringResource(R.string.general_tab_order_subtitle), fontSize = 12.sp, color = colors.textTertiary, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        tabs.forEachIndexed { index, tab ->
            ListItem(
                headlineContent = { Text(stringResource(tab.label)) },
                leadingContent = {
                    Icon(
                        if (tab.route == startTab) Icons.Rounded.Check else tab.icon,
                        contentDescription = null,
                        tint = if (tab.route == startTab) colors.accent else colors.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        StepButton(Icons.Rounded.ArrowDropUp, enabled = index > 0, onClick = {
                            val next = tabs.toMutableList()
                            next[index - 1] = tabs[index].also { next[index] = next[index - 1] }
                            onReorder(next.map { it.route })
                        })
                        StepButton(Icons.Rounded.ArrowDropDown, enabled = index < tabs.lastIndex, onClick = {
                            val next = tabs.toMutableList()
                            next[index + 1] = tabs[index].also { next[index] = next[index + 1] }
                            onReorder(next.map { it.route })
                        })
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = if (tab.route == startTab) colors.accent else colors.textPrimary,
                ),
                modifier = Modifier.clickable { onStartTab(tab.route) },
            )
            if (index < tabs.lastIndex) {
                HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 56.dp))
            }
        }
    }
}

/** 派发默认 section: pick the device/project/agent every dispatch sheet opens
 *  preselected with (v1.0.1-fengx.34). Plain radio dialogs here — the wheel
 *  picker is the dispatch sheet's interaction, this screen just stores defaults. */
@Composable
private fun DispatchDefaultsSection(viewModel: DispatchDefaultsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var showDevice by rememberSaveable { mutableStateOf(false) }
    var showFolder by rememberSaveable { mutableStateOf(false) }
    var showAgent by rememberSaveable { mutableStateOf(false) }

    val deviceName = ui.profiles.firstOrNull { it.id == settings.dispatchDefaultDevice }?.name
        ?: settings.dispatchDefaultDevice.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.dispatch_defaults_unset)
    val folderName = settings.dispatchDefaultFolderName.takeIf { settings.dispatchDefaultFolderId > 0 }
        ?: stringResource(R.string.dispatch_defaults_unset)
    val agentName = AgentType.fromWire(settings.dispatchDefaultAgent)
        .takeIf { settings.dispatchDefaultAgent.isNotBlank() && it != AgentType.UNKNOWN }?.displayName
        ?: stringResource(R.string.dispatch_defaults_any)

    ListItem(
        headlineContent = { Text(stringResource(R.string.dispatch_defaults_device)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(deviceName, fontSize = 14.sp, color = colors.textSecondary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary),
        modifier = Modifier.clickable { showDevice = true },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.dispatch_defaults_folder)) },
        supportingContent = {
            if (settings.dispatchDefaultDevice.isBlank()) {
                Text(stringResource(R.string.dispatch_defaults_pick_device_first), fontSize = 12.sp)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(folderName, fontSize = 14.sp, color = colors.textSecondary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary, supportingColor = colors.textTertiary),
        modifier = Modifier.clickable(enabled = settings.dispatchDefaultDevice.isNotBlank()) {
            viewModel.loadTargets(settings.dispatchDefaultDevice)
            showFolder = true
        },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.dispatch_defaults_agent)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agentName, fontSize = 14.sp, color = colors.textSecondary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary),
        modifier = Modifier.clickable(enabled = settings.dispatchDefaultDevice.isNotBlank()) {
            viewModel.loadTargets(settings.dispatchDefaultDevice)
            showAgent = true
        },
    )
    Text(
        stringResource(R.string.dispatch_defaults_clear),
        fontSize = 13.sp,
        color = colors.danger,
        modifier = Modifier
            .padding(start = 16.dp, top = 2.dp)
            .clickable { viewModel.clear() },
    )

    if (showDevice) {
        RadioOptionDialog(
            title = stringResource(R.string.dispatch_defaults_device),
            options = ui.profiles.map { it.id to it.name },
            selectedId = settings.dispatchDefaultDevice,
            onSelect = { id ->
                showDevice = false
                ui.profiles.firstOrNull { it.id == id }?.let { viewModel.saveDeviceOnly(it.id, it.name) }
            },
            onDismiss = { showDevice = false },
        )
    }
    if (showFolder) {
        RadioOptionDialog(
            title = stringResource(R.string.dispatch_defaults_folder),
            options = ui.folders.map { it.id.toString() to it.name },
            selectedId = settings.dispatchDefaultFolderId.takeIf { it > 0 }?.toString(),
            loading = ui.loading,
            onSelect = { id ->
                showFolder = false
                id.toIntOrNull()?.let { fid -> ui.folders.firstOrNull { it.id == fid }?.let(viewModel::saveFolder) }
            },
            onDismiss = { showFolder = false },
        )
    }
    if (showAgent) {
        // First option = 不指定 (agent omitted, server uses the folder default).
        RadioOptionDialog(
            title = stringResource(R.string.dispatch_defaults_agent),
            options = listOf("" to stringResource(R.string.dispatch_defaults_any)) +
                ui.agents.map { it.wire to it.displayName },
            selectedId = settings.dispatchDefaultAgent.takeIf { it.isNotBlank() } ?: "",
            loading = ui.loading,
            onSelect = { wire ->
                showAgent = false
                viewModel.saveAgent(wire.takeIf { it.isNotBlank() }?.let(AgentType::fromWire))
            },
            onDismiss = { showAgent = false },
        )
    }
}

@Composable
private fun RadioOptionDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    loading: Boolean = false,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (loading) {
                    Text(stringResource(R.string.dispatch_defaults_loading), fontSize = 14.sp, color = colors.textSecondary)
                } else if (options.isEmpty()) {
                    Text(stringResource(R.string.dispatch_defaults_empty), fontSize = 14.sp, color = colors.textSecondary)
                }
                options.forEach { (id, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id) }
                            .padding(vertical = 10.dp),
                    ) {
                        RadioButton(selected = id == selectedId, onClick = { onSelect(id) })
                        Text(label, fontSize = 15.sp, color = colors.textPrimary, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
    )
}
