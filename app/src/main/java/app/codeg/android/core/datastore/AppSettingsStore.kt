package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Light / Dark / System appearance, mirroring the iOS `AppearanceMode`. */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** App-wide appearance preferences. [accentId] maps to a design-system palette. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentId: String = DEFAULT_ACCENT,
    /** Bottom-tab display order as route names ("chats", "folders", …). Missing
     *  entries fall back to their enum order; unknown names are dropped. */
    val homeTabOrder: List<String> = emptyList(),
    /** 批v152: bottom-tab routes hidden by the user (Settings shows all five, the
     *  bar/rail renders `all - hidden`). SETTINGS is never hideable — the settings
     *  page is the only way back, so it is filtered out of the stored list too. */
    val hiddenHomeTabs: List<String> = emptyList(),
    /** Route name of the tab the app opens on cold start (default: activity). */
    val startTab: String = DEFAULT_START_TAB,
    /** 批v152: workbench 总览区 (stats + quick row) folded by default on cold start.
     *  A per-session manual toggle still wins for the rest of the process. */
    val workbenchOverviewExpandedDefault: Boolean = true,
    /** 批v152: workbench 需要处理区 folded by default on cold start. */
    val workbenchAttentionExpandedDefault: Boolean = true,
    /** Horizontal swipe between devices while on the Activity tab (default: off).
     *  批v150: the Folders twin toggle retired with the Folders tab. */
    val activityDeviceSwipe: Boolean = false,
    /** Floating ball on/off (batch v126; default on — it's a primary nav surface). */
    val floatBallEnabled: Boolean = true,
    /** Floating ball size tier: "small" | "medium" | "large" (unknown → medium). */
    val floatBallSize: String = DEFAULT_FLOAT_BALL_SIZE,
    /** Inbox "new idea" button placement (批v151): "corner" = classic M3 FAB
     *  bottom-right (default) | "centerWide" = bottom-center pill half the
     *  screen wide (either thumb reaches it). */
    val inboxNewIdeaStyle: String = DEFAULT_INBOX_IDEA_STYLE,
    /** Multiplier ladder over the system font scale (0.85 / 1.0 / 1.15 / 1.3). */
    val fontScale: Float = 1f,
    /** Dispatch-sheet defaults (v1.0.1-fengx.34): preselected device id, folder
     *  id/name and agent wire name. Blank device / id <= 0 / blank agent = unset
     *  (the sheet opens with its previous no-default behavior). */
    val dispatchDefaultDevice: String = "",
    val dispatchDefaultFolderId: Int = -1,
    val dispatchDefaultFolderName: String = "",
    val dispatchDefaultAgent: String = "",
) {
    companion object {
        // 批v150 §2.1 克制的青蓝：持久化层默认值必须与 AccentPalette.DEFAULT 的
        // id 一致（那边只兜「未知 id」，新用户拿到的是这里的值）。显式选过
        // 配色的用户存的是自己的 id，不受影响。
        const val DEFAULT_ACCENT = "teal"
        // The app opens on the workbench unless the user picked another start tab.
        const val DEFAULT_START_TAB = "activity"
        const val DEFAULT_FLOAT_BALL_SIZE = "medium"
        const val DEFAULT_INBOX_IDEA_STYLE = INBOX_IDEA_STYLE_CORNER
        /** The initial value [AppSettingsStore.settings] exposes before DataStore's
         *  first emission; used to detect "settings not loaded yet". */
        val INITIAL = AppSettings()
    }
}

/** The five tab routes in the enum's default order. Kept as plain strings so the
 *  data layer stays free of UI types (feature/main's HomeTab mirrors this list). */
private val HOME_TAB_ROUTES = listOf("activity", "chats", "inbox", "tasks", "settings")

/**
 * 批v152 read-side migration: orders saved before 会话 returned as a tab never
 * mention its route, and the tab order resolver appends unknown tabs AFTER the
 * saved ones — which would park 会话 behind 设置. Insert it right after 动态 so an
 * upgraded bar matches the fresh-install default instead of a surprise tail slot.
 */
internal fun migrateHomeTabOrder(saved: List<String>): List<String> {
    if (saved.isEmpty() || "chats" in saved) return saved
    val after = saved.indexOf("activity")
    return if (after < 0) listOf("chats") + saved
    else saved.toMutableList().apply { add(after + 1, "chats") }
}

/** The order a cold start/bar renders: saved known routes first, then every tab
 *  the order does not mention yet (same rule as HomeTab.orderedBy). */
private fun resolvedTabOrder(saved: List<String>): List<String> =
    (saved.filter { it in HOME_TAB_ROUTES } + HOME_TAB_ROUTES.filterNot { it in saved }).distinct()

/**
 * Persists appearance + shell settings (theme, accent, bottom-tab order, cold-start
 * tab). Applied app-wide instantly; never synced to the server (matches iOS).
 */
@Singleton
class AppSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Last settings emission (null until DataStore's first read). A ViewModel
     *  created after the app reports settings loaded can seed derived state from
     *  the real values instead of flashing the defaults for a frame. */
    @Volatile
    private var latest: AppSettings? = null
    val current: AppSettings? get() = latest

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.from(prefs[MODE]),
            accentId = prefs[ACCENT] ?: AppSettings.DEFAULT_ACCENT,
            // Comma-joined route names: DataStore's stringSet doesn't guarantee order,
            // and the whole point of this pref is the ordering. Route names contain
            // no commas.
            homeTabOrder = migrateHomeTabOrder(
                prefs[TAB_ORDER]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            ),
            hiddenHomeTabs = prefs[HIDDEN_TABS]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            startTab = prefs[START_TAB] ?: AppSettings.DEFAULT_START_TAB,
            workbenchOverviewExpandedDefault = prefs[WORKBENCH_OVERVIEW_DEFAULT] ?: true,
            workbenchAttentionExpandedDefault = prefs[WORKBENCH_ATTENTION_DEFAULT] ?: true,
            activityDeviceSwipe = prefs[ACTIVITY_DEVICE_SWIPE] ?: false,
            floatBallEnabled = prefs[FLOAT_BALL_ENABLED] ?: true,
            floatBallSize = prefs[FLOAT_BALL_SIZE]?.takeIf { it in FLOAT_BALL_SIZES } ?: AppSettings.DEFAULT_FLOAT_BALL_SIZE,
            inboxNewIdeaStyle = prefs[INBOX_NEW_IDEA_STYLE]?.takeIf { it in INBOX_IDEA_STYLES }
                ?: AppSettings.DEFAULT_INBOX_IDEA_STYLE,
            fontScale = (prefs[FONT_SCALE] ?: 1f).coerceIn(0.5f, 2f),
            dispatchDefaultDevice = prefs[DISPATCH_DEFAULT_DEVICE] ?: "",
            dispatchDefaultFolderId = prefs[DISPATCH_DEFAULT_FOLDER_ID] ?: -1,
            dispatchDefaultFolderName = prefs[DISPATCH_DEFAULT_FOLDER_NAME] ?: "",
            dispatchDefaultAgent = prefs[DISPATCH_DEFAULT_AGENT] ?: "",
        ).also { latest = it }
    }

    /** Persist the dispatch-sheet defaults in one write (they change together). */
    suspend fun setDispatchDefaults(
        device: String,
        folderId: Int,
        folderName: String,
        agentWire: String,
    ) {
        dataStore.edit {
            it[DISPATCH_DEFAULT_DEVICE] = device
            it[DISPATCH_DEFAULT_FOLDER_ID] = folderId
            it[DISPATCH_DEFAULT_FOLDER_NAME] = folderName
            it[DISPATCH_DEFAULT_AGENT] = agentWire
        }
    }

    /** Clear the dispatch defaults (sheet falls back to no preselection). */
    suspend fun clearDispatchDefaults() {
        dataStore.edit {
            it.remove(DISPATCH_DEFAULT_DEVICE)
            it.remove(DISPATCH_DEFAULT_FOLDER_ID)
            it.remove(DISPATCH_DEFAULT_FOLDER_NAME)
            it.remove(DISPATCH_DEFAULT_AGENT)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[MODE] = mode.id }
    }

    suspend fun setAccent(accentId: String) {
        dataStore.edit { it[ACCENT] = accentId }
    }

    /** Persist the bottom-tab order as an ordered list of route names. */
    suspend fun setHomeTabOrder(routes: List<String>) {
        dataStore.edit { it[TAB_ORDER] = routes.joinToString(",") }
    }

    /** Persist the cold-start tab by route name. */
    suspend fun setStartTab(route: String) {
        dataStore.edit { it[START_TAB] = route }
    }

    /** 批v152: hide/show a bottom tab by route. SETTINGS is force-kept — without
     *  it the settings page itself would be unreachable (self-lock). */
    suspend fun setTabHidden(route: String, hidden: Boolean) {
        if (route == "settings") return
        dataStore.edit { prefs ->
            val current = prefs[HIDDEN_TABS]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            val next = if (hidden) (current + route).distinct() else current - route
            prefs[HIDDEN_TABS] = next.joinToString(",")
            // A hidden tab cannot be a cold-start destination: repoint the saved
            // start tab to the first still-visible one (SETTINGS can never be
            // hidden, so the fallback list is non-empty). The running shell keeps
            // its bound start destination — its bar renders regardless (MainShell).
            if (hidden && prefs[START_TAB] == route) {
                val visible = resolvedTabOrder(
                    prefs[TAB_ORDER]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
                ).filterNot { it in next }
                prefs[START_TAB] = visible.firstOrNull() ?: "settings"
            }
        }
    }

    /** 批v152: cold-start default for the workbench 总览区. */
    suspend fun setWorkbenchOverviewExpandedDefault(expanded: Boolean) {
        dataStore.edit { it[WORKBENCH_OVERVIEW_DEFAULT] = expanded }
    }

    /** 批v152: cold-start default for the workbench 需要处理区. */
    suspend fun setWorkbenchAttentionExpandedDefault(expanded: Boolean) {
        dataStore.edit { it[WORKBENCH_ATTENTION_DEFAULT] = expanded }
    }

    /** Persist the Activity-tab device-swipe toggle. */
    suspend fun setActivityDeviceSwipe(enabled: Boolean) {
        dataStore.edit { it[ACTIVITY_DEVICE_SWIPE] = enabled }
    }

    /** Persist the floating-ball on/off toggle (batch v126). */
    suspend fun setFloatBallEnabled(enabled: Boolean) {
        dataStore.edit { it[FLOAT_BALL_ENABLED] = enabled }
    }

    /** Persist the floating-ball size tier; unknown tiers coerce to the default. */
    suspend fun setFloatBallSize(size: String) {
        dataStore.edit { it[FLOAT_BALL_SIZE] = size.takeIf { s -> s in FLOAT_BALL_SIZES } ?: AppSettings.DEFAULT_FLOAT_BALL_SIZE }
    }

    /** Persist the inbox "new idea" button placement; unknown values coerce back. */
    suspend fun setInboxNewIdeaStyle(style: String) {
        dataStore.edit { it[INBOX_NEW_IDEA_STYLE] = style.takeIf { s -> s in INBOX_IDEA_STYLES } ?: AppSettings.DEFAULT_INBOX_IDEA_STYLE }
    }

    /** Persist the font-scale multiplier (coerced to the usable band on read too). */
    suspend fun setFontScale(scale: Float) {
        dataStore.edit { it[FONT_SCALE] = scale.coerceIn(0.5f, 2f) }
    }

    /** Floating-ball position as screen-fraction (fx, fy) in 0..1, or null until the
     *  user first drags the ball. Same separate-flow pattern as [sessionReadMarks]:
     *  the JSON decode must not ride the app-wide settings flow. */
    val floatBallPos: Flow<Pair<Float, Float>?> = dataStore.data.map { prefs ->
        prefs[FLOAT_BALL_POS]?.let { raw ->
            runCatching { Json.decodeFromString(PosSerde.serializer(), raw) }
                .getOrNull()
                ?.takeIf { it.fx.isFinite() && it.fy.isFinite() }
                ?.let { it.fx.coerceIn(0f, 1f) to it.fy.coerceIn(0f, 1f) }
        }
    }

    suspend fun setFloatBallPos(fx: Float, fy: Float) {
        dataStore.edit {
            it[FLOAT_BALL_POS] = Json.encodeToString(
                PosSerde.serializer(),
                PosSerde(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f)),
            )
        }
    }

    /** Gesture→action bindings for the floating ball. Values are action ids; an
     *  explicit JSON null means "deliberately unbound" (distinct from a missing
     *  key = "use the default"). Sanitizing to the known gesture/action catalogs
     *  is the feature layer's job ([app.codeg.android.feature.floatingball.
     *  FloatingBallCatalog.sanitize]) — the store stays a dumb map. */
    val floatBallBindings: Flow<Map<String, String?>> = dataStore.data.map { prefs ->
        prefs[FLOAT_BALL_BINDINGS]?.let { raw ->
            runCatching { Json.decodeFromString(BindingsSerde.serializer(), raw).bindings }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun setFloatBallBindings(bindings: Map<String, String?>) {
        dataStore.edit {
            it[FLOAT_BALL_BINDINGS] = Json.encodeToString(BindingsSerde.serializer(), BindingsSerde(bindings))
        }
    }

    /** Local read marks, keyed `"<serverId>:<conversationId>"` → opened-at epochMilli.
     *  Deliberately NOT part of [AppSettings]: the JSON map decode must not ride the
     *  app-wide settings flow. Server has no read tracking, so this is device-local. */
    val sessionReadMarks: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs[SESSION_READ_MARKS]?.let { raw ->
            runCatching { Json.decodeFromString(ReadMarksSerde.serializer(), raw).marks }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    suspend fun markSessionRead(key: String, at: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs ->
            val current = prefs[SESSION_READ_MARKS]?.let { raw ->
                runCatching { Json.decodeFromString(ReadMarksSerde.serializer(), raw).marks }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            val next = pruneReadMarks(current + (key to at))
            prefs[SESSION_READ_MARKS] = Json.encodeToString(ReadMarksSerde.serializer(), ReadMarksSerde(next))
        }
    }

    private companion object {
        val MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_id")
        val TAB_ORDER = stringPreferencesKey("home_tab_order")
        val HIDDEN_TABS = stringPreferencesKey("hidden_home_tabs")
        val START_TAB = stringPreferencesKey("start_tab")
        val WORKBENCH_OVERVIEW_DEFAULT = booleanPreferencesKey("workbench_overview_expanded_default")
        val WORKBENCH_ATTENTION_DEFAULT = booleanPreferencesKey("workbench_attention_expanded_default")
        val ACTIVITY_DEVICE_SWIPE = booleanPreferencesKey("activity_device_swipe")
        val SESSION_READ_MARKS = stringPreferencesKey("session_read_marks")
        val FLOAT_BALL_ENABLED = booleanPreferencesKey("float_ball_enabled")
        val FLOAT_BALL_SIZE = stringPreferencesKey("float_ball_size")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FLOAT_BALL_POS = stringPreferencesKey("float_ball_pos")
        val FLOAT_BALL_BINDINGS = stringPreferencesKey("float_ball_bindings")
        val DISPATCH_DEFAULT_DEVICE = stringPreferencesKey("dispatch_default_device")
        val DISPATCH_DEFAULT_FOLDER_ID = intPreferencesKey("dispatch_default_folder_id")
        val DISPATCH_DEFAULT_FOLDER_NAME = stringPreferencesKey("dispatch_default_folder_name")
        val DISPATCH_DEFAULT_AGENT = stringPreferencesKey("dispatch_default_agent")
        val INBOX_NEW_IDEA_STYLE = stringPreferencesKey("inbox_new_idea_style")
    }
}

/** Legal floating-ball size tiers (mirrors the feature layer's dp ladder). */
val FLOAT_BALL_SIZES = setOf("small", "medium", "large")

/** Legal inbox "new idea" button placements (批v151) — screens branch on the
 *  constants, not raw literals, so a future style can't silently fall back. */
const val INBOX_IDEA_STYLE_CORNER = "corner"
const val INBOX_IDEA_STYLE_CENTER_WIDE = "centerWide"
val INBOX_IDEA_STYLES = setOf(INBOX_IDEA_STYLE_CORNER, INBOX_IDEA_STYLE_CENTER_WIDE)

/** JSON envelope for the read-marks map (DataStore carries one string). */
@Serializable
private data class ReadMarksSerde(val marks: Map<String, Long> = emptyMap())

/** JSON envelope for the ball position (screen fractions). */
@Serializable
private data class PosSerde(val fx: Float = 0.85f, val fy: Float = 0.55f)

/** JSON envelope for the gesture→action map (null value = deliberately unbound). */
@Serializable
private data class BindingsSerde(val bindings: Map<String, String?> = emptyMap())

/** Max stored read marks — beyond this the OLDEST marks (by opened-at) are dropped.
 *  A session that vanished from the lists years ago must not pin its entry forever. */
const val READ_MARKS_CAP = 500

/** Pure LRU-ish prune: keep the newest [READ_MARKS_CAP] entries by timestamp. */
fun pruneReadMarks(marks: Map<String, Long>): Map<String, Long> =
    if (marks.size <= READ_MARKS_CAP) marks
    else marks.entries.sortedByDescending { it.value }.take(READ_MARKS_CAP).associate { it.toPair() }

/** Pure unread predicate for the Activity feed's Last-24h rows: a row is unread when
 *  it changed after the last time the user opened it (or was never opened). Live rows
 *  pulse instead — never double-flagged. */
fun isSessionUnread(statusLive: Boolean, updatedAt: Long, readMark: Long?): Boolean =
    !statusLive && updatedAt > (readMark ?: Long.MIN_VALUE)
