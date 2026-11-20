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

    override val currentServerUrl: String? get() = features.server.current.value

    private val managerOpen = MutableStateFlow(false)
    val serverManagerOpen: StateFlow<Boolean> = managerOpen.asStateFlow()
    override val isWebContentVisible: Boolean get() = !managerOpen.value

    private var pendingTarget: Pair<String, String>? = null

    private val generation = MutableStateFlow(0)
    val webViewGeneration: StateFlow<Int> = generation.asStateFlow()

    internal fun createWebView(context: Context): WebView {
        val view = WebView(context)
        view.applyShellLayout()
        view.webViewClient = ShellWebViewClient(this, features.all)
        view.webChromeClient = ShellChromeClient(this, features.all)
        webView = view
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
        if (webView === view) webView = null
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
        webView?.let {
            it.visibility = View.VISIBLE
            it.requestFocus()
            it.evaluateJavascript("window.__codegShellSetHidden && window.__codegShellSetHidden(false)", null)
        }
    }

    override fun checkForUpdates() = features.update.checkNow(this)

    override fun openServerTarget(serverOrigin: String, targetUrl: String): Boolean {
        val source = serverOrigin.toHttpUrlOrNull() ?: return false
        val target = targetUrl.toHttpUrlOrNull() ?: return false
        if (source.username.isNotEmpty() || source.password.isNotEmpty() || target.username.isNotEmpty() || target.password.isNotEmpty() ||
            source.scheme != target.scheme || source.host != target.host || source.port != target.port) return false
        val normalized = app.codeg.android.shell.server.serverOrigin(serverOrigin) ?: return false
        scope.launch {
            try {
                val profile = features.server.savedProfile(normalized) ?: return@launch
                if (currentServerUrl != normalized) {
                    pendingTarget = normalized to target.toString()
                    features.server.select(profile.id)
                } else if (webView == null) {
                    pendingTarget = normalized to target.toString()
                } else {
                    webView?.loadUrl(target.toString())
                }
                closeServerManager()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: java.io.IOException) { pendingTarget = null }
        }
        return true
    }

    fun onCreate() = features.all.forEach { it.onCreate(this) }
    fun onResume() = features.all.forEach { it.onResume(this) }
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
