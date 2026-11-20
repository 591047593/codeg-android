# V8 Task 2 — one-click window-route exit

## Scope and implementation

Only the three owned navigation Kotlin files, the existing navigation string resources in both locales, the new regression script, and this requested report were changed. No host/update/server/error/notification/clipboard files were edited by this task. No staging, commits, pushes, branch changes, Gradle/APK/device/network operations, or subdelegation were performed.

- `NavFeature.kt` remembers the full same-origin `/workspace` URL (query and fragment included) from visited-history callbacks, page lifecycle callbacks, and the opener immediately before a same-origin popup replaces the main WebView. Settings subpages cannot overwrite that entry. A WebView or selected-server change invalidates the remembered destination. Page-finished forces an idempotent injection retry in case an earlier history callback evaluated before the document was ready; delayed popups cannot navigate a replacement view or a different selected server.
- The JavaScript interface only posts work. On UI it checks the document secret, active WebView, activity, document's selected server, current selected origin, and current window-style route. The destination is either the native-observed, revalidated workspace entry or that server's `/workspace`; JavaScript cannot supply an exit URL.
- Both explicit exits bypass browser back/forward history. The close wrapper sends `home(secret)` for window routes; the button calls that wrapper, with a same-origin workspace fallback if unavailable. Both scripts compare against `new URL(quotedServer).origin` and reject child frames/foreign origins.
- The button has a quoted localized label and 48px minimum dimensions. One per-document, window-route-scoped MutationObserver follows late/replaced titlebars, inserts before rather than replaces the settings menu, and restores layout on removal. SPA history hooks, popstate, native route sync, and pagehide tear it down. No polling timer or fixed overlay is added.
- System-back implementation and `BackScript.kt` are unchanged: layer dismissal, one history step, then existing exit behavior.

## Runnable regressions and red/green evidence

Node version: `v22.22.2`. No dependencies, downloads, browser, or listener/network setup required.

```text
node --test F:/Codge-android/scripts/test_window_navigation.cjs
```

Before changing production scripts, the first test failed with actual `https://codeg.test/settings/general` rather than the expected workspace URL after clicking from settings/models. History-guarded cases also failed with `Window exits must not read history.length`. This established red against the actual injected scripts, not source-text assertions.

Final run after implementation and self-review:

```text
# tests 20
# suites 0
# pass 20
# fail 0
# cancelled 0
# skipped 0
# todo 0
```

Coverage includes settings subpages; all seven window-route families; button/close/home; empty/single/back/forward histories; missing-bridge fallback; normalized default ports and trailing slashes; repeated injection; late/replaced headers; menu preservation; SPA cleanup/re-entry; restored history functions, listeners and styles; same-origin/top-frame isolation; live route revalidation; ignored caller-supplied destinations; both localized resource values; and touch-target styles.

`node --check F:/Codge-android/scripts/test_window_navigation.cjs` passed. Scoped `git diff --check` with repository line-ending settings passed; Git only warned about future LF-to-CRLF normalization for the two rewritten script files.

## Self-review and integration notes

Reviewed the owned diff for destination validation, stale-document/server/view guards, JS-interface threading, observer/listener lifecycle, menu preservation, localization, and unchanged system-back behavior. No unresolved issue found in this scoped review.

The Node VM executes the JavaScript extracted from the Kotlin raw strings. Its DOM/history and native interface are controlled boundary fixtures: it does **not** execute Kotlin, Android WebView, native workspace retention, or real layout. Coordinator CI must compile the changed Kotlin signature/resource call, then validate actual workspace restoration (including popup opener capture, reload, server/view switches, and stale-token/UI-queue races) and real header layout on Android. No cross-feature integration changes are required by these edits.
