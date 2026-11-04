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
 * Which session projects the Chats "全部" aggregate hides (批v160) — the project
 * column of the two-column filter sheet, stacking with the device column
 * ([AggregateDeviceFilterStore]): a row is hidden when its device is hidden OR
 * any project it belongs to is hidden.
 *
 * Same HIDDEN-ids convention as the device store: a freshly created project
 * shows up checked automatically, and a deleted project leaves an inert ghost
 * id behind that matches nothing (no pruning pass needed).
 */
@Singleton
class AggregateProjectFilterStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val hiddenProjects: Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[KEY] ?: emptySet() }

    /** Flip one project in/out of the visible set. */
    suspend fun setVisible(projectId: String, visible: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY] ?: emptySet()
            prefs[KEY] = if (visible) current - projectId else current + projectId
        }
    }

    /** Check every project (clear the filter). */
    suspend fun showAll() {
        dataStore.edit { prefs -> prefs.remove(KEY) }
    }

    /** Uncheck the given projects (the sheet column's hide-all). */
    suspend fun hideAll(projectIds: Collection<String>) {
        if (projectIds.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()) + projectIds
        }
    }

    companion object {
        private val KEY = stringSetPreferencesKey("aggregate_hidden_projects")
    }
}
