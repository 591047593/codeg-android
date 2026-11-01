package app.codeg.android.feature.activity

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.CodegApplication
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.data.TaskSegment
import app.codeg.android.core.data.buildBoard
import app.codeg.android.core.data.participating
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * One integer per workbench stat card (批v150): running tasks, tasks waiting for
 * the user, and how many in-scope devices answered this round. [attentionPreview]
 * is the same ATTENTION slice the task board would show (same [buildBoard]
 * projection, same sort), capped for the preview rows.
 */
data class WorkbenchStats(
    val runningTasks: Int = 0,
    val attentionTasks: Int = 0,
    val attentionPreview: List<TaskBoardEntry> = emptyList(),
    val onlineDevices: Int = 0,
    val totalDevices: Int = 0,
    /** False until the first fetch round completes: without it the cards would
     *  flash "0 everywhere" while the spinners are still running. */
    val hasLoaded: Boolean = false,
)

/**
 * The 动态 workbench's task-side counterweight to [ActivityViewModel] (批v150):
 * a LIGHT, read-only poll of every in-scope device's task list, projected through
 * the same [buildBoard] the task board uses — the numbers on the workbench can
 * never disagree with the 任务板 tab, they ARE the board's segments.
 *
 * Deliberately separate from [TaskHubViewModel] (which lives behind the 任务板
 * tab and carries detail caches / action machinery): the workbench only feeds
 * counters, so its poll runs at 60s (vs the board's 25s), holds no details, and
 * takes no actions. The two never poll at once — tabs are mutually exclusive and
 * each VM gates on its own active flag.
 */
@HiltViewModel
class WorkbenchViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext appContext: Context,
) : ViewModel() {

    /** Raw per-device slices (ids are per-server) — every device ever fetched in
     *  this VM's lifetime; the projection re-scopes instantly from this cache. */
    private val tasks = MutableStateFlow<List<TaskBoardEntry>>(emptyList())

    /** Per-device round states + hasLoaded (deviceStates re-scoped in the projection). */
    private data class RoundState(
        val deviceStates: List<DeviceLoad> = emptyList(),
        val hasLoaded: Boolean = false,
    )

    private val round = MutableStateFlow(RoundState())

    val stats: StateFlow<WorkbenchStats> =
        combine(
            round,
            tasks,
            repository.profiles,
            repository.selectedId,
            repository.activityAll,
        ) { round, tasks, profiles, selectedId, allMode ->
            val scope = scopeIds(profiles, allMode, selectedId)
            val board = buildBoard(tasks.filter { it.serverId in scope })
            WorkbenchStats(
                runningTasks = board.segments[TaskSegment.RUNNING].orEmpty().size,
                attentionTasks = board.segments[TaskSegment.ATTENTION].orEmpty().size,
                attentionPreview = board.segments[TaskSegment.ATTENTION].orEmpty().take(PREVIEW_MAX),
                onlineDevices = round.deviceStates.count { it.serverId in scope && it.status == DeviceLoadStatus.OK },
                totalDevices = round.deviceStates.count { it.serverId in scope },
                hasLoaded = round.hasLoaded,
            )
        }
            // Upstream (profiles) is a DataStore flow: a read failure must keep the
            // stats ALIVE, not silently dead (same policy as TaskHubViewModel.board).
            .retryWhen { _, attempt ->
                delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .distinctUntilChanged()
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkbenchStats())

    private val foreground = (appContext as CodegApplication).appForeground

    /** Workbench-tab visibility — the poll only runs while the tab is composed. */
    private val _active = MutableStateFlow(false)
    fun setActive(active: Boolean) { _active.value = active }

    private val _refreshTick = MutableStateFlow(0)
    fun refresh() { _refreshTick.value += 1 }

    private var generation = 0
    private var lastSeenTick = -1

    init {
        viewModelScope.launch { pollLoop() }
        // Same trigger as TaskHubViewModel: the aggregate participant set drives
        // the device fan-out; a change re-runs the round immediately.
        repository.profiles
            .map { list -> list.filter { it.visible && it.includeInAll } }
            .distinctUntilChanged()
            .onEach { _refreshTick.value += 1 }
            .retryWhen { _, attempt ->
                delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .launchIn(viewModelScope)
    }

    private fun scopeIds(profiles: List<ServerProfile>, allMode: Boolean, selectedId: String?): Set<String> =
        if (allMode) {
            profiles.filter { it.visible && it.includeInAll }.map { it.id }.toSet()
        } else {
            profiles.filter { it.id == selectedId }.map { it.id }.toSet()
        }

    private fun pollLoop() {
        viewModelScope.launch {
            combine(
                _refreshTick,
                _active,
                foreground,
                repository.selectedId,
                repository.activityAll,
            ) { tick, active, fg, _, _ -> Triple(tick, active, fg) }
                .collectLatest { (tick, active, fg) ->
                    if (tick > 0 && tick != lastSeenTick) {
                        lastSeenTick = tick
                        fetchRound()
                    }
                    when {
                        active && fg -> {
                            if (tick == 0) fetchRound()
                            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                                delay(POLL_INTERVAL_MS)
                                fetchRound()
                            }
                        }
                        tick == 0 -> fetchRound()
                    }
                }
        }
    }

    private suspend fun fetchRound() {
        // Bare init/poll coroutine: a DataStore failure must degrade to "this
        // round failed", never kill the process (v130 class).
        try {
            fetchRoundInner()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.w("WorkbenchViewModel", "round failed")
        }
    }

    private suspend fun fetchRoundInner() {
        val profiles = repository.profiles.first()
        val devices = profiles.filter {
            it.id in scopeIds(profiles, repository.activityAll.value, repository.selectedId.first())
        }
        val ids = devices.map { it.id }.toSet()
        if (devices.isEmpty()) {
            round.value = RoundState(deviceStates = emptyList(), hasLoaded = true)
            // Keep other devices' cached slices (they re-enter scope on a switch);
            // drop only DELETED profiles' slices — those are ghosts forever.
            val known = profiles.map { it.id }.toSet()
            tasks.value = tasks.value.filter { it.serverId in known }
            return
        }
        generation += 1
        val gen = generation
        round.value = round.value.copy(
            deviceStates = round.value.deviceStates.participating(ids).let { kept ->
                devices.map { d ->
                    kept.firstOrNull { it.serverId == d.id }
                        ?: DeviceLoad(d.id, d.name, DeviceLoadStatus.LOADING)
                }
            },
        )
        // A device that left the aggregate must not keep feeding its stale slice
        // into the stats (same ghost rule as TaskHubViewModel).
        tasks.value = tasks.value.filter { it.serverId in ids }
        coroutineScope {
            devices.forEach { profile ->
                launch {
                    val result = runCatching {
                        val client = repository.client(profile)
                            ?: throw IllegalStateException("Token 缺失，无法加载该设备")
                        withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                            client.workTaskList()
                        } ?: throw java.util.concurrent.TimeoutException("device timeout")
                    }
                    if (gen != generation) return@launch
                    result.fold(
                        onSuccess = { list ->
                            round.value = round.value.copy(
                                deviceStates = round.value.deviceStates.map {
                                    if (it.serverId == profile.id) it.copy(status = DeviceLoadStatus.OK, error = null) else it
                                },
                            )
                            tasks.value = tasks.value.filter { e -> e.serverId != profile.id } +
                                (list.orEmpty().map { t -> TaskBoardEntry(profile.id, profile.name, t) })
                        },
                        onFailure = { e ->
                            if (e is CancellationException) throw e
                            round.value = round.value.copy(
                                deviceStates = round.value.deviceStates.map {
                                    if (it.serverId == profile.id) {
                                        it.copy(
                                            status = DeviceLoadStatus.FAILED,
                                            error = (e as? ApiError)?.displayMessage()
                                                ?: e.message ?: "加载失败",
                                        )
                                    } else it,
                                ),
                            )
                        },
                    )
                }
            }
        }
        if (gen == generation) round.value = round.value.copy(hasLoaded = true)
    }

    companion object {
        /** Workbench poll cadence — counters, not a live board (the board tab
         *  polls at 25s); foreground-gated by the poll combine. */
        const val POLL_INTERVAL_MS = 60_000L

        /** 需要处理 preview cap (new mandate §1.3: ≤3 rows). */
        const val PREVIEW_MAX = 3
    }
}
