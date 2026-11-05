package app.codeg.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A saved, schedulable, replayable composer launch (Rust `AutomationInfo`).
 * Decode-only — the response coder's [app.codeg.network][SnakeCase strategy]
 * maps `trigger_kind` → [triggerKind] etc. Enum-ish fields stay raw strings
 * (tolerant decode): the wire vocabulary is
 * `triggerKind` = `schedule` | `manual`, `isolation` =
 * `worktree_per_run` | `shared_in_root`, `lastRunStatus` = a run status.
 * Timestamps ride as RFC3339 strings; display conversion happens in the UI
 * layer (never a wire assumption).
 */
@Serializable
data class AutomationInfo(
    val id: Int,
    val name: String,
    val enabled: Boolean = false,
    val triggerKind: String = "manual",
    val cron: String? = null,
    val timezone: String = "UTC",
    val nextRunAt: String? = null,
    val agentType: AgentType = AgentType.UNKNOWN,
    @SerialName("root_folder_id") val rootFolderId: Int? = null,
    val isolation: String = "worktree_per_run",
    val branch: String? = null,
    @SerialName("is_remote_branch") val isRemoteBranch: Boolean = false,
    /** Opaque captured composer snapshot (Rust `AutomationConfig` shape). */
    val config: JsonObject = JsonObject(emptyMap()),
    val lastRunAt: String? = null,
    val lastRunStatus: String? = null,
    @SerialName("last_run_conversation_id") val lastRunConversationId: Int? = null,
    @SerialName("unseen_failures") val unseenFailures: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val isScheduled: Boolean get() = triggerKind == "schedule"
    /** `unseen_failures` > 0 — amber row badge until [MARK_SEEN] clears it. */
    val hasUnseenFailures: Boolean get() = unseenFailures > 0
}

/** One launch+settle of an automation (Rust `AutomationRunInfo`). */
@Serializable
data class AutomationRunInfo(
    val id: Int,
    @SerialName("automation_id") val automationId: Int = 0,
    /** `running` | `succeeded` | `failed` | `cancelled` | `skipped`. */
    val status: String = "running",
    val trigger: String = "",
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("conversation_id") val conversationId: Int? = null,
    @SerialName("worktree_folder_id") val worktreeFolderId: Int? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    val error: String? = null,
    val summary: String? = null,
    @SerialName("created_at") val createdAt: String = "",
) {
    val isFailed: Boolean get() = status == "failed"
    val isSettled: Boolean get() = status != "running"
}

/**
 * The create/update payload (Rust `AutomationDraft`). REQUEST-side: the request
 * `Json` has NO naming strategy, and the Rust struct has no `rename_all` — the
 * wire is snake_case, spelled explicitly. All non-Option fields serialize
 * unconditionally (`encodeDefaults = true` covers [isRemoteBranch] = false;
 * the server rejects a missing key). Null [cron]/[branch]/[rootFolderId] are
 * OMITTED (`explicitNulls = false`) — serde defaults missing `Option`s to
 * `None`. V1 keeps [branch] = null and [isRemoteBranch] = false (PRD 红线:
 * 分支高级项不做).
 */
@Serializable
data class AutomationDraftDto(
    val name: String,
    val enabled: Boolean,
    @SerialName("trigger_kind") val triggerKind: String,
    val cron: String? = null,
    val timezone: String,
    @SerialName("agent_type") val agentType: AgentType,
    @SerialName("root_folder_id") val rootFolderId: Int? = null,
    val isolation: String,
    val branch: String? = null,
    @SerialName("is_remote_branch") val isRemoteBranch: Boolean = false,
    /** Rust `AutomationConfig` blob — snake_case keys, built by hand. */
    val config: JsonObject = JsonObject(emptyMap()),
)

/** Request envelopes — the server's params structs are `rename_all = "camelCase"`. */
@Serializable
data class AutomationIdBody(val id: Int)

@Serializable
data class AutomationRunsBody(val automationId: Int, val limit: Int = 100)

@Serializable
data class AutomationDraftBody(val draft: AutomationDraftDto)

@Serializable
data class AutomationUpdateBody(val id: Int, val draft: AutomationDraftDto)

@Serializable
data class AutomationSetEnabledBody(val id: Int, val enabled: Boolean)

@Serializable
data class AutomationRunNowBody(val automationId: Int)

@Serializable
data class AutomationCancelRunBody(val runId: Int)

@Serializable
data class AutomationComputeNextRunBody(val cron: String, val timezone: String)

/**
 * Build the Rust `AutomationConfig` blob for a text-prompt automation
 * (`launch_session` action). Snake_case keys are spelled by hand — the Rust
 * struct has no `rename_all`, so camelCase keys would be silently IGNORED
 * (`#[serde(default)]` everywhere) and the fire would launch an empty prompt.
 * The one block the fire path requires is a non-empty `prompt_blocks` (a
 * `display_text` alone fails at fire with "prompt is empty").
 */
fun buildAutomationConfig(promptText: String): JsonObject = buildJsonObject {
    put("action", JsonPrimitive("launch_session"))
    put("prompt_blocks", buildJsonArray {
        val text = truncateCodePointsSafe(promptText, 4_000)
        if (text.isNotEmpty()) {
            add(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            })
        }
    })
    put("display_text", JsonPrimitive(truncateCodePointsSafe(promptText, 2_000)))
    put("config_values", buildJsonObject { })
}

/** Assemble a full draft from the editor's fields. V1 keeps branch defaults. */
fun buildAutomationDraft(
    name: String,
    enabled: Boolean,
    triggerKind: String,
    cron: String?,
    timezone: String,
    agentType: AgentType,
    rootFolderId: Int?,
    isolation: String,
    promptText: String,
): AutomationDraftDto = AutomationDraftDto(
    name = name.trim(),
    enabled = enabled,
    triggerKind = triggerKind,
    cron = cron?.trim()?.takeIf { it.isNotEmpty() },
    timezone = timezone,
    agentType = agentType,
    rootFolderId = rootFolderId,
    isolation = isolation,
    config = buildAutomationConfig(promptText),
)

/** UTF-16-safe truncation — a raw take(n) could split an emoji surrogate pair. */
private fun truncateCodePointsSafe(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max
    while (end > 0 && Character.isHighSurrogate(text[end - 1])) end -= 1
    return text.substring(0, end)
}
