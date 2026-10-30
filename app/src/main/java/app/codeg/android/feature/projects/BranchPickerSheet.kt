package app.codeg.android.feature.projects

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.designsystem.theme.CodegMono

/**
 * Branch picker: lists the repo's local + remote branches (current marked) for
 * checkout, with an inline "new branch" field. Mirrors the iOS branch picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchPickerSheet(viewModel: ProjectDetailViewModel, onDismiss: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember { mutableStateOf(true) }
    var locals by remember { mutableStateOf<List<String>>(emptyList()) }
    var remotes by remember { mutableStateOf<List<String>>(emptyList()) }
    var newBranch by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Deleting a local branch is long-press only: it is destructive and rare, and a
    // visible per-row button would compete with the tap that switches branch.
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // Set when git refused the plain `-d` because the branch is unmerged; the dialog
    // then offers the force path explicitly instead of escalating on its own.
    var forcePrompt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val list = viewModel.loadBranches()
        locals = list.local
        remotes = list.remote.filterNot { it.endsWith("/HEAD") }
        loading = false
    }

    fun doCheckout(branch: String) {
        busy = true; error = null
        viewModel.checkout(branch) { err -> busy = false; if (err == null) onDismiss() else error = err }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.branch_switch_branch), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            error?.let { Text(it, fontSize = 12.sp, color = colors.danger) }

            // New branch.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { CodegTextField(newBranch, { newBranch = it }, label = stringResource(R.string.branch_new_branch), mono = true) }
                FilledIconButton(
                    onClick = {
                        busy = true; error = null
                        viewModel.createBranch(newBranch) { err -> busy = false; if (err == null) onDismiss() else error = err }
                    },
                    enabled = newBranch.isNotBlank() && !busy,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.accent.copy(alpha = 0.16f), contentColor = colors.accent),
                ) { Icon(Icons.Rounded.Add, stringResource(R.string.branch_create_branch)) }
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
            } else {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (locals.isNotEmpty()) item("lh") { SectionLabel(stringResource(R.string.branch_local)) }
                    items(locals, key = { "l-$it" }) { b ->
                        BranchRow(
                            branch = b,
                            current = b == ui.branch,
                            enabled = !busy,
                            onClick = { doCheckout(b) },
                            // The checked-out branch cannot be deleted by git at all, so
                            // the long-press is not offered on it.
                            onLongClick = if (b == ui.branch) null else ({ deleteTarget = b }),
                        )
                    }
                    if (remotes.isNotEmpty()) item("rh") { SectionLabel(stringResource(R.string.branch_remote)) }
                    items(remotes, key = { "r-$it" }) { b ->
                        BranchRow(b, current = false, enabled = !busy, onClick = { doCheckout(b) })
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = colors.bgElevated,
            title = { Text(stringResource(R.string.branch_delete_title, target), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.branch_delete_message), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val name = target
                    deleteTarget = null
                    busy = true; error = null
                    viewModel.deleteBranch(name, force = false) { err ->
                        busy = false
                        if (err == null) {
                            locals = locals - name
                        } else {
                            // A refusal here is almost always "not merged"; ask before
                            // forcing rather than doing it on the user's behalf.
                            error = err
                            forcePrompt = name
                        }
                    }
                }) { Text(stringResource(R.string.common_delete), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }

    forcePrompt?.let { name ->
        AlertDialog(
            onDismissRequest = { forcePrompt = null },
            containerColor = colors.bgElevated,
            title = { Text(stringResource(R.string.branch_force_delete_title, name), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.branch_force_delete_message), color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    forcePrompt = null
                    busy = true; error = null
                    viewModel.deleteBranch(name, force = true) { err ->
                        busy = false
                        if (err == null) locals = locals - name else error = err
                    }
                }) { Text(stringResource(R.string.branch_force_delete_confirm), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { forcePrompt = null }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CodegTheme.colors.textTertiary, modifier = Modifier.padding(top = 8.dp, start = 4.dp, bottom = 2.dp))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BranchRow(
    branch: String,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = CodegTheme.colors
    ListItem(
        headlineContent = { Text(branch, fontSize = 14.sp, fontFamily = CodegMono, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = if (current) {
            { Icon(Icons.Rounded.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp)) }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (current) colors.accent else colors.textPrimary,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = { if (!current) onClick() },
            ),
    )
}
