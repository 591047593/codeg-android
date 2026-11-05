package app.codeg.android.feature.dispatch

import app.codeg.android.core.model.AgentOptionsSnapshot
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ModelProviderInfo
import app.codeg.android.core.model.SessionConfigKind
import app.codeg.android.core.model.SessionConfigOption
import app.codeg.android.core.model.SessionConfigSelectGroup
import app.codeg.android.core.model.SessionConfigSelectOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fleet M1: the dispatch form's 模型 row renders the MERGED snapshot
 * ([mergeProviderModels] — probe catalog + server provider models). The subtle
 * case this locks down: a GROUPED probe catalog must receive the providers as
 * their own trailing group, because the sheet's flattener prefers a non-empty
 * flat list and would silently drop the probed groups otherwise.
 */
class MergeProviderModelsTest {

    private fun provider(name: String, model: String?) = ModelProviderInfo(
        id = 1, name = name, apiUrl = "https://gw.example/v1",
        apiKeyMasked = "••••", agentType = AgentType.CLAUDE_CODE, model = model,
    )

    private fun flatProbe(vararg models: String) = AgentOptionsSnapshot(
        configOptions = listOf(
            SessionConfigOption(
                id = "model", category = "model",
                kind = SessionConfigKind(currentValue = "m1", options = models.map {
                    SessionConfigSelectOption(value = it, name = it)
                }),
            ),
        ),
    )

    private fun groupedProbe() = AgentOptionsSnapshot(
        configOptions = listOf(
            SessionConfigOption(
                id = "model", category = "model",
                kind = SessionConfigKind(
                    groups = listOf(
                        SessionConfigSelectGroup(
                            group = "fast",
                            options = listOf(SessionConfigSelectOption(value = "m1", name = "m1")),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `no providers leaves the probe untouched`() {
        val probe = flatProbe("m1")
        assertEquals(probe, mergeProviderModels(probe, emptyList()))
    }

    @Test
    fun `probe without a model option gets one synthesized`() {
        val probe = AgentOptionsSnapshot(configOptions = emptyList())
        val merged = mergeProviderModels(probe, listOf(provider("gw", "glm-4.7")))!!
        val model = merged.configOptions.single { it.id == "model" }
        assertEquals(listOf("glm-4.7"), model.kind.options.map { it.value })
        assertEquals("gw", model.kind.options.single().description)
    }

    @Test
    fun `no probe at all still yields a provider-carried model row`() {
        val merged = mergeProviderModels(null, listOf(provider("gw", "glm-4.7")))!!
        assertEquals(listOf("glm-4.7"), merged.configOptions.single { it.id == "model" }.kind.options.map { it.value })
        assertNull(merged.modes)
    }

    @Test
    fun `providers with a blank model are dropped`() {
        // Every provider model blank → no choices at all → a null probe stays
        // null (the form then renders its no-model placeholder row).
        assertNull(mergeProviderModels(null, listOf(provider("gw", "  "), provider("gw2", null))))
    }

    @Test
    fun `duplicate model values across providers keep the first`() {
        val merged = mergeProviderModels(null, listOf(provider("gw", "glm-4.7"), provider("gw2", "glm-4.7")))!!
        val options = merged.configOptions.single { it.id == "model" }.kind.options
        assertEquals(listOf("glm-4.7"), options.map { it.value })
    }

    @Test
    fun `flat probe is topped up without duplicating known ids`() {
        val merged = mergeProviderModels(
            flatProbe("m1"),
            listOf(provider("gw", "m1"), provider("gw2", "glm-4.7")),
        )!!
        val model = merged.configOptions.single { it.id == "model" }
        assertEquals(listOf("m1", "glm-4.7"), model.kind.options.map { it.value })
        // the probe's own current value survives the merge
        assertEquals("m1", model.kind.currentValue)
    }

    @Test
    fun `grouped probe gets the providers as a trailing group, groups intact`() {
        val merged = mergeProviderModels(
            groupedProbe(),
            listOf(provider("gw", "glm-4.7")),
        )!!
        val model = merged.configOptions.single { it.id == "model" }
        assertTrue("the probed group must survive", model.kind.options.isEmpty())
        assertEquals(listOf("fast", "provider_models"), model.kind.groups.map { it.group })
        assertEquals(listOf("m1", "glm-4.7"), model.kind.groups.flatMap { g -> g.options.map { it.value } })
    }

    @Test
    fun `all provider ids already known changes nothing`() {
        val probe = flatProbe("m1", "glm-4.7")
        assertEquals(probe, mergeProviderModels(probe, listOf(provider("gw", "glm-4.7"))))
    }
}
