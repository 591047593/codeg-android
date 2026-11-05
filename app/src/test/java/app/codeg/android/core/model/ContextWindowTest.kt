package app.codeg.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 批v161.1 R1: the client-side window table must cover the CodeBuddy models
 *  this deployment runs (the server's parser leaves them null) and mirror the
 *  server's own families so the two gauges never disagree. */
class ContextWindowTest {

    @Test
    fun `codebuddy models get a window`() {
        assertEquals(128_000, inferContextWindowMaxTokens("DeepSeek-V4.1-Flash"))
        assertEquals(128_000, inferContextWindowMaxTokens("DeepSeek-V4-Pro"))
        assertEquals(128_000, inferContextWindowMaxTokens("magpie/group/deepseek-v4-flash"))
        assertEquals(256_000, inferContextWindowMaxTokens("Hy3"))
        assertEquals(256_000, inferContextWindowMaxTokens("hunyuan-turbo"))
    }

    @Test
    fun `server families keep parity`() {
        assertEquals(200_000, inferContextWindowMaxTokens("claude-opus-4.7"))
        assertEquals(1_000_000, inferContextWindowMaxTokens("claude-opus-4.7[1m]"))
        assertEquals(1_048_576, inferContextWindowMaxTokens("gemini-3-pro"))
        assertEquals(1_048_576, inferContextWindowMaxTokens("kimi-k3"))
        assertEquals(262_144, inferContextWindowMaxTokens("kimi-k2.6-code"))
        assertEquals(500_000, inferContextWindowMaxTokens("grok-4.5"))
        assertEquals(256_000, inferContextWindowMaxTokens("grok-code-fast-1"))
        assertEquals(258_000, inferContextWindowMaxTokens("gpt-5.2"))
        assertEquals(200_000, inferContextWindowMaxTokens("GLM-5.3-Flash"))
        assertEquals(128_000, inferContextWindowMaxTokens("glm-4.5"))
    }

    @Test
    fun `explicit capacity suffix wins`() {
        assertEquals(128_000, inferContextWindowMaxTokens("some-vendor/foo-128k"))
        assertEquals(1_000_000, inferContextWindowMaxTokens("bar-1M"))
    }

    @Test
    fun `unknown and empty models degrade to null`() {
        assertNull(inferContextWindowMaxTokens(null))
        assertNull(inferContextWindowMaxTokens(""))
        assertNull(inferContextWindowMaxTokens("  "))
        assertNull(inferContextWindowMaxTokens("totally-unknown-model"))
    }
}
