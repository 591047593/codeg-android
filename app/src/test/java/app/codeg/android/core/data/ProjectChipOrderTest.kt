package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionProject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 批v160 W9: chip row ordering — manual saved order, count sort, move helper. */
class ProjectChipOrderTest {

    private fun project(id: String, sortOrder: Int) =
        SessionProject(id = id, name = id, createdAt = 0L, sortOrder = sortOrder)

    private fun ids(projects: List<SessionProject>) = projects.map { it.id }

    // ---- manual order ----

    @Test
    fun `saved order wins, never-moved projects append by creation order`() {
        val projects = listOf(project("b", 1), project("a", 0), project("c", 2), project("d", 3))
        assertEquals(
            listOf("a", "b", "c", "d"),
            ids(orderedProjectChips(projects, emptyList(), sortByCount = false, counts = emptyMap())),
        )
        assertEquals(
            listOf("c", "a", "b", "d"),
            ids(orderedProjectChips(projects, listOf("c", "a"), sortByCount = false, counts = emptyMap())),
        )
    }

    @Test
    fun `deleted ids in the saved order are ignored`() {
        val projects = listOf(project("a", 0), project("b", 1))
        assertEquals(
            listOf("a", "b"),
            ids(orderedProjectChips(projects, listOf("ghost", "a"), sortByCount = false, counts = emptyMap())),
        )
    }

    @Test
    fun `single project short-circuits to itself`() {
        val projects = listOf(project("a", 0))
        assertEquals(
            listOf("a"),
            ids(orderedProjectChips(projects, listOf("a"), sortByCount = true, counts = mapOf("a" to 0))),
        )
    }

    // ---- count sort ----

    @Test
    fun `count sort is descending, ties by creation order`() {
        val projects = listOf(project("a", 0), project("b", 1), project("c", 2))
        val counts = mapOf("b" to 5, "a" to 3, "c" to 3)
        assertEquals(
            listOf("b", "a", "c"),
            ids(orderedProjectChips(projects, emptyList(), sortByCount = true, counts = counts)),
        )
    }

    @Test
    fun `count sort treats a missing count as zero`() {
        val projects = listOf(project("a", 0), project("b", 1))
        assertEquals(
            listOf("b", "a"),
            ids(orderedProjectChips(projects, emptyList(), sortByCount = true, counts = mapOf("b" to 1))),
        )
    }

    // ---- move helper ----

    @Test
    fun `move steps one slot and clamps at the ends`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("a", "c", "b"), moveProjectInOrder(order, "c", -1))
        assertEquals(listOf("b", "a", "c"), moveProjectInOrder(order, "a", 1))
        assertEquals(order, moveProjectInOrder(order, "a", -1))
        assertEquals(order, moveProjectInOrder(order, "c", 1))
    }

    @Test
    fun `move ignores an id that is not in the order`() {
        val order = listOf("a", "b")
        assertEquals(order, moveProjectInOrder(order, "ghost", -1))
    }

    // ---- materializeChipMove (R3-B) ----

    @Test
    fun `materialized move operates on the effective display order`() {
        // saved has only "c": the bar shows c (saved) then a, b (creation order).
        // Moving "a" up swaps with the chip the user actually sees above it.
        val projects = listOf(project("a", 0), project("b", 1), project("c", 2))
        assertEquals(
            listOf("a", "c", "b"),
            materializeChipMove(projects, listOf("c"), "a", -1),
        )
    }

    @Test
    fun `materialized move persists the FULL order, not just saved ids`() {
        // The point of materialization: one move on a partial saved list lands
        // every id in the pref, so the next move works off the real sequence.
        val projects = listOf(project("a", 0), project("b", 1), project("c", 2))
        assertEquals(
            listOf("c", "b", "a"),
            materializeChipMove(projects, listOf("c"), "b", -1),
        )
    }

    @Test
    fun `materialized move clamps at the ends and ignores ghost ids`() {
        val projects = listOf(project("a", 0), project("b", 1), project("c", 2))
        // "c" already sits first in the effective order — clamped, unchanged.
        assertEquals(listOf("c", "a", "b"), materializeChipMove(projects, listOf("c"), "c", -1))
        assertEquals(listOf("c", "a", "b"), materializeChipMove(projects, listOf("c"), "ghost", 1))
    }

    @Test
    fun `a duplicated id in the saved order renders one chip`() {
        // R3-B: distinct() first — a corrupt pref holding "a,a" must not
        // duplicate the chip row.
        val projects = listOf(project("a", 0), project("b", 1))
        assertEquals(
            listOf("a", "b"),
            ids(orderedProjectChips(projects, listOf("a", "a"), sortByCount = false, counts = emptyMap())),
        )
    }
}
