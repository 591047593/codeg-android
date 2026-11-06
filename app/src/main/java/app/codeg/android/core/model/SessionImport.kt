package app.codeg.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the import pipeline (fleet M4). Mirrors Rust
 * `models/conversation.rs`: `ScanResult` / `ScanFolder` / `ScanSession` /
 * `ScanSessionStatus` come back snake_case (auto-mapped by CodegJson.response
 * onto these camelCase properties), while the request envelope
 * `import_selected_sessions { selections: [{ agentType, externalId }] }` is
 * camelCase on both sides (Rust `SelectedSessionKey` is `rename_all =
 * "camelCase"`).
 */
@Serializable
enum class ScanSessionStatus {
    /** Not in the DB — importable. */
    @SerialName("new") NEW,

    /** At least one live row exists — shown for reference, never selectable. */
    @SerialName("imported") IMPORTED,

    /** Only soft-deleted rows exist — selecting it RESTORES the row in place. */
    @SerialName("deleted") DELETED,
}

@Serializable
data class ScanSession(
    val externalId: String,
    val agentType: AgentType,
    val title: String? = null,
    /** RFC3339 from the agent's own session file. */
    val startedAt: String = "",
    val endedAt: String? = null,
    val messageCount: Int = 0,
    val model: String? = null,
    val gitBranch: String? = null,
    val status: ScanSessionStatus = ScanSessionStatus.NEW,
)

@Serializable
data class ScanFolder(
    val path: String,
    val name: String,
    /** A live folder row exists for this path (import reuses it). */
    val existsInCodeg: Boolean = false,
    val folderId: Int? = null,
    val agentTypes: List<AgentType> = emptyList(),
    val sessions: List<ScanSession> = emptyList(),
) {
    val newCount: Int get() = sessions.count { it.status == ScanSessionStatus.NEW }
    val deletedCount: Int get() = sessions.count { it.status == ScanSessionStatus.DELETED }
    val importedCount: Int get() = sessions.count { it.status == ScanSessionStatus.IMPORTED }
}

@Serializable
data class ScanResult(
    val folders: List<ScanFolder> = emptyList(),
    /** Sessions whose transcript carries no cwd — not importable, count only. */
    val noFolderCount: Int = 0,
    val totalSessions: Int = 0,
    val importableCount: Int = 0,
)

/** Selection key: the same `(agent_type, external_id)` identity the DB dedups on. */
@Serializable
data class SelectedSessionKey(
    val agentType: AgentType,
    val externalId: String,
)

/** Response of the legacy whole-folder import (API parity; no UI calls it). */
@Serializable
data class ImportResult(
    val imported: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val restored: Int = 0,
)

@Serializable
data class ImportFolderOutcome(
    val path: String,
    val folderId: Int,
    /** The target folder row was freshly created (or reopened from soft-delete). */
    val created: Boolean = false,
    val imported: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val restored: Int = 0,
)

@Serializable
data class ImportSelectedResult(
    val imported: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val restored: Int = 0,
    /** Selection keys that no longer resolved (deleted on disk since the scan). */
    val notFound: Int = 0,
    /** Sessions that failed because their folder group errored. */
    val failed: Int = 0,
    val createdFolders: Int = 0,
    val folders: List<ImportFolderOutcome> = emptyList(),
    /** Human-readable per-folder failure messages, capped server-side. */
    val errors: List<String> = emptyList(),
)

/** Request envelope for `import_selected_sessions`. */
@Serializable
data class ImportSelectedBody(val selections: List<SelectedSessionKey>)

/** Request envelope for the legacy `import_local_conversations`. */
@Serializable
data class ImportLocalBody(val folderId: Int)
