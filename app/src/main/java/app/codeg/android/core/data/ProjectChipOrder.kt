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
        val ordered = savedOrder.mapNotNull { byId[it] }
        val seen = ordered.mapTo(mutableSetOf()) { it.id }
        ordered + projects.filter { it.id !in seen }.sortedBy { it.sortOrder }
    }
}

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
