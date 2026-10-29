package app.codeg.android.feature.sessiondetail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.codeg.android.R
import app.codeg.android.core.designsystem.BoundedImageDecoder
import app.codeg.android.core.designsystem.component.AgentAvatar
import app.codeg.android.core.designsystem.component.EmptyState
import app.codeg.android.core.designsystem.component.InlineError
import app.codeg.android.core.designsystem.component.LoadingView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.feature.sessiondetail.interactive.AskQuestionCard
import app.codeg.android.feature.sessiondetail.interactive.PermissionRequestCard
import app.codeg.android.feature.sessiondetail.interactive.PlanApprovalCard
import app.codeg.android.feature.sessiondetail.timeline.LocalTimelineScroll
import app.codeg.android.feature.sessiondetail.timeline.NodeBody
import app.codeg.android.feature.sessiondetail.timeline.NodeContent
import app.codeg.android.feature.sessiondetail.timeline.TimelineRow
import app.codeg.android.feature.sessiondetail.timeline.TranscriptTimeline
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The session detail screen: the transcript rendered as a vertical **timeline**
 * (one rail, every event a node) over a pinned composer. Sending resolves/opens an
 * ACP connection, streams the reply, and reconciles against the server transcript
 * on completion. Port of the iOS `SessionDetailView`.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    onOpenSession: (Int) -> Unit = {},
    /** Takes the user to the folder list — the way out of the "no folder" dead end. */
    onOpenFolders: () -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = CodegTheme.colors
    // rememberSaveable: a half-typed prompt survived neither rotation nor the
    // `recreate()` that a theme / language change triggers. The composer is the one
    // place in the app where unsaved user input is expensive to lose.
    var draft by rememberSaveable { mutableStateOf("") }
    var showInsert by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // A failed send returns the user's typed text to the composer so it isn't lost — but only
    // when the composer is empty, so it never clobbers a fresh draft typed during the send.
    LaunchedEffect(ui.restoreDraft) {
        ui.restoreDraft?.let { restored ->
            if (draft.isBlank()) draft = restored
            viewModel.consumeRestoredDraft()
        }
    }

    // The draft that survived the process (and switching between conversations). Applied
    // only into an empty composer, so a `rememberSaveable`-restored draft — the fresher
    // of the two — is never overwritten by the stored one.
    LaunchedEffect(ui.restoredPersistedDraft) {
        ui.restoredPersistedDraft?.let { stored ->
            if (draft.isBlank()) draft = stored
            viewModel.consumePersistedDraft()
        }
    }

    // Persist the composer as the user types, debounced: a keystroke-per-write would hit
    // the preferences file hundreds of times for one sentence. `snapshotFlow` restarts on
    // each change and the delay settles it; the final value is also written on dispose
    // below, so nothing is lost when the screen closes mid-debounce.
    LaunchedEffect(Unit) {
        snapshotFlow { draft }
            .debounce(DRAFT_SAVE_DEBOUNCE_MS)
            .collect { viewModel.saveDraft(it) }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.saveDraft(draft) }
    }

    if (showOptions) AgentOptionsSheet(
        viewModel = viewModel,
        onOpenSession = onOpenSession,
        onDismiss = { showOptions = false },
    )
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentPrep.MAX_COUNT),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addAttachments(uris, context.contentResolver) }

    if (showInsert) {
        ComposeInsertSheet(
            viewModel = viewModel,
            onInsert = { transform -> draft = transform(draft) },
            onDismiss = { showInsert = false },
        )
    }

    // The persisted half of the timeline is flattened in the ViewModel, off the main
    // thread — see `SessionDetailViewModel.rebuildTimeline`. Composition only re-runs the
    // cheap live append, so the ~50ms stream never forces a full re-adapt of the history.
    val persistedNodes = ui.persistedNodes
    val nodes = remember(persistedNodes, ui.live, ui.agent) {
        TranscriptTimeline.withLive(persistedNodes, ui.live, ui.agent)
    }
    val hasContent = nodes.isNotEmpty()

    // Native stick-to-bottom: auto-follow only while the bottom anchor is in view.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }
    var stick by remember { mutableStateOf(true) }
    // Re-evaluate "follow" only when a *user* scroll settles — programmatic scrolls
    // and streamed growth must not flip it (that would race the auto-follow off).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) stick = isAtBottom
        }
    }
    // A freshly sent message always re-pins to the bottom.
    LaunchedEffect(ui.pendingUserTurns.lastOrNull()?.id) {
        if (ui.pendingUserTurns.isNotEmpty()) stick = true
    }
    // Follow new / streamed content while pinned.
    //
    // Keyed on stable counts, never on `ui.live`: that is a fresh data class per streamed
    // delta, so including it restarted this effect and re-issued a snap scroll ~20×/s for
    // the whole reply — a scroll on every frame the user was pinned, and a source of the
    // stutter. The rendered *node* count is the honest signal: it changes when a new block
    // or tool appears, which is exactly when the view needs to follow, and it also covers
    // a long single paragraph that grows without adding turns.
    //
    // Deliberately not `nodes.size` as the key alone: prepending older turns also changes
    // it, and re-scrolling then would yank a user who is reading history back down. The
    // turn count distinguishes the two — a page-in raises `ui.turns.size` while the node
    // count only shifts by the prepended rows.
    val followSignal = ui.turns.size to ui.pendingUserTurns.size
    LaunchedEffect(followSignal, nodes.size, stick) {
        if (stick && nodes.isNotEmpty()) listState.scrollToItem(nodes.size)
    }
    // Grow the loaded window as the user approaches the top of a long transcript. Each
    // request pages turns in from the server, so the cost is one chunk no matter how long
    // the conversation is; `hasOlderTurns` going false ends the effect for good.
    //
    // Keyed on the loaded turn count so each landed page restarts the effect: without
    // that, a user parked at the top would load exactly one chunk and stall, because
    // `distinctUntilChanged` never re-emits `true` and nothing else restarts the flow.
    LaunchedEffect(listState, ui.hasOlderTurns, ui.turns.size) {
        if (!ui.hasOlderTurns) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex <= PREFETCH_TURNS_AT_TOP }
            .distinctUntilChanged()
            .collect { nearTop -> if (nearTop) viewModel.loadOlderTurns() }
    }
    // Pre-built id → index map. The previous `indexOfFirst` scanned every node per
    // click; on a long transcript that is a linear walk on the main thread each time a
    // citation is tapped.
    val scrollToId: (String) -> Unit = remember(nodes) {
        val indexById: Map<String, Int> = nodes.withIndex().associate { (i, n) -> n.id to i }
        return@remember { id: String ->
            val idx = indexById[id]
            if (idx != null) {
                stick = false // unpin so the stream doesn't immediately yank us back down
                scope.launch { listState.animateScrollToItem(idx) }
            }
        }
    }

    val title = when {
        ui.isNew -> stringResource(R.string.session_new_task)
        else -> ui.summary?.trimmedTitle ?: stringResource(R.string.session_title_fallback)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = colors.textPrimary,
                        )
                    }
                },
                actions = {
                    // The agent avatar is the options button (mode / config / folder / branch).
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(
                                onClickLabel = stringResource(R.string.session_agent_options),
                                role = Role.Button,
                            ) { showOptions = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        AgentAvatar(ui.agent, size = 28.dp)
                    }
                    if (ui.summary != null) SessionActionsMenu(ui, viewModel, onDeleted = onBack)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                ui.pendingPermission?.let { p ->
                    PermissionRequestCard(
                        parsed = p.parsed,
                        options = p.options,
                        onRespond = viewModel::respondPermission,
                        attempt = p.attempt,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                ui.pendingQuestion?.let { q ->
                    AskQuestionCard(
                        questions = q.questions,
                        onSubmit = viewModel::answerQuestion,
                        onSkip = viewModel::dismissQuestion,
                        attempt = q.attempt,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                ui.pendingPlanApproval?.let { p ->
                    PlanApprovalCard(
                        planMarkdown = p.planMarkdown,
                        onAnswer = viewModel::answerPlanApproval,
                        attempt = p.attempt,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                // Keep the last shown message so the exit animation has content to fade.
                var lastNotice by remember { mutableStateOf("") }
                LaunchedEffect(ui.notice) { ui.notice?.let { lastNotice = it } }
                AnimatedVisibility(
                    visible = ui.notice != null,
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
                ) {
                    NoticeBanner(lastNotice, onDismiss = viewModel::dismissNotice)
                }
                // Fixed-height slot, contents cross-faded.
                //
                // As an `AnimatedVisibility` this removed its own height when hidden, so
                // every show/hide moved the composer up or down by a line. Around a
                // reattach the status flips several times in quick succession
                // (reattaching → running → gone), and each flip shoved the whole input
                // area — read on screen as the layout flickering. A permanently reserved
                // row means the only thing that changes is the text, which is what the
                // fade is for.
                val statusVisible = ui.sendStatus != null && ui.isInFlight
                Crossfade(
                    targetState = if (statusVisible) ui.sendStatus else null,
                    animationSpec = tween(140),
                    modifier = Modifier.fillMaxWidth().height(STATUS_LINE_HEIGHT),
                ) { status ->
                    if (status != null) SendStatusLine(status) else Box(Modifier.fillMaxSize())
                }
                // A failed turn used to offer nothing: the error sat in the transcript and
                // the only way to try again was to scroll back, copy the prompt and paste
                // it. The text is still in `turns`, so retry is one tap away from the
                // composer where the user is already looking.
                if (ui.canRetryLastTurn) RetryBar(onRetry = viewModel::retryLastTurn)
                if (ui.attachments.isNotEmpty()) AttachmentStrip(ui.attachments, onRemove = viewModel::removeAttachment)
                ComposeBar(
                    text = draft,
                    onTextChange = { draft = it },
                    onSend = {
                        viewModel.send(draft)
                        draft = ""
                        // The stored draft is cleared by the persistence effect below, not
                        // here: it writes on a debounce, so the empty composer overwrites
                        // the entry a moment later. Clearing eagerly would also throw the
                        // text away on a *failed* send — the view model hands it back via
                        // `restoreDraft`, but if the process died in between there would be
                        // nothing left to restore.
                    },
                    isInFlight = ui.isInFlight,
                    onStop = viewModel::cancel,
                    onPlus = { showInsert = true },
                    onAttach = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    canSendOverride = ui.attachments.isNotEmpty(),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading && !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingView(message = stringResource(R.string.common_loading))
                    }

                ui.error != null && !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InlineError(
                            icon = Icons.Rounded.Forum,
                            title = stringResource(R.string.session_load_failed),
                            message = ui.error!!,
                            onRetry = viewModel::load,
                            retryLabel = stringResource(R.string.common_retry),
                        )
                    }

                !hasContent ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // A new task with no folder selected cannot be started at all.
                        // Saying so here beats letting the send fail with an error after
                        // the user has typed a prompt.
                        val needsFolder = ui.isNew && ui.availableFolders.isEmpty() && ui.selectedFolderId == null
                        EmptyState(
                            icon = Icons.Rounded.Forum,
                            title = if (ui.isNew) {
                                stringResource(R.string.session_new_task)
                            } else {
                                stringResource(R.string.session_empty_title)
                            },
                            message = if (needsFolder) {
                                stringResource(R.string.session_error_no_folder)
                            } else {
                                stringResource(R.string.session_empty_message)
                            },
                            // The message alone left the user to work out where "open a
                            // folder" lives. A task cannot start without one, so the
                            // empty state carries the action that fixes it.
                            actionLabel = if (needsFolder) {
                                stringResource(R.string.session_open_folders)
                            } else {
                                null
                            },
                            onAction = if (needsFolder) onOpenFolders else null,
                        )
                    }

                else -> CompositionLocalProvider(LocalTimelineScroll provides scrollToId) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    ) {
                        items(nodes, key = { it.id }, contentType = { it.contentTypeKey }) { node ->
                            TimelineRow(
                                marker = node.marker,
                                connectTop = node.connectTop,
                                connectBottom = node.connectBottom,
                                startsGroup = node.startsGroup,
                                rail = node.rail,
                                // Block rows skip item animation: streaming appends a tail
                                // block on every blank line, and the live→persisted re-key
                                // would otherwise flash all N blocks of the reply at once.
                                modifier = if (node.content is NodeContent.AssistantBlock) Modifier else Modifier.animateItem(),
                            ) {
                                NodeBody(node)
                            }
                        }
                        item(key = "__timeline_bottom_anchor__") { Spacer(Modifier.height(1.dp)) }
                    }
                }
            }
            // Floating "jump to latest" — overlaid bottom-center OVER the transcript rather
            // than sitting in the composer stack, where its full-width row pushed the bottom
            // of the transcript up under a band. Shown only while scrolled up.
            AnimatedVisibility(
                visible = hasContent && !isAtBottom,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                JumpToLatestButton {
                    stick = true
                    scope.launch { listState.animateScrollToItem(nodes.size) }
                }
            }
        }
    }
}

/** A circular "jump to latest" affordance, floated above the composer when scrolled up. */
@Composable
private fun JumpToLatestButton(onClick: () -> Unit) {
    val colors = CodegTheme.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.bgElevated)
            .border(0.5.dp, colors.surfaceStroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.ArrowDownward,
            contentDescription = stringResource(R.string.session_jump_to_latest),
            tint = colors.accent,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RetryBar(onRetry: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Refresh, null, tint = colors.danger, modifier = Modifier.size(14.dp))
        Text(
            stringResource(R.string.session_turn_failed),
            fontSize = 12.sp,
            color = colors.danger,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRetry,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            modifier = Modifier.heightIn(min = 32.dp),
        ) {
            Text(stringResource(R.string.common_retry), fontSize = 12.sp, color = colors.accent)
        }
    }
}

@Composable
private fun SendStatusLine(status: String) {
    val colors = CodegTheme.colors
    // Fills the reserved [STATUS_LINE_HEIGHT] slot its caller provides and centres its
    // text in it, so the composer below never shifts when the line appears or disappears.
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bgElevated)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = status,
            fontSize = 12.sp,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_dismiss), tint = colors.textSecondary)
        }
    }
}

/**
 * Longest side to decode a staged attachment thumbnail at. The chip is 60dp; 240px
 * covers a 4x-density screen with room to spare.
 */
private const val THUMBNAIL_MAX_PX = 240

/**
 * How close to the top of the loaded window the user must scroll before the next chunk
 * of older turns is flattened, in items.
 *
 * Measured in timeline items rather than turns because that is what the LazyColumn
 * indexes. 20 items is roughly one screenful of mixed blocks — far enough that the
 * rebuild finishes before they reach the top, close enough that short transcripts never
 * trigger it at all.
 */
private const val PREFETCH_TURNS_AT_TOP = 20

/**
 * How long the composer must be idle before its text is written to disk.
 *
 * The draft is persisted so it survives process death, but writing on every keystroke
 * would hit the preferences file hundreds of times per sentence. A pause is the natural
 * commit point; `onDispose` writes the final value so closing mid-pause still saves.
 */
private const val DRAFT_SAVE_DEBOUNCE_MS = 600L

/**
 * Reserved height of the status line above the composer.
 *
 * The line is only sometimes present ("Thinking…", "Running <tool>…", "Reattaching…"),
 * but the slot it occupies is fixed so the composer never moves. Sizing it to the line's
 * natural height (12sp text plus 4dp of vertical padding on each side, rounded up) keeps
 * the text vertically centred without measuring it at runtime.
 */
private val STATUS_LINE_HEIGHT = 24.dp

/** A horizontal strip of pending image attachments, each removable. */
@Composable
private fun AttachmentStrip(attachments: List<Attachment>, onRemove: (String) -> Unit) {
    val colors = CodegTheme.colors
    LazyRow(
        Modifier.fillMaxWidth().background(colors.bgElevated).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { att ->
            // Decoding a multi-megabyte JPEG (base64 → byte[] → Bitmap) is real work;
            // doing it inline in `remember` ran it on the composition thread and
            // stuttered the first frame that showed a freshly picked image.
            // Bounded decode: this thumbnail is drawn at 60dp, and an attachment can be
            // several megabytes — decoding it at full resolution allocated tens of MB of
            // heap per staged image.
            val bitmap by produceState<ImageBitmap?>(initialValue = null, att.id) {
                value = withContext(Dispatchers.IO) {
                    BoundedImageDecoder.decode(att.base64, THUMBNAIL_MAX_PX)?.asImageBitmap()
                }
            }
            Box(Modifier.size(60.dp)) {
                val preview = bitmap
                if (preview != null) {
                    Image(preview, contentDescription = stringResource(R.string.sessiondetail_attachment), contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)))
                } else {
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(colors.codeSurface))
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(RoundedCornerShape(50)).background(colors.bg.copy(alpha = 0.7f)).clickable { onRemove(att.id) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_remove), tint = colors.textPrimary, modifier = Modifier.size(12.dp)) }
            }
        }
    }
}
