package app.codeg.android.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
) {
    companion object {
        /**
         * Delegates to the palette so the two defaults cannot drift apart.
         *
         * This was a hardcoded `"orange"`, which meant changing `AccentPalette.DEFAULT`
         * had no effect on a fresh install — the stored default won, and the app kept
         * launching with a coloured accent while the design system said neutral.
         */
        val DEFAULT_ACCENT: String = app.codeg.android.core.designsystem.theme.AccentPalette.DEFAULT.id
    }
}

/**
 * Persists appearance settings (theme mode + accent). Applied app-wide
 * instantly; never synced to the server (matches iOS).
 */
@Singleton
class AppSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.safeData().map { prefs ->
        AppSettings(
            themeMode = ThemeMode.from(prefs[MODE]),
            accentId = prefs[ACCENT] ?: AppSettings.DEFAULT_ACCENT,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[MODE] = mode.id }
    }

    suspend fun setAccent(accentId: String) {
        dataStore.edit { it[ACCENT] = accentId }
    }

    private companion object {
        val MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_id")
    }
}
