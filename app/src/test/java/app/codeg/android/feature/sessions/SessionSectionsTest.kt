package app.codeg.android.feature.sessions

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.FolderDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Locks the grouped section list the Chats screen renders: ordering (Pinned →
 * folders in sortOrder/name order → Other), per-section sort, membership
 * (pinned excluded from folder groups), and the pre-resolved cross-folder tag.
 */
class SessionSectionsTest {

    private fun conv(
        id: Int,
        folderId: Int,
        updated: Long,
        pinnedAt: Long? = null,
    ) = ConversationSummary(
        id = id,
        folderId = folderId,
        title = "c$id",
        agentType = AgentType.CLAUDE_CODE,
        status = ConversationStatus.COMPLETED,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.ofEpochSecond(updated),
        pinnedAt = pinnedAt?.let { Instant.ofEpochSecond(it) },
    )

    private fun folder(id: Int, name: String, sortOrder: Int = 0, color: String = "") =
        FolderDetail(id = id, name = name, path = "/p/$id", sortOrder = sortOrder, color = color)

    @Test
    fun `sections are ordered pinned then folders then other`() {
        val folders = listOf(
            folder(1, "Beta", sortOrder = 1),
            folder(2, "Alpha", sortOrder = 0),
        )
        val convs = listOf(
            conv(10, folderId = 1, updated = 100, pinnedAt = 50),
            conv(11, folderId = 2, updated = 90, pinnedAt = 70),
            conv(20, folderId = 1, updated = 200),
            conv(21, folderId = 1, updated = 150),
            conv(30, folderId = 2, updated = 120),
            conv(40, folderId = 99, updated = 300), // orphan → Other
        )

        val sections = buildSessionSections(folders, convs)

        // sortedFolders orders by sortOrder then name → Alpha(0) before Beta(1).
        assertEquals(listOf("pinned", "folder-2", "folder-1", "other"), sections.map { it.id })
    }

    @Test
    fun `pinned section spans folders, sorts by pinnedAt desc, and tags the folder`() {
        val folders = listOf(folder(1, "Beta", sortOrder = 1), folder(2, "Alpha", sortOrder = 0))
        val convs = listOf(
            conv(10, folderId = 1, updated = 100, pinnedAt = 50),
            conv(11, folderId = 2, updated = 90, pinnedAt = 70),
            conv(30, folderId = 2, updated = 120),
        )

        val pinned = buildSessionSections(folders, convs).first { it.id == "pinned" }

        assertTrue(pinned.kind is SectionKind.Pinned)
        // pinnedAt 70 (conv 11) is more recent than 50 (conv 10).
        assertEquals(listOf(11, 10), pinned.rows.map { it.conversation.id })
        // Cross-folder rows carry the folder tag, pre-resolved.
        assertEquals(listOf("Alpha", "Beta"), pinned.rows.map { it.folderName })
    }

    @Test
    fun `folder group lists its non-pinned rows newest-first with no folder tag`() {
        val folders = listOf(folder(1, "Beta", sortOrder = 1), folder(2, "Alpha", sortOrder = 0))
        val convs = listOf(
            conv(11, folderId = 2, updated = 90, pinnedAt = 70), // pinned → NOT in folder-2
            conv(20, folderId = 1, updated = 200),
            conv(21, folderId = 1, updated = 150),
            conv(30, folderId = 2, updated = 120),
        )

        val sections = buildSessionSections(folders, convs)
        val beta = sections.first { it.id == "folder-1" }
        val alpha = sections.first { it.id == "folder-2" }

        assertEquals("Beta", (beta.kind as SectionKind.Folder).name)
        assertEquals(listOf(20, 21), beta.rows.map { it.conversation.id }) // newest-first
        assertTrue(beta.rows.all { it.folderName == null })               // header is the folder
        // The pinned conv 11 is excluded from its folder group; only conv 30 remains.
        assertEquals(listOf(30), alpha.rows.map { it.conversation.id })
    }

    @Test
    fun `empty folders collapse into one trailing row`() {
        val sections = buildSessionSections(
            listOf(folder(1, "Solo"), folder(2, "Idle"), folder(3, "AlsoIdle")),
            emptyList(),
        )

        // No per-folder headers: they were a stack of empty labels at the bottom of the
        // list. One row stands in for all of them and links to the Folders tab.
        assertEquals(listOf("idle-folders"), sections.map { it.id })
        val idle = sections.single().kind as SectionKind.IdleFolders
        assertEquals(3, idle.count)
    }

    @Test
    fun `the idle row sorts after every real section`() {
        val sections = buildSessionSections(
            listOf(folder(1, "Busy"), folder(2, "Idle")),
            listOf(conv(1, folderId = 1, updated = 10)),
        )
        // A populated folder section first, then the footer standing in for folder 2.
        assertEquals("folder-1", sections.first().id)
        assertEquals("idle-folders", sections.last().id)
        assertEquals(1, (sections.last().kind as SectionKind.IdleFolders).count)
    }

    @Test
    fun `no pinned or other section when there are none`() {
        val sections = buildSessionSections(
            listOf(folder(1, "A")),
            listOf(conv(1, folderId = 1, updated = 10)),
        )

        assertEquals(listOf("folder-1"), sections.map { it.id })
    }

    @Test
    fun `orphan conversations fall into Other and resolve no folder tag`() {
        val sections = buildSessionSections(
            listOf(folder(1, "A")),
            listOf(conv(40, folderId = 99, updated = 300)),
        )
        val other = sections.first { it.id == "other" }

        assertTrue(other.kind is SectionKind.Other)
        assertEquals(listOf(40), other.rows.map { it.conversation.id })
        assertNull(other.rows.single().folderName) // unknown folder → no tag
    }
}
