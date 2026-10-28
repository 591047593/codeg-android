package app.codeg.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ModelProviderInfo
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
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

/** CRUD for custom model providers (OpenAI-compatible endpoints), grouped by agent. */
@HiltViewModel
class ModelProvidersViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ModelProvidersUiState())
    val ui: StateFlow<ModelProvidersUiState> = _ui.asStateFlow()

    private var client: CodegClient? = null

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
        _ui.update { it.copy(loading = true, error = null) }
        return viewModelScope.launch {
            try {
                val profile = activeProfile ?: throw IllegalStateException("No server selected")
                val c = repository.client(profile) ?: throw IllegalStateException("Missing token")
                client = c
                _ui.update { it.copy(loading = false, providers = c.listModelProviders(), error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.displayMessage()) }
            }
        }
    }

    /** Persist a provider; [onSaved] runs only on success so the editor keeps the input on failure. */
    fun save(existing: ModelProviderInfo?, name: String, apiUrl: String, apiKey: String, agentType: AgentType, model: String?, onSaved: () -> Unit = {}) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                if (existing == null) {
                    c.createModelProvider(name.trim(), apiUrl.trim(), apiKey, agentType, model?.trim()?.ifEmpty { null })
                } else {
                    c.updateModelProvider(
                        existing.id,
                        name = name.trim(),
                        apiUrl = apiUrl.trim(),
                        apiKey = apiKey.ifEmpty { null },
                        agentType = agentType,
                        model = model?.trim(),
                    )
                }
                load()
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.displayMessage()) }
            }
        }
    }

    fun delete(provider: ModelProviderInfo) {
        val c = client ?: return
        // Re-insert only the row that failed so a concurrent change is not rolled back too.
        val removedIndex = _ui.value.providers.indexOfFirst { p -> p.id == provider.id }
        _ui.update { it.copy(providers = it.providers.filterNot { p -> p.id == provider.id }) }
        viewModelScope.launch {
            runCatching { c.deleteModelProvider(provider.id) }.onFailure { e ->
                _ui.update { state ->
                    if (state.providers.any { p -> p.id == provider.id }) {
                        state.copy(error = e.displayMessage())
                    } else {
                        state.copy(
                            providers = state.providers.toMutableList().apply {
                                add(removedIndex.coerceIn(0, size), provider)
                            },
                            error = e.displayMessage(),
                        )
                    }
                }
            }
        }
    }

    /** Clear the error shown by the screen's [ErrorBanner]. */
    fun dismissError() = _ui.update { it.copy(error = null) }
}

data class ModelProvidersUiState(
    val providers: List<ModelProviderInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** Supported agent types for model providers (iOS `modelProviderSupported`). */
    val supportedAgents: List<AgentType> = listOf(AgentType.CLAUDE_CODE, AgentType.CODEX, AgentType.GEMINI)
}
