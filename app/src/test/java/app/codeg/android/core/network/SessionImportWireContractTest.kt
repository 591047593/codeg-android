package app.codeg.android.core.network

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ImportLocalBody
import app.codeg.android.core.model.ImportSelectedBody
import app.codeg.android.core.model.ImportSelectedResult
import app.codeg.android.core.model.ScanFolder
import app.codeg.android.core.model.ScanResult
import app.codeg.android.core.model.ScanSession
import app.codeg.android.core.model.ScanSessionStatus
import app.codeg.android.core.model.SelectedSessionKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fleet M4 wire contract, pinned against the Rust server
 * (`web/handlers/conversations.rs` + `models/conversation.rs` @ 0.41.1002):
 *
 * - `import_selected_sessions` params: `ImportSelectedSessionsParams` is
 *   `rename_all = "camelCase"` and nests `SelectedSessionKey`, itself
 *   `rename_all = "camelCase"` — so BOTH levels go out camelCase (unlike the
 *   M2 automation draft's mixed rule).
 * - `ScanResult` / `ImportSelectedResult` come back plain snake_case Serialize
 *   structs, decoded here through the real [CodegJson.response] coder —
 *   including the serde(default) fields (`restored`, `not_found`,
 *   `created_folders`) that older responses may omit.
 */
class SessionImportWireContractTest {

    @Test
    fun `selections envelope and keys are camelCase at both levels`() {
        val body = ImportSelectedBody(
            selections = listOf(SelectedSessionKey(agentType = AgentType.CODEX, externalId = "abc-123")),
        )
        val root = CodegJson.request
            .parseToJsonElement(CodegJson.request.encodeToString(ImportSelectedBody.serializer(), body))
            .jsonObject
        assertEquals(setOf("selections"), root.keys)
        val key = root["selections"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("agentType", "externalId"),
            key.keys,
        )
        assertEquals("codex", key["agentType"]!!.jsonPrimitive.content)
        assertEquals("abc-123", key["externalId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy folder import envelope is camelCase`() {
        val root = CodegJson.request
            .parseToJsonElement(CodegJson.request.encodeToString(ImportLocalBody.serializer(), ImportLocalBody(folderId = 7)))
            .jsonObject
        assertEquals(setOf("folderId"), root.keys)
        assertEquals(7, root["folderId"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `scan result decodes snake_case wire with camelCase properties`() {
        val wire = """
            {
              "folders": [
                {
                  "path": "/mnt/Projects/929",
                  "name": "929",
                  "exists_in_codeg": true,
                  "folder_id": 42,
                  "agent_types": ["codex", "zcode"],
                  "sessions": [
                    {
                      "external_id": "s1",
                      "agent_type": "codex",
                      "title": "Weekly report",
                      "started_at": "2026-11-05T16:00:00Z",
                      "ended_at": "2026-11-05T17:30:00Z",
                      "message_count": 12,
                      "model": "gpt-5",
                      "git_branch": "main",
                      "status": "imported"
                    },
                    {
                      "external_id": "s2",
                      "agent_type": "zcode",
                      "started_at": "2026-11-05T18:00:00Z",
                      "message_count": 3,
                      "status": "new"
                    },
                    {
                      "external_id": "s3",
                      "agent_type": "zcode",
                      "started_at": "2026-11-05T19:00:00Z",
                      "message_count": 5,
                      "status": "deleted"
                    }
                  ]
                }
              ],
              "no_folder_count": 2,
              "total_sessions": 5,
              "importable_count": 1
            }
        """.trimIndent()
        val scan = CodegJson.response.decodeFromString(ScanResult.serializer(), wire)
        val folder = scan.folders.single()
        assertEquals("/mnt/Projects/929", folder.path)
        assertEquals(true, folder.existsInCodeg)
        assertEquals(42, folder.folderId)
        assertEquals(1, folder.newCount)
        assertEquals(1, folder.deletedCount)
        assertEquals(1, folder.importedCount)
        val imported = folder.sessions.single { it.externalId == "s1" }
        assertEquals(ScanSessionStatus.IMPORTED, imported.status)
        assertEquals(AgentType.CODEX, imported.agentType)
        assertEquals("2026-11-05T16:00:00Z", imported.startedAt)
        assertEquals("main", imported.gitBranch)
    }

    @Test
    fun `import result decodes snake_case tallies and tolerates omitted serde-default fields`() {
        val withAll = CodegJson.response.decodeFromString(
            ImportSelectedResult.serializer(),
            """
            {
              "imported": 3, "updated": 1, "skipped": 2, "restored": 1,
              "not_found": 1, "failed": 1, "created_folders": 2,
              "folders": [
                { "path": "/a", "folder_id": 9, "created": true, "imported": 3, "updated": 1, "skipped": 2, "restored": 1 }
              ],
              "errors": ["folder /a: parse boom"]
            }
            """.trimIndent(),
        )
        assertEquals(3, withAll.imported)
        assertEquals(1, withAll.notFound)
        assertEquals(2, withAll.createdFolders)
        assertEquals(9, withAll.folders.single().folderId)
        assertEquals(listOf("folder /a: parse boom"), withAll.errors)

        // Older/minimal response: only the always-present tallies.
        val minimal = CodegJson.response.decodeFromString(
            ImportSelectedResult.serializer(),
            """{ "imported": 1, "updated": 0, "skipped": 0, "failed": 0, "folders": [], "errors": [] }""",
        )
        assertEquals(0, minimal.restored)
        assertEquals(0, minimal.notFound)
        assertEquals(0, minimal.createdFolders)
    }

    @Test
    fun `unknown agent type in a scan degrades to UNKNOWN instead of failing the whole list`() {
        val wire = """
            {
              "external_id": "sx",
              "agent_type": "future_cli",
              "started_at": "2026-11-05T16:00:00Z",
              "message_count": 1,
              "status": "new"
            }
        """.trimIndent()
        val session = CodegJson.response.decodeFromString(ScanSession.serializer(), wire)
        assertEquals(AgentType.UNKNOWN, session.agentType)
        assertEquals(ScanSessionStatus.NEW, session.status)
        assertEquals(
            listOf(AgentType.CODEX, AgentType.UNKNOWN),
            CodegJson.response.decodeFromString(
                ListSerializer(ScanFolder.serializer()),
                """[ { "path": "/p", "name": "p", "agent_types": ["codex", "future_cli"], "sessions": [] } ]""",
            ).single().agentTypes,
        )
    }

    @Test
    fun `multi-word agent type rides the request with its exact snake_case wire name`() {
        // "codex" is case-insensitive — pin a multi-word wire name so a
        // serializer regression to enum name/displayName cannot slip through
        // green (claude_code must not go out as claudeCode/CLAUDE_CODE/Claude Code).
        val body = ImportSelectedBody(
            selections = listOf(SelectedSessionKey(agentType = AgentType.CLAUDE_CODE, externalId = "c1")),
        )
        val key = CodegJson.request
            .parseToJsonElement(CodegJson.request.encodeToString(ImportSelectedBody.serializer(), body))
            .jsonObject["selections"]!!.jsonArray.single().jsonObject
        assertEquals("claude_code", key["agentType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `scan folder without folder_id decodes to null (folder not yet in codeg)`() {
        val folder = CodegJson.response.decodeFromString(
            ListSerializer(ScanFolder.serializer()),
            """[ { "path": "/p", "name": "p", "exists_in_codeg": false, "agent_types": [], "sessions": [] } ]""",
        ).single()
        assertNull(folder.folderId)
        assertEquals(false, folder.existsInCodeg)
    }
}
