package app.codeg.android.feature.taskhub

import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.model.WorkTaskInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 批v161.1 Bug A: a card the engine or another client moves must announce
 *  where it went — the reported "删一张另一张也消失" was a cancel landing in
 *  已完成 on another pager page with zero cue. */
class BoardMoveNoteTest {

    private fun entry(id: Int, status: String, title: String = "T$id") = TaskBoardEntry(
        serverId = "s1",
        serverName = "srv",
        task = WorkTaskInfo(id = id, title = title, status = status),
    )

    @Test
    fun `status move is announced`() {
        val note = boardMoveNote(
            old = listOf(entry(1, "running")),
            new = listOf(entry(1, "canceled")),
            busy = false,
        )
        assertEquals("「T1」已从「进行中」移到「已完成」", note)
    }

    @Test
    fun `disappearing card is announced`() {
        val note = boardMoveNote(
            old = listOf(entry(1, "canceled"), entry(2, "done")),
            new = listOf(entry(2, "done")),
            busy = false,
        )
        assertEquals("「T1」已从看板移除", note)
    }

    @Test
    fun `no move no note`() {
        assertNull(
            boardMoveNote(
                old = listOf(entry(1, "todo")),
                new = listOf(entry(1, "todo")),
                busy = false,
            ),
        )
    }

    @Test
    fun `brand new cards are not a move`() {
        assertNull(
            boardMoveNote(
                old = emptyList(),
                new = listOf(entry(1, "todo")),
                busy = false,
            ),
        )
    }

    @Test
    fun `own in-flight round stays quiet`() {
        assertNull(
            boardMoveNote(
                old = listOf(entry(1, "todo")),
                new = listOf(entry(1, "running")),
                busy = true,
            ),
        )
    }
}
