package app.codeg.android.core.network

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.CursorAuthStatus
import app.codeg.android.core.model.CursorModelsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bare-value / null / URL helpers on [CodegClient]. */
class CodegClientStaticTest {

    @Test
    fun `decodeConnectionId reads a bare JSON string`() {
        assertEquals("conn-123", CodegClient.decodeConnectionId("\"conn-123\""))
    }

    @Test
    fun `decodeConnectionId rejects null and empty`() {
        assertThrows(ApiError.Decoding::class.java) { CodegClient.decodeConnectionId("null") }
        assertThrows(ApiError.Decoding::class.java) { CodegClient.decodeConnectionId("\"\"") }
    }

    @Test
    fun `isJsonNull recognises null and empty bodies only`() {
        assertTrue(CodegClient.isJsonNull("null"))
        assertTrue(CodegClient.isJsonNull("   "))
        assertTrue(CodegClient.isJsonNull(""))
        assertFalse(CodegClient.isJsonNull("{}"))
        assertFalse(CodegClient.isJsonNull("\"x\""))
    }

    @Test
    fun `normalizeBaseUrl trims trailing slash and whitespace`() {
        assertEquals("http://host:3080", CodegClient.normalizeBaseUrl("  http://host:3080/  "))
        assertEquals("https://example.com", CodegClient.normalizeBaseUrl("https://example.com"))
    }

    @Test
    fun `normalizeBaseUrl supplies http when the scheme is omitted`() {
        // Entering a LAN address as `host:port` is the common case; without a scheme
        // Ktor resolved it as a relative reference and the request failed opaquely.
        assertEquals("http://192.168.1.10:3080", CodegClient.normalizeBaseUrl("192.168.1.10:3080"))
        assertEquals("http://192.168.1.10:3080", CodegClient.normalizeBaseUrl("  192.168.1.10:3080/ "))
        // An explicit scheme is always preserved, including https.
        assertEquals("https://codeg.example.com", CodegClient.normalizeBaseUrl("https://codeg.example.com/"))
        // Nothing entered stays empty so callers can treat it as "not filled in yet".
        assertEquals("", CodegClient.normalizeBaseUrl("   "))
    }

    @Test
    fun `normalizeBaseUrl does not duplicate a scheme when the host is missing`() {
        // `trimEnd('/')` alone turned "http://" into "http:", which then looked
        // scheme-less and was prefixed again into "http://http:".
        assertEquals("http://", CodegClient.normalizeBaseUrl("http://"))
        assertEquals("http://", CodegClient.normalizeBaseUrl("http:///"))
        assertFalse(CodegClient.isUsableBaseUrl("http://"))
        assertFalse(CodegClient.isUsableBaseUrl("https:///"))
    }

    @Test
    fun `isUsableBaseUrl accepts bare hosts and http(s) but rejects other schemes`() {
        assertTrue(CodegClient.isUsableBaseUrl("192.168.1.10:3080"))
        assertTrue(CodegClient.isUsableBaseUrl("http://host:3080"))
        assertTrue(CodegClient.isUsableBaseUrl("https://host"))
        assertFalse(CodegClient.isUsableBaseUrl(""))
        assertFalse(CodegClient.isUsableBaseUrl("   "))
        assertFalse(CodegClient.isUsableBaseUrl("ftp://host"))
        assertFalse(CodegClient.isUsableBaseUrl("ws://host:3080"))
        assertFalse(CodegClient.isUsableBaseUrl("http://"))
    }

    @Test
    fun `decodeAgentList decodes the newest agent types and drops still-unknown ones`() {
        // code_buddy / kimi_code / pi / grok are now first-class AgentType values, so
        // they decode (grok also carries its own config payload). A genuinely-unknown
        // future wire must still be dropped, not collapsed onto claude_code.
        val json = """
            [
              {"agent_type":"claude_code","name":"Claude Code"},
              {"agent_type":"code_buddy","name":"CodeBuddy"},
              {"agent_type":"codex","name":"Codex"},
              {"agent_type":"kimi_code","name":"Kimi Code"},
              {"agent_type":"pi","name":"Pi"},
              {"agent_type":"grok","name":"Grok","grok_config_toml":"[ui]\n","grok_settings":{"permission_mode":"ask","default_reasoning_effort":"high"}},
              {"agent_type":"cursor","name":"Cursor","cursor_cli_config_json":"{}","cursor_settings":{"sandbox_mode":"enabled","permissions_allow":["Shell(ls)"],"permissions_deny":["Shell(rm)"]}},
              {"agent_type":"future_agent_9000","name":"Future"}
            ]
        """.trimIndent()
        val agents = CodegClient.decodeAgentList(json)
        assertEquals(
            listOf(
                AgentType.CLAUDE_CODE, AgentType.CODE_BUDDY, AgentType.CODEX,
                AgentType.KIMI_CODE, AgentType.PI, AgentType.GROK, AgentType.CURSOR,
            ),
            agents.map { it.agentType },
        )
        // The grok row's snake_case config payload maps onto the camelCase fields.
        val grok = agents.first { it.agentType == AgentType.GROK }
        assertEquals("ask", grok.grokSettings?.permissionMode)
        assertEquals("high", grok.grokSettings?.defaultReasoningEffort)
        // Same for the cursor row's projection (snake_case list keys included).
        val cursor = agents.first { it.agentType == AgentType.CURSOR }
        assertEquals("enabled", cursor.cursorSettings?.sandboxMode)
        assertEquals(listOf("Shell(ls)"), cursor.cursorSettings?.permissionsAllow)
        assertEquals(listOf("Shell(rm)"), cursor.cursorSettings?.permissionsDeny)
    }

    @Test
    fun `decodeAgentList never yields duplicate agent-type keys`() {
        // Several unknown future types alongside the real claude_code must NOT collapse
        // into duplicate claude_code rows — that duplicate `agentType` key crashes
        // LazyColumn.
        val json = """
            [
              {"agent_type":"future_a","name":"A"},
              {"agent_type":"claude_code","name":"Claude Code"},
              {"agent_type":"future_b","name":"B"}
            ]
        """.trimIndent()
        val agents = CodegClient.decodeAgentList(json)
        assertEquals(listOf(AgentType.CLAUDE_CODE), agents.map { it.agentType })
        val keys = agents.map { it.agentType.wire }
        assertEquals("keys must be unique", keys.distinct().size, keys.size)
    }

    @Test
    fun `cursor probe responses map their snake_case wire onto the models`() {
        // Verbatim shapes from a live `acp_cursor_auth_status` / `acp_cursor_list_models`.
        val status = CodegJson.response.decodeFromString(
            CursorAuthStatus.serializer(),
            """{"installed":true,"is_authenticated":true,"raw_status":"authenticated",
               "email":"dev@example.com","membership":null,"error":null,
               "binary_path":"/cache/cursor-agent"}""",
        )
        assertTrue(status.installed)
        assertTrue(status.isAuthenticated)
        assertEquals("authenticated", status.rawStatus)
        assertEquals("dev@example.com", status.email)
        assertEquals("/cache/cursor-agent", status.binaryPath)

        val models = CodegJson.response.decodeFromString(
            CursorModelsResult.serializer(),
            """{"models":[{"id":"auto","label":"Auto (current, default)","is_default":true},
                {"id":"gpt-5.3-codex","label":"","is_default":false}],
                "default_model":"auto","error":null}""",
        )
        assertEquals(2, models.models.size)
        assertEquals("auto", models.defaultModel)
        assertTrue(models.models[0].isDefault)
        assertEquals("Auto (current, default)", models.models[0].displayLabel)
        // A bare id with no label falls back to the id in the picker.
        assertEquals("gpt-5.3-codex", models.models[1].displayLabel)
    }
}
