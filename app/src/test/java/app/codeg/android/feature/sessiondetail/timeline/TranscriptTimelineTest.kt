package app.codeg.android.feature.sessiondetail.timeline

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import app.codeg.android.core.model.TurnUsage
import app.codeg.android.feature.sessiondetail.LiveSegment
import app.codeg.android.feature.sessiondetail.tailWindow
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
     * The windowed load path builds a suffix of the turn list and relies on that suffix
     * producing exactly the tail of the full flatten.
     *
     * This is the invariant the whole "open on the newest 120 turns" change rests on: if
     * a windowed build produced different nodes — because merge boundaries or the
     * `lastUserId` grouping shifted at the cut — the transcript would silently change
     * shape as older turns loaded, and a partially-scrolled conversation would not match
     * the same conversation opened at the bottom. Splitting only at a user turn keeps
     * every assistant run intact on both sides of the cut.
     */
    @Test
    fun `a windowed build equals the tail of the full build`() {
        val turns = (1..12).flatMap { i ->
            listOf(
                user("u$i", "prompt $i"),
                assistant("a${i}a", listOf(ContentBlock.Text("reply $i part one"))),
                assistant("a${i}b", listOf(ContentBlock.Text("reply $i part two"))),
            )
        }
        val full = TranscriptTimeline.buildPersisted(turns, emptyList(), agent)

        for (requested in listOf(3, 6, 9, 12)) {
            val windowedTurns = turns.tailWindow(requested)
            val windowed = TranscriptTimeline.buildPersisted(windowedTurns, emptyList(), agent)
            assertEquals(
                "requested=$requested should render the same node ids as the matching tail",
                full.takeLast(windowed.size).map { it.id },
                windowed.map { it.id },
            )
        }
    }

    /**
     * Turns at the window boundary must not merge across the cut.
     *
     * `buildPersisted` merges each run of consecutive assistant turns into one reply
     * with one footer. Cutting at an arbitrary turn index can land inside such a run,
     * which splits one reply into two — as older turns load, a second token/duration
     * footer would appear in the middle of a message. A cut placed at the run's start
     * keeps it whole.
     */
    @Test
    fun `cutting at the start of a reply keeps one footer`() {
        val turns = listOf(
            user("u1", "hi"),
            assistant("a1", listOf(ContentBlock.Text("first half"))),
            assistant("a2", listOf(ContentBlock.Text("second half"))),
        )
        // The boundary a windowed load would choose: both assistant turns, nothing split.
        val windowed = TranscriptTimeline.buildPersisted(
            turns.drop(1),
            emptyList(),
            agent,
        )
        assertEquals(
            "one merged reply means one footer",
            1,
            windowed.count { it.content is NodeContent.Footer },
        )
    }

    /**
     * The window is widened backward to a role boundary so a reply is never split.
     *
     * This exercises the same rule `SessionDetailViewModel` applies when it slices the
     * turn list: a suffix that begins on an assistant turn is moved back until it begins
     * on a user turn.
     */
    @Test
    fun `a suffix starting mid-reply is widened back to the reply boundary`() {
        val turns = listOf(
            user("u1", "one"),
            assistant("a1", listOf(ContentBlock.Text("r1"))),
            assistant("a2", listOf(ContentBlock.Text("r2"))),
            user("u2", "two"),
            assistant("a3", listOf(ContentBlock.Text("r3"))),
        )
        // Ask for the last three: that lands on a2, inside u1's reply.
        val cut = 3
        var widened = turns.size - cut
        while (widened > 0 && turns[widened].role == TurnRole.ASSISTANT) widened--

        assertEquals("widened cut starts on a user turn", TurnRole.USER, turns[widened].role)
        val windowed = TranscriptTimeline.buildPersisted(turns.subList(widened, turns.size), emptyList(), agent)
        val full = TranscriptTimeline.buildPersisted(turns, emptyList(), agent)
        assertEquals(full.map { it.id }, windowed.map { it.id })
    }
}
