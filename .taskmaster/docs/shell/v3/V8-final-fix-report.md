# V8 final review fix

## Scoped implementation

- `app/src/main/java/app/codeg/android/shell/error/PageRetryGate.kt`: fragment-stripped active URL and monotonically increasing generation; recovery reservation invalidates prior completion reads while preserving one-load retry gating.
- `app/src/main/java/app/codeg/android/shell/error/ErrorFeature.kt`: checks callback ownership before timeout/error/gate mutation; timeout captures generation. Finish additionally checks current URL/progress and asynchronously confirms document.readyState/location.href, then rechecks view/generation. Internal error documents cannot acquire navigation ownership; real errors do not require readiness.
- `app/src/main/java/app/codeg/android/shell/server/ServerFeature.kt`: initialization await joins actual startup and explicitly returns failure without clearing profiles.
- `app/src/main/java/app/codeg/android/shell/ShellActivityHost.kt`: awaits initialization before saved-profile lookup/selection; serializes selection side effects and checks request ownership after suspensions. Stages destination before selection publishes state; accepting a newer target immediately invalidates the old staged destination before its factory can consume it, and failure/supersession rolls back only its own unconsumed destination. Tracks WebView server ownership to avoid loading the outgoing view. Manager close uses Activity RESUMED state for visibility/focus; resume restores retained-view visibility. Operations remain on lifecycleScope/Main; no blind startup delays.
- `app/src/main/java/app/codeg/android/shell/nav/NavFeature.kt`: current renderer loss immediately clears documentToken/documentServer/backPending before ErrorFeature recovery. System-back/workspace destination logic unchanged.
- `app/src/test/java/app/codeg/android/shell/ShellReliabilityTest.kt`: three behavioral gate regressions, without source-text assertions.

## Verification

- Ran `node --test F:/Codge-android/scripts/test_window_navigation.cjs`: 20 passed, 0 failed.
- Ran `git diff --check`: exit 0; only Git LF/CRLF conversion notices.
- JVM regressions added, not executed (local Gradle prohibited): A-start/B-start/stale A finish and errors leave B loading and retry-locked until B completes; fragments preserve ownership while different queries do not; identical-URL retries invalidate old generations, including the pre-start reservation interval, and incomplete/mismatched documents cannot complete.

## Android regression scenarios recorded, not executed

1. Delay startup datastore reads; deliver two target requests. After initialization, only newest request restores its saved-profile same-origin target. Fail startup reads: preserve profiles/current page. Retry startup and redeliver target. During selection, permit recomposition before select returns: staged target reaches new view, never outgoing server view.
2. Open/cancel native manager while resumed: retain WebView/document/session. Close while backgrounded: no visible-document signal or focus request; resume reveals retained document unless manager remains open.
3. Lose current renderer while bridge/back callback is pending: tokens and backPending clear immediately; old token cannot request workspace navigation. Loss of an obsolete view must not clear current-view bridge state.
4. Deliver stale same-URL finish while current document is loading: progress/readiness checks reject completion; deliver a new start while JavaScript readiness read is pending: generation check rejects its result. Genuine main-frame network/HTTP errors remain reportable.

## Limits

WebView provides no unique native callback ID for identical-URL retries. URL, generation, progress and current-document readiness reduce stale completion exposure but cannot distinguish every identical-URL callback; identical-URL errors intentionally remain reportable. JVM execution/Android lifecycle and renderer exercises remain for coordinator CI/device verification. No Gradle, build, ADB, network, staging, commit, push, branch changes or subagents were used. Changes are confined to the six owned source/test files and this requested report; the supplied requirements file was preserved.
