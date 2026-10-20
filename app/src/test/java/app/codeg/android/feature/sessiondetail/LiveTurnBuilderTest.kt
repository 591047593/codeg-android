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
    fun `terminal tool statuses are recognised and in-flight ones are not`() {
        // The predicate drives the compose status line and whether a tool folds into a
        // group, so a missed spelling shows a finished tool as still running forever.
        for (terminal in listOf("completed", "failed", "error", "cancelled", "canceled")) {
            assertTrue("expected terminal: $terminal", isFinishedStatus(terminal))
        }
        for (running in listOf("in_progress", "pending", "input_streaming", "")) {
            assertFalse("expected running: $running", isFinishedStatus(running))
        }
    }

    @Test
    fun `only failed and error count as errors, not cancelled`() {
        assertEquals(true, LiveToolCallState("i", "t", "k", "failed", null, "", null).isError)
        assertEquals(true, LiveToolCallState("i", "t", "k", "error", null, "", null).isError)
        assertEquals(false, LiveToolCallState("i", "t", "k", "cancelled", null, "", null).isError)
        assertEquals(false, LiveToolCallState("i", "t", "k", "completed", null, "", null).isError)
    }

    @Test
    fun `activeToolTitle finds the most recent unfinished tool`() {
        val b = LiveTurnBuilder("t")
        b.upsertToolCall("a", "First", "read", "completed", null, null, null)
        b.upsertToolCall("b", "Second", "execute", "in_progress", null, null, null)
        assertEquals("Second", b.activeToolTitle())

        b.updateToolCall("b", title = null, status = "completed", rawInput = null, rawOutput = null, content = null, append = false)
        assertEquals(null, b.activeToolTitle())
    }
}
