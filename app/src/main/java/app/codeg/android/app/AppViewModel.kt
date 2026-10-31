package app.codeg.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.ServerProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state: appearance settings (drives the theme) and the saved servers
 * + selection. Owns server-selection / deletion actions.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    appSettingsStore: AppSettingsStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** null = not loaded yet (show a splash); non-null = loaded (possibly empty). */
    val servers: StateFlow<List<ServerProfile>?> = serverRepository.profiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedProfile: StateFlow<ServerProfile?> = serverRepository.selectedProfile
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Both of these write to DataStore (and, for delete, drop a Keystore entry), any of
    // which can throw on a full disk or a broken keystore. Without the guard the
    // exception reaches the default handler and takes the process down — from a tap on a
    // list row.
    fun selectServer(id: String) {
        viewModelScope.launch { runCatching { serverRepository.select(id) } }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch { runCatching { serverRepository.delete(id) } }
    }
}
