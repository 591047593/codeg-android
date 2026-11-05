package app.codeg.android.feature.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批v162（想法⇄会话快链）：锁定 [IdeaPromptBuilder.compose] 的拼装口径——
 * 标题为头、描述正文、缺谁省谁、全空给空串（调用方据此拦"没内容可发"）。
 */
class IdeaPromptBuilderTest {

    @Test
    fun bothBlankReturnsEmpty() {
        assertEquals("", IdeaPromptBuilder.compose("", ""))
    }

    @Test
    fun whitespaceOnlyCountsAsBlank() {
        assertEquals("", IdeaPromptBuilder.compose("   ", " \n\t "))
    }

    @Test
    fun titleOnlyGetsIdeaHeader() {
        assertEquals("【想法】重写首页", IdeaPromptBuilder.compose("重写首页", ""))
    }

    @Test
    fun descriptionOnlySkipsHeader() {
        assertEquals("先跑一遍 Verify", IdeaPromptBuilder.compose("", "先跑一遍 Verify"))
    }

    @Test
    fun bothPartsGetHeaderBodyBlankLine() {
        assertEquals("【想法】重写首页\n\n先跑一遍 Verify", IdeaPromptBuilder.compose("重写首页", "先跑一遍 Verify"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("【想法】标题\n\n正文", IdeaPromptBuilder.compose("  标题 \n", "\n 正文 \n"))
    }

    @Test
    fun interiorFormattingIsPreserved() {
        val desc = "第一行\n第二行\n\n第三行"
        assertEquals("【想法】t\n\n$desc", IdeaPromptBuilder.compose("t", desc))
    }
}
