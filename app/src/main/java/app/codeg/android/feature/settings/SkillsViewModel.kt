package app.codeg.android.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AgentSkillItem
import app.codeg.android.core.model.AgentSkillsListResult
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-agent skill files. Loads the server's usable agents, then the selected
 * agent's skills (global + project scope). CRUD mirrors the iOS `SkillsSettingsModel`:
 * a monotonic [loadToken] stops a slow `listAgentSkills` for a previously-selected
 * agent from overwriting the current selection, and mutations capture the agent
 * explicitly so a fast switch can't retarget a read/save/delete.
 */
@HiltViewModel
class SkillsViewModel @Inject constructor(
    /** Needed only to resolve the localized failure strings in [displayMessage]. */
    @ApplicationContext private val appContext: Context,
    private val repository: ServerRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SkillsUiState())
    val ui: StateFlow<SkillsUiState> = _ui.asStateFlow()

    private var client: CodegClient? = null
    private var loadToken = 0

    /**
     * The profile whose endpoint the loaded data belongs to. Kept in sync with the
     * app-wide selection: a one-shot `selectedProfile.first()` at open time went
     * stale as soon as the user switched servers elsewhere, leaving this screen
     * talking to the previous server. The collector joins the load it starts, so
     * `collectLatest` genuinely cancels an in-flight request for the old endpoint —
     * without the join it only cancels the lambda, and the old server's response could
     * still land on top of the new server's data.
     */
    private var activeProfile: ServerProfile? = null

    init {
        viewModelScope.launch {
            repository.selectedEndpoint.collectLatest { profile ->
                val changed = when {
                    profile == null -> activeProfile != null
                    activeProfile == null -> true
                    else -> profile.id != activeProfile?.id || profile.baseUrl != activeProfile?.baseUrl
                }
                if (changed) {
                    activeProfile = profile
                    client = null
                    load()?.join()
                }
            }
        }
    }

    fun load(): Job? {
        return viewModelScope.launch {
            try {
                val profile = activeProfile ?: throw IllegalStateException("No server selected")
                val c = repository.client(profile) ?: throw IllegalStateException("Missing token")
                client = c
                if (_ui.value.agents.isEmpty()) _ui.update { it.copy(phase = SkillsPhase.LOADING) }
                val agents = c.acpListAgents()
                    .filter { it.available && it.enabled }
                    .sortedBy { it.sortOrder }
                    .map { it.agentType }
                val selected = _ui.value.selectedAgent.takeIf { agents.contains(it) } ?: agents.firstOrNull()
                _ui.update { it.copy(phase = SkillsPhase.LOADED, agents = agents, selectedAgent = selected, error = null) }
                selected?.let { loadSkills(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = SkillsPhase.FAILED, error = e.displayMessage(appContext)) }
            }
        }
    }

    fun select(agent: AgentType) {
        if (agent == _ui.value.selectedAgent) return
        _ui.update { it.copy(selectedAgent = agent, result = null, resultAgent = null) }
        loadSkills(agent)
    }

    fun reloadCurrent() {
        _ui.value.selectedAgent?.let { loadSkills(it) }
    }

    private fun loadSkills(agent: AgentType) {
        val c = client ?: return
        val token = ++loadToken
        _ui.update { it.copy(skillsLoading = true) }
        viewModelScope.launch {
            try {
                val loaded = c.listAgentSkills(agent)
                if (token != loadToken) return@launch // superseded by a newer selection
                _ui.update { it.copy(skillsLoading = false, result = loaded, resultAgent = agent, refreshError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != loadToken) return@launch
                _ui.update { it.copy(skillsLoading = false, refreshError = e.displayMessage(appContext)) }
            }
        }
    }

    /** Read a skill's markdown for the editor. */
    suspend fun content(skill: AgentSkillItem, agent: AgentType): String {
        val c = client ?: return ""
        return c.readAgentSkill(agent, skill.scope, skill.id).content
    }

    /** Save (create or overwrite) a skill, then refresh if its agent is still selected. */
    suspend fun save(skillId: String, scope: String, content: String, layout: String?, agent: AgentType) {
        val c = client ?: return
        c.saveAgentSkill(agent, scope, skillId, content, layout)
        if (agent == _ui.value.selectedAgent) loadSkills(agent)
    }

    fun delete(skill: AgentSkillItem, agent: AgentType) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.deleteAgentSkill(agent, skill.scope, skill.id)
                if (agent == _ui.value.selectedAgent) loadSkills(agent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Don't surface A's failure on B's view if the user switched mid-delete.
                if (agent == _ui.value.selectedAgent) _ui.update { it.copy(refreshError = e.displayMessage(appContext)) }
            }
        }
    }

    fun dismissRefreshError() = _ui.update { it.copy(refreshError = null) }
}

enum class SkillsPhase { LOADING, LOADED, FAILED }

data class SkillsUiState(
    val phase: SkillsPhase = SkillsPhase.LOADING,
    val agents: List<AgentType> = emptyList(),
    val selectedAgent: AgentType? = null,
    val result: AgentSkillsListResult? = null,
    /** The agent [result] actually belongs to (mutations target THIS, not [selectedAgent]). */
    val resultAgent: AgentType? = null,
    val skillsLoading: Boolean = false,
    val refreshError: String? = null,
    val error: String? = null,
) {
    /** Skills grouped by scope, global first, each sorted by name. */
    val grouped: List<Pair<String, List<AgentSkillItem>>>
        get() {
            val skills = result?.skills ?: emptyList()
            return listOf("global", "project").mapNotNull { scope ->
                val items = skills.filter { it.scope == scope }.sortedBy { it.name }
                if (items.isEmpty()) null else scope to items
            }
        }
}
