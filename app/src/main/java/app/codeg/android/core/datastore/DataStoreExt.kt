package app.codeg.android.core.datastore

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

private const val TAG = "CodegDataStore"

/**
 * A [DataStore] read that survives a corrupt / unreadable backing file.
 *
 * `DataStore.data` is a cold flow that *terminates* with an [IOException] when the
 * file cannot be read or parsed. Every consumer here is a long-lived `stateIn` (the
 * app-level server list, appearance settings, recent searches, selector prefs), so an
 * unhandled throw kills the flow for good and the `StateFlow` keeps its initial value
 * forever.
 *
 * For [app.codeg.android.app.AppViewModel.servers] that initial value is `null`, which
 * the root composable renders as a splash — so a single bad write left the app spinning
 * on a blank screen with no way out short of clearing app data. This is the failure mode
 * DataStore's own documentation tells callers to guard against.
 *
 * The error is logged (not swallowed silently) and an empty preference set is emitted so
 * the UI renders onboarding / defaults instead of hanging. Non-IO failures still propagate
 * — those are programming errors and should not be papered over.
 */
internal fun DataStore<Preferences>.safeData(): Flow<Preferences> =
    data.catch { e ->
        if (e is IOException) {
            Log.e(TAG, "Unreadable preferences file; falling back to empty defaults", e)
            emit(emptyPreferences())
        } else {
            throw e
        }
    }
