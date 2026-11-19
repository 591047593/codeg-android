package app.codeg.android.shell.web

/**
 * Keeps the page's Page Visibility state in step with the app's foreground/background
 * state.
 *
 * codeg web gates several behaviours on it:
 * - `src/lib/desktop-notification.ts` posts OS notifications only when the page
 *   reports `document.hidden` (its default `when: "hidden"`), so a WebView that
 *   never reports hidden would silence every background alert;
 * - `src/lib/message-input-draft.ts` flushes a composer draft when the page hides;
 * - `src/lib/srn-account/web-source.ts` re-reads the account when it becomes
 *   visible again.
 *
 * Chromium-backed WebView ties visibility to window visibility, which is not
 * reliable across Activity lifecycle transitions — `document.hidden` is commonly
 * still `false` after the app has been backgrounded. So the shell owns the flag:
 * `__codegShellSetHidden` overrides both `document.hidden` and
 * `document.visibilityState` with the OR of the real state and the app's, and
 * dispatches a real `visibilitychange` when the native side flips it. The OR
 * keeps the values correct on the WebViews that do report it themselves.
 *
 * No token: the flag carries no data and a cross-origin frame cannot reach the
 * top window's function. Installed in the top frame only.
 */
internal val visibilityScript = """
(function() {
  if (window !== window.top || window.__codegShellVisibilityInstalled) return;
  var background = false;
  window.__codegShellSetHidden = function(hidden) {
    hidden = !!hidden;
    if (hidden === background) return;
    background = hidden;
    document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
  };
  try {
    var descriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
    Object.defineProperty(document, 'hidden', {
      configurable: true,
      get: function() {
        var real = descriptor && descriptor.get ? descriptor.get.call(document) : false;
        return real || background;
      }
    });
    var stateDescriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: function() {
        var real = stateDescriptor && stateDescriptor.get ? stateDescriptor.get.call(document) : 'visible';
        return (real === 'hidden' || background) ? 'hidden' : real;
      }
    });
  } catch (_) {
    // A future WebView may seal these; the notification shim reads native state
    // directly, so only the page's own gates lose this signal.
  }
  window.__codegShellVisibilityInstalled = true;
})();
""".trimIndent()

/** Native lifecycle → page flag. No-op when the script has not been installed. */
internal fun visibilityUpdateScript(hidden: Boolean): String =
    "(function(){ if (window.__codegShellSetHidden) window.__codegShellSetHidden($hidden); })()"
