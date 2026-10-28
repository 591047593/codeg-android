package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the composer's unfinished text per conversation, across process death.
 *
 * `rememberSaveable` already survives rotation and configuration changes, but it dies
 * with the process: Android reclaims a backgrounded app routinely, and a long prompt —
 * a pasted stack trace, a spec, half a plan — was simply gone the next time the app
 * opened. Switching to another conversation and back lost it too, because each screen
 * has its own saveable slot.
 *
 * Keyed by conversation id so drafts never leak between sessions. [NEW_TASK] holds the
 * not-yet-created conversation's draft (the "new task" composer), which has no id yet.
 *
 * Values are capped: a paste of an entire log file is still a draft worth keeping, but
 * unbounded storage in a preferences file is not. Text past the cap is truncated rather
 * than dropped, so the beginning of what the user wrote is still there to recover.
 */
@Singleton
class DraftStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** The draft for [conversationId], or the new-task draft when it is null. */
    fun draft(conversationId: Int?): Flow<String> =
        dataStore.safeData().map { it[keyFor(conversationId)] ?: "" }

    suspend fun current(conversationId: Int?): String =
        draft(conversationId).first()

    suspend fun save(conversationId: Int?, text: String) {
        dataStore.edit { prefs ->
            val key = keyFor(conversationId)
            val trimmed = text.take(MAX_CHARS)
            // Blank means "nothing to restore" — storing an empty string would keep a
            // useless entry per conversation forever.
            if (trimmed.isBlank()) prefs.remove(key) else prefs[key] = trimmed
        }
    }

    suspend fun clear(conversationId: Int?) {
        dataStore.edit { it.remove(keyFor(conversationId)) }
    }

    private fun keyFor(conversationId: Int?) =
        stringPreferencesKey("composer_draft_${conversationId ?: NEW_TASK}")

    private companion object {
        const val NEW_TASK = "new"
        /** ~256 KB of text: far past any real prompt, small enough for a prefs file. */
        const val MAX_CHARS = 256 * 1024
    }
}
