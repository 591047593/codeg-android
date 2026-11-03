package app.codeg.android.core.datastore

/**
 * 工作台头部两区的折叠态（批v151 引入；批v152 改由设置里的「冷启动默认」派生）。
 *
 * 批v152: the persisted per-session memory retired — the starting state now comes
 * from [AppSettings.workbenchOverviewExpandedDefault] /
 * [AppSettings.workbenchAttentionExpandedDefault], and a manual toggle only lives
 * for the current process (see WorkbenchViewModel).
 */
data class WorkbenchDisplay(
    val overviewCollapsed: Boolean = false,
    val attentionCollapsed: Boolean = false,
)
