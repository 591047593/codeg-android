package app.codeg.android.shell.nav

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import app.codeg.android.R
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import java.util.Locale
import java.util.UUID

class NavFeature : ShellFeature {
    private var backPending = false
    private var exitArmedAt: Long? = null
    private val popupHandler = Handler(Looper.getMainLooper())
    private val popups = mutableSetOf<WebView>()

    /** The page URL the back button was last synced for, or null when not shown. */
    private var backButtonPage: String? = null

    /** Secret and selected server for the current document's window bridge. */
    private var documentToken: String? = null
    private var documentServer: String? = null

    /** Native-only entry, including project/session query and fragment. */
    private var workspaceEntry: String? = null
    private var workspaceServer: String? = null

    override fun onWebViewCreated(host: ShellHost, webView: WebView) {
        backPending = false
        exitArmedAt = null
        backButtonPage = null
        documentToken = null
        documentServer = null
        workspaceEntry = null
        workspaceServer = host.currentServerUrl
        webView.addJavascriptInterface(WindowBridge(host, webView), "CodegShellWindowNative")
    }

    override fun doUpdateVisitedHistory(host: ShellHost, view: WebView, url: String, isReload: Boolean) {
        if (host.webView !== view) return
        exitArmedAt = null
        rememberWorkspaceEntry(host, view, url)
        // A client-side route change does not reload the document; this is the
        // only callback a single-page transition gives us.
        if (!isReload) syncBackButton(host, view, url)
    }

    override fun onPause(host: ShellHost) {
        exitArmedAt = null
        popupHandler.removeCallbacksAndMessages(null)
        popups.toList().forEach { it.destroy() }
        popups.clear()
    }

    override fun onRenderProcessGone(host: ShellHost, view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (host.webView !== view) return false
        documentToken = null
        documentServer = null
        backPending = false
        return false // ErrorFeature owns recovery; invalidate before it recreates the view.
    }

    override fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        if (host.webView !== view) return
        exitArmedAt = null
        // A new document starts without the injected button; the finished load
        // (and any client-side transition) puts it back.
        backButtonPage = null
        documentToken = UUID.randomUUID().toString()
        documentServer = host.currentServerUrl
        rememberWorkspaceEntry(host, view, url)
    }

    override fun onPageFinished(host: ShellHost, view: WebView, url: String) {
        if (host.webView !== view) return
        rememberWorkspaceEntry(host, view, url)
        val token = documentToken
        if (token != null && documentServer == host.currentServerUrl && sameOrigin(host, Uri.parse(url))) {
            // `window.close()` is ignored in a WebView unless the script opened
            // the window, and the shell never gives it one to close.
            view.evaluateJavascript(windowCloseBridgeScript(host.currentServerUrl.orEmpty(), token), null)
        }
        // History can fire before the new document is ready for evaluation.
        // Retry even if that early callback already marked this URL as synced.
        syncBackButton(host, view, url, force = true)
    }

    /** No WebView or host state is read until the JS interface call reaches UI. */
    private inner class WindowBridge(private val host: ShellHost, private val view: WebView) {
        @JavascriptInterface
        fun home(token: String) {
            view.post {
                if (token.isEmpty() || token != documentToken || host.webView !== view || host.activity.isDestroyed) return@post
                val server = host.currentServerUrl ?: return@post
                if (documentServer != server) return@post
                val page = view.url?.let(Uri::parse) ?: return@post
                if (!sameOrigin(host, page) || !isWindowRoute(page)) return@post
                // Only native-observed, same-origin workspace entries can be
                // restored; no destination crosses the JavaScript interface.
                val entry = workspaceEntry?.takeIf {
                    workspaceServer == server && isWorkspace(host, Uri.parse(it))
                }
                val destination = entry ?: Uri.parse(server).buildUpon()
                    .encodedPath("/workspace").clearQuery().fragment(null).build().toString()
                view.loadUrl(destination)
            }
        }
    }

    private fun rememberWorkspaceEntry(host: ShellHost, view: WebView, url: String) {
        if (host.webView !== view) return
        val server = host.currentServerUrl
        if (workspaceServer != server) {
            workspaceEntry = null
            workspaceServer = server
        }
        if (isWorkspace(host, Uri.parse(url))) workspaceEntry = url
    }

    private fun isWorkspace(host: ShellHost, uri: Uri): Boolean =
        sameOrigin(host, uri) && uri.path.orEmpty().trimEnd('/') == "/workspace"

    private fun isWindowRoute(uri: Uri): Boolean {
        val path = uri.path.orEmpty().trimEnd('/')
        return WINDOW_ROUTES.any { path == it || path.startsWith("$it/") }
    }

    /**
     * Window-style routes get a back button; [backButtonScript] explains why.
     *
     * These routes are reached by `window.open`, which the shell turns into a
     * navigation of the single WebView, so the page that opened them is gone.
     * A desktop window has its own chrome and a browser tab its back button; a
     * WebView has neither, and `SettingsShell`'s mobile title bar is a menu
     * button and nothing else.
     */
    private fun syncBackButton(host: ShellHost, view: WebView?, url: String, force: Boolean = false) {
        val server = host.currentServerUrl ?: return
        if (view == null || host.webView !== view) return
        if (!sameOrigin(host, Uri.parse(url))) return
        val visible = isWindowRoute(Uri.parse(url))
        // Nothing to remove and nothing to add.
        if (backButtonPage == null && !visible) return
        if (!force && backButtonPage != null && backButtonPage == url && visible) return
        backButtonPage = if (visible) url else null
        val label = host.activity.getString(R.string.shell_nav_back_to_workspace)
        view.evaluateJavascript(backButtonScript(server, visible, label), null)
    }

    override fun onBack(host: ShellHost): Boolean {
        if (backPending) return true
        val view = host.webView
        if (view == null) {
            exitOrBack(host, null)
            return true
        }
        backPending = true
        val server = host.currentServerUrl
        val page = view.url
        view.evaluateJavascript(dismissLayerScript) { hadLayer ->
            if (host.webView !== view || host.currentServerUrl != server || view.url != page || host.activity.isDestroyed) {
                backPending = false
            } else if (hadLayer == "true") {
                exitArmedAt = null
                view.postDelayed({
                    if (host.webView === view && !host.activity.isDestroyed) {
                        // Confirm dismissal, but never leave a page whose layer refused Escape.
                        view.evaluateJavascript(layerStillOpenScript) { backPending = false }
                    } else {
                        backPending = false
                    }
                }, 100L)
            } else {
                backPending = false
                exitOrBack(host, view)
            }
        }
        return true
    }

    private fun exitOrBack(host: ShellHost, view: WebView?) {
        if (view?.canGoBack() == true) {
            exitArmedAt = null
            view.goBack()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val armed = exitArmedAt
        if (armed != null && now - armed <= 2_000L) {
            exitArmedAt = null
            // Preserve the loaded SPA and session when the user returns to the task.
            host.activity.moveTaskToBack(true)
        } else {
            exitArmedAt = now
            Toast.makeText(host.activity, R.string.shell_nav_press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Leave the WebView only for a real external address.
     *
     * codeg web signs in to a SpaceRing account the same way a browser does
     * (`src/lib/srn-account/navigate.ts` `sendTabTo`): the top frame is sent to
     * `https://spacering.net/...` and comes back to `/workspace?srn=…` in the
     * same tab. Handing that navigation to the system browser strands the
     * sign-in — the tab that returns is not the app. So a top-frame navigation
     * to a sign-in provider stays inside the WebView.
     *
     * The provider list mirrors what the page can reach: the release origin
     * (`DEFAULT_SRN_ORIGIN`) and anything under `spacering.net` — the sign-in
     * page and the account console the page opens from its menu. A development
     * server on a custom `CODEG_SRN_ORIGIN` is not covered: those sign-ins go
     * to the browser, where the page's `srn` result would also be lost.
     *
     * The return leg is a redirect from the provider back to the server, and it
     * stays in the WebView too (see the redirect rule below), which also keeps
     * an auth proxy's login redirect and its return on the page — the only way
     * a proxied server is reachable at all.
     */
    override fun shouldOverrideUrlLoading(host: ShellHost, view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return route(host, request.url, allowExternal = false)
        if (sameOrigin(host, request.url)) return false
        if (isSignInProvider(request.url)) return false
        val scheme = request.url.scheme?.lowercase(Locale.ROOT)
        // A redirect that started inside the app stays in the app: SRN's return
        // leg, and an auth proxy's login round trip, are both chains the page
        // must finish itself. A redirect reached from an already-external page
        // (only possible when the chain began outside) is left to the default.
        val page = view.url?.let(Uri::parse)
        if (request.isRedirect && (scheme == "http" || scheme == "https") &&
            (page == null || sameOrigin(host, page) || isSignInProvider(page))
        ) return false
        return route(host, request.url, allowExternal = true)
    }

    /** Where codeg web's SRN sign-in and account console live. */
    private fun isSignInProvider(uri: Uri): Boolean {
        if (!uri.scheme.equals("https", true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == "spacering.net" || host.endsWith(".spacering.net")
    }

    private fun sameOrigin(host: ShellHost, uri: Uri): Boolean {
        val server = host.currentServerUrl?.let(Uri::parse) ?: return false
        fun port(value: Uri): Int = if (value.port != -1) value.port else if (value.scheme.equals("https", true)) 443 else 80
        return uri.host != null && uri.scheme.equals(server.scheme, true) &&
            uri.host.equals(server.host, true) && port(uri) == port(server)
    }

    /** Return false only for an ordinary same-origin HTTP navigation. */
    private fun route(host: ShellHost, uri: Uri, allowExternal: Boolean = true): Boolean {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> {
                if (sameOrigin(host, uri)) return false
                if (allowExternal) launch(host, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            }
            "mailto", "tel", "market" -> if (allowExternal) launch(host, Intent(Intent.ACTION_VIEW, uri))
            "intent" -> if (allowExternal) openIntent(host, uri.toString())
            "javascript", "file", "content" -> Unit
            else -> if (allowExternal) unavailable(host)
        }
        return true
    }

    private fun openIntent(host: ShellHost, url: String) {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: java.net.URISyntaxException) {
            unavailable(host)
            return
        }
        val fallback = intent.getStringExtra("browser_fallback_url")
        intent.action = Intent.ACTION_VIEW
        intent.flags = 0
        intent.component = null
        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.removeExtra("browser_fallback_url")
        if (intent.data?.scheme?.lowercase(Locale.ROOT) in setOf("javascript", "file", "content", "intent")) {
            unavailable(host)
            return
        }
        if (!launch(host, intent, notifyFailure = false)) {
            val uri = fallback?.let(Uri::parse)
            if (uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https")) {
                if (!route(host, uri)) host.loadUrl(uri.toString())
            } else {
                unavailable(host)
            }
        }
    }

    private fun launch(host: ShellHost, intent: Intent, notifyFailure: Boolean = true): Boolean {
        return try {
            host.activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (notifyFailure) unavailable(host)
            false
        } catch (_: SecurityException) {
            if (notifyFailure) unavailable(host)
            false
        }
    }

    private fun unavailable(host: ShellHost) {
        Toast.makeText(host.activity, R.string.shell_nav_no_handler, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateWindow(host: ShellHost, view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        if (host.webView !== view) return false
        val openerServer = host.currentServerUrl
        val popup = WebView(host.activity)
        popup.settings.allowFileAccess = false
        popup.settings.allowContentAccess = false
        popups.add(popup)
        var handled = false
        val timeout = Runnable { if (popups.remove(popup)) popup.destroy() }
        fun navigate(url: String): Boolean {
            if (url == "about:blank") return false
            if (handled) return true
            handled = true
            if (host.webView === view && host.currentServerUrl == openerServer && !host.activity.isDestroyed &&
                !route(host, Uri.parse(url))
            ) {
                // Capture the opener before replacing the single main WebView.
                // A popup opened from a settings subpage must not replace it.
                view.url?.let { rememberWorkspaceEntry(host, view, it) }
                host.loadUrl(url)
            }
            popupHandler.removeCallbacks(timeout)
            popupHandler.post { if (popups.remove(popup)) popup.destroy() }
            return true
        }
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = navigate(request.url.toString())
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = navigate(url)
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                navigate(url)
            }
        }
        popupHandler.postDelayed(timeout, 15_000L)
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }
}

/**
 * Routes codeg web opens as a separate window, and which therefore have no way
 * out in a WebView (`src/lib/api.ts`: `openSettingsWindow`, `openMergeWindow`,
 * `openStashWindow`, `openPushWindow`, `openCommitWindow`,
 * `openImportSessionsWindow`, `openProjectBootWindow`). Every one of them draws
 * `AppTitleBar` with only a `center`, leaving the left slot free for the shell's
 * back button.
 */
private val WINDOW_ROUTES = listOf(
    "/settings",
    "/merge",
    "/stash",
    "/push",
    "/commit",
    "/import-sessions",
    "/project-boot",
)
