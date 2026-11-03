package app.codeg.android.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.datastore.INBOX_IDEA_STYLE_CENTER_WIDE
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.rememberSheetDragLock
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The idea inbox + dispatch wizard (batch v140, rebuilt 批v146 阶段4+5). Ideas
 * are local drafts in a lightweight memo-style card (time + ⋯ menu + one 派发
 * action); dispatch runs the shared pipeline with the 任务书 §6.2 form order:
 * 设备与 Agent → 任务描述 → 目标（项目 + 分支） → 创建. An idea leaves the
 * inbox only when the server CONFIRMS the create; an UNKNOWN ref may only
 * re-dispatch after a probe verified "not created".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel = hiltViewModel(key = "inbox"),
    // 批v149 会话模式：派发确认后自动跳转会话（serverId 先落设备再推详情，
    // 会话详情读 selectedProfile 恰好一次，见 MainShell 的 onOpenSession 接线）。
    onOpenSession: (serverId: String, conversationId: Int) -> Unit = { _, _ -> },
    /** 批v153（UX 审查）：任务模式派发回执的「去任务板」直达。 */
    onOpenTasks: () -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
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
    // Success dialog lives HERE, not in DispatchSheet: a confirmed dispatch
    // deletes the idea, which nulls [dispatching] and unmounts the whole
    // sheet — a dialog inside it would vanish before the user reads it.
    var dispatchResult by remember { mutableStateOf<DispatchRef?>(null) }
    // 批v147 收件箱净化：列表只放本地草稿。派发回执（在途/已确认）不再独占
    // 卡片刷屏；UNKNOWN 的恢复入口挪到对应草稿卡上，草稿已删的孤儿 UNKNOWN
    // 保留一张紧凑恢复卡——去重闸门必须始终可解析，不能变成看不见的账。
    val unresolvedRefs = remember(ui.inFlightRefs, ui.pendingRefs) { ui.inFlightRefs + ui.pendingRefs }
    val ideaIds = remember(ui.ideas) { ui.ideas.map { it.id }.toSet() }
    val refByIdeaId = remember(unresolvedRefs) {
        unresolvedRefs.filter { it.ideaId != null }.associateBy { it.ideaId!! }
    }
    val orphanRefs = remember(unresolvedRefs, ideaIds) {
        unresolvedRefs.filter { it.ideaId == null || it.ideaId !in ideaIds }
    }

    // 批v151: "new idea" button placement (设置→通用→外壳). "corner" = classic M3
    // FAB bottom-right; "centerWide" = half-screen-width pill bottom-center so
    // either thumb reaches it. Both open the same IdeaEditorDialog.
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ideaStyle = settings.inboxNewIdeaStyle

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        floatingActionButton = {
            if (ideaStyle != INBOX_IDEA_STYLE_CENTER_WIDE) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.inbox_new))
                }
            }
        },
        bottomBar = {
            // Wide placement rides the bottomBar slot: Scaffold pads the list
            // content above it automatically, in both placements.
            if (ideaStyle == INBOX_IDEA_STYLE_CENTER_WIDE) {
                NewIdeaWideButton(onClick = { creating = true })
            }
        },
    ) { padding ->
        if (ui.ideas.isEmpty() && orphanRefs.isEmpty()) {
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
                orphanRefs.forEach { ref ->
                    item(key = "orphan-${ref.requestId}", contentType = { "unknown-ref" }) {
                        // 草稿已删（或旧版本记录没挂 ideaId）的 UNKNOWN 只能自立卡片：
                        // 去重闸门靠它保持可解析，绝不能无声消失。
                        UnknownRefCard(
                            ref = ref,
                            probing = ref.requestId in ui.probingIds,
                            canRedispatch = ref.requestId in ui.probeClearedIds,
                            onProbe = { viewModel.probe(ref.requestId) },
                            onDiscard = { confirmRef = ref; confirmRedispatch = false },
                            onRedispatch = { confirmRef = ref; confirmRedispatch = true },
                        )
                    }
                }
                items(ui.ideas, key = { it.id }, contentType = { "idea" }) { idea ->
                    val ref = refByIdeaId[idea.id]
                    IdeaCard(
                        idea = idea,
                        unresolved = ref,
                        probing = ref != null && ref.requestId in ui.probingIds,
                        canRedispatch = ref != null && ref.requestId in ui.probeClearedIds,
                        onDispatch = { dispatchingId = idea.id },
                        onEdit = { editingId = idea.id },
                        onCopy = { context ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("idea", "${idea.title}\n${idea.description}"))
                        },
                        onDelete = { pendingDelete = idea },
                        onProbe = { ref?.let { viewModel.probe(it.requestId) } },
                        onDiscard = { ref?.let { confirmRef = it; confirmRedispatch = false } },
                        onRedispatch = { ref?.let { confirmRef = it; confirmRedispatch = true } },
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
            onClose = { dispatchingId = null },
            // 批v149：会话模式确认后不弹回执框——直接关单跳会话（首条指令由
            // 会话详情页自动发送）；任务模式维持原回执对话框。
            onResult = { ref ->
                val convId = ref.remoteTodoId
                if (ref.sessionMode && ref.state == DispatchState.CONFIRMED && convId != null) {
                    dispatchingId = null
                    onOpenSession(ref.serverId, convId)
                } else {
                    dispatchResult = ref
                }
            },
        )
    }

    dispatchResult?.let { ref ->
        AlertDialog(
            onDismissRequest = { dispatchResult = null; dispatchingId = null },
            title = { Text(stringResource(R.string.dispatch_ok, ref.remoteTodoId ?: 0)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${ref.serverName} · ${ref.title}")
                    ui.dispatchError?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                }
            },
            confirmButton = {
                // 批v153（UX 审查）：回执直达任务板。R4 墨菲审查：能自动跳会话的
                // CONFIRMED（sessionMode + convId 非空）根本不会进本对话框，gate
                // 只看 state——会话模式 convId=null 的死路回执同样有去处。
                Row {
                    if (ref.state == DispatchState.CONFIRMED) {
                        TextButton(onClick = {
                            dispatchResult = null
                            dispatchingId = null
                            onOpenTasks()
                        }) {
                            Text(stringResource(R.string.receipt_open_task_board))
                        }
                    }
                    TextButton(onClick = { dispatchResult = null; dispatchingId = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            },
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

/**
 * Memo-style idea card (任务书 §5.2): time top-left, ⋯ menu top-right
 * (编辑/复制/删除, delete confirmed by the caller), one prominent 派发 action
 * at the bottom. Description truncates at 3 lines.
 *
 * 批v147：派发回执不再独占列表卡片——本卡未决时（[unresolved] 非空）在
 * 描述与派发钮之间内联一行状态：PENDING 显示"提交中"，UNKNOWN 给出
 * 检查/重派/放弃入口；未决期间主派发钮禁用（去重闸门本来也会拦）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeaCard(
    idea: IdeaEntry,
    unresolved: DispatchRef?,
    probing: Boolean,
    canRedispatch: Boolean,
    onDispatch: () -> Unit,
    onEdit: () -> Unit,
    onCopy: (Context) -> Unit,
    onDelete: () -> Unit,
    onProbe: () -> Unit,
    onDiscard: () -> Unit,
    onRedispatch: () -> Unit,
) {
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatStamp(idea.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_edit)) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        val context = LocalContext.current
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_copy)) },
                            onClick = { menuOpen = false; onCopy(context) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_delete), color = colors.danger) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            Text(
                idea.title.ifBlank { stringResource(R.string.inbox_untitled) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (idea.description.isNotBlank()) {
                Text(
                    idea.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (unresolved != null) {
                val inFlight = unresolved.state == DispatchState.PENDING
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    if (probing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        stringResource(if (inFlight) R.string.dispatch_pending_note else R.string.dispatch_unknown),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (inFlight) colors.textSecondary else colors.danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!inFlight) {
                        TextButton(onClick = onProbe, enabled = !probing, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(stringResource(R.string.dispatch_unknown_check), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = onRedispatch,
                            enabled = !probing && canRedispatch,
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(
                                stringResource(R.string.dispatch_redispatch),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (!probing && canRedispatch) colors.textPrimary else colors.textTertiary,
                            )
                        }
                        TextButton(onClick = onDiscard, enabled = !probing, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(stringResource(R.string.dispatch_unknown_discard), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (!inFlight && !canRedispatch) {
                    Text(
                        stringResource(R.string.dispatch_unknown_need_probe),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        colors.accent.copy(alpha = if (unresolved == null) 0.14f else 0.06f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = unresolved == null, onClick = onDispatch)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.inbox_dispatch),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (unresolved == null) colors.accent else colors.textTertiary,
                )
            }
        }
    }
}

/**
 * UNKNOWN dispatch card: 检查服务器 always available; 重新派发 unlocks ONLY
 * after a probe verified "not created" (任务书 §5.4 未核实不能再次创建).
 */
@Composable
private fun UnknownRefCard(
    ref: DispatchRef,
    probing: Boolean,
    canRedispatch: Boolean,
    onProbe: () -> Unit,
    onDiscard: () -> Unit,
    onRedispatch: () -> Unit,
) {
    val colors = CodegTheme.colors
    // PENDING 的 ref 只是路过 UNKNOWN 视图（第1轮派发审查修正）：动作全禁 +
    // 标题换成"派发中"口径，镜像 IdeaCard 的 inFlight 门——服务端确认落地前
    // 重派会双建、放弃会丢真任务。
    val inFlight = ref.state == DispatchState.PENDING
    // 批v147 视觉规范：拒绝红底大卡，孤儿 UNKNOWN 也只允许柔和底色 + 红字标题。
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.bgElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(if (inFlight) R.string.dispatch_pending_note else R.string.dispatch_unknown),
                style = MaterialTheme.typography.titleSmall,
                color = if (inFlight) colors.textSecondary else colors.danger,
            )
            Text(
                "${ref.title} · ${ref.serverName.ifBlank { ref.serverId }} · ${ref.folderName.ifBlank { "#${ref.folderId}" }}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (probing || inFlight) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                if (!inFlight) {
                    TextButton(onClick = onProbe, enabled = !probing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.dispatch_unknown_check), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = onRedispatch,
                        enabled = !probing && canRedispatch,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.dispatch_redispatch),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (!probing && canRedispatch) colors.textPrimary else colors.textTertiary,
                        )
                    }
                    TextButton(onClick = onDiscard, enabled = !probing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.dispatch_unknown_discard), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (!inFlight && !canRedispatch) {
                Text(
                    stringResource(R.string.dispatch_unknown_need_probe),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
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
            TextButton(onClick = { onSave(title, description) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * The unified dispatch panel — now a THIN host (批v150 §1.5 宿主 A): the shared
 * [app.codeg.android.feature.dispatch.DispatchForm] carries the whole flow
 * (执行方式 → 设备/Agent chips → 项目/分支 → 标题/描述 → 定时 → 摘要 → 主按钮,
 * selectors = chips + BottomSheet) and reads its state through this screen's
 * [InboxViewModel] (the v146 ledger invariants live there, unchanged). The
 * sheet contributes the fixed title (drag-to-close area, v151 contract) and
 * the idea prefill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DispatchSheet(
    idea: IdeaEntry,
    viewModel: InboxViewModel,
    onClose: () -> Unit,
    onResult: (DispatchRef) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        // 批v151 弹层契约：内容双向剩余全被锁吃掉，上下滚动两向都不再误触收起；
        // 拖拽关闭只走固定标题区（DispatchForm 自带内部滚动，标题在其外）。
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .nestedScroll(rememberSheetDragLock()),
        ) {
            // Fixed header outside DispatchForm's own scroll: the title row stays
            // the drag-to-close area (v151 sheet contract), same as QuickTaskSheet.
            // dragHandle=null → 标题条撑到 48dp（第1轮可及性审查）。
            Text(
                stringResource(R.string.inbox_dispatch),
                style = MaterialTheme.typography.titleMedium,
                color = CodegTheme.colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            app.codeg.android.feature.dispatch.DispatchForm(
                host = viewModel,
                defaultSessionMode = true,
                ideaId = idea.id,
                initialTitle = idea.title,
                initialDescription = idea.description,
                onResult = onResult,
            )
        }
    }
}

private fun formatStamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

/** 批v151 "底部居中" new-idea placement: half the screen wide, the FAB's 56dp
 *  height, accent pill — an alternative to the corner FAB for either-thumb reach. */
@Composable
private fun NewIdeaWideButton(onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.accent)
                .clickable(onClickLabel = stringResource(R.string.inbox_new), onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = colors.onAccent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.inbox_new),
                color = colors.onAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
