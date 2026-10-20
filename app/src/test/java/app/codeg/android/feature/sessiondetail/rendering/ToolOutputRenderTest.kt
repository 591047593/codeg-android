package app.codeg.android.feature.sessiondetail.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification of a tool's output into the view the card renders.
 *
 * [ToolOutputSection] used to evaluate this pipeline inline on every recomposition;
 * it is now derived once per result. These tests pin the *behaviour* the refactor had
 * to preserve, and did not have coverage before.
 */
class ToolOutputRenderTest {

    private fun vm(
        output: String?,
        bucket: ToolKindBucket = ToolKindBucket.OTHER,
        isError: Boolean = false,
    ) = ToolCallVM(
        id = "t1",
        rawName = "some_tool",
        kind = "other",
        state = if (isError) ToolCallState.ERROR else ToolCallState.DONE,
        input = null,
        output = output,
        content = null,
        isError = isError,
        displayTitle = "Some Tool",
        bucket = bucket,
        diffFiles = null,
    )

    @Test
    fun `an error wins over every other classification`() {
        // previously: READ && !isError was checked first, then isError. The refactor
        // hoists isError; a READ tool that errored must still render as an error.
        val render = deriveToolOutputRender(
            vm("""{"start_line":1,"content":"body"}""", ToolKindBucket.READ, isError = true),
        )
        assertTrue(render is ToolOutputRender.Error)
    }

    @Test
    fun `a read tool with a start_line envelope renders as a file`() {
        val render = deriveToolOutputRender(
            vm("""{"start_line":10,"content":"hello\nworld"}""", ToolKindBucket.READ),
        )
        assertTrue(render is ToolOutputRender.ReadFile)
        assertEquals(10, (render as ToolOutputRender.ReadFile).file.startLine)
    }

    @Test
    fun `a read tool without a parsable envelope falls through to classification`() {
        val render = deriveToolOutputRender(vm("plain text output", ToolKindBucket.READ))
        assertTrue(render !is ToolOutputRender.ReadFile)
    }

    @Test
    fun `a command tool has its CLI envelope stripped`() {
        val output = """
            Chunk ID: abc
            Wall time: 0.1s
            Output:
            real output here
        """.trimIndent()
        val render = deriveToolOutputRender(vm(output, ToolKindBucket.EXECUTE))
        assertTrue(render is ToolOutputRender.Command)
        assertEquals("real output here", (render as ToolOutputRender.Command).text)
    }

    @Test
    fun `a metadata-only command envelope renders nothing`() {
        // No body after the metadata — an empty card, not a dump of the raw envelope.
        val render = deriveToolOutputRender(vm("Chunk ID: abc\nWall time: 0.1s", ToolKindBucket.EXECUTE))
        assertEquals(ToolOutputRender.Empty, render)
    }

    @Test
    fun `json output is classified as json`() {
        val render = deriveToolOutputRender(vm("""{"a":1,"b":[2,3]}"""))
        assertTrue(render is ToolOutputRender.Json)
    }

    @Test
    fun `a unified diff is classified as diff`() {
        val diff = """
            diff --git a/x.kt b/x.kt
            --- a/x.kt
            +++ b/x.kt
            @@ -1,2 +1,2 @@
            -old
            +new
        """.trimIndent()
        val render = deriveToolOutputRender(vm(diff))
        assertTrue(render is ToolOutputRender.Diff)
        assertTrue((render as ToolOutputRender.Diff).parsed != null)
    }

    @Test
    fun `markdown looking output is classified as markdown`() {
        val md = "# Title\n\n- one\n- two\n\n**bold**"
        val render = deriveToolOutputRender(vm(md))
        assertTrue(render is ToolOutputRender.Markdown)
    }

    @Test
    fun `plain log output stays a log`() {
        val render = deriveToolOutputRender(vm("starting up\nlistening on :8080\ndone"))
        assertTrue(render is ToolOutputRender.Log)
    }

    @Test
    fun `derivation is stable for the same input`() {
        val v = vm("some output", ToolKindBucket.EXECUTE)
        assertEquals(deriveToolOutputRender(v), deriveToolOutputRender(v))
    }
}
