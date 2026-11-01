package app.codeg.android.core.data

import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.network.displayMessage
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Target-picker data shared by every dispatch surface (idea inbox v140, session
 * handoff v141): the visible devices, one device's projects and available agents.
 */
data class DispatchTargets(
    val profiles: List<ServerProfile> = emptyList(),
    val folders: List<FolderDetail> = emptyList(),
    val agents: List<AgentType> = emptyList(),
    val foldersLoading: Boolean = false,
    val error: String? = null,
    /** Agent listing failed for this device (ui重构 §6.3): the sheet shows a
     *  short line + retry; the raw protocol error stays in the log, never in
     *  the form. Dispatch with 自动 stays possible — the agent field is optional
     *  upstream, so a dead list must not dead-end the whole panel. */
    val agentsError: Boolean = false,
)

/**
 * Loads dispatch targets for one device with the v140 precheck semantics: a
 * cheap health call proves URL+token before the user composes anything, then
 * folders + available agents are listed. Pure loader — callers own sequencing
 * (stale-response guards) and UI state.
 */
@Singleton
class DispatchTargetLoader @Inject constructor(
    private val repository: ServerRepository,
) {
    suspend fun load(serverId: String, foldersLoading: Boolean = false): DispatchTargets {
        val profile = try {
            repository.profiles.first().firstOrNull { it.id == serverId }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val client = profile?.let { repository.client(it) }
            ?: return DispatchTargets(error = "无法连接目标设备")
        val health = runCatching { client.health() }
        if (health.isFailure) {
            val e = health.exceptionOrNull()
            return DispatchTargets(
                error = (e as? app.codeg.android.core.network.ApiError)?.displayMessage()
                    ?: e?.message ?: "目标设备不可用",
            )
        }
        val folders = runCatching { client.listFolders() }.getOrElse { emptyList() }
        val agentsResult = runCatching { client.acpListAgents() }
        // §6.3: the raw ACP error (e.g. "No models are available") is diagnostic
        // detail — log it, tell the user only "agent list unavailable", and let
        // the 自动 option keep the form usable.
        val agents = agentsResult.getOrElse { e ->
            android.util.Log.w("DispatchTargetLoader", "acp_list_agents failed: ${e.message}")
            null
        }?.filter { it.available && it.enabled && it.agentType != AgentType.UNKNOWN }
            ?.map { it.agentType }
            ?.distinct()
            ?: emptyList()
        // profiles deliberately NOT set here: the owning VM collects
        // visibleProfiles itself, so a device failure can never blank the chips.
        return DispatchTargets(
            folders = folders,
            agents = agents,
            foldersLoading = foldersLoading,
            agentsError = agentsResult.isFailure,
        )
    }
}
