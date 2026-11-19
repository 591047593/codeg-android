package app.codeg.android.shell.nav

/**
 * The back affordance for codeg web's "window-style" routes.
 *
 * Several routes are opened as a separate window on the desktop and via
 * `window.open` in a browser (`src/lib/api.ts` `openAppWindow`): `/settings`,
 * `/merge`, `/stash`, `/push`, `/commit`, `/import-sessions`, plus
 * `/project-boot`. In a desktop window the OS chrome is the way out; in a
 * browser tab it is the tab's back button.
 *
 * In a WebView there is neither. `openAppWindow` ends up navigating the single
 * WebView (see the shell's `onCreateWindow`), so the page that opened it is
 * gone, and the only way back is a gesture a phone user has no visible cue for.
 * `SettingsShell` is the worst case: its mobile title bar renders a menu button
 * and nothing else (`src/components/settings/settings-shell.tsx`), so the page
 * offers no exit at all.
 *
 * So the shell draws one. It goes into the title bar's own LEFT slot — which
 * every one of those routes leaves empty, because `AppTitleBar` is passed only
 * a `center` — so it sits in the page's layout instead of floating over its
 * content. Its colours come from the page's CSS variables, and it calls
 * `history.back()`, which is the same thing the system back gesture does.
 *
 * The button is only added while [visible]; navigating off a window route
 * removes it again.
 */
internal fun backButtonScript(origin: String, visible: Boolean): String = """
(function() {
  if (location.origin !== ${quote(origin)}) return;
  var existing = document.querySelector('[data-codeg-shell-back]');
  if (!${visible}) {
    if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
    return;
  }
  if (existing) return;

  var button = document.createElement('button');
  button.type = 'button';
  button.setAttribute('data-codeg-shell-back', '');
  button.setAttribute('aria-label', 'Back');
  button.setAttribute('title', 'Back');
  button.style.cssText = [
    'display:inline-flex',
    'align-items:center',
    'justify-content:center',
    'height:2rem',
    'width:2rem',
    'margin-inline-end:0.25rem',
    'flex:none',
    'border-radius:0.5rem',
    'border:0',
    'background:transparent',
    'color:var(--foreground)',
    'cursor:pointer',
    'padding:0'
  ].join(';');
  button.innerHTML =
    '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" ' +
    'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" ' +
    'stroke-linejoin="round" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>';
  button.addEventListener('pointerdown', function(event) { event.stopPropagation(); });
  button.addEventListener('click', function(event) {
    event.preventDefault();
    event.stopPropagation();
    // A page entered directly (a notification tap, a deep link) has no history
    // to go back to. `history.back()` would then be a no-op, and the shell's own
    // back gesture would read it as "press back again to exit". Ask the shell
    // for the workspace root instead.
    if (window.history.length <= 1) {
      var bridge = window.CodegShellWindow;
      if (bridge && bridge.home) { bridge.home(); return; }
      return;
    }
    window.history.back();
  });

  // AppTitleBar's interactive row is the drag-region element that is a flex
  // box AND has a child (its left slot, the first child of
  // app-title-bar.tsx's row). The bare drag regions around it are childless.
  var candidates = Array.prototype.slice
    .call(document.querySelectorAll('[data-tauri-drag-region]'))
    .filter(function(el) {
      return String(el.className || '').indexOf('flex') !== -1 && el.firstElementChild;
    });
  var row = candidates.pop();
  var slot = row && row.firstElementChild;
  if (slot) {
    slot.style.display = 'flex';
    slot.style.alignItems = 'center';
    // Before whatever the page put there (the settings menu button on mobile).
    slot.insertBefore(button, slot.firstChild);
    return;
  }
  // Unexpected markup: pin it to the corner rather than lose the way out.
  button.style.position = 'fixed';
  button.style.top = 'calc(env(safe-area-inset-top) + 0.375rem)';
  button.style.left = 'calc(env(safe-area-inset-left) + 0.5rem)';
  button.style.zIndex = '2147483647';
  document.body.appendChild(button);
})();
""".trimIndent()

private fun quote(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }
    append('"')
}
