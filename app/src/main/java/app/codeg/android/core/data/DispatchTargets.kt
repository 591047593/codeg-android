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
        val agents = runCatching { client.acpListAgents() }
            .getOrDefault(emptyList())
            .filter { it.available && it.enabled && it.agentType != AgentType.UNKNOWN }
            .map { it.agentType }
            .distinct()
        // profiles deliberately NOT set here: the owning VM collects
        // visibleProfiles itself, so a device failure can never blank the chips.
        return DispatchTargets(
            folders = folders,
            agents = agents,
            foldersLoading = foldersLoading,
        )
    }
}
