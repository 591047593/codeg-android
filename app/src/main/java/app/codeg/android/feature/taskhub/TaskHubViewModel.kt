package app.codeg.android.feature.taskhub

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.CodegApplication
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.data.TaskColumn
import app.codeg.android.core.data.buildBoard
import app.codeg.android.core.data.participating
import app.codeg.android.core.data.retryableIds
import app.codeg.android.core.data.retrying
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.WorkTaskChangedFile
import app.codeg.android.core.model.WorkTaskEvent
import app.codeg.android.core.model.WorkTaskInfo
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject

/**
 * The task hub (batch v139): the Codeg native work-task board aggregated across
 * every participating device. Pure projection — tasks, statuses and their
 * transitions live on the servers; this VM only fetches, groups and relays
 * actions. One offline device degrades its own slice (banner + retry), never
 * the board (v137 mechanism).
 *
 * Realtime: a 25s poll + pull-to-refresh. The server's `task://changed` push
 * carries only the task id (no payload), so it would still need an HTTP
 * round-trip per event; the poll already covers that at feed latency. A
 * WebSocket-driven board is tracked in KNOWN-ISSUES as a future upgrade.
 */
@HiltViewModel
class TaskHubViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext appContext: Context,
) : ViewModel() {

    data class TaskDetail(
        val events: List<WorkTaskEvent>? = null,
        val changedFiles: List<WorkTaskChangedFile>? = null,
        val diff: String? = null,
        val diffLoading: Boolean = false,
        val diffError: String? = null,
    )

    data class UiState(
        val deviceStates: List<DeviceLoad> = emptyList(),
        val tasks: List<TaskBoardEntry> = emptyList(),
        val lastRefreshed: Instant? = null,
        val showArchived: Boolean = false,
        /** null = all devices. */
        val deviceFilter: String? = null,
        /** serverId -> (taskId -> detail); fetched lazily when a task expands. */
        val details: Map<String, Map<Int, TaskDetail>> = emptyMap(),
        /** serverId:taskId of an in-flight action, for row spinners. */
        val busyKey: String? = null,
        val actionError: String? = null,
    ) {
        val failedDevices: Int get() = deviceStates.count { it.status == DeviceLoadStatus.FAILED }
        val archivedHidden: Int
            get() = tasks.count { it.task.archivedAt != null }.takeIf { !showArchived } ?: 0
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val foreground = (appContext as CodegApplication).appForeground

    private val _refreshTick = MutableStateFlow(0)
    private var generation = 0

    /** Board columns, recomputed only when tasks/filters change (off the poll's spinner). */
    val board: StateFlow<Map<TaskColumn, List<TaskBoardEntry>>> =
        combine(_ui, repository.profiles) { state, profiles ->
            val names = profiles.associate { it.id to it.name }
            val rows = state.tasks
                .asSequence()
                .filter { state.showArchived || it.task.archivedAt == null }
                .filter { state.deviceFilter == null || it.serverId == state.deviceFilter }
                .map { it.copy(serverName = names[it.serverId] ?: it.serverName) }
                .toList()
            buildBoard(rows)
        }
            // Upstream (profiles) is a DataStore flow: a read failure must keep
            // the last-good board, not kill the sharing coroutine (crash).
            .catch { /* keep the last-good board */ }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch { pollLoop() }
        repository.profiles
            .map { list -> list.filter { it.visible && it.includeInAll } }
            .distinctUntilChanged()
            .onEach { _refreshTick.value += 1 }
            // DataStore failure on the profiles read must not kill the process
            // (bare init coroutine = crash from the Activity tab, v130 class).
            // retryWhen (not .catch) so the collection survives and the next
            // successful profiles emission re-arms the tick.
            .retryWhen { _, attempt ->
                kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .launchIn(viewModelScope)
    }

    fun refresh() { _refreshTick.value += 1 }

    fun setShowArchived(show: Boolean) { _ui.update { it.copy(showArchived = show) } }

    fun setDeviceFilter(serverId: String?) { _ui.update { it.copy(deviceFilter = serverId) } }

    fun retryFailedDevices() {
        val retryIds = _ui.value.deviceStates.retryableIds()
        if (retryIds.isEmpty()) return
        viewModelScope.launch {
            try {
                _ui.update { it.copy(deviceStates = it.deviceStates.retrying(retryIds)) }
                fetchDevices(retryIds)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Bare DataStore read in fetchDevices' targets branch must not
                // crash the process; the devices keep their FAILED state.
            }
        }
    }

    /** Task tab visibility — the poll only runs while the board is on screen. */
    private val _active = MutableStateFlow(false)
    fun setActive(active: Boolean) { _active.value = active }

    private fun pollLoop() {
        viewModelScope.launch {
            combine(_refreshTick, _active, foreground) { tick, active, fg -> Triple(tick, active, fg) }
                .collectLatest { (tick, active, fg) ->
                    // Pull-to-refresh / device-set change: refetch now (any new tick
                    // cancels this collector, superseding a round in flight)...
                    if (tick > 0) fetchAll()
                    when {
                        // ...then ALWAYS resume the foreground poll (a one-shot
                        // `tick>0 -> return` would kill the 25s loop forever, v139 H1).
                        active && fg -> {
                            if (tick == 0) fetchAll()
                            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                                delay(25_000)
                                fetchAll()
                            }
                        }
                        // Board hidden or app backgrounded: cold start does one fetch, no poll.
                        tick == 0 -> fetchAll()
                    }
                }
        }
    }

    private suspend fun fetchAll() {
        // Top-level guard: this runs from bare init/poll coroutines, and a
        // DataStore failure on the profiles read must degrade to "this round
        // failed" — never kill the process from the Activity tab's VM.
        try {
            fetchAllInner()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Profiles unreadable: skip this round (poll retries in ≤25s) —
            // never kill the process from the Activity tab's VM.
            android.util.Log.w("TaskHubViewModel", "fetchAll round failed")
        }
    }

    private suspend fun fetchAllInner() {
        val devices = repository.profiles.first()
            .filter { it.visible && it.includeInAll }
        if (devices.isEmpty()) {
            _ui.update { it.copy(deviceStates = emptyList(), tasks = emptyList(), lastRefreshed = Instant.now()) }
            return
        }
        generation += 1
        val gen = generation
        _ui.update { state ->
            val ids = devices.map { it.id }.toSet()
            state.copy(
                deviceStates = state.deviceStates.participating(ids).let { kept ->
                    devices.map { d ->
                        kept.firstOrNull { it.serverId == d.id }
                            ?: DeviceLoad(d.id, d.name, DeviceLoadStatus.LOADING)
                    }
                },
                // A deleted or "skipped in All" device must not leave ghost rows.
                tasks = state.tasks.filter { it.serverId in ids },
            )
        }
        fetchDevices(devices.map { it.id }.toSet(), devices, gen)
        if (gen == generation) _ui.update { it.copy(lastRefreshed = Instant.now()) }
    }

    private suspend fun fetchDevices(ids: Set<String>, devices: List<ServerProfile>? = null, gen: Int = generation) {
        val targets = devices ?: repository.profiles.first().filter { it.id in ids }
        coroutineScope {
            targets.filter { it.id in ids }.forEach { profile ->
                launch {
                    val result = runCatching {
                        // A null here is a TIMEOUT, not "zero tasks" — collapsing the two
                        // would mark a slow device healthy and wipe its rows (v139 H3).
                        withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                            repository.client(profile)?.workTaskList()
                        } ?: throw java.util.concurrent.TimeoutException("device timeout")
                    }
                    if (gen != generation) return@launch
                    result.fold(
                        onSuccess = { tasks ->
                            _ui.update { state ->
                                state.copy(
                                    deviceStates = state.deviceStates.map {
                                        if (it.serverId == profile.id) {
                                            it.copy(status = DeviceLoadStatus.OK, error = null)
                                        } else it
                                    },
                                    tasks = state.tasks.filter { e -> e.serverId != profile.id } +
                                        (tasks.orEmpty().map { t -> TaskBoardEntry(profile.id, profile.name, t) }),
                                )
                            }
                        },
                        onFailure = { e ->
                            if (e is CancellationException) throw e
                            // Failure keeps the device's last slice on screen (stale rows
                            // plus the banner) instead of silently emptying it (v137 rule).
                            _ui.update { state ->
                                state.copy(
                                    deviceStates = state.deviceStates.map {
                                        if (it.serverId == profile.id) {
                                            it.copy(
                                                status = DeviceLoadStatus.FAILED,
                                                error = (e as? ApiError)?.displayMessage()
                                                    ?: e.message ?: "加载失败",
                                            )
                                        } else it
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    // region Actions — every one goes straight to the owning server, then refreshes
    // that device's slice. Server errors surface verbatim in [UiState.actionError].

    private fun key(serverId: String, taskId: Int) = "$serverId:$taskId"

    private suspend fun deviceById(serverId: String): ServerProfile? =
        repository.profiles.first().firstOrNull { it.id == serverId }

    private fun post(serverId: String, taskId: Int, block: suspend (CodegClient) -> Unit) {
        if (_ui.value.busyKey != null) return
        // Reserve the slot synchronously (main thread): setting it inside the
        // coroutine would let a second tap slip past the gate before launch runs
        // and double-submit merge/cancel (v139 H2).
        _ui.update { it.copy(busyKey = key(serverId, taskId), actionError = null) }
        viewModelScope.launch {
            try {
                val profile = deviceById(serverId) ?: error("设备不存在")
                val client = repository.client(profile) ?: error("Token 缺失")
                block(client)
                // The action moved the task (status/列/events); the cached detail
                // is a pre-action snapshot and loadDetail's existing!=null check
                // would keep serving it — drop it so the next expand refetches.
                _ui.update { state ->
                    state.copy(details = state.details + (serverId to (state.details[serverId].orEmpty() - taskId)))
                }
                fetchDevices(setOf(serverId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    it.copy(actionError = (e as? ApiError)?.displayMessage() ?: e.message ?: "操作失败")
                }
            } finally {
                _ui.update { it.copy(busyKey = null) }
            }
        }
    }

    fun start(serverId: String, taskId: Int) = post(serverId, taskId) { it.workTaskStart(taskId) }

    fun cancel(serverId: String, taskId: Int) = post(serverId, taskId) { it.workTaskCancel(taskId) }

    fun retry(serverId: String, taskId: Int) = post(serverId, taskId) { it.workTaskRetry(taskId) }

    fun requeue(serverId: String, taskId: Int) = post(serverId, taskId) { it.workTaskRequeue(taskId) }

    fun merge(serverId: String, taskId: Int, message: String? = null) =
        post(serverId, taskId) { it.workTaskMerge(taskId, message) }

    fun complete(serverId: String, taskId: Int) = post(serverId, taskId) { it.workTaskComplete(taskId) }

    // endregion

    /** Expand a task: events + changed files load once; diff loads on demand. */
    fun loadDetail(serverId: String, taskId: Int, withDiff: Boolean = false) {
        viewModelScope.launch {
            try {
                val profile = deviceById(serverId) ?: return@launch
                val client = repository.client(profile) ?: return@launch
                val existing = _ui.value.details[serverId]?.get(taskId)
                if (withDiff && existing?.diffLoading != true && existing?.diff == null && existing?.diffError == null) {
                    _ui.update { setDiffLoading(it, serverId, taskId, true) }
                    runCatching { client.workTaskDiff(taskId) }
                        .onSuccess { text -> _ui.update { setDiff(it, serverId, taskId, text, null) } }
                        .onFailure { e -> _ui.update { setDiff(it, serverId, taskId, null, e.message ?: "Diff 加载失败") } }
                }
                if (existing == null) {
                    val events = runCatching { client.workTaskEvents(taskId) }.getOrNull()
                    val files = runCatching { client.workTaskChangedFiles(taskId) }.getOrNull()
                    // Both null = the loads failed, not "no data": leave `details`
                    // unwritten so the next expand retries instead of caching a lie (v139 M3).
                    if (events == null && files == null) return@launch
                    _ui.update { state ->
                        state.copy(
                            details = state.details +
                                (serverId to (state.details[serverId].orEmpty() +
                                    (taskId to (state.details[serverId]?.get(taskId) ?: TaskDetail()).copy(
                                        events = events,
                                        changedFiles = files,
                                    )))),
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // detail loads are best-effort; the board stays usable
            }
        }
    }

    private fun setDiffLoading(state: UiState, serverId: String, taskId: Int, loading: Boolean): UiState =
        mutateDetail(state, serverId, taskId) { it.copy(diffLoading = loading) }

    private fun setDiff(state: UiState, serverId: String, taskId: Int, text: String?, error: String?): UiState =
        mutateDetail(state, serverId, taskId) { it.copy(diff = text, diffError = error, diffLoading = false) }

    private inline fun mutateDetail(
        state: UiState,
        serverId: String,
        taskId: Int,
        transform: (TaskDetail) -> TaskDetail,
    ): UiState {
        val current = state.details[serverId]?.get(taskId) ?: TaskDetail()
        return state.copy(
            details = state.details +
                (serverId to (state.details[serverId].orEmpty() + (taskId to transform(current)))),
        )
    }

    fun clearActionError() { _ui.update { it.copy(actionError = null) } }
}

private inline fun MutableStateFlow<TaskHubViewModel.UiState>.update(transform: (TaskHubViewModel.UiState) -> TaskHubViewModel.UiState) {
    value = transform(value)
}
