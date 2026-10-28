package app.codeg.android.feature.sessiondetail.timeline

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnUsage
import app.codeg.android.feature.sessiondetail.LiveSegment
import app.codeg.android.feature.sessiondetail.LiveTurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Locks down the transcript → timeline-node flattening (merge, grouping, footers, endpoints). */
class TranscriptTimelineTest {

    private val agent = AgentType.CLAUDE_CODE

    private fun user(id: String, text: String) =
        MessageTurn(id, TurnRole.USER, listOf(ContentBlock.Text(text)), Instant.EPOCH)

    private fun assistant(id: String, blocks: List<ContentBlock>, usage: TurnUsage? = null) =
        MessageTurn(id, TurnRole.ASSISTANT, blocks, Instant.EPOCH, usage = usage)

    @Test
    fun `the rail terminates at its endpoints`() {
        val nodes = TranscriptTimeline.build(listOf(user("u1", "hi")), emptyList(), null, agent)
        assertTrue(nodes.isNotEmpty())
        assertFalse(nodes.first().connectTop)
        assertFalse(nodes.last().connectBottom)
    }

    @Test
    fun `consecutive assistant turns merge into one footer with summed usage`() {
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.Text("part one")), TurnUsage(outputTokens = 10)),
            assistant("a2", listOf(ContentBlock.Text("part two")), TurnUsage(outputTokens = 5)),
        )
        val nodes = TranscriptTimeline.build(turns, emptyList(), null, agent)
        val footers = nodes.mapNotNull { it.content as? NodeContent.Footer }
        assertEquals(1, footers.size)
        assertEquals(15, footers.single().turn.usage?.total)
        // Each single-line reply is one Markdown block → one standalone AssistantBlock node.
        val textNodes = nodes.filter { it.content is NodeContent.AssistantBlock }
        assertEquals(2, textNodes.size)
        assertTrue(textNodes.all { it.rail == RailStyle.Standalone })
    }

    @Test
    fun `consecutive tools collapse into one group node`() {
        val blocks = listOf(
            ContentBlock.ToolUse("t1", "Read", """{"file_path":"a"}"""),
            ContentBlock.ToolResult("t1", "ok", false),
            ContentBlock.ToolUse("t2", "Read", """{"file_path":"b"}"""),
            ContentBlock.ToolResult("t2", "ok", false),
        )
        val nodes = TranscriptTimeline.build(listOf(user("u1", "hi"), assistant("a1", blocks)), emptyList(), null, agent)
        assertEquals(1, nodes.count { it.content is NodeContent.ToolGroup })
        assertEquals(0, nodes.count { it.content is NodeContent.Tool })
    }

    @Test
    fun `the reply footer links back to its prompting user message`() {
        val nodes = TranscriptTimeline.build(
            listOf(user("u1", "hi"), assistant("a1", listOf(ContentBlock.Text("reply")))),
            emptyList(),
            null,
            agent,
        )
        val footer = nodes.mapNotNull { it.content as? NodeContent.Footer }.single()
        assertEquals("u1", footer.questionId)
    }

    @Test
    fun `anonymous tools across replies get unique node ids`() {
        // Two replies whose first tool call has no ACP id: the synthetic fallback must be
        // turn-qualified, else both collapse to the same LazyColumn key and crash.
        val turns = listOf(
            user("u1", "a"),
            assistant(
                "a1",
                listOf(
                    ContentBlock.ToolUse(null, "Read", """{"file_path":"x"}"""),
                    ContentBlock.ToolResult(null, "ok", false),
                ),
            ),
            user("u2", "b"),
            assistant(
                "a2",
                listOf(
                    ContentBlock.ToolUse(null, "Read", """{"file_path":"y"}"""),
                    ContentBlock.ToolResult(null, "ok", false),
                ),
            ),
        )
        val ids = TranscriptTimeline.build(turns, emptyList(), null, agent).map { it.id }
        assertEquals("node ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `a multi-block assistant reply splits into per-block nodes with rail head-middle-tail`() {
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.Text("# Title\n\nbody paragraph\n\n- one\n- two"))),
        )
        val nodes = TranscriptTimeline.build(turns, emptyList(), null, agent)
        val blocks = nodes.filter { it.content is NodeContent.AssistantBlock }
        assertEquals(3, blocks.size)
        assertEquals(listOf(RailStyle.Head, RailStyle.Middle, RailStyle.Tail), blocks.map { it.rail })
        // First block keeps the base id (streaming continuity); the rest get #b<k>.
        assertEquals(listOf("a1#0", "a1#0#b1", "a1#0#b2"), blocks.map { it.id })
    }

    @Test
    fun `split block node ids stay unique`() {
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.Text("para a\n\npara b\n\npara c"))),
        )
        val ids = TranscriptTimeline.build(turns, emptyList(), null, agent).map { it.id }
        assertEquals("node ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `a streaming multi-block reply carries the caret on the last block only and ends the rail`() {
        val live = LiveTurnState(
            id = "live1",
            segments = listOf(LiveSegment.Text("t0", "intro paragraph\n\ntail paragraph")),
            livePlan = emptyList(),
            isStreaming = true,
            stopReason = null,
            errorMessage = null,
        )
        val nodes = TranscriptTimeline.build(emptyList(), emptyList(), live, agent)
        val blocks = nodes.filter { it.content is NodeContent.AssistantBlock }
        assertEquals(2, blocks.size)
        assertFalse((blocks.first().content as NodeContent.AssistantBlock).streaming)
        val last = blocks.last().content as NodeContent.AssistantBlock
        assertTrue("caret rides the final block", last.streaming)
        assertFalse("rail terminates at the streaming tail", blocks.last().connectBottom)
        assertEquals(RailStyle.Tail, blocks.last().rail)
    }

    @Test
    fun `an empty system turn produces no node`() {
        val turns = listOf(
            user("u1", "hi"),
            MessageTurn("s1", TurnRole.SYSTEM, listOf(ContentBlock.Text("   ")), Instant.EPOCH),
        )
        val nodes = TranscriptTimeline.build(turns, emptyList(), null, agent)
        assertEquals(0, nodes.count { it.content is NodeContent.System })
    }

    /**
     * The server sends a windowed transcript as an exact slice of the full one, and the
     * client renders it as a suffix.
     *
     * This is the invariant the whole windowed load rests on: the window is taken from
     * the server (`tailTurns`), not sliced locally, so what arrives must be the same
     * turns — in the same order — as the matching tail of the full transcript. If the
     * server's slice and the client's flatten disagreed at the seam, the visible history
     * would change shape as older turns paged in.
     */
    @Test
    fun `a server window renders the same nodes as the matching tail`() {
        val turns = (1..12).flatMap { i ->
            listOf(
                user("u$i", "prompt $i"),
                assistant("a${i}a", listOf(ContentBlock.Text("reply $i part one"))),
                assistant("a${i}b", listOf(ContentBlock.Text("reply $i part two"))),
            )
        }
        val full = TranscriptTimeline.buildPersisted(turns, emptyList(), agent)

        // The server's tail window always starts on a turn boundary and, because a reply
        // is a run of assistant turns, never splits one: ask for suffixes that begin on a
        // user turn, which is what `turnsOffset` reports after paging.
        for (requested in listOf(3, 6, 9, 12)) {
            val start = (turns.size - requested).coerceAtLeast(0)
            val windowed = TranscriptTimeline.buildPersisted(
                turns.subList(start, turns.size),
                emptyList(),
                agent,
            )
            assertEquals(
                "requested=$requested should render the same node ids as the matching tail",
                full.takeLast(windowed.size).map { it.id },
                windowed.map { it.id },
            )
        }
    }
}
