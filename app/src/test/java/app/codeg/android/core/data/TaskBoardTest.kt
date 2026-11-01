package app.codeg.android.core.data

import app.codeg.android.core.model.WorkTaskInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardTest {

    private fun task(
        id: Int,
        status: String,
        serverId: String = "s1",
        title: String = "T$id",
        scheduledAt: String? = null,
        updatedAt: String? = null,
        deferred: Boolean = false,
    ) = TaskBoardEntry(
        serverId = serverId,
        serverName = "srv-$serverId",
        task = WorkTaskInfo(
            id = id, title = title, status = status,
            scheduledAt = scheduledAt, updatedAt = updatedAt, deferred = deferred,
        ),
    )

    /** Web-parity mapping (author's board-columns.ts), ui重构 阶段3. */
    @Test
    fun `segment mapping follows the web board`() {
        assertEquals(TaskSegment.TODO, taskSegmentOf(task(1, "todo").task))
        assertEquals(TaskSegment.TODO, taskSegmentOf(task(1, "queued").task))
        assertEquals(TaskSegment.RUNNING, taskSegmentOf(task(1, "preparing").task))
        assertEquals(TaskSegment.RUNNING, taskSegmentOf(task(1, "running").task))
        assertEquals(TaskSegment.ATTENTION, taskSegmentOf(task(1, "awaiting_input").task))
        assertEquals(TaskSegment.ATTENTION, taskSegmentOf(task(1, "review").task))
        assertEquals(TaskSegment.ATTENTION, taskSegmentOf(task(1, "merging").task))
        assertEquals(TaskSegment.ATTENTION, taskSegmentOf(task(1, "failed").task))
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "done").task))
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "canceled").task))
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "cancelled").task))
    }

    @Test
    fun `deferred tasks are filed under done like the web board`() {
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "review", deferred = true).task))
        assertEquals(TaskSegment.DONE, taskSegmentOf(task(1, "failed", deferred = true).task))
    }

    @Test
    fun `unknown status never lands in a segment`() {
        // A future server version adding a state must surface in the neutral
        // unknown section — never silently in 已完成.
        assertNull(taskSegmentOf(task(1, "frobnicating").task))
        assertNull(taskSegmentOf(task(1, "").task))
        assertNull(taskSegmentOf(task(1, "archived").task))
        val board = buildBoard(listOf(task(1, "frobnicating")))
        assertEquals(0, board.segments[TaskSegment.DONE]!!.size)
        assertEquals(1, board.unknown.size)
    }

    @Test
    fun `status string is preserved verbatim`() {
        val entry = task(1, "some_brand_new_state")
        assertEquals("some_brand_new_state", entry.task.status)
    }

    @Test
    fun `archived rows hide unless shown`() {
        val archivedRow = TaskBoardEntry(
            serverId = "s1", serverName = "srv",
            task = WorkTaskInfo(id = 7, title = "T7", status = "done", archivedAt = "2026-10-01T00:00:00Z"),
        )
        assertEquals(0, buildBoard(listOf(archivedRow)).segments.values.sumOf { it.size })
        val shown = buildBoard(listOf(archivedRow), showArchived = true)
        assertEquals(1, shown.segments[TaskSegment.DONE]!!.size)
    }

    @Test
    fun `canceled rows hide only when the filter says so`() {
        val rows = listOf(task(1, "canceled"), task(2, "cancelled"))
        assertEquals(2, buildBoard(rows, showCanceled = true).segments[TaskSegment.DONE]!!.size)
        assertEquals(0, buildBoard(rows, showCanceled = false).segments[TaskSegment.DONE]!!.size)
    }

    @Test
    fun `counts reflect filters but not search (search applied by caller)`() {
        val rows = listOf(task(1, "todo"), task(2, "running"), task(3, "review"))
        val board = buildBoard(rows, showCanceled = false)
        assertEquals(1, board.counts[TaskSegment.TODO])
        assertEquals(1, board.counts[TaskSegment.RUNNING])
        assertEquals(1, board.counts[TaskSegment.ATTENTION])
        assertEquals(0, board.counts[TaskSegment.DONE])
    }

    @Test
    fun `scheduled tasks sort before unscheduled within a segment`() {
        val board = buildBoard(
            listOf(
                task(1, "todo", updatedAt = "2026-11-01T10:00:00Z"),
                task(2, "todo", scheduledAt = "2026-11-02T08:00:00Z"),
            ),
        )
        assertEquals(2, board.segments[TaskSegment.TODO]!!.first().task.id)
    }

    @Test
    fun `same segment aggregates across servers`() {
        val board = buildBoard(listOf(task(1, "running", serverId = "a"), task(2, "running", serverId = "b")))
        assertEquals(listOf("a", "b"), board.segments[TaskSegment.RUNNING]!!.map { it.serverId })
    }

    /** ui重构 阶段1: wild statuses stay display-known, raw preserved. */
    @Test
    fun `wild statuses are archived-like or neutral and never runnable-looking`() {
        assertTrue(task(1, "archived").task.isArchivedLike)
        assertTrue(
            TaskBoardEntry("s", "srv", WorkTaskInfo(2, "T", "done", archivedAt = "2026-10-01T00:00:00Z"))
                .task.isArchivedLike,
        )
        assertEquals(TaskStatusTone.NEUTRAL, taskStatusUi(task(3, "mystery").task).tone)
        assertEquals("mystery", taskStatusUi(task(3, "mystery").task).raw)
        assertEquals(TaskStatusTone.RUN, taskStatusUi(task(4, "running").task).tone)
        assertEquals(TaskStatusTone.WARN, taskStatusUi(task(5, "review").task).tone)
        assertEquals(TaskStatusTone.DANGER, taskStatusUi(task(6, "failed").task).tone)
    }
}
