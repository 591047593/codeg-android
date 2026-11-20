package app.codeg.android.shell.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.codeg.android.MainActivity
import app.codeg.android.R
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Web Notifications shim. Permission prompts originate only from explicit foreground requests. */
class NotifyFeature : ShellFeature {
    /** Published by UI lifecycle hooks; bridge reads never inspect the host or a WebView. */
    private data class Snapshot(
        val token: String = "",
        val origin: String? = null,
        val view: WebView? = null,
        val permission: String = "default",
        val resumed: Boolean = false,
        val ready: Boolean = false,
    )

    @Volatile private var snapshot = Snapshot()
    private val main = Handler(Looper.getMainLooper())
    private val queuedWork = AtomicInteger()
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private var requesting = false

    private class PendingTap(val id: String, val origin: String, val pageUrl: String, val at: Long)
    private var pendingTap: PendingTap? = null
    private var tapTimeout: Runnable? = null

    override fun onCreate(host: ShellHost) {
        host.activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                clearPendingTap()
                snapshot = Snapshot(permission = snapshot.permission)
            }
        })
        ensureChannel(host)
        refreshPermission(host)
        permissionLauncher = host.activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            requesting = false
            refreshPermission(host)
            updatePagePermission(host, settle = true)
        }
    }

    override fun onWebViewCreated(host: ShellHost, webView: WebView) {
        // Do not reset an in-flight permission dialog or tap when rebuilding a view.
        snapshot = Snapshot(permission = permissionState(host), resumed = snapshot.resumed)
        ensureChannel(host)
        webView.addJavascriptInterface(Bridge(host, webView), "CodegShellNotify")
    }

    override fun onRenderProcessGone(host: ShellHost, view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val current = snapshot
        if (current.view === view) {
            // Invalidate immediately, before replacement composition. Queued bridge
            // actions and late JS callbacks must fail even if the host still holds view.
            snapshot = Snapshot(permission = current.permission, resumed = current.resumed)
        }
        // Recovery belongs to the host/error feature, not notification authorization.
        return false
    }

    override fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: Bitmap?) {
        if (host.webView !== view) return
        snapshot = Snapshot(
            token = UUID.randomUUID().toString(),
            origin = serverOrigin(url),
            view = view,
            permission = permissionState(host),
            resumed = snapshot.resumed,
        )
        // Early capability install; interactions wait for the page-finished acknowledgement.
        inject(host, view, url, finished = false)
    }

    override fun onPageFinished(host: ShellHost, view: WebView, url: String) {
        inject(host, view, url, finished = true)
    }

    private fun inject(host: ShellHost, view: WebView, url: String, finished: Boolean) {
        refreshPermission(host)
        val auth = snapshot
        val origin = auth.origin ?: return
        if (!isCurrent(host, view, auth) || serverOrigin(url) != origin) return
        view.evaluateJavascript(notificationScript(origin, auth.token, auth.permission)) { result ->
            if (!finished || result != "true" || !isCurrent(host, view, auth)) return@evaluateJavascript
            snapshot = snapshot.copy(ready = true)
            updatePagePermission(host, settle = !requesting)
        }
    }

    /** UI-thread revalidation, both before actions and after asynchronous evaluations. */
    private fun isCurrent(host: ShellHost, view: WebView, auth: Snapshot): Boolean {
        val current = snapshot
        return auth.token.isNotEmpty() && auth.origin != null && current.token == auth.token &&
            current.origin == auth.origin && current.view === view && auth.view === view &&
            host.webView === view && serverOrigin(host.currentServerUrl) == auth.origin &&
            serverOrigin(view.url) == auth.origin
    }

    override fun onResume(host: ShellHost) {
        snapshot = snapshot.copy(resumed = true)
        refreshPermission(host)
        updatePagePermission(host, settle = !requesting)
    }

    override fun onPause(host: ShellHost) {
        snapshot = snapshot.copy(resumed = false)
    }

    override fun onIntent(host: ShellHost, intent: Intent): Boolean {
        val id = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return false
        val origin = intent.getStringExtra(EXTRA_NOTIFICATION_ORIGIN)?.let(::serverOrigin)
        val pageUrl = intent.getStringExtra(EXTRA_NOTIFICATION_PAGE)
        intent.removeExtra(EXTRA_NOTIFICATION_ID)
        intent.removeExtra(EXTRA_NOTIFICATION_ORIGIN)
        intent.removeExtra(EXTRA_NOTIFICATION_PAGE)
        if (!validId(id)) return true
        cancelNotification(host, id)
        // Old id-only intents have no recoverable page. Never invent a session target.
        clearPendingTap()
        val savedOrigin = origin ?: return true
        val target = savedTarget(savedOrigin, pageUrl) ?: return true
        val tap = PendingTap(id, savedOrigin, target, SystemClock.elapsedRealtime())
        pendingTap = tap
        val auth = snapshot
        val view = host.webView
        if (view == null || !host.isWebContentVisible || !auth.ready ||
            auth.origin != savedOrigin || !isCurrent(host, view, auth)
        ) {
            // Cold start, different server, or loading document: the host owns restoration,
            // including any WebView/profile creation. No previous JS instance is needed.
            restoreTap(host, tap)
            return true
        }
        val timeout = Runnable { restoreTap(host, tap) }
        tapTimeout = timeout
        main.postDelayed(timeout, TAP_CALLBACK_TIMEOUT_MS)
        view.evaluateJavascript(notificationClickScript(origin, auth.token, id)) { result ->
            if (pendingTap !== tap) return@evaluateJavascript
            if (result == "true" && host.isWebContentVisible && isCurrent(host, view, auth)) clearPendingTap()
            else restoreTap(host, tap)
        }
        return true
    }

    private fun restoreTap(host: ShellHost, tap: PendingTap) {
        if (pendingTap !== tap) return
        clearPendingTap()
        if (SystemClock.elapsedRealtime() - tap.at <= TAP_TTL_MS) {
            host.openServerTarget(tap.origin, tap.pageUrl)
        }
    }

    private fun clearPendingTap() {
        tapTimeout?.let { main.removeCallbacks(it) }
        tapTimeout = null
        pendingTap = null
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

    private fun permissionState(host: ShellHost): String {
        val context = host.activity
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return "denied"
        val group = channel?.group
        if (group != null && manager.getNotificationChannelGroup(group)?.isBlocked == true) {
            return "denied"
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "granted" else "denied"
        val asked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
        return if (asked && !requesting) "denied" else "default"
    }

    private fun refreshPermission(host: ShellHost) {
        snapshot = snapshot.copy(permission = permissionState(host))
    }

    /** Only a finished, acknowledged document receives native permission callbacks. */
    private fun updatePagePermission(host: ShellHost, settle: Boolean) {
        val auth = snapshot
        val view = auth.view ?: return
        val origin = auth.origin ?: return
        if (!auth.ready || !isCurrent(host, view, auth)) return
        view.evaluateJavascript(notificationPermissionScript(origin, auth.token, settle), null)
    }

    private fun requestPermission(host: ShellHost) {
        refreshPermission(host)
        if (!snapshot.resumed || !host.isWebContentVisible || snapshot.permission != "default") {
            updatePagePermission(host, settle = true)
            return
        }
        if (requesting) return
        requesting = true
        val prefs = host.activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasAsked = prefs.getBoolean(KEY_ASKED, false)
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        try {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
            // No dialog actually appeared: preserve the previous undecided state.
            requesting = false
            prefs.edit().putBoolean(KEY_ASKED, wasAsked).apply()
            refreshPermission(host)
            updatePagePermission(host, settle = true)
        }
    }

    private fun deliver(host: ShellHost, origin: String, id: String, title: String, body: String, pageUrl: String) {
        val target = savedTarget(origin, pageUrl) ?: return
        ensureChannel(host)
        refreshPermission(host)
        val context = host.activity
        if (snapshot.permission != "granted" ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // Distinct data prevents PendingIntent hash collisions from overwriting targets.
            .setData(Uri.Builder().scheme("codeg-notification").authority("tap").appendPath(id).build())
            .putExtra(EXTRA_NOTIFICATION_ID, id)
            .putExtra(EXTRA_NOTIFICATION_ORIGIN, origin)
            .putExtra(EXTRA_NOTIFICATION_PAGE, target)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
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
            NotificationManagerCompat.from(context).notify(id, 0, notification)
        } catch (_: SecurityException) {
            refreshPermission(host)
            updatePagePermission(host, settle = true)
        }
    }

    private fun cancelNotification(host: ShellHost, id: String) {
        NotificationManagerCompat.from(host.activity).apply {
            cancel(id, 0)
            cancel(id.hashCode()) // Compatibility with previously posted id-only notifications.
        }
    }

    private inner class Bridge(private val host: ShellHost, private val view: WebView) {
        private fun authorized(token: String): Snapshot? {
            val auth = snapshot
            return auth.takeIf {
                token.isNotEmpty() && token == it.token && it.origin != null && it.view === view
            }
        }

        /** Bounded asynchronous UI work; no bridge method waits for the UI thread. */
        private fun enqueue(auth: Snapshot, operation: () -> Unit): Boolean {
            if (queuedWork.incrementAndGet() > MAX_QUEUED_WORK) {
                queuedWork.decrementAndGet()
                return false
            }
            val queuedAt = SystemClock.elapsedRealtime()
            val accepted = main.post {
                queuedWork.decrementAndGet()
                if (SystemClock.elapsedRealtime() - queuedAt <= WORK_TTL_MS && isCurrent(host, view, auth)) {
                    operation()
                }
            }
            if (!accepted) queuedWork.decrementAndGet()
            return accepted
        }

        @JavascriptInterface
        fun state(token: String): String? = authorized(token)?.permission

        @JavascriptInterface
        fun request(token: String): Boolean {
            val auth = authorized(token) ?: return false
            return enqueue(auth) { requestPermission(host) }
        }

        @JavascriptInterface
        fun post(token: String, id: String, title: String, body: String, pageUrl: String): Boolean {
            val auth = authorized(token) ?: return false
            if (!validId(id) || auth.permission != "granted" || pageUrl.length > MAX_PAGE_URL_LENGTH) return false
            val safeTitle = title.take(MAX_TITLE_LENGTH)
            val safeBody = body.take(MAX_BODY_LENGTH)
            return enqueue(auth) { deliver(host, auth.origin!!, id, safeTitle, safeBody, pageUrl) }
        }

        @JavascriptInterface
        fun cancel(token: String, id: String) {
            val auth = authorized(token) ?: return
            if (validId(id)) enqueue(auth) { cancelNotification(host, id) }
        }
    }

    private companion object {
        const val CHANNEL_ID = "codeg_web"
        const val EXTRA_NOTIFICATION_ID = "app.codeg.android.extra.NOTIFICATION_ID"
        const val EXTRA_NOTIFICATION_ORIGIN = "app.codeg.android.extra.NOTIFICATION_SERVER_ORIGIN"
        const val EXTRA_NOTIFICATION_PAGE = "app.codeg.android.extra.NOTIFICATION_PAGE_URL"
        const val PREFS = "shell_notify"
        const val KEY_ASKED = "asked"
        const val MAX_QUEUED_WORK = 32
        const val WORK_TTL_MS = 10_000L
        const val TAP_CALLBACK_TIMEOUT_MS = 2_000L
        const val TAP_TTL_MS = 60_000L
        const val MAX_PAGE_URL_LENGTH = 16_384
        const val MAX_TITLE_LENGTH = 512
        const val MAX_BODY_LENGTH = 8_192

        fun validId(id: String): Boolean = id.isNotEmpty() && id.length <= 160

        fun savedTarget(origin: String, target: String?): String? {
            if (target == null || target.length > MAX_PAGE_URL_LENGTH) return null
            val url = target.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || serverOrigin(target) != origin) return null
            return url.toString()
        }
    }
}

/** Browser-compatible serialization, including default ports, IDN and IPv6. */
internal fun serverOrigin(url: String?): String? = url?.toHttpUrlOrNull()?.let {
    val hostname = if (it.host.contains(':')) "[${it.host}]" else it.host
    val defaultPort = if (it.isHttps) 443 else 80
    val port = if (it.port == defaultPort) "" else ":${it.port}"
    "${it.scheme}://$hostname$port"
}
