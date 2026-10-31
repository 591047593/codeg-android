package app.codeg.android.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.component.CodegMenu
import app.codeg.android.core.designsystem.component.CodegMenuItem
import app.codeg.android.core.designsystem.tagColor
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.colorFromHex
import app.codeg.android.core.model.ConversationSummary

/**
 * The Chats tab: the grouped session list for the selected server (Pinned /
 * per-folder / Other), a server switcher in the title, and a "New Task" action.
 *
 * The iOS card-zoom interaction is adapted to native **collapsible sections**: tap a
 * section header to fold/unfold its rows. The grouping itself is computed off the main
 * thread ([SessionListViewModel.sections]) and the list is virtualized with per-type
 * recycling, so a very large session list stays smooth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelectServer: (String) -> Unit,
    onManageServers: () -> Unit,
    onOpenConversation: (Int) -> Unit,
    onNewTask: () -> Unit,
    /** Open a folder's project page — from the folder tag on a cross-folder row. */
    onOpenFolder: (Int) -> Unit,
    /** Starts a new conversation in a given folder (folder tag → menu). */
    onOpenSessionInFolder: (Int) -> Unit,
    /** Opens the conversation search screen. */
    onOpenSearch: () -> Unit,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    // id → #rrggbb, built once per tag-set change rather than per row: a row with three
    // tags would otherwise scan the definition list three times on every recomposition.
    val tagsById = remember(ui.tags) { ui.tags.associate { it.id to it.color } }
    // Collapsed section ids. In the composable (not the VM) so collapsing never
    // re-runs the grouping flow; rememberSaveable keeps the fold state across
    // rotation / process death.
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // Confirmed before deleting: a conversation and its transcript are gone for good.
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    val colors = CodegTheme.colors
    val selectedName = servers.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.app_name)

    Scaffold(
        containerColor = Color.Transparent,
        // The status bar is inset by the Scaffold itself. The activity draws edge to edge,
        // and with `WindowInsets(0,0,0,0)` plus no top bar the large title was painted
        // under the system clock and battery icons. Only the top is claimed here; the
        // bottom is the app's navigation bar, which already handles its own insets.
        contentWindowInsets = WindowInsets.statusBars,
        // No fixed top bar. The Codex list opens on a large title that scrolls away with
        // the content; a pinned bar over it wastes the top strip on a phone and makes the
        // heading compete with the rows for attention. The server switcher and the manage
        // action both live in the scrolling header instead.
        topBar = {},
        // The dock lives inside the content Box, not in `bottomBar`: this screen already
        // sits above the app's navigation bar, and a second Scaffold bottom bar would
        // stack two rows of chrome at the bottom of the page.

    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.isLoading && !ui.hasLoaded -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }
                }

                !ui.hasLoaded && ui.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.sessions_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::refresh,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }
                }

                ui.hasLoaded && ui.isEmpty -> {
                    // The empty state is inside the refresh container: a server that has
                    // no conversations yet is exactly when a manual refresh is needed
                    // (a session created from another client would otherwise only appear
                    // after an app restart), and it is also the only place a failing
                    // refresh can surface its error.
                    PullToRefreshBox(
                        isRefreshing = ui.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // A scrollable child is required for the pull gesture to reach the
                        // refresh container; a bare Box swallows nothing and never
                        // reports the drag.
                        LazyColumn(Modifier.fillMaxSize()) {
                            item {
                                Box(
                                    Modifier.fillParentMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    EmptyState(
                                        icon = Icons.Rounded.Forum,
                                        title = stringResource(R.string.sessions_empty_title),
                                        message = stringResource(R.string.sessions_empty_message),
                                        actionLabel = stringResource(R.string.session_new_task),
                                        onAction = onNewTask,
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = ui.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        SessionList(
                            sections = sections,
                            selectedName = selectedName,
                            servers = servers,
                            selectedId = selectedId,
                            onSelectServer = onSelectServer,
                            onManageServers = onManageServers,
                            expandedParents = ui.expandedParents,
                            loadingChildren = ui.loadingChildren,
                            onToggleChildren = viewModel::toggleChildren,
                            tagsById = tagsById,
                            collapsed = collapsed,
                            onToggleSection = { id ->
                                collapsed = if (id in collapsed) collapsed - id else collapsed + id
                            },
                            onOpenConversation = onOpenConversation,
                            onTogglePin = { conv -> viewModel.setPinned(conv, !conv.isPinned) },
                            onDelete = { conv -> pendingDelete = conv },
                            onOpenFolder = onOpenFolder,
                            onOpenSessionInFolder = onOpenSessionInFolder,
                            // A refresh that failed over a still-populated list: surface
                            // it inline above the rows rather than swallowing it.
                            refreshError = ui.error,
                            onRetry = viewModel::refresh,
                            onDismissError = viewModel::dismissError,
                        )
                    }
                }
            }
            // Declared AFTER the content. In a Box the later child draws on top, so with
            // the dock first the list sat over it and swallowed every tap — "start a new
            // task" did nothing at all.
            NewTaskDock(onClick = onNewTask, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    pendingDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.bgElevated,
            title = { Text(stringResource(R.string.sessionactions_delete_session_confirm), color = colors.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.sessionactions_delete_session_message),
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = conv
                    pendingDelete = null
                    viewModel.delete(target)
                }) { Text(stringResource(R.string.common_delete), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun SessionList(
    sections: List<SessionSection>,
    selectedName: String,
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelectServer: (String) -> Unit,
    onManageServers: () -> Unit,
    tagsById: Map<Int, String>,
    expandedParents: Set<Int>,
    loadingChildren: Set<Int>,
    onToggleChildren: (ConversationSummary) -> Unit,
    collapsed: Set<String>,
    onToggleSection: (String) -> Unit,
    onOpenConversation: (Int) -> Unit,
    onTogglePin: (ConversationSummary) -> Unit,
    onDelete: (ConversationSummary) -> Unit,
    onOpenFolder: (Int) -> Unit,
    /** Starts a new conversation in the given folder. */
    onOpenSessionInFolder: (Int) -> Unit,
    refreshError: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    val colors = CodegTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            // Clears the docked new-task field (~60dp) plus a little air.
            bottom = 76.dp,
        ),
    ) {
        // The large title, as the first scrolling item. It carries the screen's identity
        // (which server you are looking at) and the manage-servers action, and it leaves
        // the moment you start reading.
        item(key = "large-title", contentType = "title") {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ServerSwitcher(
                    name = selectedName,
                    servers = servers,
                    selectedId = selectedId,
                    onSelect = onSelectServer,
                    onManageServers = onManageServers,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.tab_search),
                        tint = colors.textSecondary,
                    )
                }
                IconButton(onClick = onManageServers) {
                    Icon(
                        Icons.Rounded.Dns,
                        contentDescription = stringResource(R.string.home_manage_servers),
                        tint = colors.textTertiary,
                    )
                }
            }
        }

        if (refreshError != null) {
            item(key = "refresh-error", contentType = "banner") {
                RefreshErrorBanner(
                    message = refreshError,
                    onRetry = onRetry,
                    onDismiss = onDismissError,
                    modifier = Modifier.animateItem().padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }

        sections.forEach { section ->
            val isCollapsed = section.id in collapsed
            item(key = "h-${section.id}", contentType = "header") {
                val style = sectionStyle(section.kind)
                CollapsibleSectionHeader(
                    icon = style.icon,
                    tint = style.tint,
                    label = style.label,
                    count = section.count,
                    collapsed = isCollapsed,
                    onToggle = { onToggleSection(section.id) },
                    collapsible = section.count > 0,
                    modifier = Modifier.animateItem(),
                )
            }
            if (!isCollapsed) {
                itemsIndexed(
                    items = section.rows,
                    key = { _, row -> "row-${row.conversation.id}" },
                    contentType = { _, _ -> "row" },
                ) { index, row ->
                    // Flat rows under a section header, separated by a hairline — the
                    // Codex list. The inset card group it replaces read as an iOS
                    // settings screen: every row boxed, the section itself a panel. Here
                    // the page is the surface and only the hairline divides rows.
                    val isLast = index == section.rows.lastIndex
                    SessionRow(
                        conversation = row.conversation,
                        onClick = { onOpenConversation(row.conversation.id) },
                        modifier = Modifier.animateItem(),
                        folderName = row.folderName,
                        showDivider = !isLast,
                        depth = row.depth,
                        expanded = row.conversation.id in expandedParents,
                        loadingChildren = row.conversation.id in loadingChildren,
                        onToggleChildren = { onToggleChildren(row.conversation) },
                        // Transparent fill on the page itself; the selected row still
                        // tints, which is the only background a row ever gets.
                        shape = RoundedCornerShape(12.dp),
                        cardBackground = Color.Transparent,
                        onTogglePin = { onTogglePin(row.conversation) },
                        onDelete = { onDelete(row.conversation) },
                        // Only meaningful where the row shows a folder tag; the row
                        // decides whether to draw a tappable tag from this being non-null.
                        // Folder actions ride the row's long-press menu, and only where
                        // the row actually shows a folder tag (cross-folder sections).
                        onNewTaskInFolder = if (row.folderName != null) {
                            { onOpenSessionInFolder(row.conversation.folderId) }
                        } else null,
                        onOpenFolder = if (row.folderName != null) {
                            { onOpenFolder(row.conversation.folderId) }
                        } else null,
                        tagColors = row.conversation.tagIds.mapNotNull { tagsById[it] }.map(::tagColor),
                    )
                }
            }
        }
    }
}

/** Resolved header presentation for a [SectionKind] (icon + theme-aware tint + label). */
private data class SectionStyle(val icon: ImageVector, val tint: Color, val label: String)

@Composable
private fun sectionStyle(kind: SectionKind): SectionStyle {
    val colors = CodegTheme.colors
    return when (kind) {
        SectionKind.Running -> SectionStyle(
            icon = Icons.Rounded.Bolt,
            tint = colors.accent,
            label = stringResource(R.string.activity_running),
        )
        SectionKind.Recent -> SectionStyle(
            icon = Icons.Rounded.Schedule,
            tint = colors.textSecondary,
            label = stringResource(R.string.activity_recent),
        )
        SectionKind.Pinned -> SectionStyle(
            icon = Icons.Rounded.PushPin,
            tint = colors.accent,
            label = stringResource(R.string.sessions_pinned),
        )
        is SectionKind.Folder -> SectionStyle(
            icon = Icons.Rounded.Folder,
            tint = colorFromHex(kind.colorHex) ?: colors.accent,
            label = kind.name,
        )
        SectionKind.Other -> SectionStyle(
            icon = Icons.Rounded.Inbox,
            tint = colors.textSecondary,
            label = stringResource(R.string.sessions_other),
        )
    }
}

/**
 * A dismissible banner for a refresh that failed while rows are still on screen —
 * the stale rows stay visible and the error is surfaced inline with Retry / dismiss
 * (the Android analogue of iOS `RefreshErrorBanner`). The initial-load failure still
 * uses the full-screen [InlineError]; this is only for the keep-the-list case.
 */
@Composable
private fun RefreshErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CodegTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.danger.copy(alpha = 0.12f))
            .border(CodegTheme.dimens.hairlineWidth, colors.danger.copy(alpha = 0.35f), shape)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = colors.danger,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
        ) {
            Text(stringResource(R.string.common_retry))
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.common_dismiss),
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ServerSwitcher(
    name: String,
    servers: List<ServerProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onManageServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val colors = CodegTheme.colors
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                // iOS uses a Large Title here (34pt bold, tracked in), not a navigation
                // title. It is the screen's identity — the server you are looking at —
                // and at nav-title size it read as a label for the list rather than as
                // the page heading.
                style = MaterialTheme.typography.headlineLarge,
                color = colors.textPrimary,
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        CodegMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = { Text(server.name) },
                    onClick = {
                        open = false
                        if (server.id != selectedId) onSelect(server.id)
                    },
                    trailingIcon = {
                        if (server.id == selectedId) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
            HorizontalDivider()
            CodegMenuItem(
                text = stringResource(R.string.home_manage_servers),
                leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                onClick = {
                    open = false
                    onManageServers()
                },
            )
        }
    }
}

/**
 * The docked "start a task" entry at the bottom of the Chats list.
 *
 * Visually a composer — a rounded well with muted text and a filled send-shaped button —
 * but tapping anywhere opens a new session instead of typing here. That is deliberate:
 * the real composer needs a folder, an agent and a connection, none of which exist until
 * the session screen does.
 */
@Composable
private fun NewTaskDock(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.codeSurface)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.session_new_task_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
            ),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

