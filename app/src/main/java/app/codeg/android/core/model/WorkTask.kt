package app.codeg.android.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Work-task (Codeg 原生看板) wire models — batch v139.
 *
 * Server DTO (`models/work_task.rs`) carries many fields whose shape varies
 * across server versions, so the list is decoded by hand with null-tolerant
 * accessors: one malformed field must degrade to `null`, never break the whole
 * board. Wire keys are snake_case (no naming strategy on hand decoders).
 *
 * Status comes through verbatim as [WorkTaskInfo.status] — the display column
 * mapping lives in `core/data/TaskBoard.kt` and must never rewrite it.
 */
data class WorkTaskInfo(
    val id: Int,
    val title: String,
    val status: String,
    val folderId: Int? = null,
    val agentType: String? = null,
    val connectionId: String? = null,
    val conversationId: Int? = null,
    val resultSummary: String? = null,
    val verdict: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFileCount: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val scheduledAt: String? = null,
    val archivedAt: String? = null,
    val deferred: Boolean = false,
    val failureReason: String? = null,
) {
    val isTerminal: Boolean get() = status in TERMINAL_STATUSES

    companion object {
        /** States seen in server source (`db/entities/work_task.rs`); display only. */
        val KNOWN_STATUSES = setOf(
            "todo", "queued", "preparing", "running", "awaiting_input",
            "review", "merging", "done", "failed", "canceled",
        )

        /** failed/canceled — ended without acceptance; shown in their own group. */
        val TERMINAL_STATUSES = setOf("failed", "canceled")

        /**
         * Null-tolerant decode of one `work_task_list` row. Returns null only when
         * the row lacks an id or title — i.e. is not plausibly a task at all.
         */
        fun fromWire(obj: JsonObject): WorkTaskInfo? {
            val id = obj.intOrNull("id") ?: return null
            val title = obj.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return null
            return WorkTaskInfo(
                id = id,
                title = title,
                status = obj.stringOrNull("status") ?: "unknown",
                folderId = obj.intOrNull("folder_id"),
                agentType = obj.stringOrNull("agent_type"),
                connectionId = obj.stringOrNull("connection_id"),
                conversationId = obj.intOrNull("conversation_id"),
                resultSummary = obj.stringOrNull("result_summary"),
                verdict = obj.stringOrNull("verdict"),
                additions = obj.intOrNull("additions"),
                deletions = obj.intOrNull("deletions"),
                // Server's `files_changed` is an Option<i32> COUNT, not an array.
                changedFileCount = obj.intOrNull("files_changed"),
                createdAt = obj.stringOrNull("created_at"),
                updatedAt = obj.stringOrNull("updated_at"),
                scheduledAt = obj.stringOrNull("scheduled_at"),
                archivedAt = obj.stringOrNull("archived_at"),
                // Server's `deferred_at` is an ISO timestamp STRING, not an object.
                deferred = obj.stringOrNull("deferred_at") != null || obj.boolOrNull("deferred") == true,
                failureReason = obj.stringOrNull("failure_reason"),
            )
        }
    }
}

/** One entry of `work_task_events` (per-task history, limit-capped by the server). */
data class WorkTaskEvent(
    val kind: String,
    val actor: String?,
    val summary: String?,
    val createdAt: String?,
) {
    companion object {
        fun fromWire(obj: JsonObject): WorkTaskEvent? {
            val kind = obj.stringOrNull("kind") ?: return null
            val payload = obj.objectOrNull("payload")
            return WorkTaskEvent(
                kind = kind,
                actor = obj.stringOrNull("actor"),
                summary = payload?.let { p ->
                    p.stringOrNull("title")
                        ?: p.stringOrNull("message")
                        ?: p.stringOrNull("note")
                        ?: p.stringOrNull("reason")
                },
                createdAt = obj.stringOrNull("created_at"),
            )
        }
    }
}

/** One entry of `work_task_changed_files`. */
data class WorkTaskChangedFile(
    val path: String,
    val additions: Int? = null,
    val deletions: Int? = null,
) {
    companion object {
        fun fromWire(obj: JsonObject): WorkTaskChangedFile? {
            // Server's WorkTaskChangedFile serializes the field as `file`.
            val path = obj.stringOrNull("file") ?: return null
            return WorkTaskChangedFile(
                path = path,
                additions = obj.intOrNull("additions"),
                deletions = obj.intOrNull("deletions"),
            )
        }
    }
}

/** Requests (camelCase, [CodegJson.request] conventions). */
object WorkTaskWire {
    fun idBody(id: Int): JsonObject = buildJsonObject { put("id", id) }

    fun cancelBody(id: Int, deleteWorktree: Boolean = false): JsonObject =
        buildJsonObject {
            put("id", id)
            put("deleteWorktree", deleteWorktree)
        }

    fun mergeBody(id: Int, deleteWorktree: Boolean = true, message: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            put("deleteWorktree", deleteWorktree)
            message?.let { put("message", it) }
        }

    fun requeueBody(id: Int, note: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        }

    fun scheduleBody(id: Int, scheduledAtIsoUtc: String?): JsonObject =
        buildJsonObject {
            put("id", id)
            scheduledAtIsoUtc?.let { put("scheduledAt", it) }
        }
}

// Slim re-declarations of the null-tolerant accessors used above. The shared ones
// in `core.model.wire` are `internal` to that package's users — duplicating four
// tiny helpers keeps this model file dependency-free.
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { p ->
        if (p.isString) p.content.toIntOrNull() else runCatching { p.content.toInt() }.getOrNull()
    }

private fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { p ->
        when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray
