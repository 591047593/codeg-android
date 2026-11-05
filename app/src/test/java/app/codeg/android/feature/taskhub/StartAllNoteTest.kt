package app.codeg.android.feature.taskhub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fleet M1 全部启动: the outcome line the board shows after the serial
 * work_task_start_all sweep. The sweep itself needs servers; the message
 * builder is the part with branchy wording (zero claimed, single vs plural
 * devices, partial failures), so it lives file-level and gets pinned here.
 */
class StartAllNoteTest {

    @Test
    fun `nothing claimable says so plainly`() {
        assertEquals("没有可启动的任务（已认领 0 个）", startAllResultNote(0, 1, 1, emptyList()))
        assertEquals("没有可启动的任务（已认领 0 个）", startAllResultNote(0, 0, 1, emptyList()))
    }

    @Test
    fun `single device reads singular`() {
        assertEquals("已启动 3 个任务（1 台设备）", startAllResultNote(3, 1, 1, emptyList()))
    }

    @Test
    fun `multiple devices read plural`() {
        assertEquals("已启动 5 个任务（2 台设备）", startAllResultNote(5, 2, 2, emptyList()))
    }

    @Test
    fun `partial failure lists the failed targets`() {
        assertEquals(
            "已启动 2 个任务（1 台设备）；失败：dell · 929",
            startAllResultNote(2, 1, 2, listOf("dell · 929")),
        )
    }

    @Test
    fun `total failure keeps the claimed count and the failure list`() {
        // okDevices == 0 means nothing started anywhere; the base line still
        // reports the (zero) claim so the user sees the sweep ran.
        assertEquals("已启动 0 个任务（0 台设备）；失败：dell", startAllResultNote(0, 0, 2, listOf("dell")))
    }
}
