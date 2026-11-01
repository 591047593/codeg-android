package app.codeg.android.core.model

import app.codeg.android.core.data.DispatchPipeline
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the work-task request wire keys against the Rust server (批v146).
 *
 * Ground truth (`src-tauri/src/web/handlers/work_task.rs`): EVERY top-level
 * params struct carries `#[serde(rename_all = "camelCase")]`, so the wire is
 * camelCase (`deleteWorktree`, `scheduledAt`). Merge/Complete `deleteWorktree`
 * have NO serde default — serde silently DROPS a snake_case key and then 422s
 * on the missing required field. The snake_case exceptions are the NESTED
 * payloads: WorkTaskDraft (`folder_id`) and WorkTaskConfig (`prompt_blocks`,
 * `agent_type`, `base_branch`) have no rename_all.
 *
 * This casing was gotten backwards once already (the first board commit of
 * 批v146 briefly switched merge/complete to snake_case); these assertions turn
 * a relapse into a red test instead of a 422 in production.
 */
class WorkTaskWireTest {

    private fun JsonObject.text(key: String): String? =
        (this[key])?.let { it.toString().trim('"') }

    private val actionBodies =
        listOf(
            WorkTaskWire.cancelBody(1),
            WorkTaskWire.mergeBody(1),
            WorkTaskWire.completeBody(1),
            WorkTaskWire.deleteBody(1),
        )

    @Test
    fun `action bodies send camelCase deleteWorktree`() {
        actionBodies.forEach { body ->
            assertTrue("camelCase deleteWorktree missing: $body", "deleteWorktree" in body)
            assertFalse(
                "snake_case delete_worktree must never be sent (serde drops it): $body",
                "delete_worktree" in body,
            )
        }
    }

    @Test
    fun `merge defaults to keeping the worktree deleted and complete to kept`() {
        // mergeBody: deleting the worktree after a merge is the intent;
        // cancel/complete/delete default to false (keep the worktree).
        assertEquals("true", WorkTaskWire.mergeBody(1).text("deleteWorktree"))
        assertEquals("false", WorkTaskWire.cancelBody(1).text("deleteWorktree"))
        assertEquals("false", WorkTaskWire.completeBody(1).text("deleteWorktree"))
        assertEquals("false", WorkTaskWire.deleteBody(1).text("deleteWorktree"))
    }

    @Test
    fun `every action body carries id`() {
        actionBodies.plus(
            listOf(
                WorkTaskWire.idBody(1),
                WorkTaskWire.deferBody(1, deferred = true),
                WorkTaskWire.scheduleBody(1, null),
                WorkTaskWire.requeueBody(1, null),
            ),
        ).forEach { body -> assertEquals("1", body.text("id")) }
    }

    @Test
    fun `deferBody sends the required deferred flag`() {
        // DeferParams.deferred has no serde default — a bare {"id"} would 422.
        assertEquals("true", WorkTaskWire.deferBody(1, deferred = true).text("deferred"))
        assertEquals("false", WorkTaskWire.deferBody(1, deferred = false).text("deferred"))
    }

    @Test
    fun `scheduleBody uses camelCase scheduledAt and omits it when clearing`() {
        assertEquals(
            "2030-01-02T03:04:05Z",
            WorkTaskWire.scheduleBody(1, "2030-01-02T03:04:05Z").text("scheduledAt"),
        )
        assertFalse("scheduled_at" in WorkTaskWire.scheduleBody(1, "2030-01-02T03:04:05Z"))
        // null clears the plan: only id on the wire.
        assertEquals(setOf("id"), WorkTaskWire.scheduleBody(1, null).keys)
    }

    @Test
    fun `createBody outer wrapper is camelCase-free but nested draft is snake_case`() {
        val body = WorkTaskWire.createBody(3, "t", DispatchPipeline.taskConfig("d"))
        assertEquals(setOf("draft"), body.keys)
        val draft = body["draft"] as JsonObject
        assertTrue("folder_id" in draft)
        assertFalse("folderId" in draft)
        assertEquals("3", draft.text("folder_id"))
        assertEquals("t", draft.text("title"))
        assertTrue("config" in draft)
    }

    @Test
    fun `taskConfig keeps snake_case nested keys`() {
        // WorkTaskConfig has no rename_all and serde defaults everywhere:
        // camelCase keys would be silently ignored and the task would launch
        // with an empty prompt.
        val config = DispatchPipeline.taskConfig(
            description = "do it",
            agentTypeWire = "codex",
            baseBranch = "main",
        )
        assertTrue("prompt_blocks" in config)
        assertTrue("display_text" in config)
        assertTrue("agent_type" in config)
        assertTrue("base_branch" in config)
        assertFalse("promptBlocks" in config)
        assertFalse("agentType" in config)
        assertFalse("baseBranch" in config)
    }
}
