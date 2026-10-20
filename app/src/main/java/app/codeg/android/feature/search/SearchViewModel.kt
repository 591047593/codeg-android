package app.codeg.android.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.RecentSearchesStore
import app.codeg.android.core.model.ConversationSummary
import app.codeg.android.core.network.CodegClient
import app.codeg.android.core.network.displayMessage
import dagger.hilt.android.lifecycle.HiltViewModel
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
        viewModelScope.launch { recentStore.add(q) }
    }

    fun useRecent(term: String) {
        _query.value = term
        viewModelScope.launch {
            runSearch(term)
            recentStore.add(term) // promote to most-recent
        }
    }

    fun clearRecent() { viewModelScope.launch { recentStore.clear() } }

    /** Re-run the current query after a failure (drives the inline-error Retry button). */
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
            _ui.update { it.copy(searching = false, error = e.displayMessage()) }
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
