package app.codeg.android.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.DispatchOutcome
import app.codeg.android.core.data.DispatchPipeline
import app.codeg.android.core.data.DispatchService
import app.codeg.android.core.data.DispatchTargetLoader
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.DispatchStore
import app.codeg.android.core.datastore.IdeaEntry
import app.codeg.android.core.datastore.IdeaStore
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
    private val dispatchService: DispatchService,
    private val targetLoader: DispatchTargetLoader,
    private val repository: ServerRepository,
) : ViewModel() {

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

    private val _target = MutableStateFlow(DispatchTargets())
    val target: StateFlow<DispatchTargets> = _target.asStateFlow()

    init {
        // Crash mid-flight sweep: a PENDING ledger entry means the request was
        // already sent but its outcome is unobservable — flip it to UNKNOWN so it
        // surfaces as a resolvable card instead of silently rotting (or being
        // blindly re-sent, which could double-create on the server). The sweep
        // itself is self-guarded in DispatchService; these outer nets keep any
        // OTHER DataStore/flow failure from killing the process (a bare init
        // coroutine crashing here took the whole Activity tab down, v130 class).
        viewModelScope.launch {
            try {
                dispatchService.sweepPendingToUnknown()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sweep already logged its own failure path; nothing else to do.
            }
        }
        viewModelScope.launch {
            // Resubscribe loop: a DataStore failure must degrade to "empty for a
            // moment", not (a) crash, nor (b) a one-shot .catch that freezes the
            // inbox empty forever — an empty inbox invites blind re-dispatch of
            // an unresolved task (double create, PRD §7.5).
            while (currentCoroutineContext().isActive) {
                try {
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
                    break // completed normally (scope cancelling)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(2_000)
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
        viewModelScope.launch {
            try {
                ideaStore.upsert(entry)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(dispatchError = "想法保存失败（本地存储异常）")
            }
        }
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

    fun deleteIdea(id: String) = viewModelScope.launch {
        try {
            ideaStore.delete(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Delete failed: the idea stays; harmless retryable.
        }
    }

    fun clearDispatchError() { _ui.value = _ui.value.copy(dispatchError = null) }

    // region Dispatch


    /** Precheck + load the target device's projects and agents (PRD §7.3). */
    private var targetSeq = 0

    fun loadTarget(serverId: String) {
        val seq = ++targetSeq
        viewModelScope.launch {
            _target.value = _target.value.copy(folders = emptyList(), agents = emptyList(), foldersLoading = true, error = null)
            val loaded = targetLoader.load(serverId)
            // A slow response for a previously selected device must never
            // overwrite the current device's folder/agent lists. profiles stay
            // owned by the combine collector — never copied from the loader.
            if (seq != targetSeq) return@launch
            _target.value = _target.value.copy(
                folders = loaded.folders,
                agents = loaded.agents,
                foldersLoading = loaded.foldersLoading,
                error = loaded.error,
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
        /** Immediate dispatches start the task after create (idea path). */
        autoStart: Boolean = true,
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
        // Set synchronously (not inside the coroutine) so rapid double-taps can
        // never both pass the guard above.
        _ui.value = _ui.value.copy(dispatching = true, dispatchError = null, lastCreated = null)
        viewModelScope.launch {
            try {
                val outcome = dispatchService.send(
                    DispatchService.SendRequest(
                        ideaId = idea?.id,
                        serverId = serverId,
                        folderId = folderId,
                        folderName = folderName,
                        title = title,
                        description = description,
                        agentTypeWire = agentType?.wire,
                        scheduleLocalMillis = scheduleLocalMillis,
                        autoStart = autoStart,
                        restoreOnHardFail = restoreOnHardFail,
                    ),
                )
                when (outcome) {
                    is DispatchOutcome.Confirmed -> {
                        _ui.value = _ui.value.copy(
                            dispatching = false,
                            lastCreated = "${outcome.ref.serverId}:${outcome.ref.remoteTodoId}",
                            dispatchError = outcome.scheduleNote,
                        )
                        onDone(outcome.ref)
                    }
                    is DispatchOutcome.Unknown -> _ui.value = _ui.value.copy(
                        dispatching = false,
                        dispatchError = "提交结果未知",
                    )
                    is DispatchOutcome.HardFailed -> fail(outcome.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Last-resort guard: a DataStore IO failure inside send() must
                // never crash the process — degrade to a dispatch error. If this
                // was a redispatch, send() may have died BEFORE consuming
                // restoreOnHardFail — put the original UNKNOWN trace back so the
                // only evidence of the ambiguous request is not lost.
                restoreOnHardFail?.let { old ->
                    runCatching { dispatchStore.put(old.copy(state = DispatchState.UNKNOWN)) }
                }
                fail(e.message ?: "派发失败")
            }
        }
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
                // End of window: symmetric clock slack (+10min) against a FAST
                // server clock, but capped relative to the dispatch moment —
                // an unbounded "now+10min" would keep absorbing unrelated
                // same-titled tasks forever as the probe gets later.
                val refDispatched = ref.dispatchedAtEpochMs?.let { Instant.ofEpochMilli(it) } ?: started
                val windowEnd = minOf(
                    Instant.now().plusSeconds(600),
                    refDispatched.plusSeconds(3_600),
                )
                val matches = DispatchPipeline.findCreatedTasks(
                    tasks, ref.title, ref.folderId, started, windowEnd,
                )
                if (matches.size == 1) {
                    val match = matches.single()
                    dispatchStore.put(ref.copy(remoteTodoId = match.id, state = DispatchState.CONFIRMED))
                    _ui.value = _ui.value.copy(dispatchError = null, lastCreated = "${ref.serverId}:${match.id}")
                } else if (matches.size > 1) {
                    // Multiple same-titled candidates: claiming one risks binding
                    // the wrong id. Stay UNKNOWN — and do NOT say "not found",
                    // which invites a re-dispatch (double create). The task
                    // almost certainly EXISTS.
                    _ui.value = _ui.value.copy(
                        dispatchError = "服务端找到 ${matches.size} 个同名任务，无法确认归属；请到设备上人工核对后再处理，未确认前勿重新派发",
                    )
                } else {
                    _ui.value = _ui.value.copy(dispatchError = "服务端未找到该任务，可重新派发或放弃")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ledger read or confirm-write failed: stay UNKNOWN and say so —
                // never let a bare coroutine crash the process, never report
                // "not found" on an unverified check.
                _ui.value = _ui.value.copy(dispatchError = "检查失败，请稍后重试（结果未知，勿直接重派）")
            } finally {
                synchronized(probing) { probing.remove(requestId) }
            }
        }
    }

    /** Give up on an UNKNOWN request (the user checked the server themselves). */
    fun discard(requestId: String) {
        viewModelScope.launch {
            try {
                dispatchStore.remove(requestId)
                _ui.value = _ui.value.copy(dispatchError = null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(dispatchError = "放弃失败（本地存储异常），记录保留")
            }
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
            // A failed remove aborts the re-send entirely: dispatch() would
            // create a SECOND task while the original trace is still UNKNOWN.
            try {
                dispatchStore.remove(ref.requestId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(dispatchError = "本地记录更新失败，未重新派发（结果未知，勿手动重试创建）")
                return@launch
            }
            // Re-send exactly what was sent the first time (the idea may have been
            // edited since; the ledger snapshot is what the ambiguous request carried).
            dispatch(
                idea = ref.ideaId?.let { id -> _ui.value.ideas.firstOrNull { it.id == id } },
                serverId = ref.serverId,
                folderId = ref.folderId,
                folderName = ref.folderName,
                autoStart = ref.autoStart,
                // fromWire maps unknown wires to UNKNOWN — re-sending that would
                // create a task on the default agent instead of the chosen one.
                agentType = ref.agentTypeWire?.let { AgentType.fromWire(it) }?.takeIf { it != AgentType.UNKNOWN },
                title = ref.title,
                description = ref.description,
                // Preserve the original schedule intent — but a timestamp already
                // in the past can't be honored by re-sending it; drop it so the
                // re-dispatch runs immediately instead of landing in a dead slot.
                scheduleLocalMillis = ref.scheduleLocalMillis
                    ?.takeIf { it > System.currentTimeMillis() }
                    ?: scheduleLocalMillis,
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
