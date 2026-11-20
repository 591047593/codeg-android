package app.codeg.android.shell

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope

/**
 * What the shell offers to every [ShellFeature]. Implemented once by the activity;
 * features never reach into each other — they talk through this host.
 */
interface ShellHost {
    val activity: ComponentActivity

    /** Lives as long as the activity. */
    val scope: CoroutineScope

    /** The WebView once created (null before the first server is chosen or after a renderer crash). */
    val webView: WebView?

    /** Origin of the server being shown, e.g. `https://example.com:8443`, or null when none is chosen. */
    val currentServerUrl: String?

    /** False while a native management screen covers the retained web page. */
    val isWebContentVisible: Boolean get() = true

    /** Load [url] in the WebView (absolute URL). */
    fun loadUrl(url: String)

    /** Reload the current page; if nothing loaded yet, load the server root. */
    fun reload()

    /** Throw away the WebView and build a fresh one (renderer crash recovery). */
    fun recreateWebView()

    /** Show the native server manager screen (S5). */
    fun openServerManager()

    /** Restore a validated target on an already-saved server. Called on the UI thread. */
    fun openServerTarget(serverOrigin: String, targetUrl: String): Boolean

    /** Open the native manual update check and report its outcome. */
    fun checkForUpdates()
}

/**
 * One self-contained capability of the shell. Every hook has a no-op default; a feature
 * overrides only what it needs. For hooks returning Boolean, features are asked in
 * [ShellFeatures.all] order and the first `true` wins (later features are not asked).
 */
interface ShellFeature {
    /** Activity.onCreate, before setContent. Register activity-result launchers here. */
    fun onCreate(host: ShellHost) {}

    /** Right after a WebView is built and before its first load. May be called again after [ShellHost.recreateWebView]. */
    fun onWebViewCreated(host: ShellHost, webView: WebView) {}

    fun onResume(host: ShellHost) {}
    fun onPause(host: ShellHost) {}

    /** Activity.onNewIntent / launch intent. Return true when handled. */
    fun onIntent(host: ShellHost, intent: Intent): Boolean = false

    /** Back press. Return true when consumed. Asked before the default (finish). */
    fun onBack(host: ShellHost): Boolean = false

    // ---- WebViewClient ----
    fun shouldOverrideUrlLoading(host: ShellHost, view: WebView, request: WebResourceRequest): Boolean = false
    fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: Bitmap?) {}
    fun onPageFinished(host: ShellHost, view: WebView, url: String) {}
    fun doUpdateVisitedHistory(host: ShellHost, view: WebView, url: String, isReload: Boolean) {}
    fun onReceivedError(host: ShellHost, view: WebView, request: WebResourceRequest, error: WebResourceError) {}
    fun onReceivedHttpError(host: ShellHost, view: WebView, request: WebResourceRequest, response: WebResourceResponse) {}
    /** Certificate error on any request. Return true when handled; otherwise the request is cancelled. Never proceed() silently. */
    fun onReceivedSslError(host: ShellHost, view: WebView, handler: SslErrorHandler, error: SslError): Boolean = false
    /** Return true when handled (the shell then must not crash). */
    fun onRenderProcessGone(host: ShellHost, view: WebView, detail: RenderProcessGoneDetail): Boolean = false

    // ---- JavaScript dialogs ----
    /**
     * `window.confirm()`. Return true only when the feature takes ownership of
     * [result] — it must then call confirm()/cancel() exactly once, possibly
     * later. Returning false leaves the call to the shell, which passes it to
     * the platform's own dialog, so the page always gets an answer.
     */
    fun onJsConfirm(host: ShellHost, view: WebView, url: String, message: String, result: JsResult): Boolean = false

    /** `window.prompt()`, same ownership rule as [onJsConfirm]. */
    fun onJsPrompt(
        host: ShellHost,
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult,
    ): Boolean = false

    // ---- WebChromeClient ----
    fun onProgressChanged(host: ShellHost, view: WebView, progress: Int) {}
    fun onShowFileChooser(
        host: ShellHost,
        view: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean = false
    fun onPermissionRequest(host: ShellHost, request: PermissionRequest): Boolean = false
    fun onCreateWindow(host: ShellHost, view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false

    /** Extra modifier applied to the WebView container (insets, IME…). Chained in feature order. */
    @Composable
    fun webViewModifier(host: ShellHost): Modifier = Modifier

    /** Drawn above the WebView, full size (progress bar, error page, dialogs). */
    @Composable
    fun Overlay(host: ShellHost) {}
}
