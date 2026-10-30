package app.codeg.android.core.model

import app.codeg.android.core.model.wire.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A conversation/session persisted on a server (Rust `DbConversationSummary`).
 * Mutable-feeling optimistic fields on iOS (`title`, `status`, `pinnedAt`) are
 * `val`s here; the UI layer holds optimistic copies in its state.
 */
@Serializable
data class ConversationSummary(
    val id: Int,
    val folderId: Int,
    val title: String? = null,
    /**
     * True when a user action named this row (a rename, or a fork's `[Fork]` prefix),
     * so the server's auto-title backfill leaves it alone. Display-only here.
     */
    val titleLocked: Boolean = false,
    val agentType: AgentType,
    val status: ConversationStatus,
    /**
     * What kind of row this is: `regular`, `chat`, `loop` or `delegate`.
     *
     * Drives grouping. Without it the client could not tell a per-conversation "Chat"
     * scratch row from a real project conversation, so every hidden scratch row appeared
     * as a folder group of its own.
     */
    val kind: ConversationKind = ConversationKind.REGULAR,
    val model: String? = null,
    val gitBranch: String? = null,
    val externalId: String? = null,
    val messageCount: Int = 0,
    /**
     * Direct, non-deleted delegation children.
     *
     * `> 0` means this row is expandable into its sub-session subtree — the sidebar
     * chevron the desktop client draws.
     */
    val childCount: Int = 0,
    /** The parent conversation when this row is a delegation child. */
    val parentId: Int? = null,
    val parentToolUseId: String? = null,
    val delegationCallId: String? = null,
    /** The working directory this ran in when it differs from its folder's path. */
    val originCwd: String? = null,
    /** The assistant a delegation child ran as. */
    val assistantId: Int? = null,
    val assistantName: String? = null,
    /** `"cli"` for a conversation a `codeg-cli` run started; absent otherwise. */
    val source: String? = null,
    /** Tags attached to this conversation, ascending. Empty means none. */
    val tagIds: List<Int> = emptyList(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
    /** Server `pinned_at`, or null when not pinned. Pinning does NOT bump
     * `updated_at` — the "Pinned" group sorts by this instead. */
    @Serializable(with = InstantSerializer::class)
    val pinnedAt: Instant? = null,
) {
    /** Non-empty, trimmed user title, or null for an unnamed session. Render
     * verbatim (user data); fall back to a localized "Untitled session" only
     * when this is null. */
    val trimmedTitle: String?
        get() = title?.takeIf { it.isNotBlank() }

    val isPinned: Boolean get() = pinnedAt != null
}

/** Aggregate token/timing stats for a session (Rust `SessionStats`). */
@Serializable
data class SessionStats(
    val totalUsage: TurnUsage? = null,
    val totalTokens: Int? = null,
    val totalDurationMs: Int = 0,
    val contextWindowUsedTokens: Int? = null,
    val contextWindowMaxTokens: Int? = null,
    val contextWindowUsagePercent: Double? = null,
)

/**
 * Full session detail incl. message history (Rust `DbConversationDetail`).
 * Decode-only. [turns] uses the hand-written `ContentBlock` serializer for its
 * polymorphic blocks.
 */
@Serializable
data class ConversationDetail(
    val summary: ConversationSummary,
    val turns: List<MessageTurn> = emptyList(),
    val sessionStats: SessionStats? = null,
    val inFlightUserTurnId: String? = null,
    /**
     * Where [turns] starts within the full transcript.
     *
     * Present only when the request asked for a turn window (`tailTurns` / `fromIndex`).
     * Null means the server returned the legacy full transcript, in which case [turns]
     * is the whole history and windowed merging is disabled.
     *
     * The three window fields are always set together. Every *other* field on this
     * response — summary counts, stats — describes the full transcript regardless.
     */
    val turnsOffset: Int? = null,
    /** Total number of turns the transcript has, windowed or not. */
    val turnsTotal: Int? = null,
    /**
     * Assistant turns in `full[0, turnsOffset)`.
     *
     * Needed to translate a history baseline recorded before the request into window
     * coordinates; without it the send path cannot tell how many replies predate the
     * loaded window.
     */
    val assistantTurnsBeforeOffset: Int? = null,
) {
    /** True when the server returned only part of the transcript. */
    val isWindowed: Boolean get() = turnsOffset != null
}

/**
 * One page of turns from above the loaded window (`get_folder_conversation_turns`).
 *
 * The page ends exactly at the requested [turnsOffset], so it can be prepended to the
 * existing window without a gap or an overlap. [turnsOffset] is where *this page*
 * starts, which becomes the merged window's new offset.
 */
@Serializable
data class ConversationTurnsPage(
    val turns: List<MessageTurn> = emptyList(),
    val turnsOffset: Int = 0,
    val turnsTotal: Int = 0,
    val assistantTurnsBeforeOffset: Int = 0,
    /**
     * Fingerprint of `full[0, turnsOffset)`.
     *
     * The server compares it with the window the client claims to hold. A mismatch
     * means the transcript above the window was rewritten between requests — a
     * compaction, an edit, a delete — so the page cannot legally join the loaded
     * window and prepending it would duplicate or drop turns.
     */
    val prefixHash: String = "",
    /** Fingerprint the client's current window must carry for the join to be legal. */
    val prefixHashBeforeIndex: String = "",
)

/**
 * One entry in a conversation's message index (`get_folder_conversation_outline`).
 *
 * The server returns the history above a loaded window reduced to what a navigator
 * needs, so building the index costs one small response rather than the transcript.
 */
@Serializable
data class ConversationOutline(
    val turns: List<MessageTurn> = emptyList(),
    val turnsTotal: Int = 0,
    val prefixHash: String = "",
)

/** Returned by `acp_find_connection_for_conversation` when a live ACP
 * connection already owns a conversation. */@Serializable
data class ConversationConnectionInfo(
    val connectionId: String,
    val eventSeq: Long = 0,
)

/** `health` endpoint response — used to validate a server profile. */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)
