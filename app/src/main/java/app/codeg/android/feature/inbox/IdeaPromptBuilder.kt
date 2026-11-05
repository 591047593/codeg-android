package app.codeg.android.feature.inbox

/**
 * 批v162（想法⇄会话快链）：想法文字的统一拼装器。两个方向共用同一段文字：
 * - 正向（会话 composer 的「想法」插入）：append 进草稿，用户手动发送；
 * - 反向（想法卡的「追加到已有会话」）：作为首条指令的正文 arm 进
 *   [app.codeg.android.core.data.SessionAutoStart]，会话页自动发送。
 *
 * 附件不在这里拼：反向的文件路径由发送链的 📎 附注自动带出（FileAttachment →
 * filePromptAppendix），图片以内联块进 prompt——参照派发服务会话模式的同款
 * 语义，prompt 本身只承载 标题为头 + 描述正文。
 *
 * 「想法」标头是有意硬编码的中文（机器可读口径）：与派发管线里的 📎 附注、
 * Thinking… 状态词同一取舍——prompt 词汇不进 strings.xml。纯函数，单测锁定。
 */
object IdeaPromptBuilder {

    /**
     * 标题为头、描述正文；缺谁省谁，全空返回空串（调用方据此拦截"没内容可发"）。
     * 两头 trim，中间不加多余空行。
     */
    fun compose(title: String, description: String): String {
        val t = title.trim()
        val d = description.trim()
        return when {
            t.isEmpty() && d.isEmpty() -> ""
            d.isEmpty() -> "【想法】$t"
            t.isEmpty() -> d
            else -> "【想法】$t\n\n$d"
        }
    }
}
