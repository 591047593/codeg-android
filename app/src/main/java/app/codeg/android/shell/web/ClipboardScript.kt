package app.codeg.android.shell.web

import org.json.JSONObject

/**
 * Only text APIs are emulated; image APIs retain browser capability detection.
 *
 * The fallback exists for one case: non-secure HTTP origins where the browser
 * clipboard API is unavailable. It is not a way around a browser permission
 * denial — `NotAllowedError` (and any permission-shaped rejection) propagates,
 * and the native read additionally requires active user activation in the top
 * selected-server document, so backgrounded or scripted reads stay refused.
 */
internal fun clipboardScript(origin: String, token: String): String {
    val originJson = JSONObject.quote(origin)
    val tokenJson = JSONObject.quote(token)
    return """
        (() => {
          if (location.origin !== $originJson || window.__codegClipboardInstalled) return;
          // JavascriptInterface is visible to every frame; the fallback belongs
          // to the top selected-server document only.
          if (window.top !== window) return;
          const token = $tokenJson;
          const clipboard = navigator.clipboard || {};
          const isPermissionDenial = (error) =>
            error && (error.name === 'NotAllowedError' || error.name === 'SecurityError');
          for (const name of ['writeText', 'readText']) {
            const native = typeof clipboard[name] === 'function' ? clipboard[name].bind(clipboard) : null;
            const fallback = (...args) => {
              if (name === 'writeText') {
                if (!CodegShellClipboard.writeText(token, String(args[0]))) throw new Error('Clipboard unavailable');
                return;
              }
              if (!navigator.userActivation || !navigator.userActivation.isActive) {
                throw new DOMException('Clipboard read requires user activation', 'NotAllowedError');
              }
              const text = CodegShellClipboard.readText(token);
              if (text === null) throw new Error('Clipboard unavailable');
              return text;
            };
            Object.defineProperty(clipboard, name, {
              configurable: true,
              value: async (...args) => {
                if (native) {
                  try {
                    return await native(...args);
                  } catch (error) {
                    // A browser denial is a denial: do not turn it into a
                    // native success. Only unexpected failures may fall back.
                    if (isPermissionDenial(error)) throw error;
                  }
                }
                return fallback(...args);
              }
            });
          }
          if (!navigator.clipboard) Object.defineProperty(navigator, 'clipboard', { configurable: true, value: clipboard });
          window.__codegClipboardInstalled = true;
        })();
    """.trimIndent()
}
