package app.codeg.android.shell.notify

import org.json.JSONObject

/** Installs only in the selected server's top document; secrets stay inside closures. */
internal fun notificationScript(origin: String, token: String, initial: String): String {
    val originJson = JSONObject.quote(origin)
    val tokenJson = JSONObject.quote(token)
    val stateJson = JSONObject.quote(initial)
    return """
        (() => {
          if (window.top !== window || location.origin !== $originJson) return false;
          if (window.__codegNotifyInstalled) {
            return typeof window.__codegNotifyRefresh === 'function' &&
              window.__codegNotifyRefresh($tokenJson);
          }
          const bridge = window.CodegShellNotify, secret = $tokenJson;
          if (!bridge) return false;
          let state = $stateJson;
          const allowed = (token) => token === secret && window.top === window &&
            location.origin === $originJson;
          const refresh = () => {
            try {
              const next = bridge.state(secret);
              if (next === 'default' || next === 'denied' || next === 'granted') state = next;
            } catch (_) {}
            return state;
          };

          const MAX_INSTANCES = 32, INSTANCE_TTL_MS = 30 * 60 * 1000;
          const instances = new Map();
          let instanceTimer = null;
          const prune = () => {
            if (instanceTimer !== null) clearTimeout(instanceTimer);
            instanceTimer = null;
            const now = Date.now();
            for (const [id, entry] of instances) {
              if (entry.expires <= now) instances.delete(id);
            }
            if (instances.size) {
              const next = Math.min(...Array.from(instances.values(), entry => entry.expires));
              instanceTimer = setTimeout(prune, Math.max(1, next - now));
            }
          };
          const retain = (id, instance) => {
            instances.set(id, { instance, expires: Date.now() + INSTANCE_TTL_MS });
            while (instances.size > MAX_INSTANCES) instances.delete(instances.keys().next().value);
            prune();
          };

          // One timer and at most 32 promises per document. Native callbacks settle
          // them together; a lost callback resolves with the actual current state.
          const waiting = new Set();
          let permissionTimer = null;
          const settle = () => {
            refresh();
            if (permissionTimer !== null) clearTimeout(permissionTimer);
            permissionTimer = null;
            const callbacks = Array.from(waiting);
            waiting.clear();
            for (const callback of callbacks) callback(state);
          };
          window.__codegNotifyRefresh = (token) => {
            if (!allowed(token)) return false;
            refresh();
            return true;
          };
          window.__codegNotifySettle = (token) => {
            if (!allowed(token)) return false;
            settle();
            return true;
          };

          function Notification(title, options) {
            if (!(this instanceof Notification)) {
              throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator.");
            }
            options = options || {};
            this.title = String(title == null ? '' : title);
            this.body = options.body == null ? '' : String(options.body);
            this.tag = options.tag == null ? '' : String(options.tag);
            this.onclick = this.onshow = this.onerror = this.onclose = null;
            this.__id = 'n' + Math.random().toString(36).slice(2) + Date.now();
            if (refresh() !== 'granted') {
              throw new DOMException('Notification permission has not been granted', 'NotAllowedError');
            }
            // Never prompt from event delivery. Persist the actual current SPA URL
            // so native can restore the page even after this instance/process dies.
            if (!bridge.post(secret, this.__id, this.title, this.body, String(location.href))) {
              throw new DOMException('Notification could not be delivered', 'NotAllowedError');
            }
            retain(this.__id, this);
          }
          Object.defineProperty(Notification, 'permission', { configurable: true, get: refresh });
          Notification.requestPermission = function (callback) {
            refresh();
            return new Promise((resolve) => {
              const finish = (value) => {
                if (typeof callback === 'function') { try { callback(value); } catch (_) {} }
                resolve(value);
              };
              if (state !== 'default' || waiting.size >= 32) { finish(state); return; }
              const first = waiting.size === 0;
              waiting.add(finish);
              if (!first) return;
              permissionTimer = setTimeout(settle, 120000);
              try {
                if (!bridge.request(secret)) settle();
              } catch (_) { settle(); }
            });
          };
          Notification.prototype.close = function () {
            instances.delete(this.__id);
            prune();
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
          window.__codegNotifyTap = (token, id) => {
            if (!allowed(token)) return false;
            prune();
            const entry = instances.get(id);
            if (!entry) return false;
            instances.delete(id);
            prune();
            const instance = entry.instance;
            // With no live onclick there is nothing to restore in JS. Ask native
            // to open the persisted target rather than swallowing the user's tap.
            if (typeof instance.onclick !== 'function') return false;
            try { instance.onclick({ type: 'click', target: instance }); return true; }
            catch (_) { return false; }
          };
          document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible') refresh();
          });
          refresh();
          try {
            Object.defineProperty(window, 'Notification', {
              configurable: true, writable: true, value: Notification
            });
          } catch (_) { return false; }
          window.__codegNotifyInstalled = true;
          return true;
        })();
    """.trimIndent()
}

/** Evaluated only after the document-finished install acknowledgement. */
internal fun notificationClickScript(origin: String, token: String, id: String): String {
    val originJson = JSONObject.quote(origin)
    val tokenJson = JSONObject.quote(token)
    val idJson = JSONObject.quote(id)
    return """
        (() => {
          if (window.top !== window || location.origin !== $originJson) return false;
          return typeof window.__codegNotifyTap === 'function' &&
            window.__codegNotifyTap($tokenJson, $idJson);
        })();
    """.trimIndent()
}

/** Token-checked native permission update; never settles a replacement document. */
internal fun notificationPermissionScript(origin: String, token: String, settle: Boolean): String {
    val originJson = JSONObject.quote(origin)
    val tokenJson = JSONObject.quote(token)
    val methodJson = JSONObject.quote(if (settle) "__codegNotifySettle" else "__codegNotifyRefresh")
    return """
        (() => {
          if (window.top !== window || location.origin !== $originJson) return false;
          const update = window[$methodJson];
          return typeof update === 'function' && update($tokenJson);
        })();
    """.trimIndent()
}
