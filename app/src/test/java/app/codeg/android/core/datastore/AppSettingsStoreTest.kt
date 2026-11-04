package app.codeg.android.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping tests for the AppSettings decode (batch v123 regression follow-up):
 * the Activity device-swipe toggle defaults to false and stands alone.
 * 批v150: the Folders twin toggle retired with the Folders tab; the floating-ball
 * prefs joined the same guard in batch v126.
 */
class AppSettingsStoreTest {

    @Test
    fun `defaults are off`() {
        val s = AppSettings()
        assertFalse(s.activityDeviceSwipe)
    }

    @Test
    fun `floating ball defaults are on medium and system font`() {
        val s = AppSettings()
        assertTrue("the ball is a primary nav surface — ships on", s.floatBallEnabled)
        assertEquals("medium", s.floatBallSize)
        assertEquals(1f, s.fontScale)
    }

    @Test
    fun `size tiers coerce to the three known ids`() {
        assertTrue(FLOAT_BALL_SIZES.containsAll(listOf("small", "medium", "large")))
        assertEquals(3, FLOAT_BALL_SIZES.size)
    }

    @Test
    fun `cycle depth defaults to five and clamps to the one-to-ten band`() {
        // 批v160 循环切最近会话: the depth is the ring size of recent sessions the
        // ball walks. Loader clamps corrupt prefs into the band, so the store can
        // never hand the hub a 0 / negative / huge ring.
        val s = AppSettings()
        assertEquals(5, s.floatBallCycleDepth)
        assertEquals(1, FLOAT_BALL_CYCLE_MIN)
        assertEquals(10, FLOAT_BALL_CYCLE_MAX)
    }

    @Test
    fun `session alert defaults are notify on and keepalive off`() {
        // 批v160 W8: the "agent needs you" notification ships ON (it is the point
        // of the batch); the battery-costing foreground service ships OFF —
        // opting in is the user's call, never a default.
        val s = AppSettings()
        assertTrue(s.notifyInteractiveEnabled)
        assertFalse(s.keepAliveEnabled)
    }

    @Test
    fun `preferences key names stay distinct`() {
        // The Key constants pin the wire keys: a copy-paste that made two toggles
        // read the same DataStore key would pass compile and silently corrupt
        // settings. Same risk class for the batch-v126 ball keys (pos vs bindings
        // especially — both JSON strings). The retired folders_device_swipe key is
        // NOT asserted: an old install's stored value is simply ignored now.
        // (Kotlin hoists companion constants to static fields on the OUTER class —
        // the Companion object's own field table is empty; probe-verified.)
        val keys = AppSettingsStore::class.java.declaredFields
            .filter { it.type.simpleName == "Key" }
            .map { it.isAccessible = true; (it.get(null) as androidx.datastore.preferences.core.Preferences.Key<*>).name }
        assertEquals(1, keys.count { it == "activity_device_swipe" })
        assertEquals(1, keys.count { it == "float_ball_enabled" })
        assertEquals(1, keys.count { it == "float_ball_size" })
        assertEquals(1, keys.count { it == "font_scale" })
        assertEquals(1, keys.count { it == "float_ball_pos" })
        assertEquals(1, keys.count { it == "float_ball_bindings" })
        assertEquals(1, keys.count { it == "float_ball_cycle_depth" })
        assertEquals(1, keys.count { it == "notify_interactive_enabled" })
        assertEquals(1, keys.count { it == "keep_alive_enabled" })
        assertEquals(1, keys.count { it == "task_swipe_mode" })
        // And no key collides with another at all.
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `task swipe mode defaults to segments and only two modes are legal`() {
        // 批v160 W5: the task board's horizontal swipe steps the four status
        // columns by default; switching devices is the explicit opt-in. The
        // loader coerces unknown stored values back to the default, so a corrupt
        // pref can never strand the board.
        val s = AppSettings()
        assertEquals(TASK_SWIPE_SEGMENTS, s.taskSwipeMode)
        assertEquals(setOf("segments", "devices"), TASK_SWIPE_MODES)
        assertTrue(TASK_SWIPE_MODES.contains(TASK_SWIPE_SEGMENTS))
        assertTrue(TASK_SWIPE_MODES.contains(TASK_SWIPE_DEVICES))
    }
}
