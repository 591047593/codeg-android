package app.codeg.android.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.DispatchPipeline
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.DispatchStore
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.datastore.IdeaStore
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * The idea inbox + unified dispatch (batch v140). Ideas are local drafts; the
 * dispatch pipeline turns one into a server-native work_task and keeps a
 * request-id ledger so an ambiguous submit can never silently re-create tasks
 * (server has no idempotency key — PRD §7.5).
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val ideaStore: IdeaStore,
    private val dispatchStore: DispatchStore,
    private val repository: ServerRepository,
) : ViewModel() {

    data class Target(
        val profiles: List<ServerProfile> = emptyList(),
        val folders: List<FolderDetail> = emptyList(),
        val agents: List<AgentType> = emptyList(),
        val foldersLoading: Boolean = false,
        val error: String? = null,
    )

    data class UiState(
        val ideas: List<IdeaEntry> = emptyList(),
        val pendingRefs: List<DispatchRef> = emptyList(),
        val confirmedRefs: List<DispatchRef> = emptyList(),
        val dispatching: Boolean = false,
        val dispatchError: String? = null,
        /** serverId:ideaId of the last created task, for the success line. */
        val lastCreated: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _target = MutableStateFlow(Target())
    val target: StateFlow<Target> = _target.asStateFlow()

    init {
        // Crash mid-flight sweep: a PENDING ledger entry means the request was
        // already sent but its outcome is unobservable — flip it to UNKNOWN so it
        // surfaces as a resolvable card instead of silently rotting (or being
        // blindly re-sent, which could double-create on the server).
        viewModelScope.launch {
            dispatchStore.refs.first()
                .filter { it.state == DispatchState.PENDING }
                .forEach { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
        }
        viewModelScope.launch {
            combine(ideaStore.ideas, dispatchStore.refs, repository.visibleProfiles) { ideas, refs, profiles ->
                Triple(ideas, refs, profiles)
            }.collect { (ideas, refs, profiles) ->
                _ui.value = _ui.value.copy(
                    ideas = ideas,
                    pendingRefs = refs.filter { it.state == DispatchState.UNKNOWN },
                    confirmedRefs = refs.filter { it.state == DispatchState.CONFIRMED }.take(20),
                )
                if (_target.value.profiles != profiles) {
                    _target.value = _target.value.copy(profiles = profiles)
                }
            }
        }
    }

    fun saveIdea(id: String?, title: String, description: String, source: IdeaEntry? = null) {
        if (title.isBlank() && description.isBlank()) return
        val now = System.currentTimeMillis()
        val entry = (source ?: id?.let { existing -> _ui.value.ideas.firstOrNull { it.id == existing } })
            ?.copy(title = title.trim(), description = description.trim(), updatedAt = now)
            ?: IdeaEntry(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                description = description.trim(),
                createdAt = now,
                updatedAt = now,
                sourceServerId = null,
            )
        viewModelScope.launch { ideaStore.upsert(entry) }
    }

    /** Seed a new idea from a conversation (traceability only). */
    fun newIdeaFrom(serverId: String, projectId: Int, sessionId: String): IdeaEntry =
        IdeaEntry(
            id = UUID.randomUUID().toString(),
            title = "",
            description = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            sourceServerId = serverId,
            sourceProjectId = projectId,
            sourceSessionId = sessionId,
        )

    fun deleteIdea(id: String) = viewModelScope.launch { ideaStore.delete(id) }

    fun clearDispatchError() { _ui.value = _ui.value.copy(dispatchError = null) }

    // region Dispatch


    /** Precheck + load the target device's projects and agents (PRD §7.3). */
    private var targetSeq = 0

    fun loadTarget(serverId: String) {
        val seq = ++targetSeq
        viewModelScope.launch {
            _target.value = _target.value.copy(folders = emptyList(), agents = emptyList(), foldersLoading = true, error = null)
            val profile = repository.profiles.first().firstOrNull { it.id == serverId }
            val client = profile?.let { repository.client(it) }
            if (client == null) {
                if (seq == targetSeq) _target.value = _target.value.copy(foldersLoading = false, error = "无法连接目标设备")
                return@launch
            }
            // Precheck: the cheap health call proves URL+token before the user
            // composes anything; its version string also feeds the capability gate.
            val precheck = runCatching { client.health() }
            precheck.fold(
                onSuccess = {
                    // A slow response for a previously selected device must never
                    // overwrite the current device's folder/agent lists.
                    if (seq != targetSeq) return@launch
                    val folders = runCatching { client.listFolders() }.getOrElse { emptyList() }
                    val agents = runCatching { client.acpListAgents() }
                        .getOrDefault(emptyList())
                        .filter { it.available && it.enabled && it.agentType != AgentType.UNKNOWN }
                        .map { it.agentType }
                        .distinct()
                    if (seq != targetSeq) return@launch
                    _target.value = _target.value.copy(
                        folders = folders,
                        agents = agents,
                        foldersLoading = false,
                    )
                },
                onFailure = { e ->
                    if (seq != targetSeq) return@launch
                    _target.value = _target.value.copy(
                        foldersLoading = false,
                        error = (e as? ApiError)?.displayMessage() ?: e.message ?: "目标设备不可用",
                    )
                },
            )
        }
    }

    fun dispatch(
        idea: IdeaEntry?,
        serverId: String,
        folderId: Int,
        folderName: String,
        agentType: AgentType?,
        title: String,
        description: String,
        scheduleLocalMillis: Long?,
        onDone: (ref: DispatchRef) -> Unit,
        /** On a hard (non-ambiguous) failure, put this ref back as UNKNOWN — used by
         * redispatch so a failed re-send never destroys the only trace of the
         * original ambiguous request. */
        restoreOnHardFail: DispatchRef? = null,
    ) {
        if (_ui.value.dispatching) {
            _ui.value = _ui.value.copy(dispatchError = "正在派发，请稍候")
            return
        }
        if (title.isBlank()) {
            _ui.value = _ui.value.copy(dispatchError = "标题不能为空")
            return
        }
        val request = DispatchRef(
            requestId = UUID.randomUUID().toString(),
            ideaId = idea?.id,
            serverId = serverId,
            serverName = _target.value.profiles.firstOrNull { it.id == serverId }?.name.orEmpty(),
            folderId = folderId,
            folderName = folderName,
            // Cap the title the same way on create and probe: if the server
            // truncates long titles, exact-match probing must use the same string.
            title = title.trim().take(120),
            state = DispatchState.PENDING,
            createdAt = System.currentTimeMillis(),
            description = description.trim(),
            agentTypeWire = agentType?.wire,
            // Anchor the probe window at send time, not at failure time — the
            // process can die before the response and the anchor must survive.
            dispatchedAtEpochMs = System.currentTimeMillis(),
            scheduleLocalMillis = scheduleLocalMillis,
        )
        // Set synchronously (not inside the coroutine) so rapid double-taps can
        // never both pass the guard above.
        _ui.value = _ui.value.copy(dispatching = true, dispatchError = null, lastCreated = null)
        viewModelScope.launch {
            val profile = repository.profiles.first().firstOrNull { it.id == serverId }
            val client = profile?.let { repository.client(it) }
            if (client == null) {
                dispatchStore.remove(request.requestId)
                restoreOnHardFail?.let { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
                fail("设备不可用或 Token 缺失")
                return@launch
            }
            dispatchStore.put(request)
            val config = DispatchPipeline.taskConfig(
                description = description.trim(),
                agentTypeWire = agentType?.wire,
            )
            val started = Instant.ofEpochMilli(request.dispatchedAtEpochMs!!)
            val created = runCatching { client.workTaskCreate(folderId, request.title, config) }
            created.fold(
                onSuccess = { task ->
                    if (task != null) {
                        val ref = confirm(request, task.id, profile, scheduleLocalMillis)
                        onDone(ref)
                    } else {
                        // Request reached the server but the row did not decode — ambiguous.
                        markUnknown(request)
                    }
                },
                onFailure = { e ->
                    when {
                        e is kotlinx.coroutines.CancellationException -> {
                            // The coroutine is already cancelled: any other suspend
                            // call here would immediately re-throw. Clean up inside
                            // NonCancellable, then honour structured cancellation —
                            // the PENDING entry left behind is swept to UNKNOWN on
                            // next launch, which is the correct outcome anyway.
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                dispatchStore.remove(request.requestId)
                            }
                            throw e
                        }
                        // Timeout-style failures are ambiguous: the task may exist.
                        isAmbiguous(e) -> markUnknownForProbe(request, started)
                        else -> {
                            dispatchStore.remove(request.requestId)
                            restoreOnHardFail?.let { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
                            fail((e as? ApiError)?.displayMessage() ?: e.message ?: "派发失败")
                        }
                    }
                },
            )
        }
    }

    /**
     * True when the request's fate is unobservable: a dropped connection
     * (IOException, incl. socket timeout) or a wrapped transport error may mean
     * the server created the task without us seeing the response. The server has
     * no idempotency key, so any of these must block re-sending.
     */
    private fun isAmbiguous(e: Throwable): Boolean =
        e is java.io.IOException ||
            e is ApiError.Transport ||
            (e as? ApiError)?.let { it is ApiError.Server && it.status >= 500 } == true

    private suspend fun confirm(request: DispatchRef, todoId: Int, profile: ServerProfile, scheduleLocalMillis: Long?): DispatchRef {
        val ref = request.copy(remoteTodoId = todoId, state = DispatchState.CONFIRMED)
        dispatchStore.put(ref)
        var scheduleNote: String? = null
        if (scheduleLocalMillis != null) {
            val client = repository.client(profile)
            val scheduled = client?.let {
                runCatching {
                    it.workTaskSchedule(todoId, DispatchPipeline.toUtcRfc3339(scheduleLocalMillis))
                }.isSuccess
            } == true
            if (!scheduled) scheduleNote = "任务已创建，但定时设置失败，可在任务中心手动处理"
        }
        _ui.value = _ui.value.copy(
            dispatching = false,
            lastCreated = "${ref.serverId}:${todoId}",
            dispatchError = scheduleNote,
        )
        return ref
    }

    /** Mark the request UNKNOWN; the probe window is anchored on the ref itself. */
    private suspend fun markUnknownForProbe(request: DispatchRef, started: Instant) {
        dispatchStore.put(request.copy(state = DispatchState.UNKNOWN, dispatchedAtEpochMs = started.toEpochMilli()))
        _ui.value = _ui.value.copy(dispatching = false, dispatchError = "提交结果未知")
    }

    private suspend fun markUnknown(request: DispatchRef) {
        dispatchStore.put(
            request.copy(
                state = DispatchState.UNKNOWN,
                dispatchedAtEpochMs = System.currentTimeMillis() - 120_000,
            ),
        )
        _ui.value = _ui.value.copy(dispatching = false, dispatchError = "提交结果未知")
    }

    /** requestIds with a probe in flight, so the button can't fire concurrently. */
    private val probing = mutableSetOf<String>()

    /**
     * Resolve an UNKNOWN request by listing the server's tasks and looking for the
     * one we may have created (title + folder + time window). Found → CONFIRMED;
     * a failed check keeps the ref UNKNOWN and says so — it must never be
     * presented as "not found", which would invite a double create.
     */
    fun probe(requestId: String) {
        synchronized(probing) { if (!probing.add(requestId)) return }
        viewModelScope.launch {
            try {
                val ref = dispatchStore.refs.first().firstOrNull { it.requestId == requestId } ?: return@launch
                val client = repository.profiles.first()
                    .firstOrNull { it.id == ref.serverId }?.let { repository.client(it) }
                if (client == null) {
                    _ui.value = _ui.value.copy(dispatchError = "设备不可用，无法检查")
                    return@launch
                }
                val result = runCatching { client.workTaskList() }
                val tasks = result.getOrElse {
                    _ui.value = _ui.value.copy(dispatchError = "检查失败，请稍后重试（结果未知，勿直接重派）")
                    return@launch
                }
                val started = (ref.dispatchedAtEpochMs?.let { Instant.ofEpochMilli(it) }
                    ?: Instant.now().minusSeconds(600))
                    // The window compares the PHONE's send moment against the SERVER's
                    // createdAt: allow for clock skew so a slightly slow server clock
                    // can't push the real task below the window (false negative →
                    // double create).
                    .minusSeconds(300)
                val match = DispatchPipeline.findCreatedTask(
                    tasks, ref.title, ref.folderId, started, Instant.now().plusSeconds(60),
                )
                if (match != null) {
                    dispatchStore.put(ref.copy(remoteTodoId = match.id, state = DispatchState.CONFIRMED))
                    _ui.value = _ui.value.copy(dispatchError = null, lastCreated = "${ref.serverId}:${match.id}")
                } else {
                    _ui.value = _ui.value.copy(dispatchError = "服务端未找到该任务，可重新派发或放弃")
                }
            } finally {
                synchronized(probing) { probing.remove(requestId) }
            }
        }
    }

    /** Give up on an UNKNOWN request (the user checked the server themselves). */
    fun discard(requestId: String) {
        viewModelScope.launch {
            dispatchStore.remove(requestId)
            _ui.value = _ui.value.copy(dispatchError = null)
        }
    }

    /** Re-send after the user explicitly resolved an UNKNOWN as not-created. */
    fun redispatch(ref: DispatchRef, scheduleLocalMillis: Long?) {
        if (_ui.value.dispatching) {
            _ui.value = _ui.value.copy(dispatchError = "正在派发，请稍候")
            return
        }
        viewModelScope.launch {
            // Remove the old ledger entry only after the guard, so a rejected
            // re-dispatch can never drop the only trace of an unresolved request.
            dispatchStore.remove(ref.requestId)
            // Re-send exactly what was sent the first time (the idea may have been
            // edited since; the ledger snapshot is what the ambiguous request carried).
            dispatch(
                idea = ref.ideaId?.let { id -> _ui.value.ideas.firstOrNull { it.id == id } },
                serverId = ref.serverId,
                folderId = ref.folderId,
                folderName = ref.folderName,
                agentType = ref.agentTypeWire?.let { AgentType.fromWire(it) },
                title = ref.title,
                description = ref.description,
                // Preserve the original schedule intent instead of silently
                // demoting a timed dispatch to "run now".
                scheduleLocalMillis = ref.scheduleLocalMillis ?: scheduleLocalMillis,
                onDone = {},
                restoreOnHardFail = ref,
            )
        }
    }

    private fun fail(message: String) {
        _ui.value = _ui.value.copy(dispatching = false, dispatchError = message)
    }

    // endregion
}
