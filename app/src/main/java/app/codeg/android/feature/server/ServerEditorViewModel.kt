package app.codeg.android.feature.server

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Add/edit form state for a server profile, plus the Test Connection probe. */
@HiltViewModel
class ServerEditorViewModel @Inject constructor(
    /** Needed only to resolve the localized failure strings in [displayMessage]. */
    @ApplicationContext private val appContext: Context,
    private val repository: ServerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editingId: String? = savedStateHandle["serverId"]
    val isEditing: Boolean = editingId != null

    var name by mutableStateOf("")
        private set
    var url by mutableStateOf("")
        private set
    var token by mutableStateOf("")
        private set
    var test by mutableStateOf<TestState>(TestState.Idle)
        private set

    init {
        if (editingId != null) loadExisting(editingId)
    }

    fun onNameChange(value: String) { name = value }
    fun onUrlChange(value: String) { url = value }
    fun onTokenChange(value: String) { token = value }

    /**
     * False only for a URL we could not talk over (an unsupported scheme, or nothing
     * after `://`). A bare `host:port` is accepted — [CodegClient.normalizeBaseUrl]
     * supplies the `http://` prefix.
     */
    val urlValid: Boolean
        get() = CodegClient.isUsableBaseUrl(url)

    val canSave: Boolean
        get() = name.isNotBlank() && url.isNotBlank() && urlValid && token.isNotBlank()

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val profile = repository.profiles.first().firstOrNull { it.id == id } ?: return@launch
            name = profile.name
            url = profile.baseUrl
            token = repository.token(id).orEmpty()
        }
    }

    fun testConnection() {
        if (!urlValid) {
            test = TestState.Failure("Unsupported server URL")
            return
        }
        viewModelScope.launch {
            test = TestState.Testing
            test = try {
                // normalizeBaseUrl also supplies the missing scheme, so testing a bare
                // host:port exercises the same URL the save path will persist.
                val health = repository.transientClient(url, token.trim()).health()
                TestState.Success(health.version)
            } catch (e: Exception) {
                TestState.Failure(e.displayMessage(appContext))
            }
        }
    }

    /** True while a save is in flight; also disables the button (see [saving]). */
    var saving by mutableStateOf(false)
        private set

    var saveError by mutableStateOf<String?>(null)
        private set

    fun save(onDone: () -> Unit) {
        // Guard re-entry: the id is generated inside the coroutine, so two fast taps
        // would mint two ids and persist two profiles with the same name and URL.
        if (saving) return
        saving = true
        saveError = null
        viewModelScope.launch {
            val id = editingId ?: UUID.randomUUID().toString()
            val profile = ServerProfile(
                id = id,
                name = name.trim(),
                baseUrl = CodegClient.normalizeBaseUrl(url),
            )
            try {
                // Both halves can fail: the Keystore encryption of the token
                // (KeyStoreException / InvalidKeyException) and the DataStore write
                // (IOException when the disk is full). Unhandled, this propagated to
                // the default handler and killed the process; swallowed, the screen
                // simply never advanced.
                repository.save(profile, token.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saving = false
                saveError = e.displayMessage(appContext)
                return@launch
            }
            if (editingId == null) repository.select(id)
            saving = false
            onDone()
        }
    }
}

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Success(val version: String) : TestState
    data class Failure(val message: String) : TestState
}
