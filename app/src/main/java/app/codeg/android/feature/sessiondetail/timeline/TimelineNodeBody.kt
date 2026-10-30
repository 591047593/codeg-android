package app.codeg.android.feature.sessiondetail.timeline

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeg.android.R
import app.codeg.android.core.common.RelativeTime
import app.codeg.android.core.common.rememberNowInstant
import app.codeg.android.core.designsystem.markdown.MarkdownContent
import app.codeg.android.core.designsystem.markdown.SingleBlockView
import app.codeg.android.core.designsystem.theme.CodegTheme
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.feature.sessiondetail.rendering.InlineImage
import app.codeg.android.feature.sessiondetail.rendering.LivePlanView
import app.codeg.android.feature.sessiondetail.rendering.ReasoningBlock
import app.codeg.android.feature.sessiondetail.rendering.ToolCallCard
import app.codeg.android.feature.sessiondetail.rendering.ToolGroupCard
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import app.codeg.android.core.designsystem.theme.CodegMono

private val SuccessGreen = Color(0xFF85D18F)

/** Scrolls the timeline to a node id — used by the footer's jump-to-question button. */
val LocalTimelineScroll = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * "Fork from here", provided by the session screen; null when it is not offered.
 *
 * A composition local rather than a parameter on [NodeBody] because the action belongs to
 * a footer several layers down and only one screen provides it — threading it through the
 * timeline renderer would put a session-screen concern in every node type.
 */
val LocalForkFromTurn = staticCompositionLocalOf<((turnId: String) -> Unit)?> { null }

/**
 * Renders a node's content to the right of the rail. A pure dispatcher that reuses
 * the existing render leaves verbatim — the timeline is only a new shell. Port of
 * the iOS `NodeBody`.
 */
@Composable
fun NodeBody(node: TimelineNode, modifier: Modifier = Modifier) {
    when (val c = node.content) {
        is NodeContent.User -> UserNodeBody(c.turn, modifier)
        is NodeContent.System -> SystemNodeBody(c.turn, modifier)
        is NodeContent.AssistantBlock ->
            if (c.streaming) {
                Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SingleBlockView(c.block)
                    BlinkingCaret()
                }
            } else {
                SingleBlockView(c.block, modifier)
            }
        // Reasoning stays collapsed even while streaming: the thinking text is
        // diagnostic, and auto-opening it pushed the actual answer off-screen during
        // exactly the moment the user was waiting for it. Tap to read it.
        //
        // Keyed on the node id, not the text: a streamed delta changes the text, and a
        // text key reset the state — a block the user opened snapped shut 50 ms later.
        is NodeContent.Reasoning -> ReasoningBlock(c.text, modifier, stateKey = node.id)
        is NodeContent.Tool -> ToolCallCard(c.vm, modifier)
        is NodeContent.ToolGroup -> ToolGroupCard(c.items, modifier, streaming = c.streaming)
        is NodeContent.Image -> InlineImage(c.image, c.caption, modifier)
        is NodeContent.Compaction -> ContextCompactionDivider(c.before, c.after, c.running, modifier)
        is NodeContent.Footer -> TurnFooter(c.turn, c.questionId, modifier)
        is NodeContent.Plan -> LivePlanView(c.entries, modifier)
        is NodeContent.Thinking -> ThinkingShimmer(modifier)
        is NodeContent.Error -> InlineTurnError(c.message, modifier)
        is NodeContent.Unsupported -> Text(
            stringResource(R.string.timeline_unsupported_block, c.type),
            fontSize = 11.sp,
            color = CodegTheme.colors.textTertiary,
            modifier = modifier,
        )
    }
}

/** The user's prompt — a left-aligned faint-accent card filling the content column. */
@Composable
private fun UserNodeBody(turn: MessageTurn, modifier: Modifier) {
    val colors = CodegTheme.colors
    // Right-aligned, width-fitting bubble — the Claude chat shape.
    //
    // The prompt used to be a full-width tinted card with a border, which made it look
    // like a system notification sitting in the flow rather than something the user said.
    // A bubble that hugs its text and sits on the right is the single strongest "this is a
    // conversation" cue in the app, and it is what makes the assistant's full-width prose
    // read as its answer rather than as another card in a list.
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            Modifier
                // 88% of the row, capped at a readable measure. A chat bubble cannot also
                // hug a short message here: the Markdown renderer inside spans the width
                // it is given, so a width that shrank to the text would need the whole
                // measure chain rebuilt around intrinsics. At 88% the left gutter is
                // visible at every text size, which is what carries the "right-aligned
                // bubble" reading; genuinely short prompts are rare in this app.
                .fillMaxWidth(0.88f)
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                .background(colors.codeSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (block in turn.blocks) {
                when (block) {
                    is ContentBlock.Text -> if (block.text.isNotBlank()) MarkdownContent(block.text)
                    is ContentBlock.Image -> InlineImage(block.image, null)
                    is ContentBlock.ImageGeneration ->
                        if (block.image != null) InlineImage(block.image, block.revisedPrompt)
                        else if (!block.revisedPrompt.isNullOrBlank()) MarkdownContent(block.revisedPrompt)
                    else -> {}
                }
            }
        }
    }
}

/** A system note — a dim, card-less log line. */
@Composable
private fun SystemNodeBody(turn: MessageTurn, modifier: Modifier) {
    val text = remember(turn) { systemText(turn) }
    Text(
        text,
        fontSize = 12.sp,
        color = CodegTheme.colors.textTertiary,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Per-reply action + metadata row: Copy · jump-to-question · model · time/tokens/duration. */
@Composable
private fun TurnFooter(turn: MessageTurn, questionId: String?, modifier: Modifier) {
    val colors = CodegTheme.colors
    // Joining every text block is O(n) over the reply, so it is memoized on the turn.
    val copyText = remember(turn) {
        turn.blocks
            .mapNotNull { (it as? ContentBlock.Text)?.text?.takeIf { t -> t.isNotBlank() } }
            .joinToString("\n\n")
    }
    // The relative time ("47m") ticked only when something else recomposed this row;
    // it now follows a shared minute clock like every other relative timestamp.
    val now by rememberNowInstant()
    val justNow = stringResource(R.string.time_just_now)
    // Model, time, tokens and duration form one "·"-separated line. They used to be two
    // adjacent Texts (model, then meta) with a weight Spacer between the actions and the
    // model — so the model butted straight against the timestamp and rendered as
    // "deepseek-v4.1-flash10月4".
    val meta = remember(turn, now, justNow) {
        buildList {
            turn.model?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(RelativeTime.compact(turn.completedAt ?: turn.timestamp, now = now, justNow = justNow))
            val tokens = turn.usage?.total ?: 0
            if (tokens > 0) add(compactTokens(tokens))
            turn.durationMs?.let { if (it > 0) add(compactDuration(it)) }
        }.joinToString(" · ")
    }

    Row(
        modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (copyText.isNotEmpty()) CopyButton(copyText)
        LocalForkFromTurn.current?.let { fork ->
            ForkButton(onFork = { fork(turn.id) })
        }
        if (questionId != null) JumpToQuestionButton(questionId)
        Spacer(Modifier.weight(1f))
        Text(
            meta,
            fontFamily = CodegMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textTertiary,
            // Ellipsize rather than squeeze: the row's other children must keep their
            // intrinsic size, and this is the only piece that may be cut.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "Fork from here" — branch the conversation at this reply.
 *
 * The desktop client's footer action of the same name: the pre-fork history is kept as a
 * sibling conversation, so nothing is lost and the original stays readable.
 */
@Composable
private fun ForkButton(onFork: () -> Unit) {
    val colors = CodegTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onFork)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            Icons.Rounded.CallSplit,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            stringResource(R.string.session_fork_from_here),
            fontSize = 10.sp,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun CopyButton(text: String) {
    val colors = CodegTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { clipboard.setText(AnnotatedString(text)); copied = true }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Crossfade(copied, label = "copy-icon") { c ->
            Icon(
                if (c) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = if (c) SuccessGreen else colors.textTertiary,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(if (copied) stringResource(R.string.timeline_copied) else stringResource(R.string.timeline_copy), fontSize = 11.sp, color = colors.textTertiary)
    }
}

@Composable
private fun JumpToQuestionButton(questionId: String) {
    val scroll = LocalTimelineScroll.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { scroll(questionId) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            Icons.Rounded.ArrowUpward,
            contentDescription = stringResource(R.string.timeline_scroll_to_question),
            tint = CodegTheme.colors.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        Text(stringResource(R.string.timeline_question), fontSize = 11.sp, color = CodegTheme.colors.textTertiary)
    }
}

/** Three pulsing dots shown before the agent's first token. Port of iOS `ThinkingShimmer`. */
@Composable
fun ThinkingShimmer(modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        modifier.padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            val a by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(i * 180)),
                label = "dot-$i",
            )
            Spacer(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.textTertiary.copy(alpha = a)),
            )
        }
    }
}

/** A blinking text cursor trailing streamed prose. */
@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val a by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Text("▌", fontFamily = CodegMono, fontSize = 14.sp, color = CodegTheme.colors.accent, modifier = Modifier.alpha(a))
}

/**
 * The context-compaction boundary: a hairline running the content width with a small
 * archive glyph + label centred on it. Deliberately chrome-less (no card, no border) —
 * it marks "the conversation's context was compacted here", it is not something the
 * agent *did*.
 *
 * Grok stamps the before/after token counts on its compaction; codex sends none, and a
 * no-op delta (before == after) would read as a bug, so both fall back to the plain
 * label.
 */
@Composable
fun ContextCompactionDivider(before: Int?, after: Int?, running: Boolean, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    val label = when {
        running -> stringResource(R.string.compaction_running)
        before != null && after != null && before != after ->
            stringResource(R.string.compaction_done_tokens, groupDigits(before), groupDigits(after))
        else -> stringResource(R.string.compaction_done)
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp).alpha(if (running) 0.65f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.hairline)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Rounded.Archive, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(13.dp))
            Text(label, fontSize = 11.sp, color = colors.textTertiary)
        }
        HorizontalDivider(Modifier.weight(1f), color = colors.hairline)
    }
}

/** Thousands separators for the token counts, in the user's locale. */
private fun groupDigits(value: Int): String = String.format(java.util.Locale.getDefault(), "%,d", value)

/** A danger banner for a turn-tail error / cancellation. Port of iOS `InlineTurnError`. */
@Composable
fun InlineTurnError(message: String, modifier: Modifier = Modifier) {
    val colors = CodegTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.danger.copy(alpha = 0.10f))
            .border(0.5.dp, colors.danger.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = colors.danger, modifier = Modifier.size(14.dp))
        Text(message, fontSize = 12.sp, color = colors.danger, modifier = Modifier.weight(1f))
    }
}

private fun compactTokens(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> trimNum(n / 1_000.0) + "k"
    else -> trimNum(n / 1_000_000.0) + "M"
}

private fun trimNum(v: Double): String = if (v >= 100) v.roundToInt().toString() else String.format("%.1f", v)

private fun compactDuration(ms: Int): String {
    val seconds = ms / 1000.0
    if (seconds < 60) return String.format("%.1fs", seconds)
    val whole = seconds.roundToInt()
    val m = whole / 60
    val s = whole % 60
    return if (s == 0) "${m}m" else "${m}m ${s}s"
}
