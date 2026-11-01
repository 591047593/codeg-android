package app.codeg.android.core.data

import app.codeg.android.core.model.WorkTaskInfo

/**
 * Server status → display projection (ui重构 阶段3, mirrors the author's web
 * board `board-columns.ts`):
 *
 *   待办     todo, queued
 *   进行中   preparing, running
 *   等你处理 awaiting_input, review, merging, failed
 *   已完成   done, canceled/cancelled; deferred rows are filed here too
 *
 * This is a *derived, best-effort* display grouping — the server's raw
 * [WorkTaskInfo.status] stays on the row verbatim and is never rewritten.
 * Unknown statuses never land in 已完成 (PRD §8.2); they surface in their own
 * neutral section, and archived rows hide behind the archived filter.
 */
enum class TaskSegment(val labelRes: Int) {
    TODO(app.codeg.android.R.string.task_seg_todo),
    RUNNING(app.codeg.android.R.string.task_seg_running),
    ATTENTION(app.codeg.android.R.string.task_seg_attention),
    DONE(app.codeg.android.R.string.task_seg_done),
}

/** The board's display order — matches the four-way segmented control. */
val SEGMENT_ORDER = listOf(
    TaskSegment.TODO,
    TaskSegment.RUNNING,
    TaskSegment.ATTENTION,
    TaskSegment.DONE,
)

/**
 * Map one task to its display segment, or null when it belongs to the unknown
 * section (unrecognized status, or a stray status="archived" row being shown).
 */
fun taskSegmentOf(task: WorkTaskInfo): TaskSegment? = when {
    task.deferred -> TaskSegment.DONE // 上游规则：搁置的 review/failed 也归 Done
    else -> when (task.status) {
        "todo", "queued" -> TaskSegment.TODO
        "preparing", "running" -> TaskSegment.RUNNING
        "awaiting_input", "review", "merging", "failed" -> TaskSegment.ATTENTION
        "done", "canceled", "cancelled" -> TaskSegment.DONE
        else -> null
    }
}

/** One board entry: a task plus the server it came from (ids are per-server). */
data class TaskBoardEntry(
    val serverId: String,
    val serverName: String,
    val task: WorkTaskInfo,
)

/** Board projection result: per-segment lists plus the neutral unknown section. */
data class TaskBoardData(
    val segments: Map<TaskSegment, List<TaskBoardEntry>> = emptyMap(),
    val unknown: List<TaskBoardEntry> = emptyList(),
    /** Segment sizes BEFORE the search query is applied, for the segment bar. */
    val counts: Map<TaskSegment, Int> = emptyMap(),
)

/**
 * Group tasks into segments. Web-parity filtering: archived rows
 * ([WorkTaskInfo.isArchivedLike]) stay hidden unless [showArchived]; canceled
 * rows hide unless [showCanceled]. Unrecognized statuses go to [TaskBoardData.unknown].
 */
fun buildBoard(
    entries: List<TaskBoardEntry>,
    showCanceled: Boolean = true,
    showArchived: Boolean = false,
): TaskBoardData {
    val visible = entries.filter { entry ->
        val task = entry.task
        if (task.isArchivedLike && !showArchived) return@filter false
        if ((task.status == "canceled" || task.status == "cancelled") && !showCanceled) {
            return@filter false
        }
        true
    }
    val known = mutableListOf<TaskBoardEntry>()
    val unknown = mutableListOf<TaskBoardEntry>()
    visible.forEach { entry ->
        if (taskSegmentOf(entry.task) == null) unknown += entry else known += entry
    }
    val grouped = known.groupBy { taskSegmentOf(it.task)!! }
    val segments = SEGMENT_ORDER.associateWith { column ->
        grouped[column].orEmpty().sortedWith(compareByDescending { entry: TaskBoardEntry -> taskSortKey(entry) })
    }
    return TaskBoardData(
        segments = segments,
        unknown = unknown.sortedWith(compareByDescending { entry: TaskBoardEntry -> taskSortKey(entry) }),
        counts = SEGMENT_ORDER.associateWith { segments[it]?.size ?: 0 },
    )
}

/**
 * Sort key inside a segment: scheduled first (soonest wins), then most recently
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
private fun timestampKey(iso: String?): String? = iso?.let { raw ->
    runCatching { java.time.Instant.parse(raw).toEpochMilli() }
        // Offset form (…+08:00) throws in Instant.parse below API 34 — without
        // this fallback those rows sort as "no time" (bottom of every column).
        .recoverCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .getOrNull()?.toString()
}

// region Status → label/tone projection (single source of truth for tags).

enum class TaskStatusTone { NEUTRAL, RUN, WARN, INFO, DANGER, DONE }

data class TaskStatusUi(val labelRes: Int, val tone: TaskStatusTone, val raw: String? = null)

/**
 * Display label + tone for a raw status. `raw != null` means the label string
 * itself is the server's raw status (unknown state) — render it neutrally and
 * never offer status-dependent actions.
 */
fun taskStatusUi(task: WorkTaskInfo): TaskStatusUi {
    val (label, tone) = when (task.status) {
        "todo" -> R_task_todo to TaskStatusTone.NEUTRAL
        "queued" -> R_task_queued to TaskStatusTone.NEUTRAL
        "preparing" -> R_task_preparing to TaskStatusTone.RUN
        "running" -> R_task_running to TaskStatusTone.RUN
        "awaiting_input" -> R_task_awaiting to TaskStatusTone.WARN
        "review" -> R_task_review to TaskStatusTone.WARN
        "merging" -> R_task_merging to TaskStatusTone.INFO
        "done" -> R_task_done to TaskStatusTone.DONE
        "failed" -> R_task_failed to TaskStatusTone.DANGER
        "canceled", "cancelled" -> R_task_canceled to TaskStatusTone.NEUTRAL
        "archived" -> R_task_archived to TaskStatusTone.NEUTRAL
        else -> return TaskStatusUi(0, TaskStatusTone.NEUTRAL, raw = task.status)
    }
    return TaskStatusUi(label, tone)
}

// Int indirection keeps this file free of a UI import (R constants resolve fine).
private val R_task_todo = app.codeg.android.R.string.task_status_todo
private val R_task_queued = app.codeg.android.R.string.task_status_queued
private val R_task_preparing = app.codeg.android.R.string.task_status_preparing
private val R_task_running = app.codeg.android.R.string.task_status_running
private val R_task_awaiting = app.codeg.android.R.string.task_status_awaiting_input
private val R_task_review = app.codeg.android.R.string.task_status_review
private val R_task_merging = app.codeg.android.R.string.task_status_merging
private val R_task_done = app.codeg.android.R.string.task_status_done
private val R_task_failed = app.codeg.android.R.string.task_status_failed
private val R_task_canceled = app.codeg.android.R.string.task_status_canceled
private val R_task_archived = app.codeg.android.R.string.task_status_archived

// endregion
