package app.codeg.android.shell.display

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import app.codeg.android.R
import app.codeg.android.shell.ShellFeature
import app.codeg.android.shell.ShellHost
import java.util.UUID
import org.json.JSONObject

/** Display adaptation only; the server remains responsible for the page's theme. */
class DisplayFeature : ShellFeature {
    private var documentToken: String? = null

    override fun onCreate(host: ShellHost) {
        // enableEdgeToEdge already runs in MainActivity. Keep adjustResize so Android 11
        // dispatches IME insets; Compose consumes overlapping navigation/IME padding once.
        WindowCompat.setDecorFitsSystemWindows(host.activity.window, false)
        applyBackground(host, initialColor(host))
        host.activity.addOnConfigurationChangedListener { configuration ->
            // Activity retains uiMode. Reapply the resource-qualified platform theme before
            // WebView recomputes isLightTheme / prefers-color-scheme. No page reload or JS
            // matchMedia shim: Chromium dispatches the real media-query change to the page.
            host.activity.theme.applyStyle(R.style.Theme_Codeg, true)
            host.webView?.let { view ->
                view.dispatchConfigurationChanged(configuration)
                view.settings.textZoom = 100
                view.post {
                    if (host.webView === view) observePage(host, view)
                }
            } ?: applyBackground(host, initialColor(host))
        }
    }

    @Composable
    override fun webViewModifier(host: ShellHost): Modifier =
        Modifier.systemBarsPadding().displayCutoutPadding().imePadding()

    override fun onWebViewCreated(host: ShellHost, webView: WebView) {
        documentToken = null
        webView.settings.textZoom = 100
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.settings.isAlgorithmicDarkeningAllowed = false
        }
        applyBackground(host, initialColor(host), webView)
        webView.addJavascriptInterface(DisplayBridge(host, webView), "CodegShellDisplay")
        webView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            val oldHeight = oldBottom - oldTop
            if (height != oldHeight && oldHeight > 0 && host.webView === webView &&
                sameOrigin(webView.url, host.currentServerUrl)
            ) {
                // Layout callbacks occur after imePadding changes the actual AndroidView.
                // Growing (including IME dismissal) cancels any pending focus scroll.
                webView.evaluateJavascript(
                    "window.__codegShellDisplayIme && window.__codegShellDisplayIme(${height < oldHeight});",
                    null,
                )
            }
        }
    }

    override fun onPageStarted(host: ShellHost, view: WebView, url: String, favicon: Bitmap?) {
        documentToken = null
    }

    override fun onPageFinished(host: ShellHost, view: WebView, url: String) {
        observePage(host, view)
    }

    private fun observePage(host: ShellHost, view: WebView) {
        val serverUrl = host.currentServerUrl ?: return
        if (host.webView !== view || !sameOrigin(view.url, serverUrl)) return
        val token = UUID.randomUUID().toString()
        documentToken = token
        view.evaluateJavascript(mobileTurnStatsScript(JSONObject.quote(serverUrl)), null)
        view.evaluateJavascript(themeObserver(JSONObject.quote(token)), null)
        view.evaluateJavascript(imeFocusObserver, null)
    }

    private inner class DisplayBridge(private val host: ShellHost, private val view: WebView) {
        @JavascriptInterface
        fun report(token: String, red: Int, green: Int, blue: Int) {
            // Bridges are visible to every frame and run off the UI thread. The unguessable
            // per-document token is injected only in the top frame, never accepted from a
            // caller-supplied origin. Recheck navigation and generation on the UI thread.
            view.post {
                if (host.webView !== view || token != documentToken ||
                    !sameOrigin(view.url, host.currentServerUrl)
                ) return@post
                if (red !in 0..255 || green !in 0..255 || blue !in 0..255) return@post
                applyBackground(host, Color.rgb(red, green, blue), view)
            }
        }
    }

    @Suppress("DEPRECATION") // Platform bar colors remain needed on Android 11–14.
    private fun applyBackground(host: ShellHost, color: Int, view: WebView? = host.webView) {
        val window = host.activity.window
        window.setBackgroundDrawable(ColorDrawable(color))
        // Paint the whole decor, including the strips outside the padded AndroidView.
        window.decorView.setBackgroundColor(color)
        view?.setBackgroundColor(color)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        val light = ColorUtils.calculateLuminance(color) > 0.179
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun initialColor(host: ShellHost): Int =
        if (host.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        ) Color.rgb(10, 10, 10) else Color.WHITE
}

private fun sameOrigin(page: String?, server: String?): Boolean {
    if (page == null || server == null) return false
    val a = Uri.parse(page)
    val b = Uri.parse(server)
    fun port(uri: Uri): Int = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
    return a.scheme in listOf("http", "https") && a.scheme.equals(b.scheme, true) &&
        a.host != null && a.host.equals(b.host, true) && port(a) == port(b)
}

/** Canvas converts modern CSS colors (including oklch) to opaque sRGB for Android. */
internal fun themeObserver(quotedToken: String): String = """
    (() => {
      if (window.__codegShellDisplayCleanup) window.__codegShellDisplayCleanup();
      const token = $quotedToken;
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 1;
      const ctx = canvas.getContext('2d');
      if (!ctx || !document.body) return;
      const media = matchMedia('(prefers-color-scheme: dark)');
      let frame = 0;
      let previous = '';
      const report = () => {
        frame = 0;
        const meta = Array.from(document.querySelectorAll('meta[name="theme-color"]'))
          .find(tag => !tag.media || matchMedia(tag.media).matches);
        ctx.clearRect(0, 0, 1, 1);
        ctx.fillStyle = media.matches ? '#0a0a0a' : '#ffffff';
        ctx.fillRect(0, 0, 1, 1);
        for (const color of [getComputedStyle(document.documentElement).backgroundColor,
                             getComputedStyle(document.body).backgroundColor,
                             meta && meta.content]) {
          if (color && CSS.supports('color', color)) {
            ctx.fillStyle = color;
            ctx.fillRect(0, 0, 1, 1);
          }
        }
        const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
        const value = [r, g, b].join(',');
        if (value !== previous) {
          previous = value;
          window.CodegShellDisplay.report(token, r, g, b);
        }
      };
      const schedule = () => { if (!frame) frame = requestAnimationFrame(report); };
      const observer = new MutationObserver(schedule);
      observer.observe(document.documentElement, {attributes: true, attributeFilter: ['class', 'style']});
      observer.observe(document.body, {attributes: true, attributeFilter: ['class', 'style']});
      if (document.head) observer.observe(document.head, {
        subtree: true, childList: true, attributes: true,
        attributeFilter: ['content', 'media', 'name', 'href', 'style']
      });
      media.addEventListener('change', schedule);
      window.addEventListener('pageshow', schedule);
      window.__codegShellDisplayCleanup = () => {
        observer.disconnect();
        cancelAnimationFrame(frame);
        media.removeEventListener('change', schedule);
        window.removeEventListener('pageshow', schedule);
      };
      report();
    })();
""".trimIndent()
