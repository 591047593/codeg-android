package app.codeg.android.shell.web

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import app.codeg.android.BuildConfig
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Web storage persists in the app's default WebView profile; never clear it on pause. */
class WebSettingsFeature : ShellFeature {
    private var documentToken = ""
    private var documentOrigin: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    override fun onWebViewCreated(host: ShellHost, webView: WebView) {
        documentToken = ""
        documentOrigin = null
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = WebSettings.getDefaultUserAgent(webView.context) +
                " CodegAndroidShell/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(ClipboardBridge(host, webView), "CodegShellClipboard")
    }

    override fun onPause(host: ShellHost) {
        CookieManager.getInstance().flush()
        // The page believes it stays visible otherwise; see VisibilityScript.
        host.webView?.evaluateJavascript(visibilityUpdateScript(hidden = true), null)
    }

    override fun onResume(host: ShellHost) {
        host.webView?.evaluateJavascript(visibilityUpdateScript(hidden = false), null)
    }

    override fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: Bitmap?) {
        documentToken = UUID.randomUUID().toString()
        documentOrigin = origin(url)
        inject(host, view, url)
    }

    override fun onPageFinished(host: ShellHost, view: WebView, url: String) {
        inject(host, view, url)
    }

    private fun inject(host: ShellHost, view: WebView, url: String) {
        val server = origin(host.currentServerUrl) ?: return
        if (origin(url) == server && documentOrigin == server && documentToken.isNotEmpty()) {
            view.evaluateJavascript(clipboardScript(server, documentToken), null)
            view.evaluateJavascript(visibilityScript, null)
            // A page loaded while the app is already in the background starts
            // with the flag cleared; restore the real state once the script exists.
            if (!host.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                view.evaluateJavascript(visibilityUpdateScript(hidden = true), null)
            }
        }
    }

    // The current web client does not request audio/video capture. Unknown resources are denied too.
    override fun onPermissionRequest(host: ShellHost, request: PermissionRequest): Boolean {
        request.deny()
        return true
    }

    private inner class ClipboardBridge(private val host: ShellHost, private val view: WebView) {
        // JS interfaces are visible to all frames. Only the top-level injected closure gets this
        // navigation's secret; checking WebView.url alone would also admit a hostile iframe.
        private fun <T> access(token: String, operation: (ClipboardManager) -> T): T? {
            val task = FutureTask<T?> {
                val server = origin(host.currentServerUrl)
                if (token.isEmpty() || token != documentToken || server == null ||
                    documentOrigin != server || origin(view.url) != server ||
                    host.webView !== view || !view.hasWindowFocus()
                ) null else operation(host.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            }
            if (!view.post(task)) return null
            return try {
                task.get(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                task.cancel(false)
                null
            }
        }

        @JavascriptInterface
        fun writeText(token: String, text: String): Boolean = access(token) {
            it.setPrimaryClip(ClipData.newPlainText("", text))
            true
        } ?: false

        @JavascriptInterface
        fun readText(token: String): String? = access(token) {
            val clip = it.primaryClip
            if (clip == null || clip.itemCount == 0) "" else
                clip.getItemAt(0).coerceToText(host.activity)?.toString().orEmpty()
        }
    }
}

private fun origin(url: String?): String? {
    val uri = url?.let(Uri::parse) ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    val port = uri.port
    val suffix = if (port == -1 || (scheme == "http" && port == 80) ||
        (scheme == "https" && port == 443)
    ) "" else ":$port"
    val hostname = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    return "$scheme://$hostname$suffix"
}
