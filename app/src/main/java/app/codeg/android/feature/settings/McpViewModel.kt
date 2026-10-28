package app.codeg.android.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.LocalMcpServer
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** CRUD for local MCP servers (free-form JSON spec + per-app enablement). */
@HiltViewModel
class McpViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(McpUiState())
    val ui: StateFlow<McpUiState> = _ui.asStateFlow()

    private var client: CodegClient? = null
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

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
                _ui.update { it.copy(loading = false, servers = c.mcpScanLocal().sortedBy { s -> s.id.lowercase() }, error = null) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.displayMessage(appContext)) }
            }
        }
    }

    /** Returns an error string (e.g. invalid JSON) or null on success. */
    fun save(originalId: String?, serverId: String, specText: String, apps: List<String>, onResult: (String?) -> Unit) {
        val c = client ?: return onResult(appContext.getString(R.string.server_none_selected))
        val spec = runCatching { lenient.parseToJsonElement(specText) }.getOrNull()
            ?: return onResult(appContext.getString(R.string.mcp_spec_invalid_json))
        val id = serverId.trim()
        // The id is the primary key, so renaming is really "create the new key, then
        // delete the old one". Refuse an id that belongs to a *different* server rather
        // than upserting over it and then deleting the original — that silently replaced
        // an unrelated server's configuration.
        if (originalId != null && id != originalId && _ui.value.servers.any { it.id == id }) {
            return onResult(appContext.getString(R.string.mcp_id_taken))
        }
        viewModelScope.launch {
            try {
                c.mcpUpsertLocalServer(id, spec, apps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch onResult(e.displayMessage(appContext))
            }
            // The upsert landed; a failed cleanup after a rename leaves a duplicate but
            // must not be reported as success — say so instead of silently ignoring it.
            if (originalId != null && originalId != id) {
                val cleanup = runCatching { c.mcpRemoveServer(originalId) }
                if (cleanup.isFailure) {
                    load()
                    return@launch onResult(
                        appContext.getString(
                            R.string.mcp_rename_incomplete,
                            originalId,
                            cleanup.exceptionOrNull().displayMessage(appContext).orEmpty(),
                        ),
                    )
                }
            }
            load()
            onResult(null)
        }
    }

    fun delete(server: LocalMcpServer) {
        val c = client ?: return
        // Re-insert only the row that failed so a concurrent change is not rolled back too.
        val removedIndex = _ui.value.servers.indexOfFirst { it.id == server.id }
        _ui.update { it.copy(servers = it.servers.filterNot { s -> s.id == server.id }) }
        viewModelScope.launch {
            runCatching { c.mcpRemoveServer(server.id) }.onFailure { e ->
                _ui.update { state ->
                    if (state.servers.any { it.id == server.id }) {
                        state.copy(error = e.displayMessage(appContext))
                    } else {
                        state.copy(
                            servers = state.servers.toMutableList().apply {
                                add(removedIndex.coerceIn(0, size), server)
                            },
                            error = e.displayMessage(appContext),
                        )
                    }
                }
            }
        }
    }

    /** Clear the error shown by the screen's [ErrorBanner]. */
    fun dismissError() = _ui.update { it.copy(error = null) }
}

data class McpUiState(
    val servers: List<LocalMcpServer> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
