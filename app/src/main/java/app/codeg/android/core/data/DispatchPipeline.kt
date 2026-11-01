package app.codeg.android.core.data

import app.codeg.android.core.model.WorkTaskInfo
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId



/**
 * Pure dispatch-pipeline helpers (batch v140). The work_task server API has NO
 * idempotency key, so dedupe responsibility is entirely client-side: a request
 * id ledger plus a "did it actually land?" probe over work_task_list.
 */
object DispatchPipeline {

    /**
     * The opaque `WorkTaskConfig` the server stores with the task (server models/
     * work_task.rs: promptBlocks + displayText + optional agentType/modeId/
     * configValues/baseBranch). Built by hand so the wire stays snake_case
     * regardless of request naming conventions, and so unknown future options can
     * be added without touching @Serializable models.
     */
    fun taskConfig(
        description: String,
        agentTypeWire: String? = null,
        modeId: String? = null,
        baseBranch: String? = null,
        configValues: Map<String, String> = emptyMap(),
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        // WorkTaskConfig on the Rust side has NO rename_all and is serde(default)
        // everywhere: camelCase keys would be SILENTLY IGNORED and the task
        // would launch with an empty prompt. The wire is snake_case here.
        put("prompt_blocks", buildJsonArray {
            if (description.isNotBlank()) add(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(description))
                },
            )
        })
        put("display_text", JsonPrimitive(truncateCodePoints(description, 2_000)))
        agentTypeWire?.let { put("agent_type", JsonPrimitive(it)) }
        modeId?.let { put("mode_id", JsonPrimitive(it)) }
        baseBranch?.let { put("base_branch", JsonPrimitive(it)) }
        if (configValues.isNotEmpty()) {
            put("config_values", buildJsonObject { configValues.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
        }
    }

    /** UTF-16 safe truncation for the display field (a raw take(2000) would
     *  split an emoji surrogate pair in half). */
    private fun truncateCodePoints(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        while (end > 0 && Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end)
    }

    /**
     * Resolve an UNKNOWN dispatch: find the task the server actually created.
     * Matches exact title + folderId + created within [windowStart, windowEnd]
     * (creation time arrives as RFC3339; unparsable rows can't be matched).
     */
    fun findCreatedTask(
        tasks: List<WorkTaskInfo>,
        title: String,
        folderId: Int,
        windowStart: Instant,
        windowEnd: Instant,
    ): WorkTaskInfo? = findCreatedTasks(tasks, title, folderId, windowStart, windowEnd).singleOrNull()

    /** All same-titled candidates in the window — the caller distinguishes
     * 0 (maybe not created) from >1 (created, but attribution ambiguous). */
    fun findCreatedTasks(
        tasks: List<WorkTaskInfo>,
        title: String,
        folderId: Int,
        windowStart: Instant,
        windowEnd: Instant,
    ): List<WorkTaskInfo> {
        if (title.isBlank()) return emptyList()
        return tasks.filter { task ->
            // A row with no folderId can't be attributed to our request — leaving
            // it unmatched keeps the ref UNKNOWN (fail-safe) instead of riskily
            // confirming someone else's same-titled task.
            task.title == title &&
                task.folderId == folderId &&
                createdAtInRange(task, windowStart, windowEnd)
        }
    }

    private fun createdAtInRange(task: WorkTaskInfo, start: Instant, end: Instant): Boolean {
        val created = task.createdAt?.let(::parseTimestamp) ?: return false
        return !created.isBefore(start) && !created.isAfter(end)
    }

    /** RFC3339 comes in two shapes: UTC `…Z` (Instant.parse) and offset
     * `…+08:00` (only OffsetDateTime.parse handles it below API 34, where
     * Instant.parse throws on offsets — the same hole TaskBoard's key had). */
    private fun parseTimestamp(raw: String): Instant? =
        runCatching { Instant.parse(raw) }.recoverCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()

    /**
     * The server stores `scheduledAt` as an absolute UTC moment (RFC3339) and has
     * no timezone query API — the phone converts the user's local wall-clock
     * choice into that instant and the UI labels the conversion (PRD §9.3 降级).
     * `Instant.toString()` is RFC3339 UTC (`…Z`); the zone param only documents intent.
     */
    @Suppress("UNUSED_PARAMETER")
    fun toUtcRfc3339(localMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(localMillis).toString()
}
