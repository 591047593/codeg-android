package app.codeg.android.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `codeg://settings/<slug>` used to be parsed and then dropped, so every settings deep
 * link landed on the root list. These cover the vocabulary that now resolves.
 */
class SettingsSlugTest {

    @Test
    fun `every canonical slug resolves to itself`() {
        for (slug in SettingsSlug.all) {
            assertEquals(slug, SettingsSlug.resolve(slug))
        }
    }

    @Test
    fun `canonical slugs are unique`() {
        assertEquals(SettingsSlug.all.size, SettingsSlug.all.toSet().size)
    }

    @Test
    fun `aliases resolve to the canonical slug`() {
        assertEquals(SettingsSlug.APPEARANCE, SettingsSlug.resolve("theme"))
        assertEquals(SettingsSlug.APPEARANCE, SettingsSlug.resolve("colors"))
        assertEquals(SettingsSlug.MODEL_PROVIDERS, SettingsSlug.resolve("models"))
        assertEquals(SettingsSlug.MODEL_PROVIDERS, SettingsSlug.resolve("providers"))
        assertEquals(SettingsSlug.VERSION_CONTROL, SettingsSlug.resolve("git"))
        assertEquals(SettingsSlug.CHAT_CHANNELS, SettingsSlug.resolve("channels"))
        assertEquals(SettingsSlug.OPEN_SOURCE_LICENSES, SettingsSlug.resolve("licenses"))
        assertEquals(SettingsSlug.MCP, SettingsSlug.resolve("mcp_servers"))
        assertEquals(SettingsSlug.QUICK_MESSAGES, SettingsSlug.resolve("quickmessages"))
        assertEquals(SettingsSlug.ABOUT, SettingsSlug.resolve("version"))
    }

    @Test
    fun `case spaces and dashes are normalised`() {
        assertEquals(SettingsSlug.VERSION_CONTROL, SettingsSlug.resolve("Version-Control"))
        assertEquals(SettingsSlug.OPEN_SOURCE_LICENSES, SettingsSlug.resolve("OPEN_SOURCE_LICENSES"))
        assertEquals(SettingsSlug.CHAT_CHANNELS, SettingsSlug.resolve("  chat channels  "))
        assertEquals(SettingsSlug.AGENTS, SettingsSlug.resolve("Agents"))
    }

    @Test
    fun `unknown and empty slugs do not resolve`() {
        assertNull(SettingsSlug.resolve("nope"))
        assertNull(SettingsSlug.resolve(""))
        assertNull(SettingsSlug.resolve("   "))
        assertNull(SettingsSlug.resolve(null))
    }

    @Test
    fun `canonical slugs round-trip through resolve`() {
        for (slug in SettingsSlug.all) {
            val resolved = SettingsSlug.resolve(slug.uppercase())
            assertTrue("$slug did not round-trip", resolved == slug)
        }
    }
}
