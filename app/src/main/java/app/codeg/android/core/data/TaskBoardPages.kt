package app.codeg.android.core.data

import kotlin.math.max

/**
 * 批v161: the task board's paging math, kept pure so the layout decisions are
 * unit-testable without a device.
 *
 * The board always has four segments; a page shows either one or two of them
 * (phones) or all four at once (tablets — the web-parity board is the right
 * shape once the window is at least [TASK_BOARD_TABLET_BREAKPOINT_DP] wide).
 */
const val TASK_BOARD_TABLET_BREAKPOINT_DP = 600
const val TASK_BOARD_TABLET_COLUMNS = 4

/** The page's horizontal padding / inter-column gap, mirrored from the screen. */
const val TASK_BOARD_SIDE_PADDING_DP = 12
const val TASK_BOARD_COLUMN_GAP_DP = 8

/** Columns one kanban page renders: the user's 1/2 on phones, always four on tablets. */
fun taskBoardColumnsPerPage(screenWidthDp: Int, setting: Int): Int =
    if (screenWidthDp >= TASK_BOARD_TABLET_BREAKPOINT_DP) {
        TASK_BOARD_TABLET_COLUMNS
    } else {
        setting.coerceIn(1, 2)
    }

/** Pages needed for the four segments at [columnsPerPage] columns per page. */
fun taskBoardPageCount(columnsPerPage: Int): Int {
    val columns = columnsPerPage.coerceIn(1, SEGMENT_ORDER.size)
    return (SEGMENT_ORDER.size + columns - 1) / columns
}

/** The page a segment lives on (segment index → page index). */
fun segmentPageIndex(segmentIndex: Int, columnsPerPage: Int): Int =
    segmentIndex.coerceAtLeast(0) / columnsPerPage.coerceIn(1, SEGMENT_ORDER.size)

/** The segments rendered side by side on [page], in board order. */
fun pageSegments(page: Int, columnsPerPage: Int): List<TaskSegment> {
    val columns = columnsPerPage.coerceIn(1, SEGMENT_ORDER.size)
    val start = page * columns
    if (start !in SEGMENT_ORDER.indices) return emptyList()
    return SEGMENT_ORDER.subList(start, (start + columns).coerceAtMost(SEGMENT_ORDER.size))
}

/**
 * Paging steps clamp at both ends like [segmentStepIndex] — a swipe past the
 * last page must not wrap the user back to the first.
 */
fun pageStepIndex(currentPage: Int, step: Int, pageCount: Int): Int =
    (currentPage + step).coerceIn(0, max(pageCount - 1, 0))

/**
 * Where the board opens: the first segment that actually holds rows, so a
 * phone whose 待办 is empty does not greet the user with a blank page (the
 * sandbox state of the review: two pages, the first one empty).
 */
fun initialKanbanPage(board: TaskBoardData, columnsPerPage: Int): Int {
    val index = SEGMENT_ORDER.indexOfFirst { segment ->
        !board.segments[segment].isNullOrEmpty() ||
            (segment == TaskSegment.ATTENTION && board.unknown.isNotEmpty())
    }
    return if (index < 0) 0 else segmentPageIndex(index, columnsPerPage)
}

/** One column's usable width inside a page, mirroring the screen's Row layout. */
fun taskBoardColumnWidthDp(screenWidthDp: Int, columnsPerPage: Int): Int {
    val columns = columnsPerPage.coerceIn(1, SEGMENT_ORDER.size)
    val content = screenWidthDp - TASK_BOARD_SIDE_PADDING_DP * 2 - TASK_BOARD_COLUMN_GAP_DP * (columns - 1)
    return (content / columns).coerceAtLeast(0)
}

/**
 * Two or more columns per page means the narrow card: the web card's header
 * chip + full-width footer cannot share ~156dp of content width.
 */
fun taskBoardCompactCards(columnsPerPage: Int): Boolean = columnsPerPage >= 2
