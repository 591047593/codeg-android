package app.codeg.android.feature.taskhub

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.CodegApplication
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.DeviceLoad
import app.codeg.android.core.data.DeviceLoadStatus
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.data.TaskBoardData
import app.codeg.android.core.data.TaskBoardEntry
import app.codeg.android.core.data.TaskSegment
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import java.time.Instant
import javax.inject.Inject

/**
 * The task hub (ui重构 阶段2+3): the Codeg native work-task board projected onto
 * the four web-parity segments, scoped by the GLOBAL device selection (the
 * global bar in [app.codeg.android.core.designsystem.component.GlobalDeviceBar]
 * — this page renders no second device row) plus its own folder/search/filter
 * toolbar. Pure projection — tasks, statuses and their transitions live on the
 * servers; this VM only fetches, groups and relays actions. One offline device
 * degrades its own slice (banner + retry), never the board (v137 mechanism).
 *
 * Realtime: a 25s poll + pull-to-refresh; switching the global device re-runs
 * the round immediately (selectedId/tasksAll feed the poll combine).
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
        /** Set when the detail loads failed — the body shows it with a retry. */
        val loadError: String? = null,
    )

    data class UiState(
        val deviceStates: List<DeviceLoad> = emptyList(),
        val tasks: List<TaskBoardEntry> = emptyList(),
        val lastRefreshed: Instant? = null,
        /** Web-parity board filters (web defaults: canceled shown, archived not). */
        val showCanceled: Boolean = true,
        val showArchived: Boolean = false,
        /** Folder filter by NAME (folder ids are per-server, names travel). */
        val folderFilter: String? = null,
        /** serverId -> (folderId -> folder name), best-effort for filter + cards. */
        val folderNames: Map<String, Map<Int, String>> = emptyMap(),
        val searchQuery: String = "",
        /** serverId -> (taskId -> detail); fetched lazily when a task expands. */
        val details: Map<String, Map<Int, TaskDetail>> = emptyMap(),
        /** serverId:taskId of an in-flight action, for row spinners. */
        val busyKey: String? = null,
        val actionError: String? = null,
        /** Non-fatal action outcome worth telling (merge queued behind the
         *  folder slot) — styled as info, cleared with the next action. */
        val actionNote: String? = null,
        /** False until the first fetch round completes: without it the board
         * flashes "no tasks" while the spinners are still running. */
        val hasLoaded: Boolean = false,
    ) {
        val failedDevices: Int get() = deviceStates.count { it.status == DeviceLoadStatus.FAILED }
        val archivedHidden: Int
            get() = tasks.count { it.task.isArchivedLike }.takeIf { !showArchived } ?: 0
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val foreground = (appContext as CodegApplication).appForeground

    private val _refreshTick = MutableStateFlow(0)
    private var generation = 0
    /** The tick already fetched: combine re-emits on every active/foreground
     * toggle, and without this each one would redo the tick>0 refetch forever. */
    private var lastSeenTick = -1
    /** Bumped by post(): in-flight loadDetail rounds from before an action must
     * not repopulate the dropped (pre-action) detail afterwards. */
    private var detailEpoch = 0
    /** Folder lists load once per device (they change rarely); refreshed on
     * pull-to-refresh via [refreshFolders]. */
    private var foldersLoadedFor = setOf<String>()

    /**
     * Board projection: global device scope + folder/search/filters → segments.
     * Counts ignore the search query so the segment bar stays a stable map of
     * what exists while typing.
     */
    val board: StateFlow<TaskBoardData> =
        combine(
            _ui,
            repository.profiles,
            repository.selectedId,
            repository.tasksAll,
        ) { state, profiles, selectedId, allMode ->
            val names = profiles.associate { it.id to it.name }
            val scopeIds = scopeIds(profiles, allMode, selectedId)
            val inScope = state.tasks.asSequence()
                .filter { it.serverId in scopeIds }
                .map { it.copy(serverName = names[it.serverId] ?: it.serverName) }
                .toList()
            val folderMatch: (TaskBoardEntry) -> Boolean = { entry ->
                state.folderFilter == null ||
                    state.folderNames[entry.serverId]?.get(entry.task.folderId) == state.folderFilter
            }
            val searchMatch: (TaskBoardEntry) -> Boolean = { entry ->
                val q = state.searchQuery.trim()
                q.isEmpty() ||
                    entry.task.title.contains(q, ignoreCase = true) ||
                    entry.task.resultSummary?.contains(q, ignoreCase = true) == true
            }
            val base = inScope.filter { folderMatch(it) && boardFilter(it, state) }
            val counts = buildBoard(base, state.showCanceled, state.showArchived).counts
            val searchable = if (state.searchQuery.isBlank()) base else base.filter(searchMatch)
            buildBoard(searchable, state.showCanceled, state.showArchived).copy(counts = counts)
        }
            // Upstream (profiles) is a DataStore flow: a read failure must keep
            // the board ALIVE, not silently dead. `.catch` would terminate the
            // sharing coroutine forever (board frozen until VM recreation);
            // retryWhen re-subscribes so the next good emission repaints.
            .retryWhen { _, attempt ->
                kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .distinctUntilChanged()
            // Sorting/filtering a few thousand rows belongs off the main thread:
            // every 25s poll emission and every keystroke re-runs this block.
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskBoardData())

    /** Folder names present on the currently scoped devices, sorted — the picker. */
    val folderOptions: StateFlow<List<String>> =
        combine(_ui, repository.profiles, repository.selectedId, repository.tasksAll) { state, profiles, selectedId, allMode ->
            val ids = scopeIds(profiles, allMode, selectedId)
            ids.flatMap { state.folderNames[it]?.values.orEmpty() }
                .distinct()
                .sorted()
        }
            .retryWhen { _, attempt ->
                kotlinx.coroutines.delay(if (attempt < 3) 1_000 else 5_000)
                true
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun scopeIds(profiles: List<ServerProfile>, allMode: Boolean, selectedId: String?): Set<String> =
        if (allMode) {
            profiles.filter { it.visible && it.includeInAll }.map { it.id }.toSet()
        } else {
            // Single-device mode follows the global bar; a selected-but-hidden
            // device is still fetchable (the bar only lists visible ones).
            profiles.filter { it.id == selectedId }.map { it.id }.toSet()
        }

    private fun boardFilter(entry: TaskBoardEntry, state: UiState): Boolean {
        val task = entry.task
        if (task.isArchivedLike && !state.showArchived) return false
        if ((task.status == "canceled" || task.status == "cancelled") && !state.showCanceled) return false
        return true
    }

    /**
     * Task tab visibility — the poll only runs while the board is on screen.
     * MUST stay above the init block: `viewModelScope.launch` runs the
     * coroutine body synchronously on Dispatchers.Main.immediate during
     * construction, and pollLoop()'s combine collects this flow — a
     * declaration below init is still null there (the v33 任务页 NPE, stack:
     * TaskHubViewModel.<init> → combineInternal → Flow.collect on null).
     */
    private val _active = MutableStateFlow(false)
    fun setActive(active: Boolean) { _active.value = active }

    /** 批v152: one-shot segment jump from the workbench cards / preview — the
     *  board reads then clears it so a plain tab return keeps the user's choice. */
    fun consumeSegmentRequest(): TaskSegment? = repository.consumeTaskSegment()

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

    fun setShowCanceled(show: Boolean) { _ui.update { it.copy(showCanceled = show) } }

    fun setShowArchived(show: Boolean) { _ui.update { it.copy(showArchived = show) } }

    fun setFolderFilter(name: String?) { _ui.update { it.copy(folderFilter = name) } }

    fun setSearchQuery(query: String) { _ui.update { it.copy(searchQuery = query) } }

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

    private fun pollLoop() {
        viewModelScope.launch {
            // Global device scope feeds the poll: switching the bar re-runs the
            // round immediately (collectLatest cancels any round in flight).
            combine(
                _refreshTick,
                _active,
                foreground,
                repository.selectedId,
                repository.tasksAll,
            ) { tick, active, fg, _, _ -> PollInputs(tick, active, fg) }
                .collectLatest { input ->
                    // Pull-to-refresh / device-set change: refetch now (any new tick
                    // cancels this collector, superseding a round in flight)...
                    if (input.tick > 0 && input.tick != lastSeenTick) {
                        lastSeenTick = input.tick
                        fetchAll()
                    }
                    when {
                        // ...then ALWAYS resume the foreground poll (a one-shot
                        // `tick>0 -> return` would kill the 25s loop forever, v139 H1).
                        input.active && input.fg -> {
                            if (input.tick == 0) fetchAll()
                            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                                delay(25_000)
                                fetchAll()
                            }
                        }
                        // Board hidden or app backgrounded: cold start does one fetch, no poll.
                        input.tick == 0 -> fetchAll()
                    }
                }
        }
    }

    private data class PollInputs(val tick: Int, val active: Boolean, val fg: Boolean)

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
        val profiles = repository.profiles.first()
        val devices = profiles.filter { it.id in scopeIds(profiles, repository.tasksAll.value, repository.selectedId.first()) }
        if (devices.isEmpty()) {
            _ui.update { it.copy(deviceStates = emptyList(), tasks = emptyList(), lastRefreshed = Instant.now(), hasLoaded = true) }
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
                // A deleted or "skipped in All" device must not leave ghost rows
                // (rows, and the detail cache that back-expansion would serve).
                tasks = state.tasks.filter { it.serverId in ids },
                details = state.details.filterKeys { it in ids },
            )
        }
        loadFolderNames(devices)
        fetchDevices(devices.map { it.id }.toSet(), devices, gen)
        if (gen == generation) _ui.update { it.copy(lastRefreshed = Instant.now(), hasLoaded = true) }
    }

    /** Best-effort per-device folder lists (names for the filter + cards). */
    private fun loadFolderNames(devices: List<ServerProfile>) {
        val todo = devices.filter { it.id !in foldersLoadedFor }
        if (todo.isEmpty()) return
        foldersLoadedFor += todo.map { it.id }
        viewModelScope.launch {
            coroutineScope {
                todo.forEach { profile ->
                    launch {
                        val folders = runCatching {
                            repository.client(profile)?.listFolders().orEmpty()
                        }.getOrDefault(emptyList())
                            // 批v149：worktree 子目录不进 folderNames——任务的 folder_id
                            // 恒为项目根（服务端 attach_worktree 写的是独立的
                            // worktree_folder_id 列），worktree 名字只会污染过滤选择器。
                            .filter { it.parentId == null }
                        if (folders.isNotEmpty()) {
                            _ui.update { state ->
                                state.copy(
                                    folderNames = state.folderNames +
                                        (profile.id to folders.associate { it.id to it.name }),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Pull-to-refresh also reloads folder lists (a new project should appear). */
    fun refreshFolders() {
        foldersLoadedFor = emptySet()
        viewModelScope.launch {
            runCatching { loadFolderNames(repository.profiles.first().filter { it.id in _ui.value.deviceStates.map { d -> d.serverId } }) }
        }
    }

    private suspend fun fetchDevices(ids: Set<String>, devices: List<ServerProfile>? = null, gen: Int = generation) {
        val targets = devices ?: repository.profiles.first().filter { it.id in ids }
        coroutineScope {
            targets.filter { it.id in ids }.forEach { profile ->
                launch {
                    val result = runCatching {
                        // A null here is a TIMEOUT, not "zero tasks" — collapsing the two
                        // would mark a slow device healthy and wipe its rows (v139 H3).
                        val client = repository.client(profile)
                            ?: throw IllegalStateException("Token 缺失，无法加载该设备")
                        withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) {
                            client.workTaskList()
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
        _ui.update { it.copy(busyKey = key(serverId, taskId), actionError = null, actionNote = null) }
        detailEpoch += 1
        val epoch = detailEpoch
        viewModelScope.launch {
            try {
                val profile = deviceById(serverId) ?: error("设备不存在")
                val client = repository.client(profile) ?: error("Token 缺失")
                block(client)
                // The action moved the task (status/列/events); the cached detail
                // is a pre-action snapshot and loadDetail's existing!=null check
                // would keep serving it — drop it so the next expand refetches.
                _ui.update { state ->
                    if (!state.details.containsKey(serverId)) state
                    else state.copy(details = state.details + (serverId to (state.details[serverId].orEmpty() - taskId)))
                }
                // The user's action IS the newest round: bump the generation so a
                // poll that started mid-action can't invalidate this refresh
                // (the board would otherwise show the pre-action state for up to
                // one 25s poll cycle — inviting a second click).
                generation += 1
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
        post(serverId, taskId) { client ->
            // work_task_merge returns Json<bool>: true = queued behind the
            // folder's merge slot (same-project merges run one at a time).
            // Without this note the card just sits in 等你处理 and users click
            // again — the queue reply is the difference between "queued" and
            // "ignored".
            val queued = client.workTaskMerge(taskId, message)
            if (queued) {
                _ui.update { it.copy(actionNote = "已加入合并队列：同项目任务正在串行合并，稍后自动执行") }
            }
        }

    fun complete(serverId: String, taskId: Int) = post(serverId, taskId) { it.workTaskComplete(taskId) }

    /** 验收退回（review → agent 重做），feedback 必填（服务端 ReturnParams）。 */
    fun returnTask(serverId: String, taskId: Int, feedback: String) =
        post(serverId, taskId) { it.workTaskReturn(taskId, feedback) }

    /** 交付 PR（review → PR 出门，任务随 markDone 落完成）。 */
    fun deliverPr(serverId: String, taskId: Int) =
        post(serverId, taskId) { it.workTaskDeliverPr(taskId) }

    fun setArchived(serverId: String, taskId: Int, archived: Boolean) =
        post(serverId, taskId) { it.workTaskArchive(taskId, archived) }

    fun setDeferred(serverId: String, taskId: Int, deferred: Boolean) =
        post(serverId, taskId) { it.workTaskDefer(taskId, deferred) }

    fun deleteTask(serverId: String, taskId: Int) =
        post(serverId, taskId) { it.workTaskDelete(taskId) }

    // endregion

    /** Expand a task: events + changed files load once; diff loads on demand. */
    fun loadDetail(serverId: String, taskId: Int, withDiff: Boolean = false) {
        val epoch = detailEpoch
        viewModelScope.launch {
            try {
                val client = deviceById(serverId)?.let { repository.client(it) }
                if (client == null) {
                    // Silent-return left an expanded card with a blank body and
                    // no way to retry — surface the failure on the card instead.
                    _ui.update { mutateDetail(it, serverId, taskId) { it.copy(loadError = "设备不可用，无法加载详情") } }
                    return@launch
                }
                val existing = _ui.value.details[serverId]?.get(taskId)
                if (withDiff && existing?.diffLoading != true && existing?.diff == null && existing?.diffError == null) {
                    _ui.update { setDiffLoading(it, serverId, taskId, true) }
                    val diffText = runCatching { client.workTaskDiff(taskId) }
                    // Same guard as the events branch: an action ran while the
                    // diff was in flight and dropped this detail — writing the
                    // stale diff back would resurrect the dropped snapshot.
                    if (epoch != detailEpoch) return@launch
                    diffText
                        .onSuccess { text -> _ui.update { setDiff(it, serverId, taskId, text, null) } }
                        .onFailure { e -> _ui.update { setDiff(it, serverId, taskId, null, e.message ?: "Diff 加载失败") } }
                }
                if (existing == null || existing.loadError != null) {
                    val events = runCatching { client.workTaskEvents(taskId) }.getOrNull()
                    val files = runCatching { client.workTaskChangedFiles(taskId) }.getOrNull()
                    // A null here means THAT load failed, not "no data" (the
                    // decoders return emptyList for empty) — cache the failure so
                    // the card can show it with a retry instead of a blank body.
                    // An action ran while this round was in flight: the dropped
                    // detail must stay dropped (the next expand refetches) —
                    // repopulating would show a pre-action snapshot forever.
                    if (epoch != detailEpoch) return@launch
                    if (events == null || files == null) {
                        _ui.update { mutateDetail(it, serverId, taskId) { it.copy(loadError = "详情加载失败，请重试") } }
                        return@launch
                    }
                    _ui.update { state ->
                        state.copy(
                            details = state.details +
                                (serverId to (state.details[serverId].orEmpty() +
                                    (taskId to (state.details[serverId]?.get(taskId) ?: TaskDetail()).copy(
                                        events = events,
                                        changedFiles = files,
                                        loadError = null,
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

    /** Clears both the error and the non-error outcome note (merge queued…). */
    fun clearActionError() { _ui.update { it.copy(actionError = null, actionNote = null) } }
}

private inline fun MutableStateFlow<TaskHubViewModel.UiState>.update(transform: (TaskHubViewModel.UiState) -> TaskHubViewModel.UiState) {
    value = transform(value)
}
