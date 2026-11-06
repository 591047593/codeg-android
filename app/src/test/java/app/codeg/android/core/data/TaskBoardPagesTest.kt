package app.codeg.android.core.data

import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批v161: the board's paging math. The numbers here are the contract behind the
 * "two columns, two pages" phone layout — a regression in any of them either
 * strands a segment on its own page or renders a column too narrow for a card
 * footer.
 */
class TaskBoardPagesTest {

    @Test
    fun `phones honor the one-or-two setting and tablets always get four`() {
        assertEquals(2, taskBoardColumnsPerPage(392, 2))
        assertEquals(1, taskBoardColumnsPerPage(392, 1))
        // The breaking point is the Material compact/medium boundary.
        assertEquals(2, taskBoardColumnsPerPage(599, 2))
        assertEquals(4, taskBoardColumnsPerPage(600, 2))
        // A tablet ignores the phone setting: the web-parity board is right there.
        assertEquals(4, taskBoardColumnsPerPage(600, 1))
        assertEquals(4, taskBoardColumnsPerPage(840, 2))
    }

    @Test
    fun `four segments map onto one two or four pages`() {
        assertEquals(4, taskBoardPageCount(1))
        assertEquals(2, taskBoardPageCount(2))
        assertEquals(1, taskBoardPageCount(4))
        // Corrupt input never yields zero pages (the pager would have no page).
        assertEquals(4, taskBoardPageCount(0))
        assertEquals(1, taskBoardPageCount(9))
    }

    @Test
    fun `segment to page mapping pairs the columns`() {
        assertEquals(0, segmentPageIndex(0, 2))
        assertEquals(0, segmentPageIndex(1, 2))
        assertEquals(1, segmentPageIndex(2, 2))
        assertEquals(1, segmentPageIndex(3, 2))
        assertEquals(3, segmentPageIndex(3, 1))
        assertEquals(0, segmentPageIndex(0, 4))
        assertEquals(0, segmentPageIndex(-2, 2))
    }

    @Test
    fun `a page renders its segments in board order`() {
        assertEquals(
            listOf(TaskSegment.TODO, TaskSegment.RUNNING),
            pageSegments(0, 2),
        )
        assertEquals(
            listOf(TaskSegment.ATTENTION, TaskSegment.DONE),
            pageSegments(1, 2),
        )
        assertEquals(SEGMENT_ORDER, pageSegments(0, 4))
        assertEquals(listOf(TaskSegment.RUNNING), pageSegments(1, 1))
        // Out of range pages render nothing instead of throwing inside the pager.
        assertEquals(emptyList<TaskSegment>(), pageSegments(2, 2))
        assertEquals(emptyList<TaskSegment>(), pageSegments(-1, 2))
    }

    @Test
    fun `page steps clamp at both ends`() {
        assertEquals(0, pageStepIndex(0, -1, 2))
        assertEquals(1, pageStepIndex(0, 1, 2))
        assertEquals(1, pageStepIndex(1, 1, 2))
        assertEquals(0, pageStepIndex(1, -1, 2))
        assertEquals(0, pageStepIndex(0, 5, 1))
        assertEquals(0, pageStepIndex(0, 0, 0))
    }

    @Test
    fun `initial page follows the first non-empty segment`() {
        // The review's trap: empty 待办 + full 已完成 must not open on a blank page.
        val onlyDone = TaskBoardData(
            segments = mapOf(TaskSegment.DONE to listOf(sampleEntry())),
        )
        assertEquals(1, initialKanbanPage(onlyDone, 2))
        assertEquals(3, initialKanbanPage(onlyDone, 1))

        val todoAndDone = TaskBoardData(
            segments = mapOf(
                TaskSegment.TODO to listOf(sampleEntry()),
                TaskSegment.DONE to listOf(sampleEntry()),
            ),
        )
        assertEquals(0, initialKanbanPage(todoAndDone, 2))

        // A board with nothing yet opens on page 0, never on a missing page.
        assertEquals(0, initialKanbanPage(TaskBoardData(), 2))
        assertEquals(0, initialKanbanPage(TaskBoardData(), 4))
    }

    @Test
    fun `unknown statuses count as the attention page`() {
        val unknownOnly = TaskBoardData(unknown = listOf(sampleEntry()))
        assertEquals(1, initialKanbanPage(unknownOnly, 2))
        assertEquals(2, initialKanbanPage(unknownOnly, 1))
    }

    @Test
    fun `column widths match the three phone classes`() {
        // 2 columns: (width - 24 side padding - 8 gap) / 2.
        assertEquals(180, taskBoardColumnWidthDp(392, 2))
        assertEquals(164, taskBoardColumnWidthDp(360, 2))
        assertEquals(190, taskBoardColumnWidthDp(412, 2))
        assertEquals(368, taskBoardColumnWidthDp(392, 1))
        // Tablet four-column split stays well above the compact card's floor.
        assertEquals(78, taskBoardColumnWidthDp(360, 4))
        assertEquals(91, taskBoardColumnWidthDp(412, 4))
        // Degenerate widths never go negative.
        assertEquals(0, taskBoardColumnWidthDp(10, 4))
    }

    @Test
    fun `compact cards start at two columns`() {
        assertFalse(taskBoardCompactCards(1))
        assertTrue(taskBoardCompactCards(2))
        assertTrue(taskBoardCompactCards(4))
    }

    private fun sampleEntry() = TaskBoardEntry(
        serverId = "s1",
        serverName = "srv",
        task = app.codeg.android.core.model.WorkTaskInfo(id = 1, title = "t", status = "done"),
    )

    private fun sampleConv(id: Int = 1) = BoardConversationEntry(
        serverId = "s1",
        serverName = "srv",
        conversation = ConversationSummary(
            id = id,
            folderId = 7,
            title = "C$id",
            agentType = app.codeg.android.core.model.AgentType.CLAUDE_CODE,
            status = ConversationStatus.fromWire("in_progress"),
            messageCount = 4,
            createdAt = Instant.parse("2026-11-06T09:00:00Z"),
            updatedAt = Instant.parse("2026-11-06T10:00:00Z"),
        ),
        live = true,
        archived = false,
    )

    /** 批v161.2: the anchor looked at tasks only — a board holding ONLY tracked
     *  sessions opened on a blank first page with the cards elsewhere. */
    @Test
    fun `initial page follows conversations too`() {
        val onlyDone = TaskBoardData(conversations = mapOf(TaskSegment.DONE to listOf(sampleConv())))
        assertEquals(1, initialKanbanPage(onlyDone, 2))
        assertEquals(3, initialKanbanPage(onlyDone, 1))

        val running = TaskBoardData(conversations = mapOf(TaskSegment.RUNNING to listOf(sampleConv())))
        assertEquals(0, initialKanbanPage(running, 2))
        assertEquals(1, initialKanbanPage(running, 1))

        val attention = TaskBoardData(conversations = mapOf(TaskSegment.ATTENTION to listOf(sampleConv())))
        assertEquals(1, initialKanbanPage(attention, 1))

        // Mixed board: the first non-empty segment (a task) still wins.
        val mixed = TaskBoardData(
            segments = mapOf(TaskSegment.TODO to listOf(sampleEntry())),
            conversations = mapOf(TaskSegment.DONE to listOf(sampleConv())),
        )
        assertEquals(0, initialKanbanPage(mixed, 2))
    }
}
