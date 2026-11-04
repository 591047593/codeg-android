package app.codeg.android.feature.sessiondetail

import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down the live-turn accumulation logic (ported from iOS `LiveTurn`). */
class LiveTurnBuilderTest {

    @Test
    fun `consecutive text deltas coalesce into one segment`() {
        val b = LiveTurnBuilder("t")
        b.appendText("Hel")
        b.appendText("lo")
        val segs = b.snapshot().segments
        assertEquals(1, segs.size)
        assertEquals("Hello", (segs[0] as LiveSegment.Text).text)
    }

    @Test
    fun `thinking breaks the text run`() {
        val b = LiveTurnBuilder("t")
        b.appendText("a")
        b.appendThinking("reason")
        b.appendText("b")
        val segs = b.snapshot().segments
        assertEquals(3, segs.size)
        assertTrue(segs[0] is LiveSegment.Text)
        assertTrue(segs[1] is LiveSegment.Thinking)
        assertTrue(segs[2] is LiveSegment.Text)
    }

    @Test
    fun `tool call upsert then append updates output in place`() {
        val b = LiveTurnBuilder("t")
        b.upsertToolCall("id1", "Bash", "execute", "in_progress", """{"command":"ls"}""", null, null)
        b.updateToolCall("id1", title = null, status = null, rawInput = null, rawOutput = "line1\n", content = null, append = true)
        b.updateToolCall("id1", title = null, status = "completed", rawInput = null, rawOutput = "line2", content = null, append = true)
        val segs = b.snapshot().segments
        assertEquals(1, segs.size)
        val call = (segs[0] as LiveSegment.Tool).call
        assertEquals("line1\nline2", call.rawOutput)
        assertEquals("completed", call.status)
        assertTrue(call.isFinished)
        assertFalse(call.isError)
    }

    @Test
    fun `snapshotAsMessageTurn emits paired tool blocks`() {
        val b = LiveTurnBuilder("live-1")
        b.appendText("done")
        b.upsertToolCall("id1", "Read foo", "read", "completed", null, "file contents", null)
        val turn = b.snapshotAsMessageTurn()
        assertEquals(TurnRole.ASSISTANT, turn.role)
        // text + (toolUse, toolResult)
        assertEquals(3, turn.blocks.size)
        assertTrue(turn.blocks[0] is ContentBlock.Text)
        assertTrue(turn.blocks[1] is ContentBlock.ToolUse)
        assertTrue(turn.blocks[2] is ContentBlock.ToolResult)
    }

    @Test
    fun `args arriving via updates un-settle the title until a re-announce settles it`() {
        val b = LiveTurnBuilder("t")
        // CodeBuddy's shape: announce (name only, empty args) → updates streaming the
        // accumulated JSON → final re-announce with the complete arguments.
        b.upsertToolCall("id1", "Bash", "other", "inprogress", "{}", null, null)
        assertTrue((b.snapshot().segments[0] as LiveSegment.Tool).call.argsSettled)
        b.updateToolCall("id1", title = null, status = null, rawInput = "{\"command\":\"ls -la /mnt", rawOutput = null, content = null, append = false)
        val streaming = (b.snapshot().segments[0] as LiveSegment.Tool).call
        assertFalse(streaming.argsSettled)
        // The final re-announce (a `tool_call` for the same id) settles the args again.
        b.upsertToolCall("id1", "`ls -la /mnt`", "execute", "pending", """{"command":"ls -la /mnt"}""", null, null)
        assertTrue((b.snapshot().segments[0] as LiveSegment.Tool).call.argsSettled)
    }

    @Test
    fun `a terminal status settles streaming args without a re-announce`() {
        val b = LiveTurnBuilder("t")
        b.upsertToolCall("id1", "Bash", "other", "inprogress", "{}", null, null)
        b.updateToolCall("id1", title = null, status = null, rawInput = """{"command":"ls"}""", rawOutput = null, content = null, append = false)
        assertFalse((b.snapshot().segments[0] as LiveSegment.Tool).call.argsSettled)
        b.updateToolCall("id1", title = null, status = "completed", rawInput = null, rawOutput = "out", content = null, append = true)
        val call = (b.snapshot().segments[0] as LiveSegment.Tool).call
        assertTrue(call.argsSettled)
        assertTrue(call.isFinished)
    }

    @Test
    fun `output-only updates leave the settled flag alone`() {
        val b = LiveTurnBuilder("t")
        b.upsertToolCall("id1", "Bash", "execute", "in_progress", """{"command":"ls"}""", null, null)
        b.updateToolCall("id1", title = null, status = null, rawInput = null, rawOutput = "line1\n", content = null, append = true)
        assertTrue((b.snapshot().segments[0] as LiveSegment.Tool).call.argsSettled)
    }
}
