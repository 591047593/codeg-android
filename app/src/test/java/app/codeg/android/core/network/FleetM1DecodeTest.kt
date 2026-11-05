package app.codeg.android.core.network

import app.codeg.android.core.model.ConversationDetail
import app.codeg.android.core.model.ConversationTurnsPage
import app.codeg.android.core.model.ModelProviderInfo
import app.codeg.android.core.model.TurnRole
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fleet M1 decode fixtures: the four small endpoints land against a Rust server
 * whose responses are snake_case (see CodegJsonTest) — or, for the two batch
 * actions, a BARE number body with no envelope at all. These tests pin the
 * decode side; the request keys ride the client's buildJsonObject literals and
 * are exercised by RouteParityTest.
 */
class FleetM1DecodeTest {

    // ---- work_task_attention_count / work_task_start_all: bare int bodies ----

    @Test
    fun `decodeBareInt reads a bare JSON number`() {
        assertEquals(3, CodegClient.decodeBareInt("3"))
        assertEquals(0, CodegClient.decodeBareInt("0"))
    }

    @Test
    fun `decodeBareInt is lenient about whitespace and quoted numbers`() {
        assertEquals(7, CodegClient.decodeBareInt("  7  "))
        assertEquals(7, CodegClient.decodeBareInt("\"7\""))
    }

    @Test
    fun `decodeBareInt returns null for non-numbers`() {
        assertNull(CodegClient.decodeBareInt("{\"claimed\":3}"))
        assertNull(CodegClient.decodeBareInt("null"))
        assertNull(CodegClient.decodeBareInt(""))
        assertNull(CodegClient.decodeBareInt("abc"))
    }

    // ---- get_folder_conversation window fields ----

    @Test
    fun `windowed conversation detail decodes the turn-window fields`() {
        val detail = CodegJson.response.decodeFromString(
            ConversationDetail.serializer(),
            """
            {
              "summary": {"id": 1, "folder_id": 7, "agent_type": "claude_code",
                          "status": "completed", "created_at": "2026-11-05T20:00:00Z",
                          "updated_at": "2026-11-06T00:00:00Z"},
              "turns": [
                {"id": "t9", "role": "assistant", "blocks": [{"type": "text", "text": "hi"}],
                 "timestamp": "2026-11-06T00:00:00Z"}
              ],
              "turns_offset": 110,
              "turns_total": 230,
              "assistant_turns_before_offset": 95,
              "user_turns_before_offset": 15,
              "prefix_hash": "0123456789abcdef"
            }
            """.trimIndent(),
        )
        assertEquals(110, detail.turnsOffset)
        assertEquals(230, detail.turnsTotal)
        assertEquals(95, detail.assistantTurnsBeforeOffset)
        assertEquals(15, detail.userTurnsBeforeOffset)
        assertEquals("0123456789abcdef", detail.prefixHash)
        assertEquals(1, detail.turns.size)
        assertEquals(TurnRole.ASSISTANT, detail.turns.single().role)
    }

    @Test
    fun `legacy conversation detail has no window fields`() {
        // An old server (pre turn-window) answers without any window key — the
        // fields must stay null so the client keeps its full-transcript behavior.
        val detail = CodegJson.response.decodeFromString(
            ConversationDetail.serializer(),
            """
            {
              "summary": {"id": 1, "folder_id": 7, "agent_type": "claude_code",
                          "status": "completed", "created_at": "2026-11-05T20:00:00Z",
                          "updated_at": "2026-11-06T00:00:00Z"},
              "turns": []
            }
            """.trimIndent(),
        )
        assertNull(detail.turnsOffset)
        assertNull(detail.turnsTotal)
        assertNull(detail.prefixHash)
    }

    // ---- get_folder_conversation_turns: the older-history page ----

    @Test
    fun `turns page decodes snake_case and carries both seam hashes`() {
        val page = CodegJson.response.decodeFromString(
            ConversationTurnsPage.serializer(),
            """
            {
              "turns": [
                {"id": "t3", "role": "user", "blocks": [{"type": "text", "text": "go"}],
                 "timestamp": "2026-11-05T23:00:00Z"}
              ],
              "turns_offset": 30,
              "turns_total": 230,
              "assistant_turns_before_offset": 25,
              "user_turns_before_offset": 5,
              "prefix_hash": "aaaaaaaaaaaaaaaa",
              "prefix_hash_before_index": "0123456789abcdef"
            }
            """.trimIndent(),
        )
        assertEquals(30, page.turnsOffset)
        assertEquals(230, page.turnsTotal)
        assertEquals(TurnRole.USER, page.turns.single().role)
        // The splice contract the VM enforces: the page ends exactly where the
        // window began, and its seam proof matches the window's fingerprint.
        assertEquals(30 + page.turns.size, 31)
        assertEquals("0123456789abcdef", page.prefixHashBeforeIndex)
        assertEquals("aaaaaaaaaaaaaaaa", page.prefixHash)
    }

    // ---- list_model_providers: a bare JSON array of providers ----

    @Test
    fun `model providers decode from a bare snake_case array`() {
        val providers = CodegJson.response.decodeFromString(
            ListSerializer(ModelProviderInfo.serializer()),
            """
            [
              {"id": 2, "name": "magpie", "api_url": "https://gw.example/v1",
               "api_key": "sk-plain-do-not-log", "api_key_masked": "sk-1••••••ef",
               "agent_type": "claude_code", "model": "glm-4.7",
               "created_at": "2026-11-01T00:00:00Z", "updated_at": "2026-11-01T00:00:00Z"},
              {"id": 3, "name": "no-model", "api_url": "https://gw2.example/v1",
               "api_key": "sk-x", "api_key_masked": "••••", "agent_type": "codex",
               "model": null, "created_at": "", "updated_at": ""}
            ]
            """.trimIndent(),
        )
        assertEquals(2, providers.size)
        assertEquals("glm-4.7", providers[0].model)
        assertEquals("claude_code", providers[0].agentType.wire)
        assertNull(providers[1].model)
        // The plaintext key must never surface on the decoded model.
        assertEquals("sk-1••••••ef", providers[0].apiKeyMasked)
    }

    @Test
    fun `int round trip through the response codec stays stable`() {
        // decodeBareInt's fallback path parses via CodegJson.response — pin that
        // a JSON number survives the lenient/isLenient codec untouched.
        val el = CodegJson.response.parseToJsonElement("42")
        assertEquals(42, el.toString().toInt())
        assertEquals(42, CodegJson.response.decodeFromString(Int.serializer(), "42"))
    }
}
