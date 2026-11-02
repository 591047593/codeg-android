package app.codeg.android.feature.search

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ConversationStatus
import app.codeg.android.core.model.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Pure merge of the search All-mode fan-out (批v151): recency across devices,
 * deterministic tie-breaks (serverId, then server-local id), failures dropped
 * by the caller before the merge.
 */
class MergeSearchResultsTest {

    private val t0 = Instant.parse("2026-11-01T00:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    private fun conv(id: Int, atMin: Long) = ConversationSummary(
        id = id,
        folderId = 0,
        title = "s$id",
        agentType = AgentType.CODEX,
        status = ConversationStatus.COMPLETED,
        createdAt = t0,
        updatedAt = at(atMin),
    )

    @Test
    fun `merges devices sorted by updatedAt descending`() {
        val merged = mergeSearchResults(
            listOf(
                SearchDeviceResults("a", "A", listOf(conv(1, 10), conv(2, 30))),
                SearchDeviceResults("b", "B", listOf(conv(1, 20), conv(2, 40))),
            ),
        )
        // 40(b:2) > 30(a:2) > 20(b:1) > 10(a:1)
        assertEquals(listOf("b", "a", "b", "a"), merged.map { it.serverId })
        assertEquals(listOf(2, 2, 1, 1), merged.map { it.conversation.id })
    }

    @Test
    fun `identical timestamps break ties by serverId then id deterministically`() {
        val merged = mergeSearchResults(
            listOf(
                SearchDeviceResults("b", "B", listOf(conv(2, 10), conv(1, 10))),
                SearchDeviceResults("a", "A", listOf(conv(2, 10), conv(1, 10))),
            ),
        )
        assertEquals(listOf("a", "a", "b", "b"), merged.map { it.serverId })
        assertEquals(listOf(1, 2, 1, 2), merged.map { it.conversation.id })
    }

    @Test
    fun `carries the owning device name onto every hit`() {
        val merged = mergeSearchResults(
            listOf(SearchDeviceResults("a", "Dell", listOf(conv(1, 5)))),
        )
        assertEquals("Dell", merged.single().serverName)
        assertEquals("a", merged.single().serverId)
    }

    @Test
    fun `empty slices and empty input merge to empty`() {
        assertEquals(emptyList<SearchHit>(), mergeSearchResults(emptyList()))
        assertEquals(
            emptyList<SearchHit>(),
            mergeSearchResults(listOf(SearchDeviceResults("a", "A", emptyList()))),
        )
    }
}
