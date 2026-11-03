package app.codeg.android.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * The app-global device bar, pinned above every tab's content: one chip per saved
 * server plus an aggregate "All" chip, and a trailing manage-servers shortcut.
 * Selecting a device re-points the whole app (all tabs refetch for that server);
 * the aggregate chip is hidden outright on tabs that don't support it (MainShell
 * passes [showAll] — batch v132: Activity AND Chats, where it opens the project
 * filter sheet), so it can never promise a view the current tab can't show.
 *
 * Rendered by [MainShell] only when the bottom bar is shown (tab routes) and more
 * than one server exists — single-device setups get nothing to switch.
 */
data class DeviceChipColor(val label: String, val color: Color)

/** One stable accent per server name — the same device keeps the same dot colour. */
@Composable
fun deviceBadgeColor(name: String): Color {
    val colors = CodegTheme.colors
    val palette = remember(colors.isDark) {
        listOf(
            Color(0xFF4C8DFF), Color(0xFF34B27B), Color(0xFFE0A63A),
            Color(0xFFE0666B), Color(0xFF9B7BE8), Color(0xFF2FB5C4),
        )
    }
    return palette[kotlin.math.abs(name.hashCode()) % palette.size]
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GlobalDeviceBar(
    servers: List<ServerProfile>,
    selectedId: String?,
    allSelected: Boolean,
    showAll: Boolean,
    /** Active project name shown inside the All chip; null collapses the slot
     *  so the chip's rhythm is unchanged when no filter is active (review #18). */
    allSubtitle: String? = null,
    onSelectServer: (String) -> Unit,
    onSelectAll: () -> Unit,
    onManageServers: () -> Unit,
    /** Long-press a device chip → "skip in All" (batch v137). The device keeps its
     *  chip and stays individually openable; it just stops joining the aggregate. */
    onSetIncludeInAll: (String, Boolean) -> Unit = { _, _ -> },
) {
    val colors = CodegTheme.colors
    // Keep the ID, not the profile snapshot: the switch must track the live list so
    // the toggle it performs is visible immediately, and deleting/hiding the device
    // while the sheet is open simply closes it (batch v137).
    var skipTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bg.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showAll) {
                item(key = "chip-all") {
                    DeviceChip(
                        selected = allSelected,
                        leading = { iconTint -> Icon(Icons.Rounded.Dns, null, tint = iconTint, modifier = Modifier.size(14.dp)) },
                        onClick = onSelectAll,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.activity_all),
                                fontSize = 15.sp,
                                fontWeight = if (allSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (allSelected) colors.onAccent else colors.textSecondary,
                                maxLines = 1,
                            )
                            // Collapsed slot when no project filter is active.
                            if (allSubtitle != null) {
                                Text(
                                    text = "· $allSubtitle",
                                    fontSize = 12.sp,
                                    color = if (allSelected) colors.onAccent.copy(alpha = 0.85f) else colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 88.dp),
                                )
                            }
                        }
                    }
                }
            }
            items(servers.size, key = { servers[it].id }) { i ->
                val server = servers[i]
                val selected = !allSelected && server.id == selectedId
                DeviceChip(
                    selected = selected,
                    dimmed = !server.includeInAll,
                    leading = { iconTint ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(deviceBadgeColor(server.name)),
                        )
                    },
                    onClick = { onSelectServer(server.id) },
                    onLongClick = { skipTargetId = server.id },
                ) {
                    Text(
                        text = server.name,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            selected -> colors.onAccent
                            server.includeInAll -> colors.textSecondary
                            else -> colors.textTertiary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!server.includeInAll) {
                        Text(
                            text = "· " + stringResource(R.string.device_skipped_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            item(key = "chip-manage") {
                // 触达修正（第1轮可及性审查）：30dp 低于 48dp 最小触达标准——回
                // 默认尺寸，视觉仍由 16dp 的齿轮图标定调。
                IconButton(onClick = onManageServers) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.devicebar_manage),
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        HorizontalHairline()
    }

    // Long-press target: one switch + the manage entry (batch v137). Kept as a
    // sheet (not a dialog) so the device bar's context stays visible behind it.
    val skipTarget = servers.firstOrNull { it.id == skipTargetId }
    skipTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { skipTargetId = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = target.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = !target.includeInAll,
                            role = Role.Switch,
                            onValueChange = { skip -> onSetIncludeInAll(target.id, !skip) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.device_skip_in_all),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.device_skip_in_all_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                    // The row owns the toggle action (merged semantics: "Skip in All,
                    // switch, off"), so the switch itself is display-only.
                    Switch(
                        checked = !target.includeInAll,
                        onCheckedChange = null,
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        skipTargetId = null
                        onManageServers()
                    },
                ) {
                    Text(stringResource(R.string.devicebar_manage))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceChip(
    selected: Boolean,
    leading: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(50)
    val longClickLabel = stringResource(R.string.device_skip_in_all)
    // 批v151 polish (mandate 图二): a 40dp visual chip inside a ≥48dp touch box —
    // the old ~30dp pill read as cramped little cards. Selected state is the solid
    // accent container + trailing check (filter-chip convention) instead of a tint.
    Box(
        modifier = Modifier
            .alpha(if (dimmed) 0.65f else 1f)
            .clip(shape)
            .combinedClickable(
                onLongClickLabel = if (onLongClick != null) longClickLabel else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            // TalkBack 读出选中态（第1轮可及性审查）：combinedClickable 不带
            // selected 语义，选中/未选中在无障碍里听起来一模一样。
            .semantics { selected = selected }
            // Padding INSIDE the clickable: expands the touch target to 48dp without
            // fattening the visual chip.
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(
                    width = if (selected) 0.dp else 1.dp,
                    color = if (selected) Color.Transparent else colors.surfaceStroke,
                    shape = shape,
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading(if (selected) colors.onAccent else colors.textTertiary)
            content()
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.onAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun HorizontalHairline() {
    androidx.compose.material3.HorizontalDivider(
        thickness = androidx.compose.ui.unit.Dp.Hairline,
        color = CodegTheme.colors.surfaceStroke,
    )
}
