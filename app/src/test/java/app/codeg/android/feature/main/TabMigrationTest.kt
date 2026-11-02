package app.codeg.android.feature.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批v150 四栏工作台的持久化迁移：five-tab 时代存进 DataStore 的 tab 顺序与
 * 起始页都是**路由字符串**——枚举四栏化后，旧值必须安静地收敛到合法集合，
 * 而不是让未知路由出现在底栏或起始判定里。读侧纯函数即迁移面（无写回）。
 */
class TabMigrationTest {

    @Test
    fun `a five-tab saved order collapses to the four tabs`() {
        val migrated = HomeTab.orderedBy(listOf("chats", "folders", "activity", "search", "settings"))
        // "activity"/"settings" survive in their saved relative order; the demoted
        // routes are dropped; the new tabs append in enum order.
        assertEquals(listOf(HomeTab.ACTIVITY, HomeTab.SETTINGS, HomeTab.INBOX, HomeTab.TASKS), migrated)
    }

    @Test
    fun `an empty saved order keeps enum order`() {
        assertEquals(HomeTab.entries.toList(), HomeTab.orderedBy(emptyList()))
    }

    @Test
    fun `unknown routes never resurrect demoted tabs`() {
        val migrated = HomeTab.orderedBy(listOf("chats", "search", "bogus", "inbox"))
        // known=[INBOX] first, then every remaining tab in enum order (4 tabs total).
        assertEquals(listOf(HomeTab.INBOX, HomeTab.ACTIVITY, HomeTab.TASKS, HomeTab.SETTINGS), migrated)
    }

    @Test
    fun `five-tab-era startTab migrates to the workbench`() {
        // "chats" was the shipped default start tab — the most common stored value.
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute("chats"))
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute("folders"))
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute("search"))
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute(null))
    }

    @Test
    fun `new and surviving start routes resolve directly`() {
        assertEquals(HomeTab.ACTIVITY, HomeTab.byRoute("activity"))
        assertEquals(HomeTab.INBOX, HomeTab.byRoute("inbox"))
        assertEquals(HomeTab.TASKS, HomeTab.byRoute("tasks"))
        assertEquals(HomeTab.SETTINGS, HomeTab.byRoute("settings"))
    }

    @Test
    fun `the shipped default start tab is the workbench`() {
        assertEquals("activity", app.codeg.android.core.datastore.AppSettings.DEFAULT_START_TAB)
    }
}
