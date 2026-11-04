package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Locks down the args-settled title rule on the shared tool view-model. */
class ToolCallVMTest {

    @Test
    fun `unsettled args hold the announced name instead of deriving from partial json`() {
        // CodeBuddy mid-stream: the accumulated JSON is partial (unterminated).
        val vm = ToolCallVM.of(
            "t1", "Bash", "execute", ToolCallState.RUNNING,
            input = "{\"command\":\"timeout 20 ssh -o", output = null, content = null,
            isError = false, argsSettled = false,
        )
        assertEquals("Bash", vm.displayTitle)
    }

    @Test
    fun `settled args derive the command title`() {
        val vm = ToolCallVM.of(
            "t1", "Bash", "execute", ToolCallState.RUNNING,
            input = "{\"command\":\"timeout 20 ssh -o StrictHostKeyChecking=no\"}", output = null,
            content = null, isError = false, argsSettled = true,
        )
        assertNotEquals("Bash", vm.displayTitle)
    }

    @Test
    fun `persisted path defaults to settled`() {
        // The two-arg-order call sites the persisted adaptTurn uses compile unchanged
        // and keep deriving titles (persisted arguments are always complete).
        val vm = ToolCallVM.of("t1", "Bash", "execute", ToolCallState.DONE, "{\"command\":\"ls\"}", "out", null, false)
        assertEquals("ls", vm.displayTitle)
    }
}
