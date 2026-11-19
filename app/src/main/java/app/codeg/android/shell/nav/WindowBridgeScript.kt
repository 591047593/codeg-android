package app.codeg.android.shell.nav

import org.json.JSONObject

/**
 * Makes `window.close()` work in the one page of codeg web that calls it.
 *
 * `/import-sessions` offers a "Close" button whose handler is `window.close()`
 * (`src/app/import-sessions/page.tsx`). A browser only honours that call for a
 * window the script itself opened; the shell turns `window.open` into a
 * navigation of the single WebView, so the button is silently dead there.
 *
 * The desktop app closes the real window. The closest equivalent is to leave
 * the page the way the user arrived — `history.back()`, or the workspace root
 * when the page was entered directly (a notification tap, or a reload after the
 * WebView was discarded). The same `home()` is what the injected back button
 * calls when it finds no history.
 *
 * Top frame only, server origin only, token-guarded: the native interface is
 * visible to every frame, the wrapper is not.
 */
internal fun windowCloseBridgeScript(origin: String, token: String): String {
    val originJson = JSONObject.quote(origin)
    val tokenJson = JSONObject.quote(token)
    return """
        (() => {
          if (window !== window.top || location.origin !== $originJson) return;
          const secret = $tokenJson;
          const native = window.CodegShellWindowNative;
          const leave = () => {
            if (window.history.length > 1) { window.history.back(); return; }
            if (native && native.home) native.home(secret);
          };
          // `home` is what the injected back button falls back to when the page
          // was entered directly and has no history of its own.
          Object.defineProperty(window, 'CodegShellWindow', {
            configurable: true,
            value: { home: leave, close: leave }
          });
          // Only `/import-sessions` calls this; a browser ignores it here.
          Object.defineProperty(window, 'close', {
            configurable: true,
            writable: true,
            value: leave
          });
        })();
    """.trimIndent()
}
