package app.codeg.android.shell

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import android.view.View
import android.view.inputmethod.InputMethodManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The single [ShellHost] implementation, owned by MainActivity. */
class ShellActivityHost(
    override val activity: ComponentActivity,
    val features: ShellFeatures = ShellFeatures(),
) : ShellHost {
    override val scope: CoroutineScope get() = activity.lifecycleScope

    override var webView: WebView? = null
        private set
    private var webViewServer: String? = null

    override val currentServerUrl: String? get() = features.server.current.value

    private val managerOpen = MutableStateFlow(false)
    val serverManagerOpen: StateFlow<Boolean> = managerOpen.asStateFlow()
    override val isWebContentVisible: Boolean get() = !managerOpen.value

    private var pendingTarget: Pair<String, String>? = null
    private var targetRequest = 0L
    private val targetMutex = Mutex()

    private val generation = MutableStateFlow(0)
    val webViewGeneration: StateFlow<Int> = generation.asStateFlow()

    internal fun createWebView(context: Context): WebView {
        val view = WebView(context)
        view.applyShellLayout()
        view.webViewClient = ShellWebViewClient(this, features.all)
        view.webChromeClient = ShellChromeClient(this, features.all)
        webView = view
        webViewServer = currentServerUrl
        features.all.forEach { it.onWebViewCreated(this, view) }
        currentServerUrl?.let { server ->
            val pending = pendingTarget?.takeIf { it.first == server }
            if (pending != null) pendingTarget = null
            view.loadUrl(pending?.second ?: server)
        }
        if (managerOpen.value) view.visibility = View.INVISIBLE
        return view
    }

    internal fun releaseWebView(view: WebView) {
        if (webView === view) {
            webView = null
            webViewServer = null
        }
        view.stopLoading()
        view.destroy()
    }

    override fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    override fun reload() {
        val view = webView ?: return
        if (view.url.isNullOrEmpty()) currentServerUrl?.let { view.loadUrl(it) } else view.reload()
    }

    override fun recreateWebView() {
        val page = runCatching { webView?.url?.toHttpUrlOrNull() }.getOrNull()
        val server = currentServerUrl?.toHttpUrlOrNull()
        if (page != null && server != null && page.scheme == server.scheme && page.host == server.host && page.port == server.port) {
            pendingTarget = currentServerUrl!! to page.toString()
        }
        generation.value += 1
    }

    override fun openServerManager() {
        managerOpen.value = true
        webView?.let {
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
            it.visibility = View.INVISIBLE
            it.evaluateJavascript("window.__codegShellSetHidden && window.__codegShellSetHidden(true)", null)
        }
    }

    fun closeServerManager() {
        managerOpen.value = false
        val resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        webView?.let {
            it.visibility = if (resumed) View.VISIBLE else View.INVISIBLE
            if (resumed) it.requestFocus()
            it.evaluateJavascript("window.__codegShellSetHidden && window.__codegShellSetHidden(${!resumed})", null)
        }
    }

    override fun checkForUpdates() = features.update.checkNow(this)

    override fun openServerTarget(serverOrigin: String, targetUrl: String): Boolean {
        val source = serverOrigin.toHttpUrlOrNull() ?: return false
        val target = targetUrl.toHttpUrlOrNull() ?: return false
        if (source.username.isNotEmpty() || source.password.isNotEmpty() || target.username.isNotEmpty() || target.password.isNotEmpty() ||
            source.scheme != target.scheme || source.host != target.host || source.port != target.port) return false
        val normalized = app.codeg.android.shell.server.serverOrigin(serverOrigin) ?: return false
        val request = ++targetRequest
        pendingTarget = null // Supersede an older staged restore before its factory can consume it.
        scope.launch {
            // Serialize selection side effects too, not just the final WebView load.
            targetMutex.withLock {
                if (request != targetRequest) return@withLock
                val restore = normalized to target.toString()
                var restored = false
                try {
                    if (!features.server.awaitInitialization() || request != targetRequest) return@withLock
                    val profile = features.server.savedProfile(normalized) ?: return@withLock
                    if (request != targetRequest) return@withLock
                    val switching = currentServerUrl != normalized
                    if (switching) {
                        // refresh can publish the new server before select returns;
                        // its WebView factory must already have the destination.
                        pendingTarget = restore
                        features.server.select(profile.id)
                    }
                    if (request != targetRequest) return@withLock
                    if (!switching) {
                        if (webView == null || webViewServer != normalized) {
                            pendingTarget = restore
                        } else {
                            pendingTarget = null
                            webView?.loadUrl(target.toString())
                        }
                    }
                    closeServerManager()
                    restored = true
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                  catch (_: java.io.IOException) { /* Keep saved profiles and current page on read failure. */ }
                finally {
                    // Never clear a newer/recreated view's pending destination.
                    if (!restored && pendingTarget === restore) pendingTarget = null
                }
            }
        }
        return true
    }

    fun onCreate() = features.all.forEach { it.onCreate(this) }
    fun onResume() {
        webView?.visibility = if (managerOpen.value) View.INVISIBLE else View.VISIBLE
        features.all.forEach { it.onResume(this) }
    }
    fun onPause() = features.all.forEach { it.onPause(this) }
    fun onIntent(intent: Intent) = features.all.any { it.onIntent(this, intent) }

    /** Back: the open server manager first, then features; false = let the activity finish. */
    fun onBack(): Boolean {
        if (managerOpen.value && currentServerUrl != null) {
            closeServerManager()
            return true
        }
        return features.all.any { it.onBack(this) }
    }
}
