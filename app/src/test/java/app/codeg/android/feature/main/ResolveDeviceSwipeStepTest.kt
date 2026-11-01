package app.codeg.android.feature.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure arithmetic tests for the device-swipe step resolver (batch v123): the
 * Activity sequence counts the aggregate "All" as index 0 (devices 1..n) and the
 * ends fall through to the tab switch. 批v150: the Folders DEVICES_ONLY scope
 * retired with the Folders tab — Activity's shape is the only sequence left.
 */
class ResolveDeviceSwipeStepTest {

    private val ALL = DeviceSwipeVerdict.All
    private fun device(i: Int, @Suppress("UNUSED_PARAMETER") total: Int) = DeviceSwipeVerdict.Device(i)

    // ---- Activity (WITH_ALL): the All → devices sequence ----

    @Test
    fun `activity next from All goes to first device`() {
        assertEquals(device(0, 3), resolveDeviceSwipeStep(0, 3, +1))
    }

    @Test
    fun `activity previous from first device returns to All`() {
        assertEquals(ALL, resolveDeviceSwipeStep(1, 3, -1))
    }

    @Test
    fun `activity previous from All falls to tab switch`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(0, 3, -1))
    }

    @Test
    fun `activity next from last device falls to tab switch`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(3, 3, +1))
    }

    @Test
    fun `activity mid-sequence only switches devices`() {
        // Sequence index 2 = device 1 (0 is All); previous lands on device 0.
        assertEquals(device(0, 3), resolveDeviceSwipeStep(2, 3, -1))
        // Sequence index 1 = device 0; next lands on device 1.
        assertEquals(device(1, 3), resolveDeviceSwipeStep(1, 3, +1))
    }

    // ---- Edge shapes ----

    @Test
    fun `single device activity is All bookended`() {
        assertEquals(device(0, 1), resolveDeviceSwipeStep(0, 1, +1))
        assertEquals(ALL, resolveDeviceSwipeStep(1, 1, -1))
    }
}
