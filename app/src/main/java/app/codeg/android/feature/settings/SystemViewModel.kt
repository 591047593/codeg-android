package app.codeg.android.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AppUpdateCheckResult
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

/** System settings: HTTP proxy + server update check. */
@HiltViewModel
class SystemViewModel @Inject constructor(
    /** Needed only to resolve the localized failure strings in [displayMessage]. */
    @ApplicationContext private val appContext: Context,
    private val repository: ServerRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(SystemUiState())
    val ui: StateFlow<SystemUiState> = _ui.asStateFlow()

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
                val proxy = c.getSystemProxySettings()
                _ui.update { it.copy(loading = false, proxyEnabled = proxy.enabled, proxyUrl = proxy.proxyUrl ?: "", error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.displayMessage(appContext)) }
            }
        }
    }

    fun setProxyEnabled(enabled: Boolean) = _ui.update { it.copy(proxyEnabled = enabled) }
    fun onProxyUrl(url: String) = _ui.update { it.copy(proxyUrl = url) }

    fun saveProxy() {
        val c = client ?: return
        val s = _ui.value
        _ui.update { it.copy(savingProxy = true) }
        viewModelScope.launch {
            try {
                c.updateSystemProxySettings(s.proxyEnabled, s.proxyUrl.trim().ifEmpty { null })
                _ui.update { it.copy(savingProxy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(savingProxy = false, error = e.displayMessage(appContext)) }
            }
        }
    }

    fun checkUpdate() {
        val c = client ?: return
        _ui.update { it.copy(checking = true, updateResult = null) }
        viewModelScope.launch {
            try {
                _ui.update { it.copy(checking = false, updateResult = c.checkAppUpdate()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(checking = false, error = e.displayMessage(appContext)) }
            }
        }
    }
}

data class SystemUiState(
    val loading: Boolean = false,
    val proxyEnabled: Boolean = false,
    val proxyUrl: String = "",
    val savingProxy: Boolean = false,
    val checking: Boolean = false,
    val updateResult: AppUpdateCheckResult? = null,
    val error: String? = null,
)
