package app.codeg.android.feature.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.designsystem.component.CodegTextField
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegMono
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.GitConflictFileVersions
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import kotlinx.coroutines.launch

/**
 * The conflict screen for an interrupted merge / pull / rebase.
 *
 * A conflicted operation on a phone used to be a dead end: the Changes tab showed the
 * files, but nothing could resolve them and nothing could get the repo out of the
 * half-finished state without a terminal. This lists the unmerged paths, shows the
 * three sides of the one being edited, and offers the two exits that matter — write a
 * resolution, or abort the whole operation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictSheet(
    client: CodegClient,
    rootPath: String,
    operation: String,
    onDismiss: () -> Unit,
    onResolved: () -> Unit,
) {
    val colors = CodegTheme.colors
    val appContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var files by remember { mutableStateOf<List<String>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var openFile by remember { mutableStateOf<String?>(null) }
    var confirmAbort by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    suspend fun loadFiles() {
        loadError = null
        runCatching { client.gitListConflicts(rootPath) }
            .onSuccess { files = it }
            .onFailure { loadError = it.displayMessage(appContext) }
    }

    LaunchedEffect(rootPath) { loadFiles() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.WarningAmber, null, tint = colors.danger, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.conflict_title, operationLabel(operation)),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            val current = openFile
            if (current == null) {
                when {
                    files == null && loadError == null -> Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { LoadingView(stringResource(R.string.common_loading)) }

                    loadError != null -> InlineError(
                        Icons.Rounded.WarningAmber,
                        stringResource(R.string.conflict_load_failed),
                        loadError!!,
                        onRetry = { files = null; scope.launch { loadFiles() } },
                    )

                    files.orEmpty().isEmpty() -> Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            Icons.Rounded.CheckCircle,
                            stringResource(R.string.conflict_none_title),
                            stringResource(R.string.conflict_none_message),
                        )
                    }

                    else -> Column(
                        Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (file in files.orEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { openFile = file }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    file,
                                    fontFamily = CodegMono,
                                    fontSize = 13.sp,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            } else {
                ConflictFileEditor(
                    client = client,
                    rootPath = rootPath,
                    file = current,
                    onBack = { openFile = null },
                    onResolved = {
                        openFile = null
                        scope.launch {
                            // Stay in the sheet: a conflicted merge is usually several
                            // files, and closing on the first resolution made the user
                            // reopen it (and re-enter the Changes tab) once per file.
                            loadFiles()
                            onResolved()
                        }
                    },
                )
            }

            if (actionError != null) {
                Text(actionError!!, fontSize = 12.sp, color = colors.danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }

            // Abort is always reachable, including while a file editor is open: it is the
            // way out, and hiding it behind a back gesture would trap the user.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmAbort = true }, enabled = !busy) {
                    Text(stringResource(R.string.conflict_abort, operationLabel(operation)), fontSize = 13.sp, color = colors.danger)
                }
            }
        }
    }

    if (confirmAbort) {
        AlertDialog(
            onDismissRequest = { confirmAbort = false },
            containerColor = colors.bgElevated,
            title = { Text(stringResource(R.string.conflict_abort, operationLabel(operation)), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.conflict_abort_message), color = colors.textSecondary) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        actionError = null
                        scope.launch {
                            runCatching { client.gitAbortOperation(rootPath, operation) }
                                .onSuccess {
                                    busy = false
                                    confirmAbort = false
                                    onResolved()
                                    onDismiss()
                                }
                                .onFailure {
                                    busy = false
                                    confirmAbort = false
                                    actionError = it.displayMessage(appContext)
                                }
                        }
                    },
                ) { Text(stringResource(R.string.common_confirm), color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAbort = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.textSecondary)
                }
            },
        )
    }
}

/** One conflicted file: the three sides, an editable resolution, and "mark resolved". */
@Composable
private fun ConflictFileEditor(
    client: CodegClient,
    rootPath: String,
    file: String,
    onBack: () -> Unit,
    onResolved: () -> Unit,
) {
    val colors = CodegTheme.colors
    val appContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var versions by remember(file) { mutableStateOf<Result<GitConflictFileVersions>?>(null) }
    var content by remember(file) { mutableStateOf<String?>(null) }
    var saving by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    // Retry has to move this, not just clear `versions`: a `LaunchedEffect(file)` keyed on
    // the file alone would not re-run, so the spinner would show and every retry would do
    // nothing.
    var reloadToken by remember(file) { mutableStateOf(0) }

    LaunchedEffect(file, reloadToken) {
        versions = null
        val r = runCatching { client.gitConflictFileVersions(rootPath, file) }
        versions = r
        // Only seed the editor on the first successful read: a retry after a failure must
        // not throw away text the user already typed.
        if (content == null) content = r.getOrNull()?.merged.orEmpty()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), fontSize = 13.sp, color = colors.accent) }
            Text(
                file,
                fontFamily = CodegMono,
                fontSize = 12.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        val r = versions
        when {
            r == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
            }

            r.isFailure -> InlineError(
                Icons.Rounded.WarningAmber,
                stringResource(R.string.conflict_load_failed),
                r.exceptionOrNull()?.displayMessage(appContext) ?: stringResource(R.string.files_error),
                onRetry = { reloadToken += 1 },
            )

            else -> {
                val v = r.getOrThrow()
                // The three sides collapsed: ours and theirs are what a person actually
                // reads to decide, and base is the one they need least often.
                ConflictVersionBlock(stringResource(R.string.conflict_ours), v.ours)
                ConflictVersionBlock(stringResource(R.string.conflict_theirs), v.theirs)

                CodegTextField(
                    value = content.orEmpty(),
                    onValueChange = { content = it; error = null },
                    label = stringResource(R.string.conflict_resolution),
                    placeholder = stringResource(R.string.conflict_resolution_hint),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                runCatching { client.gitResolveConflict(rootPath, file, content.orEmpty()) }
                                    .onSuccess { saving = false; onResolved() }
                                    .onFailure { saving = false; error = it.displayMessage(appContext) }
                            }
                        },
                    ) {
                        Text(
                            if (saving) stringResource(R.string.common_saving) else stringResource(R.string.conflict_mark_resolved),
                            fontSize = 13.sp,
                            color = colors.accent,
                        )
                    }
                }
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = colors.danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A read-only collapsible-looking block of one side's text. */
@Composable
private fun ConflictVersionBlock(title: String, text: String) {
    val colors = CodegTheme.colors
    var expanded by remember(title, text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.codeSurface)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.textTertiary)
        Text(
            text.ifEmpty { "—" },
            fontFamily = CodegMono,
            fontSize = 11.sp,
            color = colors.textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The operation's name as the user knows it. */
private fun operationLabel(operation: String): String = when (operation) {
    "merge" -> "merge"
    "pull" -> "pull"
    "rebase" -> "rebase"
    else -> operation
}
