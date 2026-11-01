package app.codeg.android.core.data

import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.DispatchStore
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What happened to one dispatch request (batch v140/v141 shared pipeline). */
sealed interface DispatchOutcome {
    /** Server returned a task id; [scheduleNote] carries a schedule failure if any. */
    data class Confirmed(val ref: DispatchRef, val scheduleNote: String? = null) : DispatchOutcome

    /** Request fate unobservable — ledger keeps the ref UNKNOWN, no auto re-send. */
    data class Unknown(val ref: DispatchRef) : DispatchOutcome

    /** Definitive failure (4xx / unreachable before send) — the ledger entry is removed. */
    data class HardFailed(val message: String) : DispatchOutcome
}

/**
 * The one dispatch pipeline every dispatch surface goes through (idea inbox
 * v140, session handoff v141). Owns the request-id ledger: PENDING while in
 * flight, CONFIRMED with the server task id, UNKNOWN when the fate is
 * unobservable. The server has NO idempotency key, so anything ambiguous must
 * surface as [DispatchOutcome.Unknown] and block blind re-sends (PRD §7.5).
 */
@Singleton
class DispatchService @Inject constructor(
    private val dispatchStore: DispatchStore,
    private val repository: ServerRepository,
    // 批v150 P0: the session-mode "first prompt" transmitter. The consumer
    // (SessionDetailViewModel.take) shipped in v149 but nothing ever armed the
    // station — session-mode dispatches opened a blank conversation.
    private val sessionAutoStart: SessionAutoStart,
) {
    data class SendRequest(
        val ideaId: String? = null,
        val serverId: String,
        val folderId: Int,
        val folderName: String,
        val title: String,
        val description: String,
        val agentTypeWire: String? = null,
        /** Explicit base branch (ui重构 §6.2 分支选择) — null = the folder's
         *  current branch, exactly the previous behavior. The server derives the
         *  task work branch from it at start. */
        val baseBranch: String? = null,
        val scheduleLocalMillis: Long? = null,
        /** Immediate (non-scheduled) dispatches start the task right after
         * create — the server parks new tasks in todo forever, so without this
         * the "记想法→派发→等它跑" path never runs. Handoff dispatches leave
         * it false: picking up the work is the receiving side's decision. */
        val autoStart: Boolean = false,
        /** 批v149 会话模式：目标是"项目里直接开一个会话"——服务端
         * create_conversation 是纯 DB 插入（不建 worktree、不进任务板），返回
         * 会话 id 记入账本；首条指令由 UI 跳转会话后自动发送。定时/自动开始
         * 对会话无意义，此模式下被忽略。 */
        val sessionMode: Boolean = false,
        /** On a hard failure, put this ref back as UNKNOWN — a re-dispatch must
         * never destroy the only trace of the original ambiguous request. */
        val restoreOnHardFail: DispatchRef? = null,
    )

    /**
     * Crash-recovery sweep: a PENDING ledger entry means the request was sent
     * but its outcome is unobservable — flip it to UNKNOWN so it surfaces as a
     * resolvable card instead of rotting silently. Every dispatch surface calls
     * this on VM init so the sweep doesn't depend on visiting the inbox.
     *
     * Self-guarded: callers run it from bare VM-init coroutines, so a DataStore
     * IO failure here must degrade to "skip the sweep" (PENDING entries still
     * resolve via probe later), never kill the process — a bare init coroutine
     * on this page took the whole Activity tab down once before (v130 class).
     */
    suspend fun sweepPendingToUnknown() {
        try {
            val now = System.currentTimeMillis()
            dispatchStore.refs.first()
                // Only PENDING entries old enough to be corpses. A fresh PENDING
                // may be a request STILL IN FLIGHT from another surface — flipping
                // it to UNKNOWN mid-flight invites a concurrent redispatch (double
                // create). A create that takes >2 min will surface UNKNOWN on the
                // next sweep round anyway.
                .filter { it.state == DispatchState.PENDING && now - it.createdAt > PENDING_SWEEP_AGE_MS }
                .forEach { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ledger unreadable: nothing to sweep; probe/dispatch still work.
        }
    }

    /**
     * True when an UNRESOLVED (UNKNOWN) ledger entry exists that looks like the
     * same dispatch — same server, folder and capped title. Surfaces whose
     * UNKNOWN guard lives only in memory (handoff sheet reset) must consult
     * this before sending, or a reopened sheet can blind-re-send an ambiguous
     * request (double create).
     */
    suspend fun hasUnresolvedDuplicate(serverId: String, folderId: Int, title: String): Boolean =
        dispatchStore.refs.first().any {
            // PENDING counts too: it is by definition unconfirmed (in flight, or
            // left by a killed process until the age-gated sweep flips it) —
            // creating "the same task" on top of it is exactly the double-create
            // this gate exists to stop.
            it.state != DispatchState.CONFIRMED &&
                it.serverId == serverId &&
                it.folderId == folderId &&
                it.title == cap(title.trim(), 120)
        }

    /** PENDING entries younger than this are considered in flight, not dead. */
    private val PENDING_SWEEP_AGE_MS = 120_000L

    /** Serializes duplicate-check + PENDING reservation in send() (Hilt can't
     *  provide a Mutex binding, so this is a plain property — one per service,
     *  which is exactly the serialization scope we need). */
    private val gateMutex = kotlinx.coroutines.sync.Mutex()

    /** Code-point-safe truncation (never splits an emoji surrogate pair). */
    fun cap(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

    suspend fun send(req: SendRequest): DispatchOutcome {
        val now = System.currentTimeMillis()
        val profile = repository.profiles.first().firstOrNull { it.id == req.serverId }
        val client = profile?.let { repository.client(it) }
        val request = DispatchRef(
            requestId = UUID.randomUUID().toString(),
            ideaId = req.ideaId,
            serverId = req.serverId,
            serverName = profile?.name.orEmpty(),
            folderId = req.folderId,
            folderName = req.folderName,
            // Cap the title the same way on create and probe: if the server
            // truncates long titles, exact-match probing must use the same string.
            title = cap(req.title.trim(), 120),
            state = DispatchState.PENDING,
            createdAt = now,
            description = req.description.trim(),
            agentTypeWire = req.agentTypeWire,
            // Anchor the probe window at send time — the process can die before
            // the response and the anchor must survive.
            dispatchedAtEpochMs = now,
            scheduleLocalMillis = req.scheduleLocalMillis,
            autoStart = req.autoStart,
            sessionMode = req.sessionMode,
        )
        if (client == null) {
            restoreOnHardFail(req.restoreOnHardFail)
            return DispatchOutcome.HardFailed("设备不可用或 Token 缺失")
        }
        // Atomic check-and-reserve: the VM-level gates can both pass for the
        // same title from two surfaces (TOCTOU across VMs) — re-check under a
        // mutex so only one send may move a title from "no unresolved ref" to
        // "PENDING" at a time.
        gateMutex.withLock {
            if (hasUnresolvedDuplicate(req.serverId, req.folderId, request.title)) {
                restoreOnHardFail(req.restoreOnHardFail)
                return DispatchOutcome.HardFailed("已存在同目标的未确认派发记录，请先处理后再派发，避免重复创建")
            }
            dispatchStore.put(request)
        }
        // Unified "remote id" result: task mode maps the created row to its id,
        // session mode gets the conversation id straight from create_conversation
        // (a bare JSON int). null/<=0 = the request reached the server but the
        // row did not decode — ambiguous, handled identically below.
        val created: kotlin.Result<Int?> = if (req.sessionMode) {
            // The server requires a concrete agent_type; resolve 自动 (null) and
            // an UNKNOWN wire to the client default, same fallback the new-task
            // draft screen applies when a device reports no agents.
            val agent = req.agentTypeWire?.let { app.codeg.android.core.model.AgentType.fromWire(it) }
                ?.takeIf { it != app.codeg.android.core.model.AgentType.UNKNOWN }
                ?: app.codeg.android.core.model.AgentType.CLAUDE_CODE
            runCatching { client.createConversation(req.folderId, agent, request.title) }
        } else {
            val config = DispatchPipeline.taskConfig(
                description = req.description.trim(),
                agentTypeWire = req.agentTypeWire,
                baseBranch = req.baseBranch,
            )
            runCatching { client.workTaskCreate(req.folderId, request.title, config)?.id }
        }
        return created.fold(
            onSuccess = { remoteId ->
                if (remoteId != null && remoteId > 0) {
                    // Session mode: no schedule/no auto-start — the first prompt
                    // IS the start, sent by the session screen after the jump.
                    // 批v150 P0: that sender finally has a transmitter — arm the
                    // handoff station on every CONFIRMED session create, or the
                    // conversation the UI jumps to opens permanently blank.
                    if (req.sessionMode) {
                        confirm(request, remoteId, profile, null, false).also { outcome ->
                            if (outcome is DispatchOutcome.Confirmed) {
                                sessionAutoStart.arm(req.serverId, remoteId, req.description)
                            }
                        }
                    } else confirm(request, remoteId, profile, req.scheduleLocalMillis, req.autoStart)
                } else {
                    markUnknown(request)
                }
            },
            onFailure = { e ->
                when {
                    e is CancellationException -> {
                        // The coroutine is already cancelled: any other suspend
                        // call here would immediately re-throw. Honour structured
                        // cancellation — but the request may already be on the
                        // wire, so the ledger trace MUST survive: deleting it (the
                        // old behavior) orphans a possibly-created task with no
                        // UNKNOWN card, no probe, and no duplicate guard.
                        withContext(NonCancellable) { runCatching { markUnknown(request) } }
                        throw e
                    }
                    // ApiError.Decoding after a successful send() means the server
                    // ANSWERED (2xx) but the body didn't parse — the create may
                    // well have happened. This is the ambiguous case; treating it
                    // as a hard failure deleted the ledger's only trace and
                    // invited a double create (批v146 对抗审查 #1).
                    e is ApiError.Decoding -> probeOrMarkUnknown(request, profile)
                    // Timeout-style failures are ambiguous: the task may exist.
                    // Self-heal: one immediate probe — if the request actually
                    // landed, the ledger resolves to CONFIRMED and the user
                    // never sees a 提交结果未知 card at all.
                    isAmbiguous(e) -> probeOrMarkUnknown(request, profile)
                    else -> {
                        dispatchStore.remove(request.requestId)
                        req.restoreOnHardFail?.let { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
                        DispatchOutcome.HardFailed(
                            (e as? ApiError)?.displayMessage() ?: e.message ?: "派发失败",
                        )
                    }
                }
            },
        )
    }

    private suspend fun restoreOnHardFail(ref: DispatchRef?) {
        // The old entry was removed by the caller before re-sending; put it back.
        ref?.let { dispatchStore.put(it.copy(state = DispatchState.UNKNOWN)) }
    }

    /**
     * True when the request's fate is unobservable: a dropped connection
     * (IOException, incl. socket timeout) or a wrapped transport error may mean
     * the server created the task without us seeing the response. The server has
     * no idempotency key, so any of these must block re-sending.
     *
     * A [ApiError.Server] response is NOT ambiguous, verified against the server
     * source (v1.0.1-fengx.34): every rejection path — axum's serde rejection
     * (422, pre-handler) and every error the create core returns (DbError →
     * 500, raised on the folder lookup / insert BEFORE any task row exists) —
     * happens without a task being created. The server answered, so we KNOW
     * the create failed; treating 5xx as "unknown" surfaced the misleading
     * 提交结果未知 card for deterministic rejections (e.g. a stale folder id).
     */
    private fun isAmbiguous(e: Throwable): Boolean =
        e is java.io.IOException ||
            e is ApiError.Transport

    private suspend fun confirm(
        request: DispatchRef,
        todoId: Int,
        profile: app.codeg.android.core.datastore.ServerProfile,
        scheduleLocalMillis: Long?,
        autoStart: Boolean,
    ): DispatchOutcome {
        val ref = request.copy(remoteTodoId = todoId, state = DispatchState.CONFIRMED)
        putIfPresent(ref)
        var scheduleNote: String? = null
        if (scheduleLocalMillis != null) {
            val client = repository.client(profile)
            val scheduled = client?.let {
                try {
                    it.workTaskSchedule(todoId, DispatchPipeline.toUtcRfc3339(scheduleLocalMillis))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            } == true
            if (!scheduled) scheduleNote = "任务已创建，但定时设置失败，可在任务中心手动处理"
        } else if (autoStart) {
            // PRD main path is 记想法→派发→等它跑: the server parks a freshly
            // created task in todo forever, so an "immediate" dispatch must also
            // start it (scheduled ones are pulled by the server scheduler
            // instead). Best-effort: if the start call fails the task stays in
            // todo and the note tells the user where to start it by hand.
            val client = repository.client(profile)
            val started = client?.let {
                try {
                    it.workTaskStart(todoId)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            } == true
            if (!started) scheduleNote = "任务已创建，但自动开始失败，可到任务中心手动开始"
        }
        return DispatchOutcome.Confirmed(ref, scheduleNote)
    }

    private suspend fun markUnknown(request: DispatchRef): DispatchOutcome {
        dispatchStore.put(request.copy(state = DispatchState.UNKNOWN))
        return DispatchOutcome.Unknown(request)
    }

    /**
     * Tombstone check before every delayed ledger write-back (confirm from a
     * probe racing a user's 放弃): a ref the user explicitly discarded must
     * never be resurrected as CONFIRMED — that would both lie about "verified"
     * and un-block a double create. The normal send() path is unaffected (the
     * PENDING entry it wrote is still in the ledger).
     */
    private suspend fun putIfPresent(ref: DispatchRef) {
        val exists = runCatching { dispatchStore.refs.first().any { it.requestId == ref.requestId } }
            .getOrDefault(true) // Ledger unreadable: prefer writing (state UNKNOWN/CONFIRMED both honest) over losing the trace.
        if (exists) dispatchStore.put(ref)
    }

    /**
     * A genuinely ambiguous send (dropped connection mid-flight): mark the
     * ledger UNKNOWN, then immediately run ONE probe against the server. If
     * the request actually landed, the ledger resolves to CONFIRMED and the
     * user never sees the 提交结果未知 card — the probe must stay read-only
     * (list + exact title/folder/time-window match) and any failure keeps
     * the UNKNOWN card for manual resolution, never a silent delete.
     */
    private suspend fun probeOrMarkUnknown(
        request: DispatchRef,
        profile: app.codeg.android.core.datastore.ServerProfile,
    ): DispatchOutcome {
        markUnknown(request)
        try {
            // Give the server a beat — a socket timeout usually means the
            // response was in flight when the connection died.
            kotlinx.coroutines.delay(1_500)
            val client = repository.client(profile) ?: return DispatchOutcome.Unknown(request)
            val started = (request.dispatchedAtEpochMs
                ?: System.currentTimeMillis()).let { java.time.Instant.ofEpochMilli(it) }
                .minusSeconds(300)
            val windowEnd = minOf(
                java.time.Instant.now().plusSeconds(600),
                started.plusSeconds(3_900),
            )
            // 会话模式的探针域是会话列表（任务列表永远"查无此任务"会把一个
            // 可能已创建的会话误判为未创建，直接诱发重派 → 重复会话）。
            if (request.sessionMode) {
                val convs = runCatching { client.listConversations(search = request.title) }.getOrElse {
                    return DispatchOutcome.Unknown(request)
                }
                val matches = DispatchPipeline.findCreatedConversations(
                    convs, request.title, request.folderId, started, windowEnd,
                )
                if (matches.size == 1) {
                    val id = matches.single().id
                    // 批v150 P0: a probe-resolved session create arms the handoff
                    // too — the UI's jump may already be gone (this path runs after
                    // an ambiguous send), so the armed slot can linger; the
                    // staleness window in take() bounds it.
                    return confirm(request, id, profile, null, false).also { outcome ->
                        if (outcome is DispatchOutcome.Confirmed) {
                            sessionAutoStart.arm(request.serverId, id, request.description)
                        }
                    }
                }
            } else {
                val tasks = runCatching { client.workTaskList() }.getOrElse {
                    return DispatchOutcome.Unknown(request)
                }
                val matches = DispatchPipeline.findCreatedTasks(
                    tasks, request.title, request.folderId, started, windowEnd,
                )
                if (matches.size == 1) {
                    return confirm(request, matches.single().id, profile, request.scheduleLocalMillis, request.autoStart)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ledger write or list failure: stay UNKNOWN — the card remains.
        }
        return DispatchOutcome.Unknown(request)
    }
}
