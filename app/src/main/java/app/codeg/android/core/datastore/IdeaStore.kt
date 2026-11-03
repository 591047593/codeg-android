package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local idea inbox (batch v140). Ideas are APP-owned drafts — they are NOT Codeg
 * tasks and carry no execution state; after a successful dispatch only a
 * traceability link is kept ([DispatchRef.remoteTodoId]), the task's real state
 * stays on the server.
 */
@Serializable
data class IdeaEntry(
    val id: String,
    val title: String,
    val description: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Optional origin conversation, kept for traceability only. */
    val sourceServerId: String? = null,
    val sourceProjectId: Int? = null,
    val sourceSessionId: String? = null,
)

@Singleton
class IdeaStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val ideas: Flow<List<IdeaEntry>> =
        dataStore.data.map { prefs -> decode(prefs[KEY]).sortedByDescending { it.updatedAt } }

    // 批v153 R4（性能审查）：徽标专用计数流。全库共用一个 Preferences DataStore，
    // 任何 key 的写盘都会让 dataStore.data 重发射；徽标是常驻订阅，先在原始串上
    // distinct 挡掉无关写盘（绝大多数），只有 idea_inbox 真变了才 decode。
    val ideaCount: Flow<Int> =
        dataStore.data
            .map { prefs -> prefs[KEY] }
            .distinctUntilChanged()
            .map { raw -> decode(raw).size }

    suspend fun upsert(idea: IdeaEntry) {
        dataStore.edit { prefs ->
            val list = decode(prefs[KEY]).filterNot { it.id == idea.id } + idea
            prefs[KEY] = json.encodeToString(ListSerializer(IdeaEntry.serializer()), list)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(
                ListSerializer(IdeaEntry.serializer()),
                decode(prefs[KEY]).filterNot { it.id == id },
            )
        }
    }

    private fun decode(raw: String?): List<IdeaEntry> =
        raw?.let { runCatching { json.decodeFromString(ListSerializer(IdeaEntry.serializer()), it) }.getOrNull() }
            ?: emptyList()

    companion object {
        private val KEY = stringPreferencesKey("idea_inbox")
    }
}

/** Where one dispatch stands — the anti-double-create ledger (PRD §7.5). */
enum class DispatchState { PENDING, CONFIRMED, UNKNOWN }

/**
 * One dispatch attempt. [PENDING] = request in flight; [CONFIRMED] = the server
 * returned a task id; [UNKNOWN] = timeout / dropped response — the task may or
 * may not exist, and re-sending is forbidden until the user resolves it
 * (check via work_task_list or discard / re-dispatch deliberately).
 */
@Serializable
data class DispatchRef(
    val requestId: String,
    val ideaId: String? = null,
    val serverId: String,
    val serverName: String = "",
    val folderId: Int,
    val folderName: String = "",
    val title: String,
    val remoteTodoId: Int? = null,
    val state: DispatchState = DispatchState.PENDING,
    val createdAt: Long = 0,
    /** Snapshot of what was actually sent, so a re-dispatch re-sends the same thing. */
    val description: String = "",
    val agentTypeWire: String? = null,
    /** Wall-clock instant (epoch ms, UTC) the request was sent — probe window anchor. */
    val dispatchedAtEpochMs: Long? = null,
    /** Original schedule intent, preserved across a re-dispatch. */
    val scheduleLocalMillis: Long? = null,
    /** Whether an immediate (non-scheduled) send should also start the task.
     * Idea dispatches true; handoff dispatches false (receiving side decides).
     * Default true keeps pre-field ledger rows (idea-era) behaving as before. */
    val autoStart: Boolean = true,
    /** 批v149 会话模式：remoteTodoId 实为会话 id，探针走会话列表而非任务列表，
     * 成功反馈与重派语义随之切换。默认 false 保持旧账本行按任务解读。 */
    val sessionMode: Boolean = false,
)

@Singleton
class DispatchStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val refs: Flow<List<DispatchRef>> =
        dataStore.data.map { prefs ->
            decode(prefs[KEY]).sortedByDescending { it.createdAt }
        }

    suspend fun put(ref: DispatchRef) {
        dataStore.edit { prefs ->
            val list = (decode(prefs[KEY]).filterNot { it.requestId == ref.requestId } + ref)
                // Cap the ledger, but only by evicting CONFIRMED history — an
                // UNKNOWN ref is the sole trace of a possibly-created task and
                // must never be silently dropped.
                .let { all ->
                    val over = all.size - MAX_REFS
                    if (over <= 0) all
                    else {
                        val evict = all.filter { it.state == DispatchState.CONFIRMED }
                            .sortedBy { it.createdAt }.take(over).toSet()
                        if (evict.isEmpty()) all else all.filterNot { it in evict }
                    }
                }
            prefs[KEY] = json.encodeToString(ListSerializer(DispatchRef.serializer()), list)
        }
    }

    suspend fun remove(requestId: String) {
        dataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(
                ListSerializer(DispatchRef.serializer()),
                decode(prefs[KEY]).filterNot { it.requestId == requestId },
            )
        }
    }

    private fun decode(raw: String?): List<DispatchRef> =
        raw?.let { runCatching { json.decodeFromString(ListSerializer(DispatchRef.serializer()), it) }.getOrNull() }
            ?: emptyList()

    companion object {
        private val KEY = stringPreferencesKey("dispatch_refs")
        private const val MAX_REFS = 50
    }
}
