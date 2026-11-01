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
        // And no key collides with another at all.
        assertEquals(keys.size, keys.toSet().size)
    }
}
