package app.codeg.android.feature.inbox

import app.codeg.android.core.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 批v162（想法⇄会话快链）：锁定选择器的两个纯函数——
 * [filterPickerRows] 的四层交集（设备 → 项目跨设备键 → 仅看板 → 关键词），
 * [relativeStamp] 的四档相对时间口径（刚刚/分钟/小时/日期，负年龄按刚刚）。
 */
class SessionPickerFilterTest {

    private val now: Instant = Instant.parse("2026-11-06T12:00:00Z")

    private fun row(
        serverId: String = "tx",
        conversationId: Int = 1,
        title: String = "会话A",
        agent: AgentType = AgentType.CLAUDE_CODE,
        folderId: Int = 10,
        folderName: String? = "AIOS",
        updatedAt: Instant = now,
        serverName: String = "tx-tencent",
        live: Boolean = false,
    ) = SessionPickerRow(
        serverId = serverId,
        serverName = serverName,
        conversationId = conversationId,
        title = title,
        agent = agent,
        folderId = folderId,
        folderName = folderName,
        updatedAt = updatedAt,
        live = live,
    )

    // ---- filterPickerRows ----

    @Test
    fun deviceFilterKeepsSameServerOnly() {
        val rows = listOf(row(serverId = "tx"), row(serverId = "nas", conversationId = 2))
        val out = filterPickerRows(rows, "", "nas", null, false, emptySet())
        assertEquals(listOf("nas:2"), out.map { it.key })
    }

    @Test
    fun folderKeyIsCrossDevice() {
        // 同一 folderId 在两台设备上是两个项目——只有带上设备号的键才算同一项目。
        val rows = listOf(row(serverId = "tx", folderId = 10), row(serverId = "nas", conversationId = 2, folderId = 10))
        val out = filterPickerRows(rows, "", null, "nas:10", false, emptySet())
        assertEquals(listOf("nas:2"), out.map { it.key })
    }

    @Test
    fun boardOnlyKeepsTrackedConversationsOnly() {
        val rows = listOf(row(conversationId = 1), row(conversationId = 2))
        val out = filterPickerRows(rows, "", null, null, true, setOf("tx:2"))
        assertEquals(listOf("tx:2"), out.map { it.key })
    }

    @Test
    fun folderKeyAndBoardOnlyIntersect() {
        // 项目筛选与仅看板同时生效：既要同项目、又要上过看板。
        val rows = listOf(
            row(conversationId = 1, folderId = 10),            // 项目对，没上过看板
            row(conversationId = 2, folderId = 99),            // 上过看板，项目不对
            row(conversationId = 3, folderId = 10),            // 两个都对
        )
        val out = filterPickerRows(rows, "", null, "tx:10", true, setOf("tx:2", "tx:3"))
        assertEquals(listOf("tx:3"), out.map { it.key })
    }

    @Test
    fun queryMatchesTitleCaseInsensitive() {
        val rows = listOf(row(title = "Fix Login Bug"), row(title = "重构首页", conversationId = 2))
        assertEquals(listOf("tx:1"), filterPickerRows(rows, "login", null, null, false, emptySet()).map { it.key })
        assertEquals(listOf("tx:1"), filterPickerRows(rows, "LOGIN BUG", null, null, false, emptySet()).map { it.key })
    }

    @Test
    fun queryMatchesServerNameFolderNameAndAgent() {
        // 默认行：serverName="tx-tencent"、folderName="AIOS"、agent=Claude Code。
        val rows = listOf(row(conversationId = 1))
        assertEquals(listOf("tx:1"), filterPickerRows(rows, "tencent", null, null, false, emptySet()).map { it.key })
        assertEquals(listOf("tx:1"), filterPickerRows(rows, "AIOS", null, null, false, emptySet()).map { it.key })
        assertEquals(listOf("tx:1"), filterPickerRows(rows, "claude code", null, null, false, emptySet()).map { it.key })
    }

    @Test
    fun queryIsTrimmedAndNoMatchYieldsEmpty() {
        val rows = listOf(row())
        assertTrue(filterPickerRows(rows, "  ", null, null, false, emptySet()).isNotEmpty())
        assertTrue(filterPickerRows(rows, "不存在", null, null, false, emptySet()).isEmpty())
    }

    @Test
    fun originalOrderIsPreserved() {
        // 入参已按活跃倒序，过滤不许重排。
        val rows = listOf(row(conversationId = 3), row(conversationId = 1), row(conversationId = 2))
        val out = filterPickerRows(rows, "", null, null, false, emptySet())
        assertEquals(listOf("tx:3", "tx:1", "tx:2"), out.map { it.key })
    }

    // ---- relativeStamp ----

    @Test
    fun negativeAgeCountsAsJustNow() {
        assertEquals(
            RelativeStamp.JustNow,
            relativeStamp(now.plus(Duration.ofMinutes(5)), now),
        )
    }

    @Test
    fun underOneMinuteIsJustNow() {
        assertEquals(RelativeStamp.JustNow, relativeStamp(now.minusSeconds(59), now))
    }

    @Test
    fun oneMinuteStartsMinutesBucket() {
        assertEquals(RelativeStamp.Minutes(1), relativeStamp(now.minusSeconds(60), now))
        assertEquals(RelativeStamp.Minutes(59), relativeStamp(now.minusMinutes(59), now))
    }

    @Test
    fun oneHourStartsHoursBucket() {
        assertEquals(RelativeStamp.Hours(1), relativeStamp(now.minusMinutes(60), now))
        assertEquals(RelativeStamp.Hours(23), relativeStamp(now.minusMinutes(23 * 60 + 59), now))
    }

    @Test
    fun oneDayStartsDatedBucket() {
        val stamp = relativeStamp(now.minusHours(24), now, ZoneId.of("UTC"))
        assertEquals(RelativeStamp.Dated("11-05"), stamp)
    }

    @Test
    fun datedUsesUpdatedDayNotNowDay() {
        val updated = Instant.parse("2026-03-05T10:00:00Z")
        val stamp = relativeStamp(updated, now, ZoneId.of("UTC"))
        assertEquals(RelativeStamp.Dated("03-05"), stamp)
    }
}
