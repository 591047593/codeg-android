package app.codeg.android.feature.sessions

import app.codeg.android.core.data.isLiveFresh
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.datastore.isSessionUnread
import app.codeg.android.core.model.ConversationSummary

/**
 * Pure model + builders for the Chats tab's cross-device aggregate ("全部")
 * mode and the project filter, batch v132. No Android/UI types here so the
 * grouping math stays unit-testable like [SessionGrouping] / [buildActivitySections].
 */

/** One row of the aggregate Chats list: a conversation plus the device it came from. */
data class AggregateRow(
    val conversation: ConversationSummary,
    val serverId: String,
    val serverName: String,
    val unread: Boolean = false,
) {
    /** Cross-device identity — server-local conversation ids collide across devices. */
    val refKey: String get() = "$serverId:${conversation.id}"
}

/** What an [AggregateSection] represents — drives its header icon / label / dot. */
enum class AggregateKind { PINNED, DEVICE }

/** One rendered group of the aggregate list: the cross-device Pinned bucket or one device. */
data class AggregateSection(
    val id: String,
    val kind: AggregateKind,
    /** Owning device id (DEVICE sections only; null for the pinned bucket). */
    val serverId: String?,
    /** Device name (DEVICE sections only). */
    val title: String,
    val rows: List<AggregateRow>,
) {
    val count: Int get() = rows.size
}

/**
 * Build the aggregate Chats sections: a cross-device "Pinned" bucket
 * (most-recently-pinned first) followed by one section per device (its
 * non-pinned conversations, newest-first). Empty sections are dropped.
 *
 * [memberKeys] is the active project's membership set (`"serverId:conversationId"`
 * keys) or null for no filter; when present only member rows survive — this is
 * the project-filter application point, so switching projects never refetches.
 * [readMarks] drives the unread dots (same convention as the Activity feed).
 * [hiddenServerIds] is the device multi-select filter (批v154) — a pure VIEW filter,
 * so re-checking a device never refetches. [hiddenProjectIds] (批v160) is the
 * project column of that filter sheet: a row is hidden when its device is hidden
 * OR any project it belongs to is hidden — [memberProjects] maps refKey → the
 * project ids holding that session. Both filters run per-ROW (a device section
 * shrinks instead of vanishing when only some of its sessions are filtered),
 * and a section left with zero rows drops out below.
 */
fun buildAggregateSessionSections(
    perServer: List<Pair<ServerProfile, List<ConversationSummary>>>,
    memberKeys: Set<String>? = null,
    readMarks: Map<String, Long> = emptyMap(),
    // 批v160 W9: the 未添加 scope — keep rows with NO project membership (the
    // complement of [memberKeys]; the two never arrive together).
    excludeKeys: Set<String>? = null,
    hiddenServerIds: Set<String> = emptySet(),
    hiddenProjectIds: Set<String> = emptySet(),
    memberProjects: Map<String, Set<String>> = emptyMap(),
): List<AggregateSection> {
    fun rowsOf(profile: ServerProfile, convs: List<ConversationSummary>): List<AggregateRow> =
        convs.map { conv ->
            AggregateRow(
                conversation = conv,
                serverId = profile.id,
                serverName = profile.name,
                unread = isSessionUnread(
                    // R2D#2: the freshness gate, not the raw flag — an eternal
                    // "live" corpse suppresses the unread dot forever.
                    statusLive = conv.isLiveFresh(),
                    updatedAt = conv.updatedAt.toEpochMilli(),
                    readMark = readMarks["${profile.id}:${conv.id}"],
                ),
            )
        }

    val filtered = perServer
        .map { (profile, convs) ->
            val rows = rowsOf(profile, convs).filter { row ->
                row.serverId !in hiddenServerIds &&
                    hiddenProjectIds.intersect(memberProjects[row.refKey].orEmpty()).isEmpty()
            }
            if (memberKeys == null) {
                if (excludeKeys != null) rows.filter { it.refKey !in excludeKeys } else rows
            } else rows.filter { it.refKey in memberKeys }
        }

    val out = ArrayList<AggregateSection>(filtered.size + 1)

    val pinned = filtered.flatten()
        .filter { it.conversation.isPinned }
        .sortedByDescending { it.conversation.pinnedAt ?: java.time.Instant.MIN }
    if (pinned.isNotEmpty()) out += AggregateSection("agg-pinned", AggregateKind.PINNED, null, "", pinned)

    for (rows in filtered) {
        val rest = rows.filter { !it.conversation.isPinned }.sortedByDescending { it.conversation.updatedAt }
        if (rest.isNotEmpty()) {
            out += AggregateSection("agg-${rest.first().serverId}", AggregateKind.DEVICE, rest.first().serverId, rest.first().serverName, rest)
        }
    }
    return out
}

/**
 * The next project filter in the floating-ball cycle 【全部 → P1 → … → Pn → 全部】:
 * null (no filter) steps to the first project, the last project wraps back to
 * null, a stale active id restarts at the first project, no projects stays null.
 */
fun nextProjectFilter(activeId: String?, projects: List<SessionProject>): String? {
    if (projects.isEmpty()) return null
    val idx = projects.indexOfFirst { it.id == activeId }
    return when {
        idx < 0 -> projects.first().id
        idx == projects.lastIndex -> null
        else -> projects[idx + 1].id
    }
}
