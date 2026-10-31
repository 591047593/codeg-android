package app.codeg.android.feature.sessions

import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail

/**
 * One rendered group in the Chats list, fully sorted and filtered.
 *
 * Built off the main thread by [buildSessionSections] (exposed as
 * [SessionListViewModel.sections]) so the O(n·log n) grouping/sorting never runs
 * during composition — the `LazyColumn` just iterates these pre-sorted rows, which
 * is what keeps a very large session list scrolling smoothly. Mirrors the iOS
 * `SessionListView` sectioning (Pinned / per-folder / Other), realized on Android
 * as native collapsible sections rather than zoom-drill cards.
 */
data class SessionSection(
    /** Stable id used for the collapse set and the header's LazyColumn key. */
    val id: String,
    val kind: SectionKind,
    val rows: List<SessionRowItem>,
) {
    val count: Int get() = rows.size
}

/** What a [SessionSection] represents — drives its header icon, label, and tint. */
sealed interface SectionKind {
    /**
     * Conversations with a live agent attached, across all folders.
     *
     * This is the Activity tab folded into the list. Activity rendered the same
     * `SessionRow` over the same conversations, differing only in grouping (live first,
     * then the last 24 hours) — two tabs, one dataset. Showing them here keeps the
     * signal ("what is running right now") and removes a destination that existed only
     * to re-sort the list the neighbouring tab already had.
     */
    data object Running : SectionKind

    /** Touched in the last 24 hours and not running — Activity's second group. */
    data object Recent : SectionKind

    /** Pinned conversations across all folders. */
    data object Pinned : SectionKind

    /** One folder's non-pinned conversations. [colorHex] is the folder's server color. */
    data class Folder(val name: String, val colorHex: String) : SectionKind

    /** Non-pinned conversations whose folder isn't in the folder list (orphans). */
    data object Other : SectionKind
}

/** One conversation row, with its cross-folder tag pre-resolved. */
data class SessionRowItem(
    val conversation: ConversationSummary,
    /** Dim folder tag shown on cross-folder rows (Pinned/Other); `null` inside a
     * folder group, where the folder is already the header. */
    val folderName: String?,
    /**
     * How deep this row sits in a delegation tree. `0` is a root conversation; a child
     * of an expanded parent is `1`.
     *
     * The desktop sidebar nests children under their parent; this list is flat, so the
     * depth is expressed as an indent instead.
     */
    val depth: Int = 0,
)

/**
 * Compose [SessionGrouping]'s pure accessors into the ordered section list shown by
 * the Chats screen: Pinned (if any) → one section per folder in
 * [SessionGrouping.sortedFolders] order (empty folders kept, matching iOS so their
 * header still shows) → Other (if any).
 *
 * `folderName` is resolved here, once, for the cross-folder sections only, so each
 * row needs no map lookup at compose time. Pure and allocation-light; the view model
 * runs it on `Dispatchers.Default`.
 */
fun buildSessionSections(
    folders: List<FolderDetail>,
    allConversations: List<ConversationSummary>,
    /**
     * Parents whose delegation children should be listed inline, and the children
     * themselves once they have been fetched. A parent with no fetched entry renders as
     * a plain row; the expand affordance is driven by `childCount` on the row itself.
     */
    expandedParents: Set<Int> = emptySet(),
    childrenByParent: Map<Int, List<ConversationSummary>> = emptyMap(),
): List<SessionSection> {
    // Two kinds of row the desktop sidebar never shows at the top level:
    //
    //  - `chat` rows are the hidden per-folder scratch conversations the desktop client
    //    keeps. They were surfacing here as folder groups of their own — several groups
    //    literally named "Chat", each holding one conversation — which is a large part
    //    of why this list did not look like the desktop.
    //  - delegation children belong under their parent, not beside it; the parent's
    //    `childCount` drives the expand affordance instead.
    val conversations = allConversations.filter {
        it.kind.isListed && it.parentId == null
    }
    val folderNames = SessionGrouping.folderNames(folders)
    val out = ArrayList<SessionSection>()

    /**
     * A parent row followed by its children when expanded.
     *
     * Children are indented rather than nested in a subtree: this list is a flat
     * LazyColumn, so depth is a visual indent plus a stable id per row.
     */
    fun rowsFor(summary: ConversationSummary, folderName: String?): List<SessionRowItem> {
        val row = SessionRowItem(summary, folderName)
        if (summary.id !in expandedParents) return listOf(row)
        val kids = childrenByParent[summary.id].orEmpty()
        if (kids.isEmpty()) return listOf(row)
        return listOf(row) + kids.map { SessionRowItem(it, folderName, depth = 1) }
    }

    // Activity's two groups, folded in above everything else. "Running" is the only
    // thing that list had which a folder-grouped Chats list could not show at a glance,
    // and it belongs first: a live agent is the one thing the user may need to look at
    // right now.
    val running = conversations.filter { it.status.isLive }.sortedByDescending { it.updatedAt }
    if (running.isNotEmpty()) {
        out += SessionSection(
            id = "running",
            kind = SectionKind.Running,
            rows = running.flatMap { rowsFor(it, folderNames[it.folderId]) },
        )
    }
    val cutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(24))
    val recent = conversations
        .filter { !it.status.isLive && it.updatedAt.isAfter(cutoff) }
        .sortedByDescending { it.updatedAt }
    if (recent.isNotEmpty()) {
        out += SessionSection(
            id = "recent",
            kind = SectionKind.Recent,
            rows = recent.flatMap { rowsFor(it, folderNames[it.folderId]) },
        )
    }

    val pinned = SessionGrouping.pinned(conversations)

    // Rows already listed in the cross-folder views above are removed from the folder
    // groups, exactly as pinned rows are: Pinned, Running and Recent are *views* of the
    // same conversations, and repeating a row in two places made a folder look like it
    // held more work than it did. `conversations` stays the source of truth for the
    // views; only the folder loop below reads `remaining`.
    val shownAbove = (running + recent + pinned).mapTo(HashSet()) { it.id }
    val remaining = conversations.filterNot { it.id in shownAbove }

    if (pinned.isNotEmpty()) {
        out += SessionSection(
            id = "pinned",
            kind = SectionKind.Pinned,
            rows = pinned.flatMap { rowsFor(it, folderNames[it.folderId]) },
        )
    }

    for (group in SessionGrouping.folderGroups(folders, remaining)) {
        out += SessionSection(
            id = "folder-${group.folder.id}",
            kind = SectionKind.Folder(group.folder.name, group.folder.color),
            rows = group.conversations.flatMap { rowsFor(it, folderName = null) },
        )
    }

    val other = SessionGrouping.ungrouped(folders, remaining)
    if (other.isNotEmpty()) {
        out += SessionSection(
            id = "other",
            kind = SectionKind.Other,
            rows = other.flatMap { rowsFor(it, folderNames[it.folderId]) },
        )
    }

    // Groups that actually hold something come first; empty folders sink to the bottom.
    //
    // The folder order itself is the server's `sortOrder` then name — a stable, user-
    // chosen ordering that says nothing about activity. Rendering it verbatim meant a
    // device with a dozen idle folders scattered them *between* the ones in use, so the
    // screen opened on a wall of empty headings with the one active session buried
    // somewhere in the middle. Sorting by emptiness keeps the user's ordering within
    // each band while making the first screen show live content.
    //
    // Pinned stays at the very top (it is a cross-folder view, not a folder) and `other`
    // is a catch-all that is only present when non-empty, so neither is reordered.
    return out.sortedBy { it.count == 0 }
}
