package app.codeg.android.core.data

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ContentBlock
import app.codeg.android.core.model.MessageTurn

/**
 * A cross-device session handoff package (batch v141, PRD §6.2): an agent-agnostic,
 * user-editable summary of where the work stands on the source session. It is NOT
 * a transcript restore — agent histories are not portable — and it carries no code:
 * moving code is the user's job via git (mode B precheck).
 */
data class HandoffPackage(
    val sourceServerId: String,
    val sourceServerName: String,
    val sourceProjectId: Int,
    val sourceFolderName: String,
    val sourceAgent: AgentType,
    val sourceSessionId: Int,
    val sourceSessionTitle: String,
    val gitBranch: String?,
    /** Server-side filesystem path of the source project, for mode B git precheck. */
    val sourceFolderPath: String?,
    // Editable payload fields.
    val goal: String,
    val completed: String,
    val progress: String,
    val decisions: String,
    val knownIssues: String,
    val nextSteps: String,
) {
    /** Rendered task description sent to the target device (markdown, plain text safe). */
    fun toTaskDescription(gitNote: String? = null): String = buildString {
        appendLine("# 跨设备会话交接")
        appendLine()
        appendLine(
            "来源：${sourceServerName.ifBlank { sourceServerId }} · $sourceFolderName · " +
                "${sourceAgent.displayName} · 会话#$sourceSessionId" +
                (gitBranch?.takeIf { it.isNotBlank() }?.let { " · 分支 `$it`" } ?: ""),
        )
        appendLine()
        fun section(title: String, body: String) {
            if (body.isBlank()) return
            appendLine("## $title")
            appendLine(body.trim())
            appendLine()
        }
        section("工作目标", goal)
        section("已完成", completed)
        section("当前进度", progress)
        section("技术决策", decisions)
        section("已知问题", knownIssues)
        section("下一步", nextSteps)
        if (!gitNote.isNullOrBlank()) {
            section("Git 接力提示", gitNote)
        }
    }.trimEnd() + "\n"
}

/** Where a handoff is generated from (the source session's metadata + transcript). */
data class HandoffSource(
    val serverId: String,
    val serverName: String,
    val conversationId: Int,
    val sessionTitle: String,
    val folderId: Int,
    val folderName: String,
    val folderPath: String?,
    val agent: AgentType,
    val gitBranch: String?,
    val turns: List<MessageTurn>,
)

object HandoffPackageFactory {

    /** Code-point-safe truncation (never splits an emoji surrogate pair). */
    fun cap(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, text.offsetByCodePoints(0, max))


    /**
     * Light template fill (DECISION D1): the last user turns seed the goal and
     * next steps, the last assistant texts seed the progress — everything stays
     * user-editable before dispatch. No AI summarisation, no full-history scan.
     */
    fun fromSource(source: HandoffSource): HandoffPackage {
        // Tail-based extraction: only the last N turns of each role are read, so
        // a multi-thousand-turn transcript costs a reverse scan, not a full copy.
        fun tailTexts(role: String, count: Int): List<String> {
            val out = ArrayList<String>(count)
            for (turn in source.turns.asReversed()) {
                if (turn.role.wire != role) continue
                val text = turn.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
                if (text.isBlank()) continue
                out.add(0, text)
                if (out.size == count) break
            }
            return out
        }
        val userTexts = tailTexts("user", 3)
        val assistantTexts = tailTexts("assistant", 2)
        return HandoffPackage(
            sourceServerId = source.serverId,
            sourceServerName = source.serverName,
            sourceProjectId = source.folderId,
            sourceFolderName = source.folderName,
            sourceAgent = source.agent,
            sourceSessionId = source.conversationId,
            sourceSessionTitle = source.sessionTitle,
            gitBranch = source.gitBranch,
            sourceFolderPath = source.folderPath,
            goal = userTexts.joinToString("\n\n---\n\n").let { cap(it, 1_500) },
            completed = "",
            progress = assistantTexts.joinToString("\n\n---\n\n").let { cap(it, 1_200) },
            decisions = "",
            knownIssues = "",
            nextSteps = userTexts.lastOrNull()?.let { cap(it, 600) } ?: "",
        )
    }
}
