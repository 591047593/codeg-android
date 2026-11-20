package app.codeg.android.shell.nav

import org.json.JSONObject

/**
 * Top-frame, selected-origin wrapper for explicit window exits. Both close and
 * the titlebar button ask native code for the remembered workspace entry; the
 * browser's back/forward list says nothing about where a window route ends.
 * Native code owns the destination and revalidates the document on the UI thread.
 */
internal fun windowCloseBridgeScript(origin: String, token: String): String {
    val originJson = JSONObject.quote(origin)
    val tokenJson = JSONObject.quote(token)
    return """
        (() => {
          if (window !== window.top) return;
          let origin;
          try { origin = new URL($originJson).origin; } catch (_) { return; }
          if (location.origin !== origin) return;
          const secret = $tokenJson;
          const native = window.CodegShellWindowNative;
          const routes = ['/settings', '/merge', '/stash', '/push', '/commit', '/import-sessions', '/project-boot'];
          const leave = () => {
            if (location.origin !== origin || !routes.some(route =>
              location.pathname === route || location.pathname.startsWith(route + '/'))) return;
            if (native && typeof native.home === 'function') native.home(secret);
            else location.assign(new URL('/workspace', origin).href);
          };
          Object.defineProperty(window, 'CodegShellWindow', {
            configurable: true,
            value: { home: leave, close: leave }
          });
          Object.defineProperty(window, 'close', {
            configurable: true,
            writable: true,
            value: leave
          });
        })();
    """.trimIndent()
}
