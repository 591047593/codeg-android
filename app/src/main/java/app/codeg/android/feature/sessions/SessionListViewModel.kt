package app.codeg.android.feature.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.model.ConversationTag
import app.codeg.android.core.model.FolderDetail
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Drives the Chats (session list) screen: loads folders + conversations for the
 * selected server and keeps the full list in memory so grouping / pin toggles
 * stay instant. Faithful port of the iOS `SessionListViewModel` — the grouped
 * display (Pinned / per-folder / Other) is derived by [SessionGrouping].
 *
 * Rebinds automatically when the selected server changes (or is edited in place
 * to a new endpoint): a monotonic [fetchGeneration] guards against a slow fetch
 * clobbering a newer one.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(SessionListUiState())
    val ui: StateFlow<SessionListUiState> = _ui.asStateFlow()

    /**
     * The grouped, fully-sorted sections the Chats list renders (Pinned / per-folder
     * / Other). Derived from [_ui] but recomputed ONLY when the folders/conversations
     * actually change — a refresh-spinner or error toggle keeps the same list
     * references, so [distinctUntilChanged] short-circuits and nothing re-sorts. The
     * O(n·log n) grouping runs on [Dispatchers.Default], never on the main thread or
     * during composition, which is what keeps a very large session list smooth.
     */
    val sections: StateFlow<List<SessionSection>> =
        _ui
            .map {
                GroupingInput(
                    folders = it.folders,
                    conversations = it.conversations,
                    expandedParents = it.expandedParents,
                    childrenByParent = it.childrenByParent,
                )
            }
            .distinctUntilChanged()
            .map {
                buildSessionSections(
                    folders = it.folders,
                    allConversations = it.conversations,
                    expandedParents = it.expandedParents,
                    childrenByParent = it.childrenByParent,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The only [_ui] fields the grouping depends on, so spinner/error toggles don't re-sort. */
    private data class GroupingInput(
        val folders: List<FolderDetail>,
        val conversations: List<ConversationSummary>,
        val expandedParents: Set<Int> = emptySet(),
        val childrenByParent: Map<Int, List<ConversationSummary>> = emptyMap(),
    )

    private var client: CodegClient? = null
    private var loadedEndpoint: String? = null
    private var fetchGeneration = 0

    init {
        viewModelScope.launch {
            repository.selectedProfile.collectLatest { profile -> onProfile(profile) }
        }
        // Refetch when a conversation is renamed/pinned/status-changed/deleted elsewhere.
        // A change that lands while a fetch is already running is remembered and applied
        // when that fetch finishes, instead of being dropped: the previous version
        // consumed the event and did nothing, so a rename made during a pull-to-refresh
        // left the old title on screen until some unrelated action refreshed the list.
        viewModelScope.launch {
            repository.conversationsChanged
                .conflate()
                .collect {
                    if (client == null) return@collect
                    if (_ui.value.isBusy) {
                        pendingRefresh = true
                    } else {
                        fetch(isInitial = false)
                        drainPendingRefresh()
                    }
                }
        }
    }

    /** A change arrived while a fetch was in flight; apply it once that fetch settles. */
    private var pendingRefresh = false

    /** Guards against the re-entrant drain: fetch's `finally` calls back into here. */
    private var drainingRefresh = false

    private suspend fun drainPendingRefresh() {
        if (drainingRefresh) return
        drainingRefresh = true
        try {
            while (pendingRefresh) {
                pendingRefresh = false
                if (client != null && !_ui.value.isBusy) fetch(isInitial = false)
            }
        } finally {
            drainingRefresh = false
        }
    }

    private suspend fun onProfile(profile: ServerProfile?) {
        if (profile == null) {
            client = null
            loadedEndpoint = null
            _ui.value = SessionListUiState()
            return
        }
        val endpoint = "${profile.id}|${profile.baseUrl}"
        val changed = endpoint != loadedEndpoint
        loadedEndpoint = endpoint
        val resolved = repository.client(profile)
        client = resolved
        if (resolved == null) {
            // No token for this server. The list on screen belongs to whichever server
            // was selected before, so it must be cleared rather than kept under the new
            // server's title — tapping one of those rows would resolve a foreign
            // conversation id against the new server.
            _ui.value = SessionListUiState(
                hasLoaded = true,
                isLoading = false,
                error = appContext.getString(R.string.sessions_missing_token),
            )
            return
        }
        fetch(isInitial = true, force = changed)
    }

    /**
     * Pull-to-refresh / toolbar refresh — keeps the current list on screen.
     *
     * Also drives the Retry button on the full-screen error, which is only shown before
     * anything has loaded. That case has to run the *initial* path: the non-initial one
     * only sets `isRefreshing`, so the screen would clear its error and fall through to
     * an empty list with a pull indicator instead of a spinner.
     */
    fun refresh() {
        if (_ui.value.isBusy) return
        val initial = !_ui.value.hasLoaded
        viewModelScope.launch { fetch(isInitial = initial) }
    }

    private suspend fun fetch(isInitial: Boolean, force: Boolean = false) {
        val active = client ?: return
        fetchGeneration += 1
        val token = fetchGeneration
        if (force) {
            // Tags belong to the server that defined them: ids are per-database, so
            // keeping the old set across a switch would paint rows with another
            // workspace's colours for as long as the fetch takes.
            _ui.update {
                it.copy(folders = emptyList(), conversations = emptyList(), tags = emptyList(), hasLoaded = false)
            }
        }
        _ui.update {
            if (isInitial) it.copy(isLoading = true, error = null)
            else it.copy(isRefreshing = true, error = null)
        }
        try {
            val result = coroutineScope {
                val folders = async { active.listFolders() }
                val conversations = async { active.listConversations() }
                // Tags ride along with every fetch rather than in their own request: the
                // rows render colour dots from them, and a list that painted before the
                // definitions arrived would flicker the dots in a moment later. A tag
                // endpoint that fails must not take the list down with it, hence the
                // default inside the async.
                val tags = async { runCatching { active.listConversationTags() }.getOrDefault(emptyList()) }
                Triple(folders.await(), conversations.await(), tags.await())
            }
            if (token != fetchGeneration) return
            _ui.update {
                it.copy(
                    folders = result.first,
                    conversations = result.second,
                    tags = result.third,
                    hasLoaded = true,
                    isLoading = false,
                    isRefreshing = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token != fetchGeneration) return
            _ui.update { it.copy(isLoading = false, isRefreshing = false, error = e.displayMessage(appContext)) }
        } finally {
            // A change notification that arrived while this fetch was running applies now.
            if (pendingRefresh) drainPendingRefresh()
        }
    }

    /**
     * Optimistically pin/unpin, then persist. On failure the local change reverts
     * and an error is surfaced. The optimistic `pinnedAt` is a local stand-in for
     * ordering; the next refresh replaces it with the server's value.
     */
    fun setPinned(conversation: ConversationSummary, pinned: Boolean) {
        val active = client ?: return
        // Remember only this row's previous pin so a failure reverts only this row.
        // Restoring the whole captured list put back a snapshot taken before the
        // request: a concurrent pin of another row, a pull-to-refresh that arrived
        // meanwhile, or a server switch would all be undone — resurrecting deleted
        // rows, dropping a newer pin, or repopulating the screen with another
        // server's conversations.
        val previousPinnedAt = conversation.pinnedAt
        _ui.update { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.id == conversation.id) it.copy(pinnedAt = if (pinned) Instant.now() else null) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                active.setPinned(conversation.id, pinned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { state ->
                    // Revert only if this row still exists and the server it belongs to is
                    // still the selected one.
                    if (client !== active) {
                        state
                    } else {
                        state.copy(
                            conversations = state.conversations.map {
                                if (it.id == conversation.id) it.copy(pinnedAt = previousPinnedAt) else it
                            },
                            error = e.displayMessage(appContext),
                        )
                    }
                }
            }
        }
    }

    /**
     * Delete a conversation from the list.
     *
     * Removed optimistically and restored by the next refresh if the call fails, so the
     * row does not linger for a round trip. The caller confirms first.
     */
    fun delete(conversation: ConversationSummary) {
        val active = client ?: return
        // Capture only this row's index so a failure re-inserts THAT row instead of
        // restoring a snapshot of the whole list. Restoring the captured list undoes
        // anything that landed meanwhile — a concurrent delete, a pin, a refresh that
        // added a new conversation, or a server switch — which is the same defect the
        // pin path above already avoids by reverting a single field.
        val previous = _ui.value.conversations
        val removedIndex = previous.indexOfFirst { it.id == conversation.id }
        _ui.update { it.copy(conversations = it.conversations.filterNot { c -> c.id == conversation.id }) }
        viewModelScope.launch {
            try {
                active.deleteConversation(conversation.id)
                repository.notifyConversationsChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { state ->
                    // Only touch the list if it still belongs to the same server, and only
                    // re-insert the row that actually failed — and only if it is still
                    // missing, so a refresh that already brought it back is left alone.
                    if (client !== active) {
                        state
                    } else if (state.conversations.any { it.id == conversation.id }) {
                        state.copy(error = e.displayMessage(appContext))
                    } else {
                        state.copy(
                            conversations = state.conversations.toMutableList().apply {
                                add(removedIndex.coerceIn(0, size), conversation)
                            },
                            error = e.displayMessage(appContext),
                        )
                    }
                }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /**
     * Show or hide a conversation's delegation children inline.
     *
     * Children are fetched the first time a row is expanded and then kept, so toggling
     * twice costs one request. The desktop sidebar does the same thing with a chevron
     * driven by the parent's `child_count`; here the rows are indented into the same
     * flat list instead of opening a subtree.
     */
    fun toggleChildren(conversation: ConversationSummary) {
        val id = conversation.id
        if (id in _ui.value.expandedParents) {
            _ui.update { it.copy(expandedParents = it.expandedParents - id) }
            return
        }
        _ui.update { it.copy(expandedParents = it.expandedParents + id) }
        if (_ui.value.childrenByParent.containsKey(id)) return

        val c = client ?: return
        _ui.update { it.copy(loadingChildren = it.loadingChildren + id) }
        viewModelScope.launch {
            val fetched = runCatching { c.listChildConversations(id) }.getOrNull()
            _ui.update { state ->
                state.copy(
                    childrenByParent = if (fetched != null) {
                        state.childrenByParent + (id to fetched)
                    } else {
                        state.childrenByParent
                    },
                    loadingChildren = state.loadingChildren - id,
                    // A failed fetch collapses the row again rather than leaving an
                    // expanded parent with nothing under it.
                    expandedParents = if (fetched == null) state.expandedParents - id else state.expandedParents,
                    error = if (fetched == null) state.error else state.error,
                )
            }
        }
    }
}

/** Raw list state; the grouped display is derived by [SessionGrouping]. */
data class SessionListUiState(
    val folders: List<FolderDetail> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    /**
     * Delegation children already fetched, by parent id.
     *
     * Loaded on demand when a row with `childCount > 0` is expanded, then kept so
     * collapsing and re-expanding does not refetch.
     */
    val childrenByParent: Map<Int, List<ConversationSummary>> = emptyMap(),
    /** Parents whose children are currently shown inline. */
    val expandedParents: Set<Int> = emptySet(),
    /** Parents whose children are being fetched, so the row can show a spinner. */
    val loadingChildren: Set<Int> = emptySet(),
    /**
     * Every tag definition, so a row can paint its `tagIds` as coloured dots.
     *
     * A conversation carries ids only; without the definitions a tagged row would look
     * untagged, so both travel in the same fetch.
     */
    val tags: List<ConversationTag> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isLoading || isRefreshing

    /**
     * Empty ⟺ no conversations: every conversation lands in some section (Pinned, a
     * folder group, or Other), so folders-but-no-sessions correctly shows the empty
     * state instead of a wall of empty folder headers (matches iOS). Reads raw [_ui]
     * fields, so the decision stays atomic with the data and never flickers against
     * the separately-derived [SessionListViewModel.sections] flow.
     */
    val isEmpty: Boolean get() = conversations.isEmpty()
}

/** One folder and the (non-pinned) conversations shown under its group. */
data class FolderGroup(
    val folder: FolderDetail,
    val conversations: List<ConversationSummary>,
)

/**
 * Pure grouping/filtering of a session list, ported 1:1 from the iOS
 * `SessionListViewModel` derived-data accessors. Pinned conversations appear
 * only in the Pinned group; each folder group lists its non-pinned
 * conversations newest-first.
 */
object SessionGrouping {

    fun sortedFolders(folders: List<FolderDetail>): List<FolderDetail> =
        folders.sortedWith(
            compareBy({ it.sortOrder }, { it.name.lowercase() }),
        )

    /** `folderId -> name` for the dim folder tag shown on cross-folder rows. */
    fun folderNames(folders: List<FolderDetail>): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (folder in folders) out.putIfAbsent(folder.id, folder.name)
        return out
    }

    /** Pinned conversations across all folders, most-recently-pinned first. */
    fun pinned(conversations: List<ConversationSummary>, search: String = ""): List<ConversationSummary> =
        matching(conversations.filter { it.isPinned }, search)
            .sortedByDescending { it.pinnedAt ?: Instant.MIN }

    /**
     * One group per folder (in [sortedFolders] order) holding that folder's
     * non-pinned conversations, newest-first. With no search every folder is
     * included; when searching, folders with no match are dropped.
     */
    fun folderGroups(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        search: String = "",
    ): List<FolderGroup> {
        val searching = search.trim().isNotEmpty()
        val unpinnedByFolder = conversations.filter { !it.isPinned }.groupBy { it.folderId }
        return sortedFolders(folders).mapNotNull { folder ->
            val convs = matching(unpinnedByFolder[folder.id] ?: emptyList(), search)
                .sortedByDescending { it.updatedAt }
            if (searching && convs.isEmpty()) null else FolderGroup(folder, convs)
        }
    }

    /** Non-pinned conversations whose folder isn't in the folder list (orphans). */
    fun ungrouped(
        folders: List<FolderDetail>,
        conversations: List<ConversationSummary>,
        search: String = "",
    ): List<ConversationSummary> {
        val known = folders.map { it.id }.toSet()
        return matching(
            conversations.filter { !it.isPinned && it.folderId !in known },
            search,
        ).sortedByDescending { it.updatedAt }
    }

    private fun matching(convs: List<ConversationSummary>, search: String): List<ConversationSummary> {
        val query = search.trim()
        if (query.isEmpty()) return convs
        return convs.filter { conv ->
            (conv.trimmedTitle?.contains(query, ignoreCase = true) == true) ||
                (conv.model?.contains(query, ignoreCase = true) == true)
        }
    }
}
