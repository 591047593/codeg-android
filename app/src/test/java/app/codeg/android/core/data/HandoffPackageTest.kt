package app.codeg.android.core.data

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn
import app.codeg.android.core.model.TurnRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HandoffPackageTest {

    private fun turn(role: TurnRole, text: String, id: String = role.wire) = MessageTurn(
        id = id,
        role = role,
        blocks = listOf(ContentBlock.Text(text)),
        timestamp = Instant.parse("2026-11-01T00:00:00Z"),
    )

    private fun source(vararg turns: MessageTurn) = HandoffSource(
        serverId = "srv1", serverName = "VPS-A", conversationId = 42,
        sessionTitle = "Fix login", folderId = 3, folderName = "api", folderPath = "/srv/api",
        agent = AgentType.CLAUDE_CODE, gitBranch = "feat/login", turns = turns.toList(),
    )

    @Test
    fun `template fills goal from user turns and progress from assistant`() {
        val pkg = HandoffPackageFactory.fromSource(
            source(
                turn(TurnRole.USER, "first ask"),
                turn(TurnRole.ASSISTANT, "did things"),
                turn(TurnRole.USER, "latest ask"),
                turn(TurnRole.ASSISTANT, "recent status"),
            ),
        )
        assertTrue(pkg.goal.contains("first ask") && pkg.goal.contains("latest ask"))
        assertTrue(pkg.progress.contains("recent status"))
        assertEquals("latest ask", pkg.nextSteps)
        assertEquals("feat/login", pkg.gitBranch)
        assertEquals("/srv/api", pkg.sourceFolderPath)
    }

    @Test
    fun `empty transcript yields empty editable fields not garbage`() {
        val pkg = HandoffPackageFactory.fromSource(source())
        assertTrue(pkg.goal.isEmpty() && pkg.progress.isEmpty() && pkg.nextSteps.isEmpty())
    }

    @Test
    fun `task description carries source line sections and git note`() {
        val pkg = HandoffPackageFactory.fromSource(source(turn(TurnRole.USER, "do x")))
        val md = pkg.toTaskDescription(gitNote = "分支 main，2 个未提交文件")
        assertTrue(md.contains("# 跨设备会话交接"))
        assertTrue(md.contains("VPS-A · api ·"))
        assertTrue(md.contains("## 工作目标"))
        assertTrue(md.contains("## Git 接力提示"))
        assertFalse(md.contains("## 已完成\n")) // empty sections dropped
        // Sections the user left blank must not render as empty headers.
        assertFalse(md.contains("## 已知问题"))
    }

    @Test
    fun `mode A omits git note section`() {
        val md = HandoffPackageFactory.fromSource(source(turn(TurnRole.USER, "do x"))).toTaskDescription(null)
        assertFalse(md.contains("Git 接力提示"))
    }
}
