package app.codeg.android.shell.nav

import org.json.JSONObject

/**
 * Window-style routes have no desktop window chrome in the single WebView.
 * Their explicit exit is not system back: it leaves all settings subpages at
 * once through the native workspace-entry bridge, irrespective of history.
 *
 * One observer owns the button while on a window route. It follows late or
 * replaced AppTitleBar rows without polling, preserves the settings menu, and
 * tears down its observer, history wrappers and layout changes on exit.
 */
internal fun backButtonScript(origin: String, visible: Boolean, label: String): String {
    val originJson = JSONObject.quote(origin)
    val labelJson = JSONObject.quote(label)
    return """
        (() => {
          if (window !== window.top) return;
          let origin;
          try { origin = new URL($originJson).origin; } catch (_) { return; }
          if (location.origin !== origin) return;
          const routes = ['/settings', '/merge', '/stash', '/push', '/commit', '/import-sessions', '/project-boot'];
          const onWindowRoute = () => location.origin === origin && routes.some(route =>
            location.pathname === route || location.pathname.startsWith(route + '/'));
          const previous = window.__codegShellBack;
          if (!${visible} || !onWindowRoute()) {
            if (previous) previous.teardown();
            return;
          }
          if (previous) {
            previous.setLabel($labelJson);
            previous.refresh();
            return;
          }

          const button = document.createElement('button');
          button.type = 'button';
          button.setAttribute('data-codeg-shell-back', '');
          const setLabel = label => {
            button.setAttribute('aria-label', label);
            button.setAttribute('title', label);
          };
          setLabel($labelJson);
          button.style.cssText = [
            'display:inline-flex', 'align-items:center', 'justify-content:center',
            'min-height:48px', 'min-width:48px', 'height:48px', 'width:48px',
            'margin-inline-end:0.25rem', 'flex:none', 'border-radius:0.5rem',
            'border:0', 'background:transparent', 'color:var(--foreground)',
            'cursor:pointer', 'padding:0', '-webkit-app-region:no-drag'
          ].join(';');
          button.innerHTML =
            '<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" ' +
            'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" ' +
            'stroke-linejoin="round" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>';
          button.addEventListener('pointerdown', event => event.stopPropagation());
          button.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            if (!onWindowRoute()) return;
            const bridge = window.CodegShellWindow;
            if (bridge && typeof bridge.home === 'function') bridge.home();
            else location.assign(new URL('/workspace', origin).href);
          });

          let slot = null;
          let savedDisplay;
          let savedAlignment;
          let stopped = false;
          const restoreSlot = () => {
            if (!slot) return;
            if (slot.style.display === 'flex') slot.style.display = savedDisplay;
            if (slot.style.alignItems === 'center') slot.style.alignItems = savedAlignment;
            slot = null;
          };
          const history = window.history;
          const originalPush = history.pushState;
          const originalReplace = history.replaceState;
          const teardown = () => {
            if (stopped) return;
            stopped = true;
            observer.disconnect();
            window.removeEventListener('popstate', refresh);
            window.removeEventListener('pagehide', teardown);
            if (history.pushState === push) history.pushState = originalPush;
            if (history.replaceState === replace) history.replaceState = originalReplace;
            button.remove();
            restoreSlot();
            if (window.__codegShellBack === state) delete window.__codegShellBack;
          };
          const refresh = () => {
            if (stopped) return;
            if (!onWindowRoute()) { teardown(); return; }
            // AppTitleBar's interactive flex row has a first-child left slot.
            const rows = Array.from(document.querySelectorAll('[data-tauri-drag-region]'))
              .filter(el => String(el.className || '').split(/\s+/).includes('flex') && el.firstElementChild);
            const row = rows.pop();
            const next = row && row.firstElementChild;
            if (next !== slot) {
              restoreSlot();
              slot = next;
              if (slot) {
                savedDisplay = slot.style.display;
                savedAlignment = slot.style.alignItems;
                slot.style.display = 'flex';
                slot.style.alignItems = 'center';
              }
            }
            // Wait for a header rather than float over/hide its controls.
            if (!slot) { button.remove(); return; }
            if (slot.firstElementChild !== button) slot.insertBefore(button, slot.firstChild);
          };
          const push = function() { const result = originalPush.apply(this, arguments); refresh(); return result; };
          const replace = function() { const result = originalReplace.apply(this, arguments); refresh(); return result; };
          const observer = new MutationObserver(refresh);
          const state = { setLabel, refresh, teardown };
          window.__codegShellBack = state;
          history.pushState = push;
          history.replaceState = replace;
          window.addEventListener('popstate', refresh);
          window.addEventListener('pagehide', teardown);
          observer.observe(document, {
            childList: true, subtree: true, attributes: true,
            attributeFilter: ['class', 'data-tauri-drag-region']
          });
          refresh();
        })();
    """.trimIndent()
}
