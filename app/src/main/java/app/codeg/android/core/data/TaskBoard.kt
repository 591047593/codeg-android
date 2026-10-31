package app.codeg.android.core.data

import app.codeg.android.core.model.WorkTaskInfo

/**
 * Server status → display column projection (batch v139).
 *
 * The board shows the server's own [WorkTaskInfo.status] verbatim; the column is
 * a *derived, best-effort* grouping. Unknown statuses (a newer server adding a
 * state) land in [TaskColumn.UNKNOWN] — never silently in DONE (PRD §8.2: 未知
 * 状态不得自动归类为完成).
 */
enum class TaskColumn(val labelKey: String) {
    PENDING("task_column_pending"),
    RUNNING("task_column_running"),
    ATTENTION("task_column_attention"),
    DONE("task_column_done"),
    ENDED("task_column_ended"),
    UNKNOWN("task_column_unknown"),
}

/** The board's display order — unknown last, so it can never hide a real column. */
val BOARD_ORDER = listOf(
    TaskColumn.ATTENTION,
    TaskColumn.RUNNING,
    TaskColumn.PENDING,
    TaskColumn.DONE,
    TaskColumn.ENDED,
    TaskColumn.UNKNOWN,
)

/**
 * Map a raw server status to its display column.
 *
 *   awaiting_input/review → 需要处理 (agent blocked on the user)
 *   preparing/running     → 执行中
 *   todo/queued           → 待执行
 *   merging/done          → 已完成 (merging tagged "合并中" by the UI)
 *   failed/canceled       → 失败/取消
 *   anything else         → 未知 (raw string preserved on the row)
 */
fun taskColumnOf(status: String): TaskColumn = when (status) {
    "awaiting_input", "review" -> TaskColumn.ATTENTION
    "preparing", "running" -> TaskColumn.RUNNING
    "todo", "queued" -> TaskColumn.PENDING
    "merging", "done" -> TaskColumn.DONE
    "failed", "canceled" -> TaskColumn.ENDED
    else -> TaskColumn.UNKNOWN
}

/** One board entry: a task plus the server it came from (ids are per-server). */
data class TaskBoardEntry(
    val serverId: String,
    val serverName: String,
    val task: WorkTaskInfo,
)

/** Group tasks into the board columns, each column sorted by priority below. */
fun buildBoard(entries: List<TaskBoardEntry>): Map<TaskColumn, List<TaskBoardEntry>> {
    val grouped = entries.groupBy { taskColumnOf(it.task.status) }
    return BOARD_ORDER.associateWith { column ->
        grouped[column].orEmpty().sortedWith(compareByDescending<TaskBoardEntry> { taskSortKey(it) })
    }
}

/**
 * Sort key inside a column: scheduled first (soonest wins), then most recently
 * updated. Wire timestamps are RFC3339 UTC strings, which sort lexicographically.
 */
private fun taskSortKey(entry: TaskBoardEntry): String =
    timestampKey(entry.task.scheduledAt)
        ?: timestampKey(entry.task.updatedAt)
        ?: timestampKey(entry.task.createdAt)
        ?: ""

/**
 * Normalize an RFC3339 timestamp to an epoch-millis string so sorting is order-
 * safe regardless of the wire offset (`+08:00` vs `Z`); unparsable → null.
 */
private fun timestampKey(iso: String?): String? =
    iso?.let { raw -> runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()?.toString() }

/** Columns that actually have rows, in board order — for the section headers. */
fun activeColumns(board: Map<TaskColumn, List<TaskBoardEntry>>): List<TaskColumn> =
    BOARD_ORDER.filter { board[it]?.isNotEmpty() == true }
