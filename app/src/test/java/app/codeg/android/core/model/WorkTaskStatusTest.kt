package app.codeg.android.core.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批v147 P1 回归钉：dell 服务器把归档写成 `status="archived"` 后，旧客户端
 * 枚举反序列化直接炸掉整块看板（"unexpected value for WorkTaskStatus enum:
 * archived"）。客户端约定是——状态永远按原文字符串保留、手工解码逐字段
 * 容错、未知状态绝不映射成 done；这里把 archived/cancelled/陌生状态三路
 * 全部钉死，任何一次把解码改回枚举反序列化都会被测试拦下。
 */
class WorkTaskStatusTest {

    private fun wire(status: String, archivedAt: String? = null) = buildJsonObject {
        put("id", 11)
        put("title", "1212")
        put("status", status)
        if (archivedAt != null) put("archived_at", archivedAt)
    }

    @Test
    fun `archived status decodes without crashing and stays archived-like`() {
        val task = WorkTaskInfo.fromWire(wire("archived"))
        assertNotNull(task)
        assertEquals("archived", task!!.status)
        assertTrue(WorkTaskInfo.KNOWN_STATUSES.contains("archived"))
        assertTrue(task.isArchivedLike)
    }

    @Test
    fun `cancelled status decodes as terminal but not archived`() {
        val task = WorkTaskInfo.fromWire(wire("cancelled"))
        assertNotNull(task)
        assertEquals("cancelled", task!!.status)
        assertTrue(WorkTaskInfo.KNOWN_STATUSES.contains("cancelled"))
        assertTrue(task.isTerminal)
        assertFalse(task.isArchivedLike)
    }

    @Test
    fun `archived_at flag archives the row regardless of status`() {
        val task = WorkTaskInfo.fromWire(wire("done", archivedAt = "2026-11-01T10:00:00Z"))
        assertNotNull(task)
        assertTrue(task!!.isArchivedLike)
    }

    @Test
    fun `unknown future status never crashes and never maps to done`() {
        val task = WorkTaskInfo.fromWire(wire("a_status_from_a_newer_server"))
        assertNotNull(task)
        // Verbatim: the display layer decides; the model must not rewrite it.
        assertEquals("a_status_from_a_newer_server", task!!.status)
        assertFalse(WorkTaskInfo.KNOWN_STATUSES.contains(task.status))
        assertFalse(task.isTerminal)
    }

    @Test
    fun `row without id or title is dropped instead of crashing`() {
        assertNull(WorkTaskInfo.fromWire(buildJsonObject { put("status", "archived") }))
        assertNull(WorkTaskInfo.fromWire(buildJsonObject { put("id", 1) }))
    }
}
