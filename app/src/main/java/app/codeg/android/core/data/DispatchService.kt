package app.codeg.android.core.data

import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchState
import app.codeg.android.core.datastore.DispatchStore
import app.codeg.android.core.network.ApiError
import app.codeg.android.core.network.displayMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
) {
    data class SendRequest(
        val ideaId: String? = null,
        val serverId: String,
        val folderId: Int,
        val folderName: String,
        val title: String,
        val description: String,
        val agentTypeWire: String? = null,
        val scheduleLocalMillis: Long? = null,
        /** Immediate (non-scheduled) dispatches start the task right after
         * create — the server parks new tasks in todo forever, so without this
         * the "记想法→派发→等它跑" path never runs. Handoff dispatches leave
         * it false: picking up the work is the receiving side's decision. */
        val autoStart: Boolean = false,
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
            it.state == DispatchState.UNKNOWN &&
                it.serverId == serverId &&
                it.folderId == folderId &&
                it.title == cap(title.trim(), 120)
        }

    /** PENDING entries younger than this are considered in flight, not dead. */
    private val PENDING_SWEEP_AGE_MS = 120_000L

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
        )
        if (client == null) {
            restoreOnHardFail(req.restoreOnHardFail)
            return DispatchOutcome.HardFailed("设备不可用或 Token 缺失")
        }
        dispatchStore.put(request)
        val config = DispatchPipeline.taskConfig(
            description = req.description.trim(),
            agentTypeWire = req.agentTypeWire,
        )
        val created = runCatching {
            client.workTaskCreate(req.folderId, request.title, config)
        }
        return created.fold(
            onSuccess = { task ->
                if (task != null) {
                    confirm(request, task.id, profile, req.scheduleLocalMillis, req.autoStart)
                } else {
                    // Request reached the server but the row did not decode — ambiguous.
                    markUnknown(request)
                }
            },
            onFailure = { e ->
                when {
                    e is CancellationException -> {
                        // The coroutine is already cancelled: any other suspend
                        // call here would immediately re-throw. Clean up inside
                        // NonCancellable, then honour structured cancellation —
                        // a PENDING entry left behind is swept to UNKNOWN on next
                        // launch, which is the correct outcome anyway.
                        withContext(NonCancellable) { dispatchStore.remove(request.requestId) }
                        throw e
                    }
                    // Timeout-style failures are ambiguous: the task may exist.
                    isAmbiguous(e) -> markUnknown(request)
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
     */
    private fun isAmbiguous(e: Throwable): Boolean =
        e is java.io.IOException ||
            e is ApiError.Transport ||
            (e as? ApiError)?.let { it is ApiError.Server && it.status >= 500 } == true

    private suspend fun confirm(
        request: DispatchRef,
        todoId: Int,
        profile: app.codeg.android.core.datastore.ServerProfile,
        scheduleLocalMillis: Long?,
        autoStart: Boolean,
    ): DispatchOutcome {
        val ref = request.copy(remoteTodoId = todoId, state = DispatchState.CONFIRMED)
        dispatchStore.put(ref)
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
}
