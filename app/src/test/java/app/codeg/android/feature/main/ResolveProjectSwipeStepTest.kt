package app.codeg.android.feature.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure arithmetic tests for the project-swipe step resolver (批v160 W9): the row
 * is 全部项目 at 0, projects 1..n, 未添加 at n+1 (chipCount = n+2); a device-focused
 * entry reads -1 so NEXT lands on 全部项目 and PREV falls through to the tab layer,
 * and both ends fall through to the tab switch — the device sequence's
 * "inner first, outer at the edge" convention.
 */
class ResolveProjectSwipeStepTest {

    private fun chip(i: Int) = ProjectSwipeVerdict.Chip(i)
    private val TAB = ProjectSwipeVerdict.Tab

    @Test
    fun `next from 全部项目 goes to first project`() {
        assertEquals(chip(1), resolveProjectSwipeStep(0, 4, +1))
    }

    @Test
    fun `previous from first project returns to 全部项目`() {
        assertEquals(chip(0), resolveProjectSwipeStep(1, 4, -1))
    }

    @Test
    fun `previous from 全部项目 falls to tab switch`() {
        assertEquals(TAB, resolveProjectSwipeStep(0, 4, -1))
    }

    @Test
    fun `next from 未添加 falls to tab switch`() {
        // chipCount 4 = 全部项目 + 2 projects + 未添加; index 3 is the last.
        assertEquals(TAB, resolveProjectSwipeStep(3, 4, +1))
    }

    @Test
    fun `next from last project lands on 未添加`() {
        assertEquals(chip(3), resolveProjectSwipeStep(2, 4, +1))
    }

    @Test
    fun `previous from 未添加 returns to last project`() {
        assertEquals(chip(2), resolveProjectSwipeStep(3, 4, -1))
    }

    @Test
    fun `mid-row only steps between neighbors`() {
        assertEquals(chip(1), resolveProjectSwipeStep(2, 4, -1))
        assertEquals(chip(3), resolveProjectSwipeStep(2, 4, +1))
    }

    @Test
    fun `device-focused entry reads -1 — next enters 全部项目, prev leaves to tabs`() {
        assertEquals(chip(0), resolveProjectSwipeStep(-1, 4, +1))
        assertEquals(TAB, resolveProjectSwipeStep(-1, 4, -1))
    }

    @Test
    fun `row of just 全部项目 plus 未添加 still walks both`() {
        // No projects at all (chipCount 2): 全部项目 → 未添加 → Tab.
        assertEquals(chip(1), resolveProjectSwipeStep(0, 2, +1))
        assertEquals(TAB, resolveProjectSwipeStep(1, 2, +1))
        assertEquals(chip(0), resolveProjectSwipeStep(1, 2, -1))
        assertEquals(TAB, resolveProjectSwipeStep(0, 2, -1))
    }
}
