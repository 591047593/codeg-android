package app.codeg.android.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.ScanResult
import app.codeg.android.core.model.ScanSession
import app.codeg.android.core.model.ScanSessionStatus
import app.codeg.android.core.model.SelectedSessionKey
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
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
 * rescans on switch. Same discipline as the M3 fan-out: runCatching +
 * generation counter arbitrates races, unsupported old servers get a polite
 * "no" instead of an error, CancellationException always rethrown. Budgets are
 * [Companion.IMPORT_BUDGET_MS] (not the 10s aggregate-probe one): the server
 * walks and parses every local transcript for both calls — minutes on a big
 * library. The sheet calls [onSheetShown] on every open; the VM survives the
 * sheet (backstack-scoped), so each open starts a fresh round.
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
        /** Scan failure (FAILED phase) or the picker's import error row. */
        val error: String? = null,
        val scan: ScanResult? = null,
        /** [sessionKey]s — only selectable rows live here (NEW / opt-in
         *  DELETED / agentType != UNKNOWN), pruned on every scan. */
        val selection: Set<String> = emptySet(),
        val includeDeleted: Boolean = false,
        val busy: Boolean = false,
        /** Non-null replaces the picker with the receipt panel. */
        val receipt: Receipt? = null,
        /** Bumped per import that changed conversations — the host refreshes
         *  its list on each change (a Boolean would miss repeat imports). */
        val importEpoch: Int = 0,
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

    /**
     * The sheet calls this on every open: receipt/selection/toggle are the
     * scratchpad of ONE import round, and the VM outlives the sheet — without
     * the reset a reopen would resurrect the last receipt (or a stale
     * selection) instead of a fresh scan.
     */
    fun onSheetShown() {
        _ui.value = UiState()
        viewModelScope.launch { loadDevicesAndScan() }
    }

    private suspend fun loadDevicesAndScan() {
        // DataStore reads can throw on an unreadable preferences file —
        // ActivityViewModel's identical call carries this guard for exactly
        // that reason; a sheet open must never crash the process.
        val loaded = runCatching {
            repository.profiles.first().filter { it.visible } to repository.selectedId.first()
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            _ui.update { it.copy(phase = Phase.FAILED, error = "读取设备列表失败") }
            return
        }
        val (profiles, selected) = loaded
        val preset = selected
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
        _ui.update { it.copy(devices = profiles, selectedDeviceId = preset) }
        preset?.let { scanDevice(it) }
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
                // into the import request (web picker parity). Only reachable
                // with a non-null scan (the toggle renders inside the picker).
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
        // UNKNOWN agents must never ride back to the server (core contract) —
        // the import request filters them out silently, so they can't even be
        // checked, or the CTA count would overstate what actually gets sent.
        if (session.agentType == AgentType.UNKNOWN) return
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
                val profile = runCatching {
                    repository.profiles.first().firstOrNull { it.id == deviceId }
                }.getOrNull()
                val client = profile?.let { repository.client(it) }
                    ?: throw IllegalStateException("Token 缺失，无法连接该设备")
                val result = withTimeoutOrNull(IMPORT_BUDGET_MS) { client.importSelectedSessions(selections) }
                    ?: throw java.util.concurrent.TimeoutException("import timeout")
                val changed = result.imported > 0 || result.restored > 0 || result.updated > 0
                val receipt = Receipt(
                    imported = result.imported,
                    updated = result.updated,
                    restored = result.restored,
                    skipped = result.skipped,
                    notFound = result.notFound,
                    failed = result.failed,
                    errors = result.errors,
                )
                // The user may have switched devices while the import ran.
                // The receipt belongs to THAT device — landing it on another
                // device's picker is worse than dropping it (switching back
                // rescans and shows the truth), so only the busy flag crosses.
                var switchedAway = false
                _ui.update {
                    if (it.selectedDeviceId == deviceId) {
                        it.copy(
                            busy = false,
                            error = null,
                            receipt = receipt,
                            selection = emptySet(),
                            importEpoch = if (changed) it.importEpoch + 1 else it.importEpoch,
                        )
                    } else {
                        switchedAway = true
                        it.copy(busy = false)
                    }
                }
                // The scan is now stale (imported rows flipped status) — re-scan
                // in the background so reopening the picker shows the truth.
                if (!switchedAway) scanDevice(deviceId)
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

    /** UI-called (device chips, retry, post-import refresh): bumps the
     *  generation synchronously — "last user action wins" must not depend on
     *  where each launched round happens to suspend first — then launches. */
    private fun scanDevice(serverId: String) {
        val gen = ++generation
        viewModelScope.launch { scanDeviceInner(serverId, gen) }
    }

    private suspend fun scanDeviceInner(serverId: String, gen: Int) {
        val profile = runCatching {
            repository.profiles.first().firstOrNull { it.id == serverId }
        }.getOrNull()
        if (profile == null) {
            if (gen != generation) return
            _ui.update { it.copy(phase = Phase.FAILED, error = "设备不存在") }
            return
        }
        val result = runCatching {
            val client = repository.client(profile)
                ?: throw IllegalStateException("Token 缺失，无法连接该设备")
            withTimeoutOrNull(IMPORT_BUDGET_MS) { client.scanImportableSessions() }
                ?: throw java.util.concurrent.TimeoutException("scan timeout")
        }
        // Both guards: a superseded round must not land, and a round for a
        // device the user already left must not paint its rows under another
        // device's chip (the import path can legitimately outlive a switch).
        if (gen != generation || _ui.value.selectedDeviceId != serverId) return
        result.fold(
            onSuccess = { scan ->
                _ui.update { state ->
                    // Selection hygiene: keys whose rows flipped status (or
                    // whose agent is UNKNOWN) must not linger as checked-but-
                    // disabled ghosts; an empty selection (first open / just
                    // imported) preselects all NEW, web-picker parity.
                    val legalKeys = scan.folders.asSequence()
                        .flatMap { it.sessions.asSequence() }
                        .filter { it.agentType != AgentType.UNKNOWN }
                        .filter {
                            it.status == ScanSessionStatus.NEW ||
                                (it.status == ScanSessionStatus.DELETED && state.includeDeleted)
                        }
                        .mapTo(mutableSetOf()) { sessionKey(it) }
                    state.copy(
                        phase = Phase.OK,
                        error = null,
                        scan = scan,
                        selection = if (state.selection.isEmpty()) {
                            scan.folders.flatMap { it.sessions }
                                .filter {
                                    it.status == ScanSessionStatus.NEW &&
                                        it.agentType != AgentType.UNKNOWN
                                }
                                .mapTo(mutableSetOf()) { sessionKey(it) }
                        } else {
                            state.selection.intersect(legalKeys)
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
        /** scan 与 import 服务端都要全量解析本机 transcript——大库（一年以上
         *  CLI 历史）是分钟级，10s 聚合探针预算在这里必死。HTTP 层同档放宽见
         *  [CodegClient.IMPORT_HTTP_TIMEOUT_MS]；这里多留 10s 余量，让 HTTP
         *  层的真实超时错误先浮出来而不是笼统的 budget timeout。 */
        private val IMPORT_BUDGET_MS = CodegClient.IMPORT_HTTP_TIMEOUT_MS + 10_000L

        /** Selection identity — agent wire name never contains `|`. */
        fun sessionKey(session: ScanSession): String = "${session.agentType.wire}|${session.externalId}"
    }
}
