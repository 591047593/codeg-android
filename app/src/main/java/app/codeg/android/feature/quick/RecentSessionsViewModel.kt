package app.codeg.android.feature.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.codeg.android.core.data.AGG_DEVICE_TIMEOUT_MS
import app.codeg.android.core.data.ServerRepository
import app.codeg.android.core.datastore.ServerProfile
import app.codeg.android.core.model.ConversationSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real aggregate "recent sessions" for the quick-handoff picker (批v150 §1.4):
 * one listConversations round per visible device (aggregate timeout family,
 * per-device failure tolerated), merged and sorted by the server's updatedAt —
 * the same truth the aggregate Chats list shows, one-shot instead of polled.
 * Total per-device failure with zero rows offers a retry; partial results show.
 */
@HiltViewModel
class RecentSessionsViewModel @Inject constructor(
    private val repository: ServerRepository,
) : ViewModel() {

    data class Row(
        val serverId: String,
        val serverName: String,
        val conversationId: Int,
        val title: String,
        val updatedAt: Instant,
    ) {
        val key: String get() = "$serverId:$conversationId"

        /** Compact stamp (MM-dd HH:mm) — a chooser labels rows, no relative math. */
        val timeLabel: String
            get() = DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .format(updatedAt.atZone(ZoneId.systemDefault()))
    }

    data class Ui(
        val loading: Boolean = true,
        /** Every device failed AND nothing came back — offer a retry. */
        val failed: Boolean = false,
        val sessions: List<Row> = emptyList(),
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var generation = 0

    init {
        refresh()
    }

    fun retry() = refresh()

    private fun refresh() {
        val gen = ++generation
        viewModelScope.launch {
            _ui.value = Ui(loading = true)
            val profiles = try {
                repository.visibleProfiles.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (gen != generation) return@launch
            if (profiles.isEmpty()) {
                _ui.value = Ui(loading = false, failed = false, sessions = emptyList())
                return@launch
            }
            var anySuccess = false
            val merged = mutableListOf<Row>()
            try {
                coroutineScope {
                    // Per-device slices, failures isolated (one dead device never
                    // blanks the picker — same rule as the aggregate lists). The
                    // slice carries its profile so rows know their device.
                    val slices = profiles.map { profile -> async { fetchSlice(profile) } }
                    slices.forEach { deferred ->
                        val slice = deferred.await()
                        val rows = slice.rows
                        if (rows != null) {
                            anySuccess = true
                            merged += rows.map { c ->
                                Row(
                                    serverId = slice.profile.id,
                                    serverName = slice.profile.name,
                                    conversationId = c.id,
                                    title = c.trimmedTitle ?: "#${c.id}",
                                    updatedAt = c.updatedAt,
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Scope-level failure: treat as no data this round.
            }
            if (gen != generation) return@launch
            _ui.value = Ui(
                loading = false,
                failed = !anySuccess && merged.isEmpty(),
                sessions = merged.sortedByDescending { it.updatedAt }.take(PICK_LIMIT),
            )
        }
    }

    /** One device's conversation list, or null when the fetch failed/timed out. */
    private suspend fun fetchSlice(profile: ServerProfile): Slice {
        val rows = runCatching {
            val client = repository.client(profile) ?: error("token missing")
            withTimeoutOrNull(AGG_DEVICE_TIMEOUT_MS) { client.listConversations() } ?: error("device timeout")
        }.getOrNull()
        return Slice(profile, rows)
    }

    private class Slice(val profile: ServerProfile, val rows: List<ConversationSummary>?)

    private companion object {
        /** The picker is a chooser, not a browser — top 20 is plenty. */
        const val PICK_LIMIT = 20
    }
}
