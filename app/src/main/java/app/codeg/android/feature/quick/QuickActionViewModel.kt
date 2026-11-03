package app.codeg.android.feature.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.AttachmentStager
import app.codeg.android.core.data.DispatchOutcome
import app.codeg.android.core.data.DispatchService
import app.codeg.android.core.data.DispatchTargetLoader
import app.codeg.android.core.data.DispatchTargets
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.AppSettings
import app.codeg.android.core.datastore.AppSettingsStore
import app.codeg.android.core.datastore.DispatchRef
import app.codeg.android.core.datastore.DispatchStore
import app.codeg.android.core.model.AgentType
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.feature.dispatch.DispatchFormHost
import app.codeg.android.feature.dispatch.DispatchFormSubmit
import app.codeg.android.feature.dispatch.DispatchFormUi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 宿主 B of the unified dispatch form (批v150 §1.5): the floating ball's
 * 创建任务/创建会话 path. Loads targets + branches exactly like the inbox host
 * and fires [DispatchService.send] with the SAME guards (unresolved-duplicate
 * ledger gate, stale-folder check, past-schedule normalize) — the two hosts must
 * never drift apart on double-create safety.
 *
 * An ambiguous outcome lands in the GLOBAL ledger ([DispatchStore]); the inbox
 * tab's UNKNOWN cards remain the resolution surface (plan §1.5 — the ledger is
 * app-wide, this VM deliberately carries no probe/redispatch machinery).
 */
@HiltViewModel
class QuickActionViewModel @Inject constructor(
    private val dispatchService: DispatchService,
    private val dispatchStore: DispatchStore,
    private val targetLoader: DispatchTargetLoader,
    private val repository: ServerRepository,
    /** 批v155: the dispatch form's 模型 row probes agent modes through this. */
    private val agentOptionsProber: app.codeg.android.core.data.AgentOptionsProber,
    /** 批v154: the shared form stages picked files here. */
    override val attachmentStager: AttachmentStager,
    appSettingsStore: AppSettingsStore,
) : ViewModel(), DispatchFormHost {

    override val settings: StateFlow<AppSettings> = appSettingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.INITIAL)

    private val _formUi = MutableStateFlow(DispatchFormUi())
    override val formUi: StateFlow<DispatchFormUi> = _formUi.asStateFlow()

    private val _targets = MutableStateFlow(DispatchTargets())
    override val targets: StateFlow<DispatchTargets> = _targets.asStateFlow()

    /** B-7 hoisted (round-3 review): the CONFIRMED receipt renders at SHELL
     *  level (MainShell), so a sheet dismissed mid-dispatch can't swallow it. */
    val confirmedRef = MutableStateFlow<DispatchRef?>(null)
    fun confirmCreated(ref: DispatchRef) { confirmedRef.value = ref }
    fun clearConfirmed() { confirmedRef.value = null }

    /** The VM is activity-scoped and outlives the sheet — a stale error line
     *  must not greet the next open. */
    fun clearError() {
        if (!_formUi.value.dispatching) {
            _formUi.value = _formUi.value.copy(error = null)
        }
    }

    init {
        // Same crash-recovery sweep as every dispatch surface (DispatchService
        // contract): a PENDING corpse must not depend on visiting the inbox tab.
        viewModelScope.launch { dispatchService.sweepPendingToUnknown() }
        // Device chips come from the live visible profiles; the loader owns
        // folders/agents per selection. Resubscribe loop: a DataStore failure
        // degrades to a stale list, never a dead VM (v130 class guard).
        viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    repository.visibleProfiles.collect { profiles ->
                        _targets.value = _targets.value.copy(profiles = profiles)
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(2_000)
                }
            }
        }
    }

    // region Target + branch loading (same sequencing rules as the inbox host)

    private var targetSeq = 0

    override fun loadTarget(serverId: String) {
        val seq = ++targetSeq
        viewModelScope.launch {
            _targets.value = _targets.value.copy(folders = emptyList(), agents = emptyList(), foldersLoading = true, error = null)
            val loaded = targetLoader.load(serverId)
            // A slow response for a previously selected device must never
            // overwrite the current device's folder/agent lists.
            if (seq != targetSeq) return@launch
            _targets.value = _targets.value.copy(
                folders = loaded.folders,
                agents = loaded.agents,
                foldersLoading = loaded.foldersLoading,
                error = loaded.error,
                // 批v155(R2C#3): same as host C — a failed list must show its
                // error row + retry, not a silent "无项目/无 Agent".
                agentsError = loaded.agentsError,
                foldersError = loaded.foldersError,
                loadedForServerId = serverId,
            )
        }
    }

    private var branchSeq = 0

    override fun loadBranches(serverId: String, folder: FolderDetail) {
        val seq = ++branchSeq
        val key = "$serverId:${folder.path}"
        _formUi.value = _formUi.value.copy(branchesLoading = true, branchesError = false, branchFolderKey = key)
        viewModelScope.launch {
            // 批v153: the two data-source reads used to run bare — a failed
            // DataStore/Keystore read (disk full/corrupt) would crash the process
            // from a sheet the v151 ball made reachable. Mirror dispatch()'s
            // containment; the network call below keeps its own runCatching, and a
            // null profile/client lands in the existing branchesError path.
            val profile = try {
                repository.profiles.first().firstOrNull { it.id == serverId }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val client = profile?.let {
                try {
                    repository.client(it)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            val list = client?.let { c ->
                runCatching { c.gitListAllBranches(folder.path) }
                    .onFailure { e -> android.util.Log.w("QuickActionViewModel", "git_list_all_branches failed: ${e.message}") }
                    .getOrNull()
            }
            if (seq != branchSeq) return@launch
            _formUi.value = if (list == null) {
                _formUi.value.copy(branchesLoading = false, branchesError = true, branches = emptyList())
            } else {
                _formUi.value.copy(
                    branchesLoading = false,
                    branchesError = false,
                    branches = (list.local + list.remote).distinct(),
                )
            }
        }
    }

    /** 批v155: the shared form's 模型 row — cache-first probe, shared store. */
    override suspend fun probeAgentOptions(
        serverId: String,
        agentType: AgentType,
        workingDir: String?,
    ): app.codeg.android.core.model.AgentOptionsSnapshot? =
        agentOptionsProber.probe(serverId, agentType, workingDir)

    // endregion

    // region Dispatch (guarded thin pipeline — see class kdoc)

    override fun dispatch(submit: DispatchFormSubmit, onDone: (DispatchRef) -> Unit) {
        if (_formUi.value.dispatching) {
            _formUi.value = _formUi.value.copy(error = "正在派发，请稍候")
            return
        }
        if (submit.title.isBlank()) {
            _formUi.value = _formUi.value.copy(error = "标题不能为空")
            return
        }
        // Synchronous set: rapid double-taps can never both pass the guard.
        _formUi.value = _formUi.value.copy(dispatching = true, error = null)
        viewModelScope.launch {
            try {
                // Same ledger gate as the inbox host: an unresolved ref with the
                // same target+title means a previous request's fate is unknown —
                // creating again is the classic double-create (PRD §7.5).
                if (dispatchService.hasUnresolvedDuplicate(submit.serverId, submit.folderId, submit.title)) {
                    fail("存在同目标「结果未知」的派发记录，请先在收件箱的派发记录中检查/放弃后再派发，避免重复创建")
                    return@launch
                }
                // A schedule time that already passed can't be honored — the
                // form's 30s-ticker guard has a stale window; normalize here.
                val effectiveSchedule = submit.scheduleLocalMillis?.takeIf { it > System.currentTimeMillis() }
                // Stale-selection check (v146 invariant, host-owned): the folder
                // list reloads when the device changes; a restored state could
                // carry another device's folder id.
                val folders = _targets.value.folders
                if (folders.isNotEmpty() && folders.none { it.id == submit.folderId }) {
                    fail("目标项目已失效，请重新选择项目后再派发")
                    return@launch
                }
                val outcome = dispatchService.send(
                    DispatchService.SendRequest(
                        ideaId = submit.ideaId,
                        serverId = submit.serverId,
                        folderId = submit.folderId,
                        folderName = submit.folderName,
                        title = submit.title,
                        description = submit.description,
                        agentTypeWire = submit.agentType?.wire,
                        baseBranch = submit.baseBranch,
                        modeId = submit.modeId,
                        scheduleLocalMillis = effectiveSchedule,
                        autoStart = submit.autoStart,
                        sessionMode = submit.sessionMode,
                        attachments = submit.attachments,
                    ),
                )
                when (outcome) {
                    is DispatchOutcome.Confirmed -> {
                        _formUi.value = _formUi.value.copy(dispatching = false)
                        onDone(outcome.ref)
                    }
                    // Ambiguous: the global ledger holds the ref; the inbox tab's
                    // cards resolve it. Never say "failed" here — it may exist.
                    is DispatchOutcome.Unknown -> _formUi.value = _formUi.value.copy(
                        dispatching = false,
                        error = "提交结果未知——已记入收件箱派发记录，请到收件箱检查后再处理",
                    )
                    is DispatchOutcome.HardFailed -> fail(outcome.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "派发失败")
            }
        }
    }

    private fun fail(message: String) {
        _formUi.value = _formUi.value.copy(dispatching = false, error = message)
    }

    // endregion
}
