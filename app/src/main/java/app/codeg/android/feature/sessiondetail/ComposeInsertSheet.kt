package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.component.sheetContentScrollLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.core.model.ExpertListItem
import app.codeg.android.core.model.QuickMessage

/** Draft transforms for the compose "+" inserts. Port of iOS `ComposeInsertModel`. */
object ComposeInsert {
    fun appendMessage(content: String, draft: String): String =
        if (draft.isBlank()) content else "${draft.trimEnd()} $content"

    fun appendCommand(name: String, draft: String): String =
        if (draft.isBlank()) "/$name " else "${draft.trimEnd()} /$name "

    /** Prepend `<prefix><id> `, replacing a leading expert mention if its id is known. */
    fun applyExpert(id: String, draft: String, agent: AgentType, knownIds: Set<String>): String {
        val prefix = if (agent == AgentType.CODEX) "$" else "/"
        val regex = Regex("^" + Regex.escape(prefix) + "([A-Za-z0-9_-]+)\\s+([\\s\\S]*)$")
        val rest = regex.find(draft)?.let { m -> if (knownIds.contains(m.groupValues[1])) m.groupValues[2] else null } ?: draft
        return if (rest.isBlank()) "$prefix$id " else "$prefix$id ${rest.trimStart()}"
    }
}

private enum class InsertSource { QUICK, EXPERTS, COMMANDS, IDEAS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeInsertSheet(
    viewModel: SessionDetailViewModel,
    onInsert: ((String) -> String) -> Unit,
    onPickFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var source by remember { mutableStateOf(InsertSource.QUICK) }
    var loading by remember { mutableStateOf(true) }
    var quick by remember { mutableStateOf<List<QuickMessage>>(emptyList()) }
    var experts by remember { mutableStateOf<List<ExpertListItem>>(emptyList()) }
    var commands by remember { mutableStateOf<List<AvailableCommandInfo>>(emptyList()) }
    // 批v162 正向快链：想法卡列表 + 点选后的预览确认（导入前必看全文）。
    var ideas by remember { mutableStateOf<List<IdeaEntry>>(emptyList()) }
    var previewIdea by remember { mutableStateOf<IdeaEntry?>(null) }
    var knownIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(source) {
        loading = true
        // 切换来源时退出预览：预览态只属于当前来源。
        previewIdea = null
        when (source) {
            InsertSource.QUICK -> quick = viewModel.loadQuickMessages()
            InsertSource.EXPERTS -> { experts = viewModel.loadExpertsForInsert(); knownIds = viewModel.knownExpertIds() }
            InsertSource.COMMANDS -> commands = viewModel.loadSlashCommands()
            InsertSource.IDEAS -> ideas = viewModel.loadIdeas()
        }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().sheetContentScrollLock().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // File upload first — it's an action (opens the system file manager), not
            // an insert source, so it sits above the segmented selector.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPickFiles() }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.InsertDriveFile,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(stringResource(R.string.insert_pick_files), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    Text(stringResource(R.string.insert_pick_files_hint), fontSize = 12.sp, color = colors.textTertiary)
                }
            }
            HorizontalDivider(thickness = androidx.compose.ui.unit.Dp.Hairline, color = colors.surfaceStroke)
            // Segmented selector.
            val sources = InsertSource.entries
            val sourceLabels = listOf(
                stringResource(R.string.insert_quick_messages),
                stringResource(R.string.insert_experts),
                stringResource(R.string.insert_commands),
                stringResource(R.string.insert_ideas),
            )
            CodegSegmented(
                options = sourceLabels,
                selectedIndex = sources.indexOf(source),
                onSelect = { source = sources[it] },
            )

            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                when {
                    // 批v162：预览确认盖在列表上——同一块面板，返回即回列表。
                    previewIdea != null -> previewIdea?.let { idea ->
                        IdeaPreviewPane(
                            idea = idea,
                            onBack = { previewIdea = null },
                            onImportText = {
                                onInsert { draft ->
                                    viewModel.importIdeaText(
                                        app.codeg.android.feature.inbox.IdeaPromptBuilder.compose(idea.title, idea.description),
                                        draft,
                                    )
                                }
                                onDismiss()
                            },
                            onImportWithAttachments = {
                                onInsert { draft ->
                                    viewModel.importIdeaText(
                                        app.codeg.android.feature.inbox.IdeaPromptBuilder.compose(idea.title, idea.description),
                                        draft,
                                    )
                                }
                                viewModel.importIdeaAttachments(idea.attachments)
                                onDismiss()
                            },
                        )
                    }
                    loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                    source == InsertSource.QUICK -> InsertList(quick.isEmpty(), stringResource(R.string.insert_empty_quick)) {
                        items(quick, key = { it.id }) { qm ->
                            InsertRow(qm.title.ifEmpty { qm.content }, qm.content) {
                                onInsert { draft -> ComposeInsert.appendMessage(qm.content, draft) }; onDismiss()
                            }
                        }
                    }
                    source == InsertSource.EXPERTS -> InsertList(experts.isEmpty(), stringResource(R.string.insert_empty_experts)) {
                        items(experts, key = { it.id }) { ex ->
                            InsertRow(ex.metadata.title(), ex.metadata.desc()) {
                                onInsert { draft -> ComposeInsert.applyExpert(ex.id, draft, viewModel.agentForInsert, knownIds) }; onDismiss()
                            }
                        }
                    }
                    source == InsertSource.IDEAS -> InsertList(ideas.isEmpty(), stringResource(R.string.insert_empty_ideas)) {
                        items(ideas, key = { it.id }) { idea ->
                            IdeaInsertRow(idea) { previewIdea = idea }
                        }
                    }
                    else -> InsertList(commands.isEmpty(), stringResource(R.string.insert_empty_commands)) {
                        items(commands, key = { it.name }) { cmd ->
                            InsertRow("/${cmd.name}", cmd.description ?: "", mono = true) {
                                onInsert { draft -> ComposeInsert.appendCommand(cmd.name, draft) }; onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsertList(empty: Boolean, emptyText: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val colors = CodegTheme.colors
    if (empty) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text(emptyText, fontSize = 13.sp, color = colors.textTertiary) }
    } else {
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun InsertRow(title: String, subtitle: String, mono: Boolean = false, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 批v162：想法来源的行——标题 + 描述摘要 + 附件数。点选不直接插入，先进
 * 预览确认（导入是"往草稿里塞一大段"，用户必须先看到全文）。
 */
@Composable
private fun IdeaInsertRow(idea: IdeaEntry, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            idea.title.ifBlank { idea.description.lineSequence().firstOrNull().orEmpty().ifBlank { "…" } },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (idea.description.isNotBlank()) {
            Text(idea.description, fontSize = 12.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (idea.attachments.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.InsertDriveFile,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
                Text(stringResource(R.string.attach_count, idea.attachments.size), fontSize = 12.sp, color = colors.textTertiary)
            }
        }
    }
}

/**
 * 批v162：导入前的预览确认面板（盖在列表位置）。标题/描述全文/附件缩略图，
 * 底部「只导文字 / 文字+附件」两个出口；无附件时只给一个「导入」。
 */
@Composable
private fun IdeaPreviewPane(
    idea: IdeaEntry,
    onBack: () -> Unit,
    onImportText: () -> Unit,
    onImportWithAttachments: () -> Unit,
) {
    val colors = CodegTheme.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 返回箭头：预览态回想法列表，不关表单。
            IconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.insert_idea_preview_back),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(stringResource(R.string.insert_idea_preview_title), fontSize = 13.sp, color = colors.textTertiary)
        }
        Text(
            idea.title.ifBlank { stringResource(R.string.inbox_untitled) },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        if (idea.description.isNotBlank()) {
            // 描述全文可滚：预览的价值就在"看得全"，截断就失去意义了。
            Text(
                idea.description,
                fontSize = 13.sp,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            )
        }
        if (idea.attachments.isNotEmpty()) {
            val images = idea.attachments.filter { it.isImage }
            if (images.isNotEmpty()) {
                // LazyRow 缩略图（最多 20 张，横排不撑爆面板）。
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(images, key = { it.id }) { att ->
                        app.codeg.android.core.designsystem.component.LocalImageThumbnail(
                            path = att.localPath,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
            }
            val files = idea.attachments.filterNot { it.isImage }
            files.forEach { att ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.InsertDriveFile,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(att.name, fontSize = 12.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (idea.attachments.isEmpty()) {
                // 没附件：单一出口，别摆一个永远用不上的"文字+附件"。
                Button(onClick = onImportText, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.insert_idea_import_text))
                }
            } else {
                Button(onClick = onImportText, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.insert_idea_import_text))
                }
                Button(onClick = onImportWithAttachments, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.insert_idea_import_all))
                }
            }
        }
    }
}
