package app.codeg.android.core.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits whenever the device gains or loses a default network.
 *
 * The app had no connectivity awareness at all. A socket drop was retried six times over
 * about thirty seconds and then abandoned — so coming out of a tunnel, or walking from
 * Wi-Fi onto mobile data, left the session permanently disconnected with nothing to bring
 * it back short of leaving and reopening the screen.
 *
 * A `callbackFlow` rather than a `StateFlow`: subscribers want the *transition*, and the
 * initial value is delivered by the callback immediately on registration.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** `true` on a usable default network, `false` when there is none. */
    val isOnline: Flow<Boolean> = callbackFlow {
        val cm = manager
        if (cm == null) {
            // No connectivity service (should not happen on a phone): report "always
            // online" so callers never wait for a transition that cannot arrive.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                // `onLost` fires per network; only report offline when the *default*
                // network is gone, or a Wi-Fi → mobile handover briefly reads as offline.
                trySend(cm.activeNetwork != null)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        trySend(cm.activeNetwork != null)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
