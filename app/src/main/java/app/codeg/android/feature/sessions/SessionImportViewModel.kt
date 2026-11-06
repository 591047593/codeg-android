package app.codeg.android.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ScanResult
import app.codeg.android.core.model.ScanSession
import app.codeg.android.core.model.ScanSessionStatus
import app.codeg.android.core.model.SelectedSessionKey
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.displayMessage
import app.codeg.android.core.network.isUnsupportedMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 会话导入（fleet M4）: scan → preview/select → import_selected. One device at
 * a time — the scan walks THAT device's local CLI session files — so the sheet
 * starts with a device chip row (preselected from the current scope) and
 * rescans on switch. Same discipline as the M3 fan-out: runCatching + 10s
 * budget, unsupported old servers get a polite "no" instead of an error,
 * generation counter arbitrates races, CancellationException always rethrown.
 */
@HiltViewModel
class SessionImportViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    enum class Phase { LOADING, OK, FAILED, UNSUPPORTED }

    data class UiState(
        val devices: List<ServerProfile> = emptyList(),
        val selectedDeviceId: String? = null,
        val phase: Phase = Phase.LOADING,
        /** Failure description for the FAILED phase. */
        val error: String? = null,
        val scan: ScanResult? = null,
        /** [sessionKey]s — only NEW (or DELETED with the toggle) live here. */
        val selection: Set<String> = emptySet(),
        val includeDeleted: Boolean = false,
        val busy: Boolean = false,
        /** Non-null replaces the picker with the receipt panel. */
        val receipt: Receipt? = null,
        /** True once an import changed conversations — the host refreshes its list. */
        val importedSomething: Boolean = false,
    )

    data class Receipt(
        val imported: Int,
        val updated: Int,
        val restored: Int,
        val skipped: Int,
        val notFound: Int,
        val failed: Int,
        val errors: List<String>,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var generation = 0

    init {
        viewModelScope.launch {
            val profiles = repository.profiles.first().filter { it.visible }
            val preset = repository.selectedId.first()
                ?.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull()?.id
            _ui.update { it.copy(devices = profiles, selectedDeviceId = preset) }
            preset?.let { scanDevice(it) }
        }
    }

    fun selectDevice(serverId: String) {
        if (_ui.value.selectedDeviceId == serverId) return
        _ui.update {
            it.copy(
                selectedDeviceId = serverId,
                phase = Phase.LOADING,
                scan = null,
                selection = emptySet(),
                includeDeleted = false,
                receipt = null,
                error = null,
            )
        }
        scanDevice(serverId)
    }

    fun rescan() {
        val id = _ui.value.selectedDeviceId ?: return
        _ui.update { it.copy(phase = Phase.LOADING, error = null) }
        scanDevice(id)
    }

    fun toggleIncludeDeleted() {
        val on = !_ui.value.includeDeleted
        _ui.update { state ->
            state.copy(
                includeDeleted = on,
                // Turning the toggle off must also drop already-checked deleted
                // rows — the checkboxes would be gone but their keys would ride
                // into the import request (web picker parity).
                selection = if (on) state.selection else state.selection.filterNot { key ->
                    deletedKeys(state).contains(key)
                }.toSet(),
            )
        }
    }

    private fun deletedKeys(state: UiState): Set<String> =
        state.scan?.folders.orEmpty().flatMap { folder ->
            folder.sessions.filter { it.status == ScanSessionStatus.DELETED }.map { sessionKey(it) }
        }.toSet()

    fun toggleSession(session: ScanSession) {
        val selectable = session.status == ScanSessionStatus.NEW ||
            (session.status == ScanSessionStatus.DELETED && _ui.value.includeDeleted)
        if (!selectable) return
        val key = sessionKey(session)
        _ui.update { state ->
            state.copy(
                selection = if (key in state.selection) state.selection - key else state.selection + key,
            )
        }
    }

    fun closeReceipt() { _ui.update { it.copy(receipt = null) } }

    fun importSelected() {
        val state = _ui.value
        if (state.busy || state.selection.isEmpty()) return
        val deviceId = state.selectedDeviceId ?: return
        // Decode the selection keys against the current scan; an UNKNOWN agent
        // must never be sent back to the server (core model contract), and a
        // key the scan no longer holds is stale — drop both.
        val byKey = state.scan?.folders.orEmpty()
            .flatMap { it.sessions }
            .associateBy { sessionKey(it) }
        val selections = state.selection.mapNotNull { key -> byKey[key] }
            .filter { it.agentType != AgentType.UNKNOWN }
            .map { SelectedSessionKey(agentType = it.agentType, externalId = it.externalId) }
        if (selections.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                val profile = repository.profiles.first().firstOrNull { it.id == deviceId }
                val client = profile?.let { repository.client(it) }
                    ?: throw IllegalStateException("Token 缺失，无法连接该设备")
                val result = withTimeoutOrNull(IMPORT_TIMEOUT_MS) { client.importSelectedSessions(selections) }
                    ?: throw java.util.concurrent.TimeoutException("import timeout")
                val changed = result.imported > 0 || result.restored > 0 || result.updated > 0
                _ui.update {
                    it.copy(
                        busy = false,
                        receipt = Receipt(
                            imported = result.imported,
                            updated = result.updated,
                            restored = result.restored,
                            skipped = result.skipped,
                            notFound = result.notFound,
                            failed = result.failed,
                            errors = result.errors,
                        ),
                        selection = emptySet(),
                        importedSomething = it.importedSomething || changed,
                    )
                }
                // The scan is now stale (imported rows flipped status) — re-scan
                // in the background so reopening the picker shows the truth.
                scanDevice(deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        busy = false,
                        error = (e as? ApiError)?.displayMessage() ?: e.message ?: "导入失败",
                    )
                }
            }
        }
    }

    private suspend fun scanDevice(serverId: String) {
        val profile = repository.profiles.first().firstOrNull { it.id == serverId }
        if (profile == null) {
            _ui.update { it.copy(phase = Phase.FAILED, error = "设备不存在") }
            return
        }
        generation += 1
        val gen = generation
        val result = runCatching {
            val client = repository.client(profile)
                ?: throw IllegalStateException("Token 缺失，无法连接该设备")
            withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) { client.scanImportableSessions() }
                ?: throw java.util.concurrent.TimeoutException("scan timeout")
        }
        if (gen != generation) return
        result.fold(
            onSuccess = { scan ->
                _ui.update { state ->
                    state.copy(
                        phase = Phase.OK,
                        error = null,
                        scan = scan,
                        selection = state.selection.ifEmpty {
                            scan.folders.flatMap { it.sessions }
                                .filter { it.status == ScanSessionStatus.NEW }
                                .mapTo(mutableSetOf()) { sessionKey(it) }
                        },
                    )
                }
            },
            onFailure = { e ->
                if (e is CancellationException) throw e
                val unsupported = isUnsupportedMethod(e)
                _ui.update {
                    it.copy(
                        phase = if (unsupported) Phase.UNSUPPORTED else Phase.FAILED,
                        error = (e as? ApiError)?.displayMessage() ?: e.message ?: "扫描失败",
                    )
                }
            },
        )
    }

    companion object {
        private const val IMPORT_TIMEOUT_MS = 30_000L

        /** Selection identity — agent wire name never contains `|`. */
        fun sessionKey(session: ScanSession): String = "${session.agentType.wire}|${session.externalId}"
    }
}
