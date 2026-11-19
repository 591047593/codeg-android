package app.codeg.android.shell.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.codeg.android.MainActivity
import app.codeg.android.R
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Implements the Web Notifications API the web build calls (`src/lib/notification.ts`,
 * `src/components/settings/desktop-notification-settings.tsx`).
 *
 * Android WebView has no `window.Notification`, so the page's own capability
 * probe reports "unsupported" and every background turn, permission request and
 * question alert is dropped — the one case where a phone client matters most.
 * This feature installs the same three pieces a browser exposes
 * (`Notification.permission`, `Notification.requestPermission()` and
 * `new Notification(...)`) and turns the last one into a system notification.
 *
 * The page posts only while it believes it is hidden (`when: "hidden"` is its
 * default), which is exactly when the shell is in the background.
 *
 * Android 13+ needs `POST_NOTIFICATIONS` at runtime. It is requested from the
 * page's own `requestPermission()` call (and from an undecided `post`, which is
 * what a page that posts without asking looks like); a refusal is remembered so
 * the API then reports `denied`, which the web settings panel renders as
 * "blocked by the system".
 */
class NotifyFeature : ShellFeature {
    private var documentToken = ""
    private var documentOrigin: String? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private var requesting = false

    /** A tap that arrived before the page existed (cold start, renderer crash). */
    private var pendingTap: String? = null

    /** Payloads that arrived before the permission answer; posted on grant. */
    private val waitingForPermission = ConcurrentHashMap<String, Pair<String, String>>()

    override fun onCreate(host: ShellHost) {
        permissionLauncher = host.activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            requesting = false
            if (!granted) {
                waitingForPermission.clear()
                return@registerForActivityResult
            }
            val pending = waitingForPermission.toList()
            waitingForPermission.clear()
            pending.forEach { (id, payload) ->
                host.activity.runOnUiThread { deliver(host, id, payload.first, payload.second) }
            }
        }
    }

    override fun onWebViewCreated(host: ShellHost, webView: WebView) {
        documentToken = ""
        documentOrigin = null
        requesting = false
        waitingForPermission.clear()
        ensureChannel(host)
        webView.addJavascriptInterface(Bridge(host, webView), "CodegShellNotify")
    }

    override fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: Bitmap?) {
        // One token per document, like the clipboard bridge: `onPageFinished` can
        // fire more than once for the same document, and re-issuing the token
        // there would strand the installed script with a stale secret.
        documentToken = UUID.randomUUID().toString()
        documentOrigin = origin(url)
        // Notifications belong to the page that raised them; a tap after a
        // navigation must not reach a new document's instances.
        waitingForPermission.clear()
        // Early install: the web settings panel reads `Notification.permission`
        // in a mount effect, which can run before `onPageFinished`. The script is
        // idempotent per document, so the later pass is the safety net.
        inject(host, view, url)
    }

    override fun onPageFinished(host: ShellHost, view: WebView, url: String) {
        inject(host, view, url)
    }

    private fun inject(host: ShellHost, view: WebView, url: String) {
        val server = origin(host.currentServerUrl) ?: return
        if (documentToken.isEmpty() || documentOrigin != server) return
        if (origin(url) != server || origin(view.url) != server || host.webView !== view) return
        view.evaluateJavascript(notificationScript(server, documentToken, permissionState(host)), null)
        // A tap that started the app has no page instance to click yet; the
        // script above is what creates them, so hand it over now.
        pendingTap?.let { id ->
            pendingTap = null
            view.evaluateJavascript(notificationClickScript(id), null)
        }
    }

    override fun onIntent(host: ShellHost, intent: Intent): Boolean {
        val id = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return false
        intent.removeExtra(EXTRA_NOTIFICATION_ID)
        NotificationManagerCompat.from(host.activity).cancel(id.hashCode())
        val view = host.webView
        if (view == null) {
            // Cold start or a renderer crash: the page (and its instance) is
            // rebuilt after this call, so deliver the tap once it has loaded.
            pendingTap = id
            return true
        }
        view.evaluateJavascript(notificationClickScript(id), null)
        return true
    }

    override fun onResume(host: ShellHost) {
        // Coming back from system settings (or the permission dialog) is the one
        // moment the page's cached `Notification.permission` can be stale; the
        // page itself only listens for visibilitychange, which a WebView does
        // not reliably dispatch for an activity pause.
        host.webView?.evaluateJavascript(REFRESH_SCRIPT, null)
    }

    private fun ensureChannel(host: ShellHost) {
        val manager = host.activity.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                host.activity.getString(R.string.shell_notify_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = host.activity.getString(R.string.shell_notify_channel_description) },
        )
    }

    /** The three values the Web API exposes: `granted`, `denied`, `default`. */
    private fun permissionState(host: ShellHost): String {
        val context = host.activity
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "granted" else "denied"
        // Android only shows the system dialog once per install; a refusal is
        // final until the user changes it in Settings, so it reads as `denied`
        // rather than `default` (which would make the page ask again forever).
        val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)
        return if (asked && !requesting) "denied" else "default"
    }

    private fun requestPermission(host: ShellHost) {
        if (requesting) return
        val context = host.activity
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requesting = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
        // The launcher must run on the main thread; bridge calls do not.
        host.activity.runOnUiThread {
            try {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {
                requesting = false
            }
        }
    }

    private fun deliver(host: ShellHost, id: String, title: String, body: String) {
        val context = host.activity
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(host)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra(EXTRA_NOTIFICATION_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_codeg)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the post.
        }
    }

    private inner class Bridge(private val host: ShellHost, private val view: WebView) {
        // JS interfaces are visible to every frame. Only the top-level injected
        // closure gets this navigation's secret; checking WebView.url alone
        // would also admit a hostile iframe.
        private fun allowed(token: String): Boolean =
            token.isNotEmpty() && token == documentToken && documentOrigin != null &&
                origin(host.currentServerUrl) == documentOrigin && origin(view.url) == documentOrigin &&
                host.webView === view

        @JavascriptInterface
        fun state(token: String): String? = if (allowed(token)) permissionState(host) else null

        @JavascriptInterface
        fun request(token: String): Boolean {
            if (!allowed(token)) return false
            requestPermission(host)
            return true
        }

        @JavascriptInterface
        fun post(token: String, id: String, title: String, body: String): Boolean {
            if (!allowed(token) || id.isEmpty()) return false
            return when (permissionState(host)) {
                "granted" -> {
                    host.activity.runOnUiThread { deliver(host, id, title, body) }
                    true
                }
                // A page that skipped requestPermission still gets its alert
                // once the user answers the dialog we raise for it.
                else -> {
                    waitingForPermission[id] = title to body
                    requestPermission(host)
                    true
                }
            }
        }
        @JavascriptInterface
        fun cancel(token: String, id: String) {
            if (!allowed(token)) return
            waitingForPermission.remove(id)
            host.activity.runOnUiThread {
                NotificationManagerCompat.from(host.activity).cancel(id.hashCode())
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "codeg_web"
        const val EXTRA_NOTIFICATION_ID = "app.codeg.android.extra.NOTIFICATION_ID"
        const val PREFS = "shell_notify"
        const val KEY_ASKED = "asked"
        const val REFRESH_SCRIPT =
            "(function(){ if (window.__codegNotifyRefresh) window.__codegNotifyRefresh(); })()"
    }
}

private fun origin(url: String?): String? = url?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
