package app.codeg.android.shell

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/** Fans every WebViewClient callback out to the features (see [ShellFeature]). */
internal class ShellWebViewClient(
    private val host: ShellHost,
    private val features: List<ShellFeature>,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        features.any { it.shouldOverrideUrlLoading(host, view, request) }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        features.forEach { it.onPageStarted(host, view, url, favicon) }
    }

    override fun onPageFinished(view: WebView, url: String) {
        features.forEach { it.onPageFinished(host, view, url) }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        features.forEach { it.doUpdateVisitedHistory(host, view, url, isReload) }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        features.forEach { it.onReceivedError(host, view, request, error) }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        features.forEach { it.onReceivedHttpError(host, view, request, errorResponse) }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (!features.any { it.onReceivedSslError(host, view, handler, error) }) handler.cancel()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (features.any { it.onRenderProcessGone(host, view, detail) }) return true
        // Nobody handled it: rebuild instead of letting the app crash.
        host.recreateWebView()
        return true
    }
}

/** Fans every WebChromeClient callback out to the features (see [ShellFeature]). */
internal class ShellChromeClient(
    private val host: ShellHost,
    private val features: List<ShellFeature>,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) {
        features.forEach { it.onProgressChanged(host, view, newProgress) }
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        if (features.any { it.onShowFileChooser(host, webView, filePathCallback, fileChooserParams) }) return true
        filePathCallback.onReceiveValue(null)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        if (!features.any { it.onPermissionRequest(host, request) }) request.deny()
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        features.any { it.onCreateWindow(host, view, isDialog, isUserGesture, resultMsg) }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        if (features.any { it.onJsConfirm(host, view, url, message, result) }) return true
        // Nobody claimed it: hand the call back to the platform, which shows its
        // own dialog. Answering `false` ourselves would silently decline a
        // confirm the page still needs an answer to.
        return super.onJsConfirm(view, url, message, result)
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult,
    ): Boolean {
        if (features.any { it.onJsPrompt(host, view, url, message, defaultValue, result) }) return true
        return super.onJsPrompt(view, url, message, defaultValue, result)
    }
}
