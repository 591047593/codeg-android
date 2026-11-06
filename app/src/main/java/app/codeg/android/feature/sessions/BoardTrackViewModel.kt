package app.codeg.android.feature.sessions

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.codeg.android.R
import app.codeg.android.core.datastore.BoardTrackStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 批v162 P1: the 「加入任务板 / 移出看板」 switch, shared by every list that shows
 * conversations (Chats, Chats-aggregate, Activity feed, Search).
 *
 * Deliberately its own ViewModel instead of a field on each screen's VM: the
 * membership ledger is a singleton, so one small observable is enough, and the
 * four screens get the SAME behaviour (and the same toast) without four
 * near-identical copies of the read-modify-write.
 */
@HiltViewModel
class BoardTrackViewModel @Inject constructor(
    private val store: BoardTrackStore,
) : ViewModel() {
    /** `<serverId>:<conversationId>` of every member — archived ones included,
     *  because the menu must still offer 移出 for a card hidden by the filter. */
    val trackedKeys: StateFlow<Set<String>> = store.tracked
        .map { members -> members.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggle(serverId: String, conversationId: Int, folderId: Int, onResult: (Boolean) -> Unit = {}) {
        val onBoard = key(serverId, conversationId) in trackedKeys.value
        viewModelScope.launch {
            // A DataStore failure must not crash a list that is only offering a
            // convenience: the next poll simply keeps the old membership. The
            // caller's toast waits for the write — 批v161.2, a failed write
            // must not claim success.
            runCatching {
                if (onBoard) store.untrack(serverId, conversationId)
                else store.track(serverId, conversationId, folderId)
            }
                .onSuccess { onResult(true) }
                .onFailure { onResult(false) }
        }
    }

    companion object {
        fun key(serverId: String, conversationId: Int) = "$serverId:$conversationId"
    }
}

/**
 * What a session row needs from the ledger: one boolean per row and one toggle.
 * A plain class (not the ViewModel) so the row composables stay free of Hilt.
 */
@Stable
class BoardTrackMenu(
    private val keys: Set<String>,
    private val doToggle: (serverId: String, conversationId: Int, folderId: Int) -> Unit,
) {
    fun isOnBoard(serverId: String, conversationId: Int): Boolean =
        BoardTrackViewModel.key(serverId, conversationId) in keys

    fun toggle(serverId: String, conversationId: Int, folderId: Int) {
        doToggle(serverId, conversationId, folderId)
    }
}

/**
 * One call per screen. The toast is the whole point of returning a class: the
 * user must learn that adding does NOT navigate anywhere and removing does NOT
 * delete the session (that asymmetry is the only thing that can surprise here).
 */
@Composable
fun rememberBoardTrackMenu(): BoardTrackMenu {
    val viewModel: BoardTrackViewModel = hiltViewModel()
    val keys by viewModel.trackedKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    return remember(viewModel, keys, context) {
        BoardTrackMenu(keys) { serverId, conversationId, folderId ->
            val onBoard = BoardTrackViewModel.key(serverId, conversationId) in keys
            viewModel.toggle(serverId, conversationId, folderId) { ok ->
                // 批v161.2: success/failure toast — the old code toasted before
                // the write, so a failed DataStore write still claimed 「已加入」.
                Toast.makeText(
                    context,
                    context.getString(
                        when {
                            !ok -> R.string.board_conv_failed
                            onBoard -> R.string.board_conv_remove
                            else -> R.string.board_conv_added
                        },
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
