package app.codeg.android.feature.activity

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessions.CollapsibleSectionHeader
import app.codeg.android.feature.sessions.SessionRow
import java.time.Instant

/**
 * The Activity tab: a live feed split into **Running** and **Last 24 Hours** sections.
 * Mirrors the Chats list's polish — the grouping is computed off the main thread
 * ([ActivityViewModel.sections]), the sections share the prominent collapsible header,
 * and the list recycles per item type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onOpenConversation: (Int) -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // Confirmed before deleting: the transcript goes with it.
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    val colors = CodegTheme.colors

    // The feed is a 25s poll. Bound to RESUMED, not to the composition: leaving the tab
    // disposes this composable, but pressing Home or locking the screen does not — the
    // destination stays composed and a `DisposableEffect` keeps polling with nothing
    // collecting the result.
    LifecycleResumeEffect(Unit) {
        viewModel.startPolling()
        onPauseOrDispose { viewModel.stopPolling() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_activity)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colors.textPrimary),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !ui.hasLoaded ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingView(stringResource(R.string.common_loading)) }

                ui.hasLoaded && ui.isEmptyFeed ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Rounded.Bedtime, stringResource(R.string.activity_empty_title), stringResource(R.string.activity_empty_message))
                    }

                // Nothing loaded and the last attempt failed. Without this branch the
                // screen fell through to an empty pull-to-refresh surface with no
                // message and no way to retry, and stayed there until a poll happened
                // to succeed. `error` was written by the view model but read nowhere.
                !ui.hasLoaded && ui.error != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Bedtime,
                            title = stringResource(R.string.activity_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::refresh,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }

                else -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                    val toggle: (String) -> Unit = { id ->
                        collapsed = if (id in collapsed) collapsed - id else collapsed + id
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                        sections.forEach { section ->
                            val isCollapsed = section.id in collapsed
                            item(key = "h-${section.id}", contentType = "header") {
                                val style = activitySectionStyle(section.kind)
                                CollapsibleSectionHeader(
                                    icon = style.icon,
                                    tint = style.tint,
                                    label = style.label,
                                    count = section.count,
                                    collapsed = isCollapsed,
                                    onToggle = { toggle(section.id) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            if (!isCollapsed) {
                                items(section.rows, key = { "row-${it.conversation.id}" }, contentType = { "row" }) { row ->
                                    SessionRow(
                                        row.conversation,
                                        onClick = { onOpenConversation(row.conversation.id) },
                                        modifier = Modifier.animateItem(),
                                        folderName = row.folderName,
                                        onDelete = { pendingDelete = row.conversation },
                                    )
                                }
                            }
                        }
                        ui.lastRefreshed?.let { ts ->
                            item("updated", contentType = "footer") { UpdatedFooter(ts) }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.bgElevated,
            title = {
                Text(stringResource(R.string.sessionactions_delete_session_confirm), color = colors.textPrimary)
            },
            text = {
                Text(stringResource(R.string.sessionactions_delete_session_message), color = colors.textSecondary)
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

/** Resolved header presentation for an [ActivityKind] (icon + theme-aware tint + label). */
private data class ActivityStyle(val icon: ImageVector, val tint: Color, val label: String)

@Composable
private fun activitySectionStyle(kind: ActivityKind): ActivityStyle {
    val colors = CodegTheme.colors
    return when (kind) {
        ActivityKind.RUNNING -> ActivityStyle(
            icon = Icons.Rounded.GraphicEq,
            tint = colors.accent,
            label = stringResource(R.string.activity_running),
        )
        ActivityKind.RECENT -> ActivityStyle(
            icon = Icons.Rounded.History,
            tint = colors.textSecondary,
            label = stringResource(R.string.activity_recent),
        )
    }
}

/** "Updated 2 minutes ago" — a dim, centred footer echoing the iOS Activity feed. */
@Composable
private fun UpdatedFooter(timestamp: Instant) {
    val relative = DateUtils.getRelativeTimeSpanString(
        timestamp.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    Text(
        text = stringResource(R.string.activity_updated, relative),
        style = MaterialTheme.typography.labelSmall,
        color = CodegTheme.colors.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
    )
}
