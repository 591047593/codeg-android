package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which devices the Chats "全部" aggregate hides (批v154).
 *
 * The user asked for a multi-select device filter next to the project bookmark:
 * everything is checked by default, unchecking a device removes its sections from
 * the list. This is a pure VIEW filter, exactly like the per-server hidden
 * sections in [ChatListDisplayStore] — the fan-out keeps asking every device, so
 * re-checking one costs no round trip.
 *
 * Stored as HIDDEN ids (not shown ones): a freshly paired device then shows up
 * automatically instead of being invisibly filtered by a stale "shown" list. A
 * deleted device leaves an inert ghost id behind — matching an id that no longer
 * participates is a no-op, so no pruning pass is needed.
 */
@Singleton
class AggregateDeviceFilterStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val hiddenDevices: Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[KEY] ?: emptySet() }

    /** Flip one device in/out of the visible set. */
    suspend fun setVisible(serverId: String, visible: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY] ?: emptySet()
            prefs[KEY] = if (visible) current - serverId else current + serverId
        }
    }

    /** Check every device (clear the filter). */
    suspend fun showAll() {
        dataStore.edit { prefs -> prefs.remove(KEY) }
    }

    /** Uncheck the given devices (the sheet column's hide-all, 批v160). */
    suspend fun hideAll(serverIds: Collection<String>) {
        if (serverIds.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()) + serverIds
        }
    }

    companion object {
        private val KEY = stringSetPreferencesKey("aggregate_hidden_devices")
    }
}