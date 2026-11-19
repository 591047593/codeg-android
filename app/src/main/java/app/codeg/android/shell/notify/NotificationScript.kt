package app.codeg.android.shell.notify

import org.json.JSONObject

/**
 * Installs the Web Notifications API codeg web expects (`src/lib/notification.ts`):
 * `Notification.permission`, `Notification.requestPermission()` and
 * `new Notification(title, { body })`.
 *
 * Android WebView does not implement the API at all, so without this shim the
 * web build reports "unsupported" (`getNotificationPermission`) and never posts.
 *
 * `permission` is a live getter over a JS variable the native side refreshes.
 * `requestPermission()` asks natively and polls until the answer settles: the
 * Android dialog is modal to the app and produces no page callback, and the
 * settings panel needs a real three-valued answer. Everything is origin- and
 * token-guarded like the clipboard bridge.
 */
internal fun notificationScript(origin: String, token: String, initial: String): String {
    val originJson = JSONObject.quote(origin)
    val tokenJson = JSONObject.quote(token)
    val stateJson = JSONObject.quote(initial)
    return """
        (() => {
          if (location.origin !== $originJson) return;
          if (window.__codegNotifyInstalled) {
            // A later pass (page-finished) carries the same token; refresh the
            // cached permission so the panel never reads a stale answer.
            if (typeof window.__codegNotifyRefresh === 'function') window.__codegNotifyRefresh();
            return;
          }
          const bridge = window.CodegShellNotify, secret = $tokenJson;
          if (!bridge) return;
          let state = $stateJson;
          const instances = new Map();
          const refresh = () => { const next = bridge.state(secret); if (next) state = next; };

          function Notification(title, options) {
            if (!(this instanceof Notification)) {
              throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator.");
            }
            options = options || {};
            this.title = String(title == null ? '' : title);
            this.body = options.body == null ? '' : String(options.body);
            this.tag = options.tag == null ? '' : String(options.tag);
            this.onclick = null;
            this.onshow = null;
            this.onerror = null;
            this.onclose = null;
            this.__id = 'n' + Math.random().toString(36).slice(2) + Date.now();
            if (state === 'denied') {
              throw new DOMException('Notification permission has been denied', 'NotAllowedError');
            }
            // Native decides: a granted channel posts at once, an undecided one
            // raises Android's permission dialog and posts after the grant.
            if (!bridge.post(secret, this.__id, this.title, this.body)) {
              throw new DOMException('Notification could not be delivered', 'NotAllowedError');
            }
            instances.set(this.__id, this);
          }
          Object.defineProperty(Notification, 'permission', { configurable: true, get: () => state });
          Notification.requestPermission = function (callback) {
            const settle = () => {
              refresh();
              if (typeof callback === 'function') { try { callback(state); } catch (_) {} }
              return state;
            };
            return new Promise((resolve) => {
              if (state === 'granted' || state === 'denied') { resolve(settle()); return; }
              bridge.request(secret);
              const started = Date.now();
              const timer = setInterval(() => {
                refresh();
                // Well past any real answer to a modal dialog, and this must
                // not poll forever if the user swipes the app away instead.
                if (state !== 'default' || Date.now() - started > 120000) {
                  clearInterval(timer);
                  if (state === 'default') state = 'denied';
                  resolve(settle());
                }
              }, 300);
            });
          };
          Notification.prototype.close = function () {
            instances.delete(this.__id);
            bridge.cancel(secret, this.__id);
            if (typeof this.onclose === 'function') {
              try { this.onclose({ type: 'close', target: this }); } catch (_) {}
            }
          };
          Notification.prototype.addEventListener = function (type, listener) {
            if (type === 'click') this.onclick = listener;
            else if (type === 'close') this.onclose = listener;
            else if (type === 'show') this.onshow = listener;
            else if (type === 'error') this.onerror = listener;
          };
          // Native calls this when the user taps a notification. Unknown ids
          // (the page navigated since) are silently ignored.
          window.__codegNotifyTap = (id) => {
            const instance = instances.get(id);
            if (!instance) return false;
            instances.delete(id);
            if (typeof instance.onclick === 'function') {
              try { instance.onclick({ type: 'click', target: instance }); } catch (_) {}
            }
            return true;
          };
          // Permission can change in system settings while the app is in the
          // background; re-read it whenever the page becomes visible again.
          document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') refresh();
          });
          // The first install may itself follow a grant (the caller re-injects
          // after the dialog); take that answer rather than the stale literal.
          refresh();
          window.__codegNotifyRefresh = refresh;
          window.__codegNotifyInstalled = true;
          try {
            Object.defineProperty(window, 'Notification', {
              configurable: true, writable: true, value: Notification
            });
          } catch (_) {
            // A platform that defines Notification as non-configurable keeps
            // its own; the shim stays installed for the rest of the API.
          }
        })();
    """.trimIndent()
}

/** Native tap delivery: invoke the page's `onclick` for the notification, if still live. */
internal fun notificationClickScript(id: String): String =
    "(function(){ return window.__codegNotifyTap ? window.__codegNotifyTap(${JSONObject.quote(id)}) : false; })()"
