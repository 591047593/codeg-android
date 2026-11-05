package app.codeg.android.core.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.codeg.android.MainActivity
import app.codeg.android.R
import app.codeg.android.core.common.LocaleManager
import app.codeg.android.core.data.InteractiveRequestWatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Optional foreground service (dataSync) that keeps the interaction listener
 * alive while the app is backgrounded (批v160 W8 「后台保持监听」, default OFF).
 * Holds one [InteractiveRequestWatcher] reference: with the shell dead, its
 * start/stop refcount keeps the poll running. START_STICKY — a ROM that still
 * kills it gets it restarted (best effort; the toggle in settings is the real
 * contract).
 */
@AndroidEntryPoint
class KeepAliveService : Service() {

    @Inject
    lateinit var watcher: InteractiveRequestWatcher

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        watcher.start()
        try {
            startInForeground()
        } catch (_: Exception) {
            // A ROM refusing the foreground promotion leaves a started-but-
            // invisible service — pointless AND unkillable by the toggle's
            // stopService is still possible, but the honest move is to die.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        watcher.stop()
        super.onDestroy()
    }

    private fun startInForeground() {
        val ctx = LocaleManager.wrap(this)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse("codeg://tab/settings"), ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(ctx, InteractiveNotifier.CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.notif_keepalive_title))
            .setContentText(ctx.getString(R.string.notif_keepalive_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(
            this,
            KEEPALIVE_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    companion object {
        const val KEEPALIVE_NOTIFICATION_ID = 42

        /** Start (idempotent). Callers must catch — Android 12+ forbids
         *  foreground-service starts from the background. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
