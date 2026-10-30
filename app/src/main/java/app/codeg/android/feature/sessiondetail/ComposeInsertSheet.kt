package app.codeg.android.feature.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.component.CodegSegmented
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AvailableCommandInfo
import app.codeg.android.core.model.DirectoryItem
import app.codeg.android.core.model.ExpertListItem
import app.codeg.android.core.model.QuickMessage
import kotlinx.coroutines.launch
import app.codeg.android.core.designsystem.theme.CodegMono

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

private enum class InsertSource { QUICK, FILES, EXPERTS, COMMANDS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeInsertSheet(
    viewModel: SessionDetailViewModel,
    onInsert: ((String) -> String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CodegTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var source by remember { mutableStateOf(InsertSource.QUICK) }
    var loading by remember { mutableStateOf(true) }
    var quick by remember { mutableStateOf<List<QuickMessage>>(emptyList()) }
    var experts by remember { mutableStateOf<List<ExpertListItem>>(emptyList()) }
    var commands by remember { mutableStateOf<List<AvailableCommandInfo>>(emptyList()) }
    // Directory browser state for the Files source: one level at a time, rooted at the
    // session's folder, because a phone cannot show a full tree and the flat listing
    // the server returns is already everything a picker needs.
    var fileDir by remember { mutableStateOf<String?>(null) }
    var fileEntries by remember { mutableStateOf<List<DirectoryItem>>(emptyList()) }
    var fileQuery by remember { mutableStateOf("") }
    var knownIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Directory navigation happens inside click callbacks, which are not suspend; the
    // listing call is a network round trip, so it runs on this scope and writes back
    // through the state above.
    val scope = rememberCoroutineScope()

    LaunchedEffect(source) {
        loading = true
        when (source) {
            InsertSource.QUICK -> quick = viewModel.loadQuickMessages()
            InsertSource.FILES -> {
                val root = viewModel.folderPathForInsert
                fileDir = root
                fileEntries = root?.let { r -> runCatching { viewModel.listDirectory(r) }.getOrDefault(emptyList()) } ?: emptyList()
            }
            InsertSource.EXPERTS -> { experts = viewModel.loadExpertsForInsert(); knownIds = viewModel.knownExpertIds() }
            InsertSource.COMMANDS -> commands = viewModel.loadSlashCommands()
        }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Segmented selector.
            val sources = InsertSource.entries
            val sourceLabels = listOf(
                stringResource(R.string.insert_quick_messages),
                stringResource(R.string.insert_files),
                stringResource(R.string.insert_experts),
                stringResource(R.string.insert_commands),
            )
            CodegSegmented(
                options = sourceLabels,
                selectedIndex = sources.indexOf(source),
                onSelect = { source = sources[it] },
            )

            Box(Modifier.fillMaxWidth().height(420.dp)) {
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                    source == InsertSource.QUICK -> InsertList(quick.isEmpty(), stringResource(R.string.insert_empty_quick)) {
                        items(quick, key = { it.id }) { qm ->
                            InsertRow(qm.title.ifEmpty { qm.content }, qm.content) {
                                onInsert { draft -> ComposeInsert.appendMessage(qm.content, draft) }; onDismiss()
                            }
                        }
                    }
                    source == InsertSource.FILES -> InsertFilesBrowser(
                        dir = fileDir,
                        root = viewModel.folderPathForInsert,
                        entries = fileEntries,
                        query = fileQuery,
                        onQuery = { fileQuery = it },
                        // The filter deliberately survives a directory change: the user
                        // is usually looking for a name, not for one particular folder, so
                        // clearing it on every step meant retyping it.
                        onOpenDir = { path ->
                            fileDir = path
                            scope.launch { fileEntries = viewModel.listDirectory(path) }
                        },
                        onUp = {
                            val root = viewModel.folderPathForInsert
                            val current = fileDir
                            if (root != null && current != null && current != root) {
                                val parent = current.substringBeforeLast('/', "")
                                // Never step above the session's folder: `parent` is a
                                // plain string prefix, so compare by containment rather
                                // than by length — a sibling directory can be longer than
                                // the root and a length check would wrongly accept it.
                                val target = if (parent.length >= root.length && parent.startsWith(root)) parent else root
                                if (target != current) {
                                    fileDir = target
                                    scope.launch { fileEntries = viewModel.listDirectory(target) }
                                }
                            }
                        },
                        onPick = { path ->
                            onInsert { draft -> appendFileReference(draft, path) }
                            onDismiss()
                        },
                    )
                    source == InsertSource.EXPERTS -> InsertList(experts.isEmpty(), stringResource(R.string.insert_empty_experts)) {
                        items(experts, key = { it.id }) { ex ->
                            InsertRow(ex.metadata.title(), ex.metadata.desc()) {
                                onInsert { draft -> ComposeInsert.applyExpert(ex.id, draft, viewModel.agentForInsert, knownIds) }; onDismiss()
                            }
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, fontFamily = if (mono) CodegMono else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The file picker behind the "+" sheet: one directory at a time, with a filter box.
 *
 * A phone has no room for a horizontal splitter, and the server's listing endpoint
 * returns exactly one level, so this is a folder-at-a-time browser with a search field
 * for jumping straight to a name. Picking a file inserts its `@` reference and closes the
 * sheet — the desktop's file mention, reduced to the one gesture that fits.
 */
@Composable
private fun InsertFilesBrowser(
    dir: String?,
    root: String?,
    entries: List<DirectoryItem>,
    query: String,
    onQuery: (String) -> Unit,
    onOpenDir: (String) -> Unit,
    onUp: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = CodegTheme.colors
    if (dir == null || root == null) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.insert_empty_files), fontSize = 13.sp, color = colors.textTertiary)
        }
        return
    }

    // Filtering is on the current directory only, which is what the server gave us; the
    // empty state says so instead of pretending the name does not exist repo-wide.
    val shown = remember(entries, query) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) entries else entries.filter { it.name.lowercase().contains(q) })
            .sortedWith(compareByDescending<DirectoryItem> { it.isDir }.thenBy { it.name.lowercase() })
    }

    // `fillMaxHeight` + a weighted list: the caller bounds this in height, and a
    // LazyColumn inside a Column without a weight is measured with an infinite maximum
    // height — it then either throws or lays out a single row.
    Column(Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dir != root) {
                Text(
                    "..",
                    fontFamily = CodegMono,
                    fontSize = 13.sp,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onUp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                // The path relative to the repo, which is what a prompt reads best as.
                dir.removePrefix(root).removePrefix("/").ifEmpty { "/" },
                fontFamily = CodegMono,
                fontSize = 11.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        CodegTextField(
            value = query,
            onValueChange = onQuery,
            label = stringResource(R.string.insert_files_filter),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.insert_empty_files), fontSize = 13.sp, color = colors.textTertiary)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shown, key = { it.path }) { entry ->
                    InsertRow(
                        title = entry.name,
                        subtitle = if (entry.isDir) stringResource(R.string.insert_files_folder) else formatSize(entry.size),
                        mono = !entry.isDir,
                    ) {
                        if (entry.isDir) onOpenDir(entry.path) else onPick(entry.path)
                    }
                }
            }
        }
    }
}

/** Compact byte size for a file row; directories have none. */
private fun formatSize(bytes: Long?): String = when {
    bytes == null -> ""
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
