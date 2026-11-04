package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionProject

/**
 * 批v160 W9: the project chips' left-to-right order on the 动态/会话 bar.
 *
 * Two modes, owner-specified:
 * - manual (default): the saved id order ([AppSettings.projectChipOrder]);
 *   projects never seen before append at the end in their creation order
 *   (sortOrder), so a fresh install or a new project degrades to creation
 *   order, never to HashMap noise;
 * - by count: sessions-per-project descending, ties by creation order.
 *
 * Deleted ids in the saved order are ignored; hidden projects are filtered by
 * the CALLER (the bar also needs the hidden set for the row, this helper stays
 * pure ordering).
 */
fun orderedProjectChips(
    projects: List<SessionProject>,
    savedOrder: List<String>,
    sortByCount: Boolean,
    counts: Map<String, Int>,
): List<SessionProject> {
    if (projects.size <= 1) return projects
    return if (sortByCount) {
        projects.sortedWith(
            compareByDescending<SessionProject> { counts[it.id] ?: 0 }.thenBy { it.sortOrder },
        )
    } else {
        val byId = projects.associateBy { it.id }
        // R3-B: distinct() first — a duplicate id in the saved list (corrupt
        // pref, parallel writers) would otherwise render duplicate chips.
        val ordered = savedOrder.distinct().mapNotNull { byId[it] }
        val seen = ordered.mapTo(mutableSetOf()) { it.id }
        ordered + projects.filter { it.id !in seen }.sortedBy { it.sortOrder }
    }
}

/**
 * R3-B: the settings page's ↑/↓ operates on the row's EFFECTIVE display order —
 * saved ids first, then the never-moved rest in creation order — because a move
 * on a partial saved list must still move the row the user actually sees.
 * Returns the FULL materialized order for persistence. Deliberately ignores the
 * count sort: the editor only renders when it is off (GeneralScreen), and the
 * manual order must keep accumulating underneath it.
 */
fun materializeChipMove(
    projects: List<SessionProject>,
    savedOrder: List<String>,
    id: String,
    step: Int,
): List<String> =
    moveProjectInOrder(
        orderedProjectChips(projects, savedOrder, sortByCount = false, counts = emptyMap())
            .map { it.id },
        id,
        step,
    )

/** 批v160 W9 settings-page helper: move one id one slot up (-1) / down (+1). */
fun moveProjectInOrder(order: List<String>, id: String, step: Int): List<String> {
    val from = order.indexOf(id)
    if (from < 0) return order
    val to = (from + step).coerceIn(0, order.lastIndex)
    if (to == from) return order
    return order.toMutableList().apply {
        removeAt(from)
        add(to, id)
    }
}
