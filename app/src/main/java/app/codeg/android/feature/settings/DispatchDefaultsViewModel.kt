package app.codeg.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.DispatchTargetLoader
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.datastore.ServerProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DispatchDefaultsUiState(
    /** Visible devices for the device row. */
    val profiles: List<ServerProfile> = emptyList(),
    /** Folder/agent lists for the device the user is currently choosing from. */
    val folders: List<FolderDetail> = emptyList(),
    val agents: List<AgentType> = emptyList(),
    val loading: Boolean = false,
)

/**
 * Backs the 派发默认 section in General settings: pick a device/project/agent
 * once and every dispatch sheet opens with them preselected. Folder/agent lists
 * load through the same [DispatchTargetLoader] the dispatch sheets use, so the
 * choices shown here are exactly the ones the sheet will honor.
 */
@HiltViewModel
class DispatchDefaultsViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val appSettingsStore: AppSettingsStore,
    private val targetLoader: DispatchTargetLoader,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.INITIAL)

    private val _ui = MutableStateFlow(DispatchDefaultsUiState())
    val ui: StateFlow<DispatchDefaultsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                profiles = runCatching {
                    repository.profiles.first().filter { it.visible }
                }.getOrDefault(emptyList()),
            )
        }
    }

    /** Load the folder/agent columns for [serverId] — called when the user opens
     *  the project or agent picker. */
    fun loadTargets(serverId: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val loaded = targetLoader.load(serverId)
            _ui.value = _ui.value.copy(
                folders = loaded.folders,
                agents = loaded.agents,
                loading = loaded.error != null && loaded.folders.isEmpty(),
            )
        }
    }

    fun save(deviceId: String, deviceName: String, folder: FolderDetail?, agent: AgentType?) {
        viewModelScope.launch {
            appSettingsStore.setDispatchDefaults(
                device = deviceId,
                folderId = folder?.id ?: -1,
                folderName = folder?.name.orEmpty(),
                agentWire = agent?.wire.orEmpty(),
            )
        }
    }

    fun saveDeviceOnly(deviceId: String, deviceName: String) {
        viewModelScope.launch {
            val current = settings.value
            val stillValid = current.dispatchDefaultDevice == deviceId
            appSettingsStore.setDispatchDefaults(
                device = deviceId,
                folderId = if (stillValid) current.dispatchDefaultFolderId else -1,
                folderName = if (stillValid) current.dispatchDefaultFolderName else "",
                agentWire = if (stillValid) current.dispatchDefaultAgent else "",
            )
        }
    }

    fun saveFolder(folder: FolderDetail) {
        viewModelScope.launch {
            appSettingsStore.setDispatchDefaults(
                device = settings.value.dispatchDefaultDevice,
                folderId = folder.id,
                folderName = folder.name,
                agentWire = settings.value.dispatchDefaultAgent,
            )
        }
    }

    fun saveAgent(agent: AgentType?) {
        viewModelScope.launch {
            appSettingsStore.setDispatchDefaults(
                device = settings.value.dispatchDefaultDevice,
                folderId = settings.value.dispatchDefaultFolderId,
                folderName = settings.value.dispatchDefaultFolderName,
                agentWire = agent?.wire.orEmpty(),
            )
        }
    }

    fun clear() {
        viewModelScope.launch { appSettingsStore.clearDispatchDefaults() }
    }
}
