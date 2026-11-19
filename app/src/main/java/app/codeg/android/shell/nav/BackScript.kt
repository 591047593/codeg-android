package app.codeg.android.shell.nav

/**
 * Content nodes only: open triggers and collapsibles are not dismissible layers.
 *
 * Checked against codeg web v0.46.0 `src/components/ui/`:
 * - Radix surfaces carry `data-slot` and `data-state="open|closed"` (`dialog`,
 *   `alert-dialog`, `popover`, `dropdown-menu`, `context-menu`, `select`,
 *   `hover-card`).
 * - Base UI surfaces (`drawer`, `combobox`) carry `data-slot` and the boolean
 *   `data-open` / `data-closed` attributes instead — no `data-state` at all.
 * - Streamdown's fullscreen views portal to `<body>` as `role="dialog"` with no
 *   `data-state`; they only exist while open.
 *
 * An element with no state attribute at all is treated as open (that is the
 * Streamdown case), and `data-closed` marks the exit animation of a Base UI
 * surface, which must not be reported as still open.
 */
private const val LAYER_SELECTOR =
    "[role=\"dialog\"],[role=\"alertdialog\"],[role=\"menu\"],[role=\"listbox\"]," +
        "[data-slot=\"dialog-content\"],[data-slot=\"alert-dialog-content\"]," +
        "[data-slot=\"popover-content\"],[data-slot=\"dropdown-menu-content\"]," +
        "[data-slot=\"dropdown-menu-sub-content\"],[data-slot=\"context-menu-content\"]," +
        "[data-slot=\"context-menu-sub-content\"],[data-slot=\"select-content\"]," +
        "[data-slot=\"combobox-content\"],[data-slot=\"hover-card-content\"]," +
        "[data-slot=\"drawer-popup\"],[data-streamdown=\"table-fullscreen\"]," +
        "[data-streamdown=\"mermaid-fullscreen\"]"

internal val dismissLayerScript = """
(function() {
  var selector = ${quote(LAYER_SELECTOR)};
  var layers = Array.from(document.querySelectorAll(selector)).filter(function(el) {
    return el.getAttribute('data-state') !== 'closed' && !el.hasAttribute('data-closed') &&
      !el.hidden && el.getAttribute('aria-hidden') !== 'true' &&
      getComputedStyle(el).visibility !== 'hidden' && el.getClientRects().length > 0;
  });
  if (!layers.length) return false;
  (document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', {
    key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true
  }));
  return true;
})()
""".trimIndent()

/** Separate evaluation lets React commit dismissal before we inspect the DOM again. */
internal val layerStillOpenScript = """
(function() {
  return Array.from(document.querySelectorAll(${quote(LAYER_SELECTOR)})).some(function(el) {
    return el.getAttribute('data-state') !== 'closed' && !el.hasAttribute('data-closed') &&
      !el.hidden && el.getAttribute('aria-hidden') !== 'true' &&
      getComputedStyle(el).visibility !== 'hidden' && el.getClientRects().length > 0;
  });
})()
""".trimIndent()

/** JSON-quote for embedding inside the IIFE (no dependency on org.json here). */
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
