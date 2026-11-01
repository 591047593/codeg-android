package app.codeg.android.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.data.TaskStatusTone
import app.codeg.android.core.data.taskStatusUi
import app.codeg.android.core.designsystem.theme.CodegTheme

/**
 * 批v150 工作台头三块（统计行 / 快捷入口行 / 需要处理预览）——全部真实数据：
 * 数字来自 [WorkbenchViewModel] 的任务板同源投影，快捷入口只做导航，预览行
 * 跳任务板。放在 feed 上方的固定区，feed 的滚动/高亮机制（v132-v137）零改动。
 */

/** One stat card: value + label. Value "…" until the first round lands. */
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 统计行：运行中 / 需要处理 / 在线设备（真实计数，首round未落地前显示 …）。 */
@Composable
internal fun WorkbenchStatsRow(stats: WorkbenchStats, modifier: Modifier = Modifier) {
    val value = { n: Int -> if (stats.hasLoaded) n.toString() else "…" }
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(stringResource(R.string.workbench_stat_running), value(stats.runningTasks), Modifier.weight(1f))
        StatCard(
            stringResource(R.string.workbench_stat_attention),
            value(stats.attentionTasks),
            Modifier.weight(1f),
        )
        StatCard(
            stringResource(R.string.workbench_stat_online),
            if (stats.hasLoaded) "${stats.onlineDevices}/${stats.totalDevices}" else "…",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 快捷入口行：会话 / 项目 / 搜索 + 新建会话主按钮。 */
@Composable
internal fun WorkbenchQuickRow(
    onOpenChats: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickCard(Icons.Rounded.Forum, stringResource(R.string.workbench_quick_chats), onOpenChats, Modifier.weight(1f))
        QuickCard(Icons.Rounded.FolderOpen, stringResource(R.string.workbench_quick_folders), onOpenFolders, Modifier.weight(1f))
        QuickCard(Icons.Rounded.Search, stringResource(R.string.workbench_quick_search), onOpenSearch, Modifier.weight(1f))
        // Primary action: same frosted-chip language as the ball size chips.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1.4f)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accent)
                .clickable(onClick = onOpenNewSession)
                .padding(horizontal = 8.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(R.string.workbench_new_session),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onAccent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** One 需要处理 preview row: status tag + title + device. Tap → task board. */
@Composable
private fun AttentionRow(entry: app.codeg.android.core.data.TaskBoardEntry, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val status = taskStatusUi(entry.task)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = when (status.tone) {
                TaskStatusTone.WARN, TaskStatusTone.DANGER -> colors.warning.copy(alpha = 0.16f)
                TaskStatusTone.RUN, TaskStatusTone.INFO -> colors.accentDim
                else -> colors.textPrimary.copy(alpha = 0.08f)
            },
        ) {
            Text(
                text = if (status.labelRes != 0) stringResource(status.labelRes) else status.raw.orEmpty(),
                fontSize = 11.sp,
                color = when (status.tone) {
                    TaskStatusTone.WARN, TaskStatusTone.DANGER -> colors.warning
                    TaskStatusTone.RUN, TaskStatusTone.INFO -> colors.accent
                    else -> colors.textSecondary
                },
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
            text = entry.task.title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entry.serverName,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 需要处理卡：≤3 条真实任务预览 + 去任务板。空态整块不渲染。 */
@Composable
internal fun WorkbenchAttentionCard(
    stats: WorkbenchStats,
    onOpenTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    if (stats.attentionTasks == 0) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.workbench_stat_attention),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenTasks).padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(R.string.workbench_open_task_board),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.size(2.dp))
        stats.attentionPreview.forEach { entry -> AttentionRow(entry, onOpenTasks) }
        if (stats.attentionTasks > stats.attentionPreview.size) {
            Text(
                text = stringResource(R.string.workbench_attention_more, stats.attentionTasks - stats.attentionPreview.size),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp).clickable(onClick = onOpenTasks),
            )
        }
    }
}

/** The whole fixed header: stats → quick entry → attention preview. */
@Composable
internal fun WorkbenchHeader(
    stats: WorkbenchStats,
    onOpenChats: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNewSession: () -> Unit,
    onOpenTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WorkbenchStatsRow(stats, modifier = Modifier.padding(top = 8.dp))
        WorkbenchQuickRow(onOpenChats, onOpenFolders, onOpenSearch, onOpenNewSession)
        WorkbenchAttentionCard(stats, onOpenTasks)
    }
}
