package app.codeg.android.core.data

import app.codeg.android.core.model.WorkTaskInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskBoardTest {

    private fun task(
        id: Int,
        status: String,
        serverId: String = "s1",
        title: String = "T$id",
        scheduledAt: String? = null,
        updatedAt: String? = null,
    ) = TaskBoardEntry(
        serverId = serverId,
        serverName = "srv-$serverId",
        task = WorkTaskInfo(
            id = id, title = title, status = status,
            scheduledAt = scheduledAt, updatedAt = updatedAt,
        ),
    )

    @Test
    fun `column mapping follows server enum`() {
        assertEquals(TaskColumn.PENDING, taskColumnOf("todo"))
        assertEquals(TaskColumn.PENDING, taskColumnOf("queued"))
        assertEquals(TaskColumn.RUNNING, taskColumnOf("preparing"))
        assertEquals(TaskColumn.RUNNING, taskColumnOf("running"))
        assertEquals(TaskColumn.ATTENTION, taskColumnOf("awaiting_input"))
        assertEquals(TaskColumn.ATTENTION, taskColumnOf("review"))
        assertEquals(TaskColumn.DONE, taskColumnOf("merging"))
        assertEquals(TaskColumn.DONE, taskColumnOf("done"))
        assertEquals(TaskColumn.ENDED, taskColumnOf("failed"))
        assertEquals(TaskColumn.ENDED, taskColumnOf("canceled"))
    }

    @Test
    fun `unknown status never lands in done`() {
        // A future server version adding a state must surface as 未知, not complete.
        assertEquals(TaskColumn.UNKNOWN, taskColumnOf("frobnicating"))
        assertEquals(TaskColumn.UNKNOWN, taskColumnOf(""))
        val board = buildBoard(listOf(task(1, "frobnicating")))
        assertEquals(0, board[TaskColumn.DONE]!!.size)
        assertEquals(1, board[TaskColumn.UNKNOWN]!!.size)
    }

    @Test
    fun `status string is preserved verbatim`() {
        val entry = task(1, "some_brand_new_state")
        assertEquals("some_brand_new_state", entry.task.status)
    }

    @Test
    fun `board keeps all six columns in fixed order`() {
        val board = buildBoard(
            listOf(
                task(1, "todo"),
                task(2, "running"),
                task(3, "awaiting_input"),
                task(4, "done"),
                task(5, "failed"),
                task(6, "mystery"),
            ),
        )
        assertEquals(BOARD_ORDER, board.keys.toList())
    }

    @Test
    fun `active columns omit empty ones`() {
        val board = buildBoard(listOf(task(1, "running"), task(2, "done")))
        assertEquals(listOf(TaskColumn.RUNNING, TaskColumn.DONE), activeColumns(board))
    }

    @Test
    fun `scheduled tasks sort before unscheduled within a column`() {
        val board = buildBoard(
            listOf(
                task(1, "todo", updatedAt = "2026-11-01T10:00:00Z"),
                task(2, "todo", scheduledAt = "2026-11-02T08:00:00Z"),
            ),
        )
        assertEquals(2, board[TaskColumn.PENDING]!!.first().task.id)
    }

    @Test
    fun `same column aggregates across servers`() {
        val board = buildBoard(listOf(task(1, "running", serverId = "a"), task(2, "running", serverId = "b")))
        assertEquals(listOf("a", "b"), board[TaskColumn.RUNNING]!!.map { it.serverId })
    }

    /** ui重构 阶段1：wild-server statuses must not fall over or fake done. */
    @Test
    fun `cancelled alias maps to ended`() {
        assertEquals(TaskColumn.ENDED, taskColumnOf("cancelled"))
        assertEquals(TaskColumn.ENDED, taskColumnOf("canceled"))
    }

    @Test
    fun `archived status never lands in done and is archived-like`() {
        // dell/win databases contain rows written as status="archived" by another
        // build. Raw string kept; column UNKNOWN; hidden by the archived filter.
        val entry = task(1, "archived")
        assertEquals(TaskColumn.UNKNOWN, taskColumnOf("archived"))
        assertEquals(true, entry.task.isArchivedLike)
        val board = buildBoard(listOf(entry))
        assertEquals(0, board[TaskColumn.DONE]!!.size)
        assertEquals(1, board[TaskColumn.UNKNOWN]!!.size)
    }

    @Test
    fun `archivedAt flag alone marks row archived-like`() {
        val entry = TaskBoardEntry(
            serverId = "s1",
            serverName = "srv-s1",
            task = WorkTaskInfo(id = 2, title = "T2", status = "done", archivedAt = "2026-10-01T00:00:00Z"),
        )
        assertEquals(true, entry.task.isArchivedLike)
        assertEquals(false, task(3, "done").task.isArchivedLike)
    }
}
