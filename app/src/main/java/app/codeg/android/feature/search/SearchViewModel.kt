package app.codeg.android.feature.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.RecentSearchesStore
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Global conversation search: debounced server query + persisted recent terms. Port of iOS `SearchView`. */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val recentStore: RecentSearchesStore,
    /** Needed only to resolve the localized failure strings in [displayMessage]. */
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    val recent: StateFlow<List<String>> =
        recentStore.searches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var client: CodegClient? = null
    private var folderNames: Map<Int, String> = emptyMap()

    /**
     * Monotonic token identifying the newest search. Every write to `results` checks it,
     * so a slow response for an older term can't overwrite newer results — the debounce
     * collector was the only path that cancelled its predecessor, while `submit`,
     * `useRecent` and `retry` all write independently.
     */
    private var searchGeneration = 0

    init {
        viewModelScope.launch {
            repository.selectedProfile.collectLatest { profile ->
                val resolved = profile?.let { repository.client(it) }
                client = resolved
                folderNames = resolved?.let { c ->
                    runCatching { c.listFolders() }.getOrNull()?.associate { it.id to it.name }
                } ?: emptyMap()
                // The previous server's results are not this server's results: keep them
                // on screen and the user taps a row whose id only means something to the
                // server they just switched away from.
                searchGeneration += 1
                _ui.update {
                    it.copy(
                        folderNames = folderNames,
                        results = emptyList(),
                        searching = false,
                        query = "",
                        error = null,
                    )
                }
                _query.value = ""
            }
        }
        viewModelScope.launch {
            _query.debounce(300).collectLatest { q -> runSearch(q) }
        }
    }

    fun onQueryChange(value: String) { _query.value = value }

    /**
     * Commit the current term to the recent list.
     *
     * Only deliberate submits land here — pressing the keyboard's search action,
     * tapping a result. This used to be called from [runSearch] on every success,
     * and [runSearch] is driven by a 300 ms debounce on every keystroke, so typing
     * "codeg" recorded "c", "co", "cod", "code", "codeg" as five separate history
     * entries and buried anything the user had actually searched for.
     */
    fun submit() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            // Search first, persist second. Recording before searching meant this
            // coroutine sat on a DataStore write while the debounce collector ran the
            // *newer* term, and this call then wrote the older term's results over it.
            runSearch(q)
            if (_query.value.trim() == q) recentStore.add(q)
        }
    }

    /** Record the current term without re-running the query (tapping a result row). */
    fun rememberCurrentQuery() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch { runCatching { recentStore.add(q) } }
    }

    fun useRecent(term: String) {
        _query.value = term
        viewModelScope.launch {
            runSearch(term)
            // A failed write must not lose the search that just succeeded.
            runCatching { recentStore.add(term) } // promote to most-recent
        }
    }

    fun clearRecent() { viewModelScope.launch { runCatching { recentStore.clear() } } }

    /** Re-run the current query after a failure (drives the inline-error Retry button). */
    /**
     * Delete a conversation from the search results.
     *
     * Removed optimistically and put back if the call fails. The caller confirms first.
     */
    fun delete(conversation: ConversationSummary) {
        val c = client ?: return
        // Re-insert only the row that failed, so a concurrent search or delete that
        // landed while this request was in flight is not rolled back with it.
        val removedIndex = _ui.value.results.indexOfFirst { r -> r.id == conversation.id }
        _ui.update { it.copy(results = it.results.filterNot { r -> r.id == conversation.id }) }
        viewModelScope.launch {
            try {
                c.deleteConversation(conversation.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { state ->
                    if (state.results.any { r -> r.id == conversation.id }) {
                        state.copy(error = e.displayMessage(appContext))
                    } else {
                        state.copy(
                            results = state.results.toMutableList().apply {
                                add(removedIndex.coerceIn(0, size), conversation)
                            },
                            error = e.displayMessage(appContext),
                        )
                    }
                }
            }
        }
    }

    fun retry() { viewModelScope.launch { runSearch(_query.value) } }

    private suspend fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            searchGeneration += 1
            _ui.update { it.copy(results = emptyList(), searching = false, query = "", error = null) }
            return
        }
        val c = client ?: return
        searchGeneration += 1
        val generation = searchGeneration
        _ui.update { it.copy(searching = true, query = q, error = null) }
        try {
            val results = c.listConversations(search = q, sortBy = "updated")
            // A newer search (or a server switch) superseded this one while it was in
            // flight; its answer describes a question the user has moved on from.
            if (generation != searchGeneration) return
            _ui.update { it.copy(results = results, searching = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != searchGeneration) return
            _ui.update { it.copy(searching = false, error = e.displayMessage(appContext)) }
        }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<ConversationSummary> = emptyList(),
    val folderNames: Map<Int, String> = emptyMap(),
    val searching: Boolean = false,
    val error: String? = null,
)
