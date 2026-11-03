package app.codeg.android.core.data

import app.codeg.android.core.model.AgentOptionsSnapshot
import app.codeg.android.core.model.AgentType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批v155: the model picker inside [app.codeg.android.feature.dispatch.DispatchForm]
 * (and the task-board 新建 form) needs one agent's live mode list for a given
 * device + project — the same `describeAgentOptions` probe the session-detail
 * options sheet uses. The probe spawns the agent CLI server-side and takes
 * seconds, so it routes through [AgentCatalogStore]: one probe per
 * (server, agent, workingDir) shared across every open form.
 *
 * Pure probe — callers own the UI state (loading/error) and staleness (the
 * effect re-runs and re-resolves on key change). A null result means the probe
 * failed; the form shows the model row as unavailable and dispatches without
 * mode_id (upstream defaults). Cache-first: a completed entry answers instantly
 * and no new probe spawns — agent modes are stable per CLI version, and a
 * dispatch form re-opening must not re-pay a multi-second CLI spawn.
 */
@Singleton
class AgentOptionsProber @Inject constructor(
    private val repository: ServerRepository,
    private val catalog: AgentCatalogStore,
) {
    /**
     * Mode/options snapshot for one agent on one device+working dir. Cache hit
     * returns instantly; a miss single-flights the probe on the store's scope
     * (it outlives the calling ViewModel).
     */
    suspend fun probe(serverId: String, agentType: AgentType, workingDir: String?): AgentOptionsSnapshot? {
        // serverId first: worktree paths collide across devices, and a probe
        // result is only valid for the server that produced it (store contract).
        val key = "$serverId|${agentType.wire}|${workingDir.orEmpty()}"
        catalog.get(key)?.let { return it }
        return catalog.getOrProbe(key) {
            val profile = repository.profiles.first().firstOrNull { it.id == serverId }
                ?: return@getOrProbe null
            // client() is nullable (token may be missing) — a missing token is
            // just "probe unavailable", same as any other failure.
            val client = repository.client(profile) ?: return@getOrProbe null
            runCatching { client.describeAgentOptions(agentType, workingDir) }.getOrNull()
        }.await()
    }
}
