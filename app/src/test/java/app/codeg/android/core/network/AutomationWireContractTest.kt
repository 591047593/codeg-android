package app.codeg.android.core.network

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.AutomationDraftBody
import app.codeg.android.core.model.AutomationDraftDto
import app.codeg.android.core.model.AutomationInfo
import app.codeg.android.core.model.AutomationRunInfo
import app.codeg.android.core.model.AutomationUpdateBody
import app.codeg.android.core.model.buildAutomationConfig
import app.codeg.android.core.model.buildAutomationDraft
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fleet M2 wire contract, pinned against the Rust server
 * (`web/handlers/automation.rs` + `models/automation.rs` @ 0.41.1002):
 *
 * - request params are `rename_all = "camelCase"` structs, BUT the nested
 *   `AutomationDraft` has NO rename — its wire keys are snake_case, and a
 *   camelCase key would be silently dropped (`#[serde(default)]`-less fields
 *   would 4xx; the config blob's defaults would blank the prompt).
 * - responses are plain snake_case Serialize structs, decoded here through the
 *   real [CodegJson.response] coder.
 * 批v143 R10: "顶层 camelCase、draft 内 snake_case" — this suite is that rule.
 */
class AutomationWireContractTest {

    private fun draft(): AutomationDraftDto = buildAutomationDraft(
        name = "  站会日报  ",
        enabled = true,
        triggerKind = "schedule",
        cron = "0 9 * * 1-5",
        timezone = "Asia/Shanghai",
        agentType = AgentType.ZCODE,
        rootFolderId = 42,
        isolation = "worktree_per_run",
        promptText = "拉取昨天进度，生成立会日报",
    )

    @Test
    fun `draft wire keys are snake_case and camelCase keys are absent`() {
        val root = CodegJson.request
            .parseToJsonElement(CodegJson.request.encodeToString(AutomationDraftDto.serializer(), draft()))
            .jsonObject
        assertEquals(
            setOf("name", "enabled", "trigger_kind", "cron", "timezone", "agent_type", "root_folder_id", "isolation", "is_remote_branch", "config"),
            root.keys,
        )
        assertEquals("schedule", root["trigger_kind"]!!.jsonPrimitive.content)
        assertEquals("zcode", root["agent_type"]!!.jsonPrimitive.content)
        assertEquals(42, root["root_folder_id"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, root["is_remote_branch"]!!.jsonPrimitive.content.toBoolean())
        // name is trimmed by the builder
        assertEquals("站会日报", root["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `draft null optionals are omitted and server serde defaults apply`() {
        val json = CodegJson.request.encodeToString(
            AutomationDraftDto.serializer(),
            buildAutomationDraft(
                name = "manual", enabled = false, triggerKind = "manual", cron = null,
                timezone = "UTC", agentType = AgentType.CODEX, rootFolderId = null,
                isolation = "shared_in_root", promptText = "hi",
            ),
        )
        val root = CodegJson.request.parseToJsonElement(json).jsonObject
        assertFalse("null cron must be omitted (serde Option defaults)", root.containsKey("cron"))
        assertFalse(root.containsKey("branch"))
        assertFalse(root.containsKey("root_folder_id"))
        assertTrue("is_remote_branch=false must still serialize (server has no default)", root.containsKey("is_remote_branch"))
    }

    @Test
    fun `create and update envelopes wrap the draft as expected`() {
        val create = CodegJson.request
            .parseToJsonElement(CodegJson.request.encodeToString(AutomationDraftBody.serializer(), AutomationDraftBody(draft())))
            .jsonObject
        assertEquals(setOf("draft"), create.keys)
        val update = CodegJson.request
            .parseToJsonElement(CodegJson.request.encodeToString(AutomationUpdateBody.serializer(), AutomationUpdateBody(7, draft())))
            .jsonObject
        assertEquals(setOf("id", "draft"), update.keys)
        assertEquals(7, update["id"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `config blob carries action prompt_blocks display_text with snake_case keys`() {
        val config = buildAutomationConfig("hello 世界")
        assertEquals(
            setOf("action", "prompt_blocks", "display_text", "config_values"),
            config.keys,
        )
        assertEquals("launch_session", config["action"]!!.jsonPrimitive.content)
        val block = config["prompt_blocks"]!!.jsonArray.single().jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("hello 世界", block["text"]!!.jsonPrimitive.content)
        assertEquals("hello 世界", config["display_text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `automation_info decodes the full snake_case fixture`() {
        val fixture = """
            {"id":3,"name":"站会日报","enabled":true,"trigger_kind":"schedule",
             "cron":"0 0 9 * * 1-5","timezone":"Asia/Shanghai",
             "next_run_at":"2026-11-07T01:00:00Z","agent_type":"zcode",
             "root_folder_id":42,"isolation":"worktree_per_run","branch":null,
             "is_remote_branch":false,"config":{"action":"launch_session"},
             "last_run_at":"2026-11-06T01:00:00Z","last_run_status":"failed",
             "last_run_conversation_id":91,"unseen_failures":2,
             "created_at":"2026-11-01T00:00:00Z","updated_at":"2026-11-05T00:00:00Z"}
        """.trimIndent()
        val info = CodegJson.response.decodeFromString(AutomationInfo.serializer(), fixture)
        assertEquals(3, info.id)
        assertEquals("站会日报", info.name)
        assertTrue(info.enabled)
        assertTrue(info.isScheduled)
        assertEquals("Asia/Shanghai", info.timezone)
        assertEquals("2026-11-07T01:00:00Z", info.nextRunAt)
        assertEquals(AgentType.ZCODE, info.agentType)
        assertEquals(42, info.rootFolderId)
        assertEquals("worktree_per_run", info.isolation)
        assertFalse(info.isRemoteBranch)
        assertEquals("failed", info.lastRunStatus)
        assertEquals(91, info.lastRunConversationId)
        assertEquals(2, info.unseenFailures)
        assertTrue(info.hasUnseenFailures)
    }

    @Test
    fun `automation_info tolerates a minimal fixture and unknown agent`() {
        val info = CodegJson.response.decodeFromString(
            AutomationInfo.serializer(),
            """{"id":9,"name":"x","agent_type":"brand-new-agent","trigger_kind":"manual"}""",
        )
        assertEquals(9, info.id)
        assertFalse(info.enabled)
        assertEquals(AgentType.UNKNOWN, info.agentType)
        assertEquals("worktree_per_run", info.isolation)
        assertEquals(0, info.unseenFailures)
        assertFalse(info.hasUnseenFailures)
        assertFalse(info.isScheduled)
    }

    @Test
    fun `automation_list is a bare array of rows`() {
        val fixture = """[{"id":1,"name":"a","agent_type":"codex"},
                          {"id":2,"name":"b","agent_type":"claude_code"}]"""
        val rows = CodegJson.response.decodeFromString(
            ListSerializer(AutomationInfo.serializer()),
            fixture,
        )
        assertEquals(listOf(1, 2), rows.map { it.id })
        assertEquals(AgentType.CODEX, rows[0].agentType)
    }

    @Test
    fun `automation_run_info decodes the snake_case fixture`() {
        val fixture = """
            {"id":55,"automation_id":3,"status":"succeeded","trigger":"schedule",
             "scheduled_for":"2026-11-06T01:00:00Z","started_at":"2026-11-06T01:00:01Z",
             "ended_at":"2026-11-06T01:03:44Z","conversation_id":91,
             "worktree_folder_id":77,"stop_reason":null,"error":null,
             "summary":"日报已生成","created_at":"2026-11-06T01:00:00Z"}
        """.trimIndent()
        val run = CodegJson.response.decodeFromString(AutomationRunInfo.serializer(), fixture)
        assertEquals(55, run.id)
        assertEquals(3, run.automationId)
        assertEquals("succeeded", run.status)
        assertTrue(run.isSettled)
        assertFalse(run.isFailed)
        assertEquals(91, run.conversationId)
        assertEquals(77, run.worktreeFolderId)
        assertEquals("日报已生成", run.summary)
    }

    @Test
    fun `failed run status marks the row`() {
        val run = CodegJson.response.decodeFromString(
            AutomationRunInfo.serializer(),
            """{"id":1,"automation_id":1,"status":"failed","error":"boom"}""",
        )
        assertTrue(run.isFailed)
        assertTrue(run.isSettled)
    }

    @Test
    fun `compute_next_run response is a quoted rfc3339 or null`() {
        assertEquals(
            "2026-11-07T01:00:00Z",
            CodegClient.decodeBareStringOrNull("\"2026-11-07T01:00:00Z\""),
        )
        assertNull(CodegClient.decodeBareStringOrNull("null"))
        assertNull(CodegClient.decodeBareStringOrNull("{}"))
        assertNull(CodegClient.decodeBareStringOrNull(""))
        // defensive: an unquoted token still surfaces as text
        assertEquals("2026-11-07T01:00:00Z", CodegClient.decodeBareStringOrNull("2026-11-07T01:00:00Z"))
    }

    @Test
    fun `run_now response is a bare run id number`() {
        assertEquals(123, CodegClient.decodeBareInt("123"))
        assertEquals(123, CodegClient.decodeBareInt("\"123\""))
        assertNull(CodegClient.decodeBareInt("null"))
    }
}
