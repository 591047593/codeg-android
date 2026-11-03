package app.codeg.android.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 批v149 会话模式派发的首条指令交接站：consume-once + 精确匹配。
 * 唯一的安全底线——一条指令绝不能从一个更晚的无关访问里复活重发。
 */
class SessionAutoStartTest {

    @Test
    fun `take consumes exactly once on exact match`() {
        val s = SessionAutoStart()
        s.arm("srv1", 42, "把首页按钮改成蓝色")
        assertEquals("把首页按钮改成蓝色", s.take("srv1", 42))
        // Second take (re-entry, rotation, anything) reads null — never re-fires.
        assertNull(s.take("srv1", 42))
    }

    @Test
    fun `different conversation or server reads null without consuming`() {
        val s = SessionAutoStart()
        s.arm("srv1", 42, "prompt")
        assertNull(s.take("srv1", 43))
        assertNull(s.take("srv2", 42))
        // Still armed for the real target after near-miss reads.
        assertEquals("prompt", s.take("srv1", 42))
    }

    @Test
    fun `blank prompt is never armed`() {
        val s = SessionAutoStart()
        s.arm("srv1", 42, "   ")
        assertNull(s.take("srv1", 42))
        // An armed prompt survives a later blank arm attempt (blank arms are no-ops).
        s.arm("srv1", 42, "real")
        s.arm("srv1", 42, "  ")
        assertEquals("real", s.take("srv1", 42))
    }

    @Test
    fun `prompt is trimmed on arm`() {
        val s = SessionAutoStart()
        s.arm("srv1", 42, "  do it  \n")
        assertEquals("do it", s.take("srv1", 42))
    }

    @Test
    fun `re-arming replaces the single slot`() {
        val s = SessionAutoStart()
        s.arm("srv1", 1, "first")
        s.arm("srv1", 2, "second")
        assertNull(s.take("srv1", 1))
        assertEquals("second", s.take("srv1", 2))
    }

    // ---- 批v150 staleness window: a lingering slot never fires late ----

    @Test
    fun `a fresh prompt fires inside the window`() {
        val s = SessionAutoStart()
        var now = 1_000_000L
        s.clock = { now }
        s.arm("srv1", 42, "prompt")
        now += SessionAutoStart.STALE_MS - 1
        assertEquals("prompt", s.take("srv1", 42))
    }

    @Test
    fun `a stale prompt never fires and the slot is consumed`() {
        val s = SessionAutoStart()
        var now = 1_000_000L
        s.clock = { now }
        s.arm("srv1", 42, "late prompt")
        now += SessionAutoStart.STALE_MS + 1
        assertNull(s.take("srv1", 42))
        // Consumed, not retained: a second visit must not resurrect it either.
        assertNull(s.take("srv1", 42))
    }

    // ---- 批v154 attachments: a dispatch may carry files, not just text ----

    @Test
    fun `files ride with the prompt and come back through takePending`() {
        val s = SessionAutoStart()
        val files = listOf(UploadedAttachment("spec.pdf", "/work/uploads/spec.pdf"))
        s.arm("srv1", 42, "看看这个", files)
        val pending = s.takePending("srv1", 42)!!
        assertEquals("看看这个", pending.prompt)
        assertEquals(files, pending.files)
        // Consumed: the next visit must not re-send either part.
        assertNull(s.takePending("srv1", 42))
    }

    @Test
    fun `files alone arm a dispatch`() {
        val s = SessionAutoStart()
        val files = listOf(UploadedAttachment("a.png", "/work/uploads/a.png"))
        s.arm("srv1", 7, "  ", files)
        assertEquals(files, s.takePending("srv1", 7)?.files)
    }

    @Test
    fun `a stale slot drops its files as well`() {
        val s = SessionAutoStart()
        var now = 1_000_000L
        s.clock = { now }
        s.arm("srv1", 42, "prompt", listOf(UploadedAttachment("a.png", "/work/uploads/a.png")))
        now += SessionAutoStart.STALE_MS + 1
        assertNull(s.takePending("srv1", 42))
        assertNull(s.takePending("srv1", 42))
    }

    @Test
    fun `staleness is judged per slot not per visit`() {
        val s = SessionAutoStart()
        var now = 1_000_000L
        s.clock = { now }
        s.arm("srv1", 42, "prompt")
        now += SessionAutoStart.STALE_MS - 1
        assertEquals("prompt", s.take("srv1", 42))
    }
}
