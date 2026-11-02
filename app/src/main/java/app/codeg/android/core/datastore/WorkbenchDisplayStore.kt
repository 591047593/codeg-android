package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 批v151 工作台头部折叠记忆（跨启动）。无记录/读取失败 = 全部展开。 */
data class WorkbenchDisplay(
    val overviewCollapsed: Boolean = false,
    val attentionCollapsed: Boolean = false,
)

@Singleton
class WorkbenchDisplayStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val display: Flow<WorkbenchDisplay> = dataStore.data.map { prefs ->
        WorkbenchDisplay(
            overviewCollapsed = prefs[OVERVIEW_COLLAPSED] ?: false,
            attentionCollapsed = prefs[ATTENTION_COLLAPSED] ?: false,
        )
    }

    suspend fun setOverviewCollapsed(collapsed: Boolean) {
        dataStore.edit { it[OVERVIEW_COLLAPSED] = collapsed }
    }

    suspend fun setAttentionCollapsed(collapsed: Boolean) {
        dataStore.edit { it[ATTENTION_COLLAPSED] = collapsed }
    }

    private companion object {
        val OVERVIEW_COLLAPSED = booleanPreferencesKey("workbench_overview_collapsed")
        val ATTENTION_COLLAPSED = booleanPreferencesKey("workbench_attention_collapsed")
    }
}
