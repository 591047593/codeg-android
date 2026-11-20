# Task 2 — one-click exit from window routes

Work in F:\Codge-android current feat/update-route-selection checkout. User approved fixes and release. NO staging/commit/push/branch switch/Gradle/APK/device/network/subagent operations. You own ONLY shell/nav/NavFeature.kt, BackButtonScript.kt, WindowBridgeScript.kt, existing strings_shell_nav.xml in both locales, and new scripts/test_window_navigation.cjs. Do not touch host/other feature files. Coordinator integrates and runs CI.

Requirements:
- Top-bar back and window.close leave settings/commit/etc in ONE step to the remembered same-origin /workspace URL that opened them. Remember entry on URL history callbacks and before redirecting same-origin popup into main WebView. While browsing settings subpages retain the original entry; do not overwrite with another settings page.
- System back remains existing dismiss-layer / one history step / exit behavior. Injected button must not use history.length to guess whether it can leave. JS wrapper calls native home(secret) (or safe same-origin workspace fallback) every time.
- Bridge home reads no WebView properties on JS interface thread. Post to UI, then recheck secret, view identity, current selected origin and current page; only allow exit on known window-style routes. Never navigate arbitrary external URLs supplied by JS.
- Origin comparison in scripts uses new URL(quotedServer).origin so default ports/trailing slash cannot break it.
- Button idempotent, handles SPA history changes and late-mounted/replaced titlebar. Use a bounded lifecycle observer or per-document MutationObserver with explicit teardown when leaving window routes; do not inject a global polling timer. Preserve settings menu; 48px-ish practical touch target where possible without hiding header controls. Label via bilingual string resource passed as quoted JS value; no hardcoded English 'Back'.
- No need for true multi-WebView windows in this task.
- Tests: execute actual injected script in Node VM or local Chromium, not source-text assertions. Cover several settings subpages exiting to workspace, empty/back/forward history irrelevance, repeated installation, late header, leave-route cleanup, same-origin/top-frame isolation. At least record red before green for the behavior you change. Node22 available; no dependencies. Existing scripts/test_mobile_token_rate.cjs illustrates extracting Kotlin strings and Chromium but avoid copying a giant harness unnecessarily.

Report .taskmaster/docs/shell/v3/V8-task2-report.md with tests and integration notes. Final <=250 words. Stay within scoped files, finish with self-review.
