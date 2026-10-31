package app.codeg.android.core.data

import app.codeg.android.core.model.WorkTaskInfo
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DispatchPipelineTest {

    @Test
    fun `task config wraps description as text block`() {
        val cfg = DispatchPipeline.taskConfig("do the thing", agentTypeWire = "codex", baseBranch = "main")
        val obj = cfg.jsonObject
        assertEquals(1, obj["promptBlocks"]!!.jsonArray.size)
        assertEquals("do the thing", obj["displayText"]!!.jsonPrimitive.content)
        assertEquals("codex", obj["agentType"]!!.jsonPrimitive.content)
        assertEquals("main", obj["baseBranch"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank description yields empty prompt blocks`() {
        val cfg = DispatchPipeline.taskConfig("   ")
        assertEquals(0, cfg.jsonObject["promptBlocks"]!!.jsonArray.size)
    }

    @Test
    fun `find created task matches title folder and window`() {
        val windowStart = Instant.parse("2026-11-01T00:00:00Z")
        val windowEnd = Instant.parse("2026-11-01T01:00:00Z")
        val t = WorkTaskInfo(
            id = 7, title = "My Task", status = "todo", folderId = 3,
            createdAt = "2026-11-01T00:10:00Z",
        )
        assertEquals(7, DispatchPipeline.findCreatedTask(listOf(t), "My Task", 3, windowStart, windowEnd)?.id)
        // wrong title
        assertNull(DispatchPipeline.findCreatedTask(listOf(t), "Other", 3, windowStart, windowEnd))
        // outside window
        assertNull(
            DispatchPipeline.findCreatedTask(
                listOf(t), "My Task", 3,
                Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-02T01:00:00Z"),
            ),
        )
        // unparsable createdAt can't be matched (fail-safe: no false confirm)
        assertNull(
            DispatchPipeline.findCreatedTask(
                listOf(t.copy(createdAt = "yesterday")), "My Task", 3, windowStart, windowEnd,
            ),
        )
        // a row with no folderId can't be attributed to our request — never confirm it
        assertNull(
            DispatchPipeline.findCreatedTask(
                listOf(t.copy(folderId = null)), "My Task", 3, windowStart, windowEnd,
            ),
        )
    }

    @Test
    fun `local time converts to UTC rfc3339`() {
        val millis = Instant.parse("2026-11-01T04:30:00Z").toEpochMilli()
        val utc = DispatchPipeline.toUtcRfc3339(millis, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-11-01T04:30:00Z", utc)
        // The same wall clock in Shanghai must produce the same absolute instant.
        assertEquals(millis, Instant.parse(utc).toEpochMilli())
    }
}
