package app.codeg.android.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The file reference is spliced into a prompt as Markdown and resolved by the backend
 * from the link destination, so a mis-encoded path silently references nothing.
 */
class FileReferenceTest {

    @Test
    fun `plain posix path keeps its shape`() {
        assertEquals("file:///home/me/proj/Main.kt", FileReference.uri("/home/me/proj/Main.kt"))
    }

    @Test
    fun `spaces are percent encoded so the destination does not truncate`() {
        assertEquals("file:///home/me/my%20proj/a%20b.kt", FileReference.uri("/home/me/my proj/a b.kt"))
    }

    @Test
    fun `a hash must be encoded or it becomes a fragment`() {
        assertEquals("file:///tmp/issue%231.md", FileReference.uri("/tmp/issue#1.md"))
    }

    @Test
    fun `a plus must be encoded to round trip`() {
        assertEquals("file:///tmp/a%2Bb.txt", FileReference.uri("/tmp/a+b.txt"))
    }

    @Test
    fun `windows separators are normalised`() {
        assertEquals("file:///C%3A/work/proj/Main.kt", FileReference.uri("C:\\work\\proj\\Main.kt"))
    }

    @Test
    fun `a UNC share keeps its double slash authority`() {
        assertEquals("file://server/share/a.txt", FileReference.uri("//server/share/a.txt"))
    }

    @Test
    fun `unreserved characters stay literal`() {
        assertEquals("file:///a/b-c_d.e~f", FileReference.uri("/a/b-c_d.e~f"))
    }

    @Test
    fun `the label defaults to the file name and brackets are escaped`() {
        assertEquals("[Mai\\[n\\].kt](file:///a/Mai[n].kt)", FileReference.markdown("/a/Mai[n].kt"))
    }

    @Test
    fun `appending separates entries with a single space`() {
        assertEquals("[a.kt](file:///r/a.kt) ", appendFileReference("", "/r/a.kt"))
        assertEquals("look at [a.kt](file:///r/a.kt) ", appendFileReference("look at", "/r/a.kt"))
        // Existing trailing whitespace is trimmed, so repeated inserts do not accumulate.
        assertEquals("x [a.kt](file:///r/a.kt) ", appendFileReference("x   ", "/r/a.kt"))
    }
}
