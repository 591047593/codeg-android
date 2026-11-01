package app.codeg.android.feature.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks WheelPicker's pad geometry: the two half-viewport pads shift real rows
 * one LazyColumn index down, so a settle must be reported (and validated) as
 * the DISPLAY row — lazyIndex − 1. v149.1 regression: the raw LazyColumn index
 * was validated against display bounds, which silently dropped the LAST row's
 * settle (the dispatch agent wheel's final entry never committed) and
 * committed one row below every other scroll selection.
 */
class WheelPickerIndexTest {

    @Test
    fun `first row maps from lazy index one`() {
        assertEquals(0, wheelDisplayIndex(1, itemCount = 5))
    }

    @Test
    fun `last row maps from lazy index itemCount`() {
        // The regression case: lazy index == itemCount was rejected by the old
        // display-bounds check, so settling on the last row committed nothing.
        assertEquals(4, wheelDisplayIndex(5, itemCount = 5))
    }

    @Test
    fun `middle rows map one below their lazy index`() {
        assertEquals(1, wheelDisplayIndex(2, itemCount = 5))
        assertEquals(2, wheelDisplayIndex(3, itemCount = 5))
    }

    @Test
    fun `pad indices report null`() {
        assertNull(wheelDisplayIndex(0, itemCount = 5))
        assertNull(wheelDisplayIndex(6, itemCount = 5))
    }

    @Test
    fun `out of range reports null`() {
        assertNull(wheelDisplayIndex(9, itemCount = 5))
        assertNull(wheelDisplayIndex(-1, itemCount = 5))
    }
}
