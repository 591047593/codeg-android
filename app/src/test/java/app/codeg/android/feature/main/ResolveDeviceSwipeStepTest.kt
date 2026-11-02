package app.codeg.android.feature.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure arithmetic tests for the device-swipe step resolver (batch v123): the
 * WITH_ALL sequence counts the aggregate "All" as index 0 (devices 1..n) and the
 * ends fall through to the tab switch. 批v150 retired the Folders scope; 批v151
 * revives it as DEVICE_ONLY for the Folders push (no All row, devices 0..n-1,
 * both ends are Tab — a no-op on pushed screens).
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

    // ---- DEVICE_ONLY (批v151: the Folders push) ----

    @Test
    fun `device_only mid-sequence steps between devices`() {
        assertEquals(device(1, 3), resolveDeviceSwipeStep(0, 3, +1, DeviceSwipeScope.DEVICE_ONLY))
        assertEquals(device(0, 3), resolveDeviceSwipeStep(1, 3, -1, DeviceSwipeScope.DEVICE_ONLY))
    }

    @Test
    fun `device_only ends fall to Tab — no aggregate, no tab switch from a push`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(0, 3, -1, DeviceSwipeScope.DEVICE_ONLY))
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(2, 3, +1, DeviceSwipeScope.DEVICE_ONLY))
    }

    @Test
    fun `device_only single device never moves`() {
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(0, 1, +1, DeviceSwipeScope.DEVICE_ONLY))
        assertEquals(DeviceSwipeVerdict.Tab, resolveDeviceSwipeStep(0, 1, -1, DeviceSwipeScope.DEVICE_ONLY))
    }
}
