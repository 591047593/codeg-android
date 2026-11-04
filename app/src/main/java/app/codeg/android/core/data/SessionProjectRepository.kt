package app.codeg.android.core.data

import app.codeg.android.core.datastore.SessionProject
import app.codeg.android.core.datastore.SessionProjectStore
import app.codeg.android.core.datastore.SessionRef
import app.codeg.android.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 批v160 W9: sentinel scope id for 「未添加」 — the pseudo-project of every
 * session that belongs to NO project at all. Legal value of
 * [activeProjectId]; never a real project, and [setActive] accepts it without
 * existence validation.
 */
const val UNASSIGNED_PROJECT_ID = "__unassigned__"

/**
 * UI-facing holder for the cross-device session projects: the project list and
 * membership (persisted via [SessionProjectStore]) plus the currently active
 * filter (in-memory, like [ServerRepository.activityAll]). Lives in core/data
 * so feature ViewModels and MainShell share one instance — Hilt ViewModels
 * cannot inject each other.
 *
 * Invariant (batch v132 review #3): `activeProjectId != null` only while the
 * showing surface is in an aggregate ("全部") mode; MainShell clears it whenever
 * the UI leaves that context so a single-device list is never silently filtered.
 */
@Singleton
class SessionProjectRepository @Inject constructor(
    private val store: SessionProjectStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    // .catch guards: these share on the APPLICATION scope (no VM to die with),
    // so an upstream DataStore failure here would kill the whole process from
    // any screen. Degrade to the empty initial instead.
    val projects: StateFlow<List<SessionProject>> =
        store.projects
            .map { list -> list.sortedBy { it.sortOrder } }
            .catch { /* keep the empty initial */ }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val members: StateFlow<Map<String, List<SessionRef>>> =
        store.members
            .catch { /* keep the empty initial */ }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** refKey → the project ids holding that session — [members] inverted once,
     *  the shape the row-level hidden-project filters consult. Shared by the
     *  Chats and Activity aggregates (R2-B P2-1) so both use one inversion. */
    val memberProjects: Flow<Map<String, Set<String>>> =
        members.map { mem ->
            buildMap<String, MutableSet<String>> {
                for ((projectId, refs) in mem) {
                    for (ref in refs) getOrPut(ref.key) { mutableSetOf() }.add(projectId)
                }
            }
        }

    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    /** Member keys ("serverId:conversationId") of the active project, or null
     *  when no filter is active — the exact shape the section builders want.
     *  The 未添加 sentinel yields null here: its complement-narrowing rides
     *  [activeExcludeKeys] instead (a key-set can't express "everything else").
     *  A real id with zero members yields an EMPTY set — the scope matches
     *  nothing — never null, which would mean "no filter" (R1-B P2-3). */
    val activeMemberKeys: Flow<Set<String>?> =
        combine(_activeProjectId, members) { id, mem ->
            id?.takeIf { it != UNASSIGNED_PROJECT_ID }
                ?.let { pid -> mem[pid]?.map { it.key }?.toSet() ?: emptySet() }
        }

    /** 批v160 W9: the complement of [activeMemberKeys] — every ASSIGNED ref key,
     *  non-null only while the 未添加 scope is active. List layers filter
     *  `refKey !in keys` with it. */
    val activeExcludeKeys: Flow<Set<String>?> =
        combine(_activeProjectId, members) { id, mem ->
            if (id == UNASSIGNED_PROJECT_ID) {
                mem.values.flatten().map { it.key }.toSet()
            } else {
                null
            }
        }

    fun isUnassignedActive(): Boolean = _activeProjectId.value == UNASSIGNED_PROJECT_ID

    fun setActive(id: String?) {
        _activeProjectId.value = when {
            id == null -> null
            id == UNASSIGNED_PROJECT_ID -> UNASSIGNED_PROJECT_ID
            // A deleted project id degrades to no filter, never a phantom scope.
            else -> id.takeIf { pid -> projects.value.any { it.id == pid } }
        }
    }

    /**
     * Activate an id straight out of [create] WITHOUT re-validating against the
     * [projects] StateFlow: that snapshot is fed on the ApplicationScope and lags
     * the DataStore write by ≥1 dispatcher hop, so `setActive` on a just-created
     * id deterministically drops it (R3-1, proven by test). Callers must only pass
     * ids returned by [create].
     */
    fun activateCreated(id: String) {
        _activeProjectId.value = id
    }

    fun projectById(id: String?): SessionProject? = projects.value.firstOrNull { it.id == id }

    /** Project ids a session belongs to (drives the add-to-project checkmarks). */
    fun projectIdsOf(refKey: String): Set<String> =
        members.value.filterValues { refs -> refs.any { it.key == refKey } }.keys

    fun memberCount(projectId: String): Int = members.value[projectId]?.size ?: 0

    suspend fun create(name: String): SessionProject {
        val project = SessionProject(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            createdAt = System.currentTimeMillis(),
            sortOrder = (projects.value.maxOfOrNull { it.sortOrder } ?: 0) + 1,
        )
        store.create(project)
        return project
    }

    suspend fun rename(id: String, name: String) = store.rename(id, name.trim())

    suspend fun remove(id: String) {
        if (_activeProjectId.value == id) _activeProjectId.value = null
        store.removeProject(id)
    }

    suspend fun setMembership(projectId: String, ref: SessionRef, member: Boolean) =
        store.setMembership(projectId, ref, member)

    /** Session deleted on its device — drop the ref everywhere (review #8). */
    suspend fun removeRef(serverId: String, conversationId: Int) =
        store.removeRef(serverId, conversationId)

    /** Device deleted — drop all its refs (review #8). */
    suspend fun removeRefsForServer(serverId: String) = store.removeRefsForServer(serverId)

    /**
     * Prune stale refs for one server. Call ONLY with a complete (untruncated)
     * conversation list — `take(200)` output must not prune (review #5).
     */
    suspend fun pruneServerIfComplete(serverId: String, liveKeys: Set<String>, truncated: Boolean) {
        if (!truncated) store.pruneServer(serverId, liveKeys)
    }
}
