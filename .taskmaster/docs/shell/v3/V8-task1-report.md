# V8 Task 1 — notification / clipboard correctness

Status: **DONE_WITH_CONCERNS**. Implementation and lightweight script verification are complete. Android compilation, lint and runtime verification remain for coordinator CI/device work; local Gradle, builds, devices and network access were prohibited.

## Edited paths

- `F:\Codge-android\app\src\main\java\app\codeg\android\shell\notify\NotifyFeature.kt`
- `F:\Codge-android\app\src\main\java\app\codeg\android\shell\notify\NotificationScript.kt`
- `F:\Codge-android\app\src\main\java\app\codeg\android\shell\web\ClipboardScript.kt`
- `F:\Codge-android\app\src\main\java\app\codeg\android\shell\web\WebSettingsFeature.kt`
- `F:\Codge-android\scripts\test_notify_clipboard.cjs` (new)
- `F:\Codge-android\app\src\test\java\app\codeg\android\shell\notify\NotificationOriginTest.kt` (new; not executed locally)
- `F:\Codge-android\.taskmaster\docs\shell\v3\V8-task1-report.md` (this report)

No host, server, configuration or unrelated files were edited. No staging, commits, pushes, Gradle, dependency installation, device operations or network access.

## Implementation

### Origins and bridge threading

Notification origins use OkHttp canonical hosts, omit HTTP :80 / HTTPS :443, and bracket IPv6. The existing clipboard origin helper remains private and unchanged; no web-to-notify coupling was introduced.

Notification synchronous bridge reads consult one immutable volatile snapshot containing the document token, origin, view identity, permission and lifecycle state. They do not call WebView methods, read host properties or wait for UI. Requests, posts and cancellations enqueue bounded asynchronous UI work (32 queued items, 10-second validity); execution rechecks the current token, view, selected server and actual WebView origin.

### Permission correctness

The native permission calculation checks runtime POST_NOTIFICATIONS permission, global areNotificationsEnabled(), channel IMPORTANCE_NONE and blocked channel groups. The cached answer refreshes on document injection, resume, explicit request, delivery and permission result. Only explicit requests from resumed, uncovered web content may show the system permission dialog. Posts never request permission or retain undecided payloads.

JS permission getters read the safe native snapshot. Permission promises settle through token-checked native callbacks, including background suppression and launch failure; a two-minute non-polling safety timer resolves with the then-current permission, never a fabricated denial. At most 32 waiters and one timer are retained; concurrent callers share one bridge request. Resume and permission-result updates target only an acknowledged page-finished installation.

### Notification taps and persistence

New notification PendingIntents carry the canonical server origin and the same-origin current page URL captured by the injected post call. Native parsing rejects non-HTTP(S), credential-bearing and cross-origin targets. Distinct Intent data and notification tags avoid hash collisions overwriting notification targets.

Warm taps attempt a live onclick only in a current, visible, page-finished-acknowledged document. The actual click script rechecks token, top frame and origin; native revalidates the document again in its result callback. Unknown ids, absent handlers, exceptions, expired instances, process death, covered/loading documents and missing WebViews fall back through `ShellHost.openServerTarget(serverOrigin, targetUrl)`—never raw WebView navigation. A missing JS callback falls back after two seconds. Only one native pending tap is retained; it is consumed once, and stale work expires after a minute.

JS notification instances are limited to 32 with a 30-minute lifetime and one cleanup timer. The fallback restores the captured page and its existing web persistence, not an invented session id. Legacy id-only notifications cannot recover a page URL they never stored; they are cancelled without inventing a target.

### Clipboard and coordinator visibility steering

Clipboard injection explicitly rejects child frames. Native read fallback requires active `navigator.userActivation` at the time of fallback. Browser NotAllowedError and SecurityError are propagated for reads and writes instead of being converted into native success. Working browser APIs and normal Android paste handling remain unchanged; non-secure HTTP text read/write fallback remains available for actual user-triggered calls.

`ClipboardBridge.access` retains the document secret/origin/current-view checks and now also requires `host.isWebContentVisible`, `view.isShown` and window focus on UI. `WebSettingsFeature.onResume` keeps the document hidden while the native manager covers it; injection also applies hidden state when covered or not resumed. Existing pause and visibility script wiring is otherwise preserved.

## Verification and self-review

Command (actual execution, Node v22.22.2):

```text
node --test F:/Codge-android/scripts/test_notify_clipboard.cjs
```

Result: **23 tests, 23 passed, 0 failed, 0 skipped**. Red-first runs demonstrated the original behavior gaps; harness mistakes found during review were corrected before relying on green results. The expanded suite was also run red before the final callback/bounds implementation.

The harness extracts and executes the actual Kotlin raw-string installation, tap and permission scripts in Node VMs. It exercises normalized default-port/IPv6 origins, frame and origin isolation, token-checked reinjection/callbacks, captured SPA page URLs, permission refresh/result/refusal/timeout, bounded concurrent requests, instance expiry/eviction, process-restart and unknown-id tap responses, browser clipboard denials, missing/inactive activation, simulated user-click HTTP fallback, browser passthrough and reinjection idempotence. No source-text-only behavior assertions, external traffic or real clipboard access.

Scoped `git diff --check` passed (only Git LF/CRLF conversion warnings). Self-review checked bridge thread access, token revalidation, channel state, cold-start restoration, tap timeout ownership, callback acknowledgement and coordinator visibility guards.

## Integration notes and remaining verification

- Coordinator APIs used: `ShellHost.openServerTarget(String, String)` and `ShellHost.isWebContentVisible`. Both were reported implemented by the coordinator; host files were not modified.
- Node tests prove actual JS behavior and false/true tap responses, not Android PendingIntent persistence, UI-thread scheduling, notification settings, host restoration or visibility properties. User activation is simulated, not generated by Chromium.
- `NotificationOriginTest` exercises the actual Kotlin serializer for explicit default ports, non-default ports, IPv6, IDN and invalid schemes; it is added for CI but **not run locally**. The Node origin cases alone do not verify Kotlin serialization.
- Coordinator should run Android compilation/lint and JVM tests, then verify permission/channel changes, covered-server clipboard refusal, warm clicks and cold-start/cross-server restoration. No claim of Android runtime verification is made here.

## Final integration follow-up — renderer loss and destruction

Changed only `F:\Codge-android\app\src\main\java\app\codeg\android\shell\notify\NotifyFeature.kt` and this report for the coordinator's follow-up.

- Added `onRenderProcessGone`: if the lost renderer belongs to `snapshot.view`, immediately replace the snapshot, clearing token, origin, view and ready while preserving permission and resumed state. An old view's callback cannot invalidate a newer view's credentials. Return `false` so the host/error feature still owns recovery; no WebView destruction or host changes.
- Registered a lifecycle observer in `onCreate`: `ON_DESTROY` calls `clearPendingTap()` to remove the delayed tap callback and clear its identity, then invalidates document authorization. Late tap callbacks cannot restore a target after destruction. Queued-work accounting is unchanged; already-enqueued work drains normally and fails authorization.
- Re-ran `node --test F:/Codge-android/scripts/test_notify_clipboard.cjs`: **23 passed, 0 failed, 0 skipped**. Scoped `git diff --check` passed (LF/CRLF warning only).
- Self-review checked lost-view identity, immediate token invalidation, preservation of permission/resumed on renderer loss, non-ownership of recovery, destroy-time timeout cancellation and queued-work accounting. The added native lifecycle paths are **not executed by the Node tests**; Android compilation/runtime verification remains deferred under the task constraints.
