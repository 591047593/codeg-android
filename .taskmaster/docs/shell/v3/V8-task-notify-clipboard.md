# Task 1 — notification / clipboard correctness

Repository F:\Codge-android, baseline 0089bff, branch feat/update-route-selection. User approved repairing reviewed correctness gaps, integration, release. Work in this checkout, but NO commits/staging, pushes, branch changes, Gradle/APK builds, dependency additions, device/ADB operations or subdelegation. Other files are owned by the coordinator.

## Owned files ONLY

- app/src/main/java/app/codeg/android/shell/notify/NotifyFeature.kt
- app/src/main/java/app/codeg/android/shell/notify/NotificationScript.kt
- app/src/main/java/app/codeg/android/shell/web/ClipboardScript.kt
- app/src/main/java/app/codeg/android/shell/web/WebSettingsFeature.kt (only clipboard capability guards if necessary; preserve visibility wiring)
- New scripts/test_notify_clipboard.cjs and narrowly needed app/src/test/.../notify tests.

## Requirements

1. Browser-compatible origins (omit :80/:443, handle IPv6). No strict mismatch between Kotlin serialized origins and JS location.origin.
2. JS interfaces run off UI thread: never read view.url or other WebView methods there. Cache safe synchronous permission/auth snapshots; dispatch requests/posts/cancels to UI and revalidate token/current view/current origin there. Do not synchronously wait for UI from Notification permission reads.
3. Notification.permission reflects current Android permission AND channel state; default/denied/granted and request settlement do not hang or request on background event delivery. Polling may be replaced by callback settlement. Refresh on resume and permission result.
4. Tap must not depend on a prior document's instances after process death. Notification PendingIntent carries a persisted server origin plus a same-origin current page URL; capture that URL in the injected post call. Warm taps may call live onclick, but unknown/old ids fall back to saved navigation. Bound/expire retained JS instances and native pending work. No promise of an exact session ID if upstream only supplies title/body: restore the captured page and existing web persistence.
5. Coordinator adds ShellHost.openServerTarget(serverOrigin: String, targetUrl: String): Boolean. You may call it on UI thread; it validates an already-saved server and target, switches profile and restores page after WebView creation. Do NOT edit ShellContract/ShellActivityHost/ServerFeature yourself.
6. Clipboard native read fallback requires active user activation in the top selected-server document. Do not turn browser NotAllowedError/permission denial into native success. Non-secure HTTP text copy/paste remains usable for actual clicks; normal native paste unchanged. Preserve existing top-document secret and origin checks; add an explicit top-frame check in clipboardScript if missing.
7. Write behavior tests against actual injected scripts (Node VM for API behavior is fine, no source-text-only assertions), run red first where feasible, then green. No external traffic or real clipboard. Include default origins, frame isolation, denied browser read, no activation, actual user-triggered fallback, permission updates, and old notification tap fallback semantics.

Read the current files; choose a minimal, maintainable implementation, no framework rewrites or unrelated features. Return DONE/DONE_WITH_CONCERNS/BLOCKED, edited paths, exact test command/result, any integration notes in <=250 words. Write longer notes if needed to .taskmaster/docs/shell/v3/V8-task1-report.md. Keep tool use focused (<25 calls ideally). Do not invoke unrelated skills: you are dispatched with this explicit task.
