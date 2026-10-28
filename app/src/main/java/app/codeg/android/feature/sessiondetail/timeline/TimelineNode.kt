package app.codeg.android.feature.sessiondetail.timeline

import app.codeg.android.core.designsystem.markdown.LiveBlockParser
import app.codeg.android.core.designsystem.markdown.MarkdownBlock
import app.codeg.android.core.designsystem.markdown.MarkdownCache
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.ImageData
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.PlanEntry
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnUsage
import app.codeg.android.feature.sessiondetail.LiveSegment
import app.codeg.android.feature.sessiondetail.rendering.ContextCompaction
import app.codeg.android.feature.sessiondetail.LiveToolCallState
import app.codeg.android.feature.sessiondetail.LiveTurnState
import app.codeg.android.feature.sessiondetail.rendering.MessageRender
import app.codeg.android.feature.sessiondetail.rendering.RenderPart
import app.codeg.android.feature.sessiondetail.rendering.ToolCallState
import app.codeg.android.feature.sessiondetail.rendering.ToolCallVM

/**
 * The payload of one timeline row. The whole transcript (persisted turns +
 * optimistic pending turns + the live streaming reply) flattens into a single
 * ordered list of [TimelineNode]s, each drawn against the rail with a gutter
 * marker. Port of the iOS `TimelineNode.Content`.
 */
sealed interface NodeContent {
    data class User(val turn: MessageTurn) : NodeContent
    data class System(val turn: MessageTurn) : NodeContent

    /** One Markdown block of an assistant reply. A long message is split into these so
     *  the LazyColumn virtualizes within it; [streaming] (last block only) draws the caret. */
    data class AssistantBlock(val block: MarkdownBlock, val streaming: Boolean = false) : NodeContent
    data class Reasoning(val text: String, val streaming: Boolean = false) : NodeContent
    data class Tool(val vm: ToolCallVM) : NodeContent
    data class ToolGroup(val items: List<ToolCallVM>, val streaming: Boolean) : NodeContent
    data class Image(val image: ImageData, val caption: String?) : NodeContent
    /** A context-compaction boundary — rendered as a chrome-less divider, not a card.
     *  Token counts are Grok-only (codex sends none). */
    data class Compaction(val before: Int?, val after: Int?, val running: Boolean) : NodeContent
    data class Footer(val turn: MessageTurn, val questionId: String?) : NodeContent
    data class Plan(val entries: List<PlanEntry>) : NodeContent
    data object Thinking : NodeContent
    data class Error(val message: String) : NodeContent
    data class Unsupported(val type: String) : NodeContent
}

/**
 * One row on the timeline. [connectTop]/[connectBottom] are suppressed only at the
 * very first/last node so the rail terminates at its endpoints; [startsGroup]
 * marks a reply boundary (user/system) that gets extra breathing room above.
 * Port of the iOS `TimelineNode`.
 */
data class TimelineNode(
    val id: String,
    val content: NodeContent,
    val agent: AgentType,
    val connectTop: Boolean = true,
    val connectBottom: Boolean = true,
    val startsGroup: Boolean = false,
    val rail: RailStyle = RailStyle.Standalone,
) {
    val marker: MarkerKind
        get() = when (val c = content) {
            is NodeContent.User -> MarkerKind.User
            is NodeContent.System -> MarkerKind.System
            is NodeContent.AssistantBlock -> MarkerKind.Assistant(agent)
            is NodeContent.Reasoning -> MarkerKind.Reasoning
            is NodeContent.Tool -> MarkerKind.Tool(c.vm.bucket, c.vm.state)
            is NodeContent.ToolGroup -> MarkerKind.ToolGroup(c.items.any { it.state == ToolCallState.ERROR }, c.streaming)
            is NodeContent.Image -> MarkerKind.Image
            is NodeContent.Compaction -> MarkerKind.Compaction
            is NodeContent.Footer -> MarkerKind.Footer
            is NodeContent.Plan -> MarkerKind.Plan
            is NodeContent.Thinking -> MarkerKind.Thinking
            is NodeContent.Error -> MarkerKind.Error
            is NodeContent.Unsupported -> MarkerKind.System // reuse the dim system tick
        }

    /** LazyColumn `contentType` — recycled slots only reuse a subtree of the same shape,
     *  so assistant blocks split by their Markdown kind (a Paragraph slot can't serve Code). */
    val contentTypeKey: Any
        get() = when (val c = content) {
            is NodeContent.AssistantBlock -> when (c.block) {
                is MarkdownBlock.Paragraph -> "ab.paragraph"
                is MarkdownBlock.Heading -> "ab.heading"
                is MarkdownBlock.BulletList -> "ab.bullet"
                is MarkdownBlock.NumberedList -> "ab.numbered"
                is MarkdownBlock.Quote -> "ab.quote"
                is MarkdownBlock.Code -> "ab.code"
                MarkdownBlock.Rule -> "ab.rule"
                is MarkdownBlock.Table -> "ab.table"
            }
            is NodeContent.User -> "user"
            is NodeContent.System -> "system"
            is NodeContent.Reasoning -> "reasoning"
            is NodeContent.Tool -> "tool"
            is NodeContent.ToolGroup -> "toolgroup"
            is NodeContent.Image -> "image"
            is NodeContent.Compaction -> "compaction"
            is NodeContent.Footer -> "footer"
            is NodeContent.Plan -> "plan"
            is NodeContent.Thinking -> "thinking"
            is NodeContent.Error -> "error"
            is NodeContent.Unsupported -> "unsupported"
        }
}

/** A turn's text blocks, joined — the renderable system-note text (and the empty-skip check). */
internal fun systemText(turn: MessageTurn): String =
    turn.blocks.mapNotNull { (it as? ContentBlock.Text)?.text }.joinToString("\n").trim()

/** Drop the trailing run of assistant turns (those after the last non-assistant turn).
 *  On reattach the live turn rebuilt from the snapshot owns the in-flight reply, so the
 *  partial copy the server has persisted at the tail must be hidden to avoid doubling it. */
internal fun List<MessageTurn>.dropTrailingAssistantRun(): List<MessageTurn> {
    var end = size
    while (end > 0 && this[end - 1].role == TurnRole.ASSISTANT) end--
    return if (end == size) this else subList(0, end)
}

/**
 * Replace the tail of the loaded transcript with [fresh], keeping the older turns already
 * paged in above it.
 *
 * Coordinates: the loaded list covers global turns `[currentOffset, currentOffset + size)`
 * and the fresh window covers `[freshOffset, freshOffset + fresh.size)`. The post-turn
 * reconcile asks only for the newest window, so `fresh` is a suffix of the conversation;
 * assigning it straight to state would drop everything above it, and since this runs after
 * every turn, a user scrolling back through history would be reset to the newest window
 * each time a reply landed.
 *
 * What is safe to keep is exactly the region strictly above `freshOffset` — that is,
 * `this[0, freshOffset - currentOffset)`. Everything from `freshOffset` on comes from the
 * server. That join is legal only when the loaded list actually reaches `freshOffset`: if
 * the fresh window starts at or below the loaded one, or begins past where the loaded list
 * ends (a gap — an edit, delete or compaction above the window), there is no contiguous
 * prefix and the fresh window alone is the honest answer.
 */
internal fun List<MessageTurn>.mergeWindow(
    fresh: List<MessageTurn>,
    freshOffset: Int,
    currentOffset: Int,
): List<MessageTurn> {
    val keep = freshOffset - currentOffset
    // Nothing above the fresh window, or the fresh window already starts at/below what is
    // loaded: the server's copy covers it entirely.
    if (keep <= 0) return fresh
    // A gap: the loaded list ends before the fresh window begins, so turns between the two
    // are unknown and cannot be bridged.
    if (keep > size) return fresh
    return subList(0, keep) + fresh
}

/**
 * Drop later nodes that repeat an earlier node's id.
 *
 * Node ids become `LazyColumn` keys, and a duplicate key is a hard crash ("Key … was
 * already used"). Tool nodes are keyed by the server's `tool_use_id`, which `merge()`
 * carries across the consecutive assistant turns it folds together — so a server that
 * re-emits one id (a split reply, a re-sync after reconnect, a repeated block) produces
 * two identical keys. Keeping the first occurrence is right either way: the second copy
 * describes the same call.
 *
 * Ids are otherwise unique by construction (turn ids for user/system nodes, position for
 * anonymous text/tool parts), so this is a guard rather than a routine rewrite, and it is
 * a no-op — same list instance — on every transcript that is already well formed.
 */
internal fun List<TimelineNode>.distinctByNodeId(): List<TimelineNode> {
    val seen = HashSet<String>(size)
    var duplicate = false
    for (node in this) if (!seen.add(node.id)) { duplicate = true; break }
    if (!duplicate) return this
    val out = ArrayList<TimelineNode>(size)
    val kept = HashSet<String>(size)
    for (node in this) if (kept.add(node.id)) out.add(node)
    return out
}

/**
 * Flattens a conversation into an ordered `[TimelineNode]`. User/system turns map
 * 1:1; runs of consecutive assistant turns merge into one reply (codeg persists a
 * logical reply as several turns) that expands into part nodes + a single footer.
 * Faithful port of the iOS `TranscriptTimeline`.
 */
object TranscriptTimeline {

    fun build(
        turns: List<MessageTurn>,
        pending: List<MessageTurn>,
        live: LiveTurnState?,
        agent: AgentType,
        hideTrailingAssistantReply: Boolean = false,
    ): List<TimelineNode> =
        withLive(buildPersisted(turns, pending, agent, hideTrailingAssistantReply), live, agent)

    /**
     * The persisted + optimistic-pending nodes, with the rail NOT yet terminated.
     * Memoize this (it re-merges/adapts every turn) separately from [withLive] so the
     * ~50ms live stream only re-runs the cheap live append, not the whole transcript.
     *
     * [hideTrailingAssistantReply] drops the trailing run of persisted assistant
     * turns — used on reattach, where the live turn rebuilt from the snapshot is the
     * authoritative in-flight reply while the agent concurrently persists a partial
     * copy into [turns]; showing both would double the reply. Port of the iOS
     * `liveOwnsInFlightReply` de-dup.
     */
    fun buildPersisted(
        turns: List<MessageTurn>,
        pending: List<MessageTurn>,
        agent: AgentType,
        hideTrailingAssistantReply: Boolean = false,
    ): List<TimelineNode> {
        val source = if (hideTrailingAssistantReply) turns.dropTrailingAssistantRun() else turns
        val nodes = mutableListOf<TimelineNode>()
        var lastUserId: String? = null
        var i = 0
        while (i < source.size) {
            val turn = source[i]
            when (turn.role) {
                TurnRole.USER -> {
                    lastUserId = turn.id
                    nodes.add(TimelineNode(turn.id, NodeContent.User(turn), agent, startsGroup = true))
                    i++
                }
                TurnRole.SYSTEM -> {
                    if (systemText(turn).isNotEmpty()) {
                        nodes.add(TimelineNode(turn.id, NodeContent.System(turn), agent, startsGroup = true))
                    }
                    i++
                }
                TurnRole.ASSISTANT -> {
                    var j = i + 1
                    while (j < source.size && source[j].role == TurnRole.ASSISTANT) j++
                    val merged = merge(source.subList(i, j))
                    nodes.addAll(assistantNodes(merged, lastUserId, agent))
                    i = j
                }
            }
        }
        for (turn in pending) {
            nodes.add(TimelineNode(turn.id, NodeContent.User(turn), agent, startsGroup = true))
        }
        return nodes.distinctByNodeId()
    }

    /**
     * Append the in-flight reply to the persisted nodes and terminate the rail's endpoints.
     *
     * The copy is skipped when there is no live turn and the rail is already terminated
     * on both ends — the state of a settled transcript. [buildPersisted] leaves the rail
     * unterminated (it does not know which node will be last), so the common case still
     * copies once; but a live stream redraws this every ~50ms over the whole history, and
     * that is the path worth keeping cheap.
     */
    fun withLive(persisted: List<TimelineNode>, live: LiveTurnState?, agent: AgentType): List<TimelineNode> {
        if (live == null && persisted.isNotEmpty() &&
            !persisted.first().connectTop && !persisted.last().connectBottom
        ) {
            return persisted
        }
        val nodes = persisted.toMutableList()
        // The live reply can repeat a key the persisted window already holds (the server
        // persisting a turn we are still streaming), and a duplicate LazyColumn key is a
        // crash. Checked only when a live turn exists: the settled path above returns
        // early, and this runs ~20x/s while streaming, so the no-live case must stay free.
        // One HashSet pass over the persisted ids keeps it O(n) rather than O(n·m).
        if (live != null) {
            val known = HashSet<String>(nodes.size * 2)
            for (n in nodes) known.add(n.id)
            for (n in liveNodes(live, agent)) if (known.add(n.id)) nodes.add(n)
        }
        if (nodes.isNotEmpty()) {
            nodes[0] = nodes[0].copy(connectTop = false)
            nodes[nodes.lastIndex] = nodes[nodes.lastIndex].copy(connectBottom = false)
        }
        return nodes
    }

    /** A merged reply → ordered part nodes + a footer (always emitted, even tool-only).
     *  Text parts split into per-block nodes; persisted text is parse-memoized. */
    private fun assistantNodes(merged: MessageTurn, questionId: String?, agent: AgentType): List<TimelineNode> {
        val out = MessageRender.adaptTurn(merged)
            .flatMapIndexed { idx, part -> nodesFor(part, merged.id, idx, agent, cached = true) }
            .toMutableList()
        out.add(TimelineNode("${merged.id}#footer", NodeContent.Footer(merged, questionId), agent))
        return out
    }

    /** The in-flight reply → plan? + thinking? + grouped parts + error?. */
    private fun liveNodes(live: LiveTurnState, agent: AgentType): List<TimelineNode> {
        val out = mutableListOf<TimelineNode>()
        if (live.livePlan.isNotEmpty()) {
            out.add(TimelineNode("${live.id}#plan", NodeContent.Plan(live.livePlan), agent))
        }
        if (live.isEmpty && live.isStreaming) {
            out.add(TimelineNode("${live.id}#thinking", NodeContent.Thinking, agent))
        }
        // Same grouping as the persisted path: consecutive non-task tools collapse.
        // Live text is parsed uncached (each flush is a new key → cache churn).
        val parts = MessageRender.groupConsecutiveTools(liveParts(live))
        val partNodes = parts.flatMapIndexed { idx, part -> nodesFor(part, live.id, idx, agent, cached = false) }.toMutableList()
        // Caret only when the *final* block is prose — a trailing tool/group shows its
        // own pulsing marker instead (matches iOS `adaptLive` streaming-on-last-segment).
        if (live.isStreaming && partNodes.isNotEmpty()) {
            val lastIdx = partNodes.lastIndex
            when (val c = partNodes[lastIdx].content) {
                is NodeContent.AssistantBlock -> partNodes[lastIdx] = partNodes[lastIdx].copy(content = c.copy(streaming = true))
                is NodeContent.Reasoning -> partNodes[lastIdx] = partNodes[lastIdx].copy(content = c.copy(streaming = true))
                else -> {}
            }
        }
        out.addAll(partNodes)
        live.errorMessage?.let { out.add(TimelineNode("${live.id}#error", NodeContent.Error(it), agent)) }
        return out
    }

    private fun liveParts(live: LiveTurnState): List<RenderPart> {
        val parts = mutableListOf<RenderPart>()
        for (seg in live.segments) {
            when (seg) {
                is LiveSegment.Text -> if (seg.text.isNotBlank()) parts.add(RenderPart.Text(seg.text))
                is LiveSegment.Thinking -> if (seg.text.isNotBlank()) parts.add(RenderPart.Reasoning(seg.text))
                // Same boundary-marker treatment as the persisted path: codex emits the
                // `Context compacting` → `Context compacted` pair on one id, so the
                // divider flips from running to settled in place.
                is LiveSegment.Tool -> if (ContextCompaction.matches(seg.call.meta)) {
                    val (before, after) = ContextCompaction.tokens(seg.call.meta)
                    parts.add(RenderPart.Compaction(before, after, !seg.call.isFinished))
                } else {
                    parts.add(RenderPart.Tool(seg.call.toVM()))
                }
            }
        }
        return parts
    }

    /**
     * One render part → node(s). Text splits into per-block nodes (below); everything
     * else is a single node. The ID scheme is load-bearing: a tool keeps the same row
     * identity across the live→persisted handoff (keyed by its ACP tool id), so the
     * card's expand state and marker survive without flashing.
     */
    private fun nodesFor(part: RenderPart, ownerId: String, index: Int, agent: AgentType, cached: Boolean): List<TimelineNode> {
        val base = "$ownerId#$index"
        return when (part) {
            is RenderPart.Text -> textBlockNodes(part.text, base, agent, cached)
            is RenderPart.Reasoning -> listOf(TimelineNode(base, NodeContent.Reasoning(part.text), agent))
            is RenderPart.Tool -> listOf(TimelineNode("tool-${part.vm.id}", NodeContent.Tool(part.vm), agent))
            is RenderPart.ToolGroup -> {
                val gid = part.items.firstOrNull()?.let { "toolgroup-${it.id}" } ?: base
                listOf(TimelineNode(gid, NodeContent.ToolGroup(part.items, part.streaming), agent))
            }
            is RenderPart.Image -> listOf(TimelineNode(base, NodeContent.Image(part.image, part.caption), agent))
            is RenderPart.Compaction ->
                listOf(TimelineNode(base, NodeContent.Compaction(part.before, part.after, part.running), agent))
            is RenderPart.Unknown -> listOf(TimelineNode(base, NodeContent.Unsupported(part.type), agent))
        }
    }

    /**
     * Split an assistant prose part into one node per Markdown block so a long message
     * virtualizes in the LazyColumn (and streaming re-renders only the tail block). The
     * first block keeps the base id `$owner#$index` (stable across the live→persisted
     * handoff); later blocks get `#b$k`. Persisted text is parse-memoized ([MarkdownCache]);
     * the growing live tail uses [LiveBlockParser], which re-parses only the tail (not the
     * whole segment) each flush. [RailStyle] marks the head (avatar) vs the markerless,
     * tight-spaced continuation rows.
     */
    private fun textBlockNodes(text: String, base: String, agent: AgentType, cached: Boolean): List<TimelineNode> {
        val blocks = if (cached) MarkdownCache.blocks(text) else LiveBlockParser.parse(base, text)
        if (blocks.isEmpty()) return emptyList()
        return blocks.mapIndexed { k, block ->
            val id = if (k == 0) base else "$base#b$k"
            val rail = when {
                blocks.size == 1 -> RailStyle.Standalone
                k == 0 -> RailStyle.Head
                k == blocks.lastIndex -> RailStyle.Tail
                else -> RailStyle.Middle
            }
            TimelineNode(id, NodeContent.AssistantBlock(block), agent, rail = rail)
        }
    }

    /** Collapse a run of consecutive assistant turns into one synthetic reply. */
    fun merge(group: List<MessageTurn>): MessageTurn {
        if (group.size <= 1) return group[0]
        val usages = group.mapNotNull { it.usage }
        val mergedUsage = if (usages.isEmpty()) null else TurnUsage(
            inputTokens = usages.sumOf { it.inputTokens },
            outputTokens = usages.sumOf { it.outputTokens },
            cacheCreationInputTokens = usages.sumOf { it.cacheCreationInputTokens },
            cacheReadInputTokens = usages.sumOf { it.cacheReadInputTokens },
        )
        val durations = group.mapNotNull { it.durationMs }
        return MessageTurn(
            id = group.first().id,
            role = TurnRole.ASSISTANT,
            blocks = group.flatMap { it.blocks },
            timestamp = group.first().timestamp,
            usage = mergedUsage,
            durationMs = if (durations.isEmpty()) null else durations.sum(),
            model = group.lastOrNull { !it.model.isNullOrEmpty() }?.model ?: group.first().model,
            completedAt = group.last().completedAt ?: group.last().timestamp,
        )
    }
}

/** Live tool call → the shared view-ready [ToolCallVM] (mirrors the persisted path). */
private fun LiveToolCallState.toVM(): ToolCallVM {
    val state = when {
        !isFinished -> ToolCallState.RUNNING
        isError -> ToolCallState.ERROR
        else -> ToolCallState.DONE
    }
    return ToolCallVM.of(id, title, kind, state, rawInput, rawOutput, content, isError)
}
