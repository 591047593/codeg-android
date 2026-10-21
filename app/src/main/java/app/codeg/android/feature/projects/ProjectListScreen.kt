package app.codeg.android.feature.projects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.common.rememberNowInstant
import app.codeg.android.core.designsystem.component.ErrorBanner
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.FolderBadge
import app.codeg.android.core.designsystem.component.GlassCard
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LivePulse
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.FolderDetail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    onOpenFolder: (Int) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    // Which folder the user asked to remove. Confirmed before the call.
    var pendingRemove by remember { mutableStateOf<FolderDetail?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_folders)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { menuOpen = true },
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.projects_add))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_open_folder)) },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                        onClick = { menuOpen = false; showBrowser = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.projects_clone_repo)) },
                        leadingIcon = { Icon(Icons.Rounded.CloudDownload, null) },
                        onClick = { menuOpen = false; showClone = true },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !ui.hasLoaded ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }
                !ui.hasLoaded && ui.error != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(Icons.Rounded.FolderOpen, stringResource(R.string.projects_load_failed), ui.error!!, viewModel::refresh)
                    }
                ui.hasLoaded && ui.folders.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Rounded.FolderOpen, stringResource(R.string.projects_empty_title), stringResource(R.string.projects_empty_message))
                    }
                else -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                    ) {
                        // One continuous grouped surface holding every folder as a
                        // borderless row split by inset hairlines — the iOS grouped-list
                        // look, matching the Settings tab. Folder counts are small, so a
                        // single card inside the lazy list is fine.
                        item {
                            GlassCard(padding = 0.dp) {
                                // Sorted once per folder-set rather than on every
                                // recomposition of the list card.
                                val list = remember(ui.folders) { sortedFolders(ui.folders) }
                                list.forEachIndexed { index, folder ->
                                    if (index > 0) {
                                        HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(start = 66.dp))
                                    }
                                    FolderRow(
                                        folder = folder,
                                        running = ui.runningCount(folder.id),
                                        onClick = { onOpenFolder(folder.id) },
                                        onRemove = { pendingRemove = folder },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBrowser) {
        DirectoryBrowserSheet(
            title = stringResource(R.string.projects_open_folder),
            confirmLabel = stringResource(R.string.projects_open_this_folder),
            loadHome = viewModel::homeDirectory,
            loadDirs = viewModel::listDirectories,
            onSelect = { path -> showBrowser = false; viewModel.openFolder(path) {} },
            onDismiss = { showBrowser = false },
        )
    }
    if (showClone) {
        CloneRepoSheet(
            loadHome = viewModel::homeDirectory,
            loadDirs = viewModel::listDirectories,
            clone = viewModel::clone,
            onCloned = { showClone = false; viewModel.refresh() },
            onDismiss = { showClone = false },
        )
    }

    // Removal is bookkeeping only — the directory and its conversations stay put — but
    // it is still not something to trigger by accident, so it is confirmed and the
    // dialog says plainly what is not being deleted.
    pendingRemove?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            containerColor = colors.bgElevated,
            title = { Text(stringResource(R.string.projects_remove_title), color = colors.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.projects_remove_message, folder.name),
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = folder
                        pendingRemove = null
                        viewModel.removeFromHistory(target) { err -> removeError = err }
                    },
                ) { Text(stringResource(R.string.common_remove), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }

    removeError?.let { message ->
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.BottomCenter) {
            ErrorBanner(message = message, onDismiss = { removeError = null })
        }
    }
}


/**
 * One folder as a borderless grouped row: a colored [FolderBadge] tile, the name
 * with its full path + branch underneath, a trailing running-count pill or
 * relative last-opened time, then a drill-in chevron. The enclosing [GlassCard]
 * draws the surface; rows are split by inset hairlines.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderDetail,
    running: Int,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val tile = colorFromHex(folder.color) ?: colors.accent
    val now by rememberNowInstant()
    val justNowLabel = stringResource(R.string.time_just_now)
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    Row(
        modifier.fillMaxWidth()
            // Long-press opens the removal menu; a plain tap still opens the folder.
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FolderBadge(color = tile, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(folder.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                folder.gitBranch?.let { BranchPill(it) }
            }
        }
        if (running > 0) {
            RunningPill(running)
        } else {
            folder.lastOpenedAt?.let {
                Text(
                    RelativeTime.compact(it, now = now, justNow = justNowLabel),
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                )
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // Only the history removal takes the row out of this list: the list is built
            // from `list_all_folder_details`, and `remove_folder_from_workspace` merely
            // closes an open folder — the row reappears on the next refresh. Verified
            // against the live server: history removal dropped the count 32 -> 31,
            // workspace removal left it at 32.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.projects_remove_from_list)) },
                leadingIcon = { Icon(Icons.Rounded.FolderOff, null, tint = colors.textSecondary) },
                onClick = { menuOpen = false; onRemove() },
            )
        }
    }
}

/** Active-task indicator: a pulsing dot + count in an accent capsule (iOS folder row). */
@Composable
private fun RunningPill(count: Int) {
    val colors = CodegTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.accentDim).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        LivePulse(dotSize = 7.dp)
        Text("$count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.accent)
    }
}

@Composable
fun BranchPill(branch: String) {
    val colors = CodegTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.textPrimary.copy(alpha = 0.06f)).padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.AltRoute, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(11.dp))
        Text(
            branch,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun sortedFolders(folders: List<FolderDetail>): List<FolderDetail> =
    folders.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
