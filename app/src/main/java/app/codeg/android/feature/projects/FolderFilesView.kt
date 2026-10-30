package app.codeg.android.feature.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.designsystem.component.CopyButton
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.DirectoryItem
import app.codeg.android.core.model.GitChange
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.designsystem.theme.CodegMono

/** Recursive file browser with an internal directory stack + file preview. */
@Composable
fun FolderFilesView(client: CodegClient, rootPath: String) {
    val colors = CodegTheme.colors
    val appContext = LocalContext.current
    var stack by remember { mutableStateOf(listOf(rootPath)) }
    var previewPath by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = previewPath != null || stack.size > 1) {
        if (previewPath != null) previewPath = null else stack = stack.dropLast(1)
    }

    val preview = previewPath
    if (preview != null) {
        FilePreview(client, rootPath, preview)
        return
    }

    // Bumped by pull-to-refresh; both the listing and the status re-fetch on it, because
    // a file the user just wrote on the server is exactly the case where the cached
    // colours would be stale.
    var refreshToken by remember(rootPath) { mutableStateOf(0) }
    var refreshing by remember(rootPath) { mutableStateOf(false) }

    val dir = stack.last()
    var result by remember(dir) { mutableStateOf<Result<List<DirectoryItem>>?>(null) }
    LaunchedEffect(dir, refreshToken) {
        result = null
        result = runCatching { client.listDirectoryWithFiles(dir) }
    }

    // Working-tree state for the whole repo, once per directory change. The listing
    // endpoint carries no git data, so the colours come from a separate `git_status`
    // mapped onto repo-relative paths — the same source the desktop tree paints from.
    // A repo-less folder (or a failed call) simply has no colours; it must never take
    // the browser down with it.
    // Keyed on the repo root, not the directory: `git_status` reports the whole tree, so
    // re-fetching it on every folder the user opens would pay a full status walk per tap
    // for data that has not changed.
    var changes by remember(rootPath) { mutableStateOf<Map<String, GitChange>>(emptyMap()) }
    var dirChanges by remember(rootPath) { mutableStateOf<Map<String, GitChange>>(emptyMap()) }
    LaunchedEffect(rootPath, refreshToken) {
        val statuses = runCatching { client.gitStatus(rootPath) }.getOrDefault(emptyList())
        changes = statuses.associate { it.path to it.change }
        // A directory row shows the strongest change anywhere beneath it, so a folded
        // folder still signals that something inside it is dirty.
        val rolled = mutableMapOf<String, GitChange>()
        for ((path, change) in changes) {
            var prefix = path.substringBeforeLast('/', "")
            while (prefix.isNotEmpty()) {
                rolled[prefix] = strongest(rolled[prefix], change)
                prefix = prefix.substringBeforeLast('/', "")
            }
        }
        dirChanges = rolled
        refreshing = false
    }

    val r = result
    when {
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(message = stringResource(R.string.common_loading)) }
        r.isFailure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            InlineError(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.files_load_failed), r.exceptionOrNull()?.displayMessage(appContext) ?: stringResource(R.string.files_error), onRetry = { result = null })
        }
        else -> {
            // Sorting on every recomposition (scroll, selection, theme) re-ran an
            // O(n log n) compare over the whole directory listing; the list itself
            // only changes when the fetch result does.
            val items = remember(r) { sortItems(r.getOrDefault(emptyList())) }
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    refreshToken += 1
                },
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                if (stack.size > 1) {
                    item("up") {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { stack = stack.dropLast(1) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                            Text("..", fontFamily = CodegMono, color = colors.textSecondary)
                        }
                    }
                }
                if (items.isEmpty()) {
                    item("empty") { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { EmptyState(Icons.Rounded.Folder, stringResource(R.string.files_empty_folder), stringResource(R.string.files_empty_folder_message)) } }
                }
                items(items, key = { it.path }) { item ->
                    val rel = if (item.path.startsWith("$rootPath/")) item.path.removePrefix("$rootPath/") else item.path
                    val change = if (item.isDir) dirChanges[rel] else changes[rel]
                    FileRow(item, change) {
                        if (item.isDir) stack = stack + item.path else previewPath = item.path
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun FileRow(item: DirectoryItem, change: GitChange?, onClick: () -> Unit) {
    val colors = CodegTheme.colors
    val sizeLabel = item.size?.takeIf { !item.isDir }?.let { formatBytes(it) }
    val tint = change?.let { GitChangeColors[it] }
    ListItem(
        headlineContent = {
            Text(
                item.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A changed file's name is tinted the way the desktop tree tints it, so
                // the eye finds dirty files without reading the badge column.
                color = tint ?: colors.textPrimary,
            )
        },
        leadingContent = {
            Icon(
                if (item.isDir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = if (item.isDir) colors.accent else colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sizeLabel?.let { Text(it, fontSize = 11.sp, color = colors.textTertiary) }
                if (change != null) GitChangeBadge(change)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = colors.textPrimary),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() },
    )
}

@Composable
private fun FilePreview(client: CodegClient, rootPath: String, absPath: String) {
    val colors = CodegTheme.colors
    val appContext = LocalContext.current
    val rel = if (absPath.startsWith("$rootPath/")) absPath.removePrefix("$rootPath/") else absPath.substringAfterLast('/')
    var result by remember(absPath) { mutableStateOf<Result<String>?>(null) }
    LaunchedEffect(absPath) { result = runCatching { client.readFilePreview(rootPath, rel).content } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(rel, fontFamily = CodegMono, fontSize = 12.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            result?.getOrNull()?.let { if (it.isNotEmpty()) CopyButton(it) }
        }
        val r = result
        when {
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
            r.isFailure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                InlineError(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.files_read_failed), r.exceptionOrNull()?.displayMessage(appContext) ?: stringResource(R.string.files_error), onRetry = { result = null })
            }
            else -> {
                val content = r.getOrDefault("")
                if (content.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.files_empty_file), stringResource(R.string.files_empty_file_message)) }
                } else {
                    FileContent(content)
                }
            }
        }
    }
}

@Composable
private fun FileContent(content: String) {
    val colors = CodegTheme.colors
    val truncationNote = stringResource(R.string.files_truncated_note, 4000)
    val lines = remember(content, truncationNote) {
        val all = content.split("\n")
        if (all.size > 4000) all.take(4000) + truncationNote else all
    }
    val gutterWidth = (lines.size.toString().length * 8 + 12).dp

    // Long lines wrap instead of each row owning a horizontal ScrollState.
    //
    // The previous version called `horizontalScroll(rememberScrollState())` inside the
    // LazyColumn's item lambda. `remember` is scoped to the composition that calls it,
    // so that minted a *separate* ScrollState for every row — up to 4000 remember slots
    // for a large file — and each row panned independently, so the file never scrolled
    // as a document. Sharing one state across rows is not a valid alternative: parallel
    // scroll modifiers on the same state contend and only one of them wins.
    //
    // Wrapping is the honest fix here. The vertical list already virtualises, the row
    // count is capped at 4000 before the split, and a wrapped long line is readable
    // where a per-row horizontal pan was not. `softWrap` is the Text default, so this
    // is simply the absence of the scroll modifier.
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.codeSurface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        // No item key: the row identity IS its index, and a different file changes the
        // content behind every index — a key would claim the row survived when only the
        // position did.
        items(lines.size) { i ->
            Row {
                Text(
                    "${i + 1}",
                    fontFamily = CodegMono,
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.width(gutterWidth).padding(start = 6.dp, end = 6.dp),
                )
                Text(
                    lines[i].ifEmpty { " " },
                    fontFamily = CodegMono,
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

private fun sortItems(items: List<DirectoryItem>): List<DirectoryItem> =
    items.sortedWith(compareByDescending<DirectoryItem> { it.isDir }.thenBy { it.name.lowercase() })

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** The more alarming of two change categories, for rolling a folder's state up. */
private fun strongest(current: GitChange?, next: GitChange): GitChange = when {
    current == null -> next
    current == GitChange.CONFLICTED || next == GitChange.CONFLICTED -> GitChange.CONFLICTED
    current == GitChange.DELETED || next == GitChange.DELETED -> GitChange.DELETED
    current == GitChange.MODIFIED || next == GitChange.MODIFIED -> GitChange.MODIFIED
    current == GitChange.RENAMED || next == GitChange.RENAMED -> GitChange.RENAMED
    current == GitChange.ADDED || next == GitChange.ADDED -> GitChange.ADDED
    current == GitChange.UNTRACKED || next == GitChange.UNTRACKED -> GitChange.UNTRACKED
    else -> current
}
