package app.codeg.android.feature.activity

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * R2-B P2-1 regression lock: the Activity feed's project-scope + hidden-project
 * narrowing, extracted from the ViewModel's combine lambda. The single-device
 * gate (`!all` passthrough) is the part that once leaked — a hidden project or
 * a stale scope must never filter a feed the user can't escape from.
 */
class NarrowActivityRowsTest {

    private val t0 = Instant.parse("2026-11-01T00:00:00Z")

    private fun row(serverId: String, convId: Int) = ActivityRow(
        conversation = ConversationSummary(
            id = convId,
            folderId = 0,
            title = "s$convId",
            agentType = AgentType.CODEX,
            status = ConversationStatus.COMPLETED,
            createdAt = t0,
            updatedAt = t0,
        ),
        serverId = serverId,
        serverName = serverId.uppercase(),
    )

    // a:1 ∈ p1(hidden), a:2 ∈ p2(visible), b:3 unassigned.
    private val rows = listOf(row("a", 1), row("a", 2), row("b", 3))
    private val members = mapOf("a:1" to setOf("p1"), "a:2" to setOf("p2"))

    @Test
    fun `single-device feed is never narrowed - not by scope, not by hidden`() {
        assertEquals(
            rows,
            narrowActivityRows(
                rows,
                all = false,
                memberKeys = setOf("a:1"),
                excludeKeys = null,
                hiddenProjects = setOf("p1", "p2"),
                memberProjects = members,
            ),
        )
    }

    @Test
    fun `no scope in aggregate keeps every row`() {
        assertEquals(
            rows,
            narrowActivityRows(
                rows, all = true, memberKeys = null, excludeKeys = null,
                hiddenProjects = emptySet(), memberProjects = members,
            ),
        )
    }

    @Test
    fun `memberKeys own the narrowing and a stale exclude list cannot stack`() {
        assertEquals(
            listOf(rows[0]),
            narrowActivityRows(
                rows, all = true, memberKeys = setOf("a:1"),
                excludeKeys = setOf("a:1", "a:2", "b:3"),
                hiddenProjects = emptySet(), memberProjects = members,
            ),
        )
    }

    @Test
    fun `unassigned scope keeps only rows with no project membership`() {
        assertEquals(
            listOf(rows[2]),
            narrowActivityRows(
                rows, all = true, memberKeys = null,
                excludeKeys = setOf("a:1", "a:2"),
                hiddenProjects = emptySet(), memberProjects = members,
            ),
        )
    }

    @Test
    fun `hidden project drops its member rows in the aggregate`() {
        assertEquals(
            listOf(rows[1], rows[2]),
            narrowActivityRows(
                rows, all = true, memberKeys = null, excludeKeys = null,
                hiddenProjects = setOf("p1"), memberProjects = members,
            ),
        )
    }

    @Test
    fun `hidden filter stacks on top of a memberKeys scope`() {
        // Project scope p1+p2 members + hidden p1: a:1 filtered twice over.
        assertEquals(
            listOf(rows[1]),
            narrowActivityRows(
                rows, all = true, memberKeys = setOf("a:1", "a:2"),
                excludeKeys = null, hiddenProjects = setOf("p1"), memberProjects = members,
            ),
        )
    }

    @Test
    fun `hidden project unknown to memberProjects hides nothing`() {
        assertEquals(
            rows,
            narrowActivityRows(
                rows, all = true, memberKeys = null, excludeKeys = null,
                hiddenProjects = setOf("ghost"), memberProjects = members,
            ),
        )
    }
}
