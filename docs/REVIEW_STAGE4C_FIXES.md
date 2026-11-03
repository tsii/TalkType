# Review Stage 4c — FIX pass (C-1…C-5)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Subject:** fixes from `docs/REVIEW_STAGE4C.md` in `DictationCoordinator.kt`, `VoiceKeyboardService.kt`,
`DictationCoordinatorTest.kt`. **Gradle lock held → no build** (not even a filtered run). Pure reading.

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| N-1 | **P1** | `DictationCoordinator.kt:235-244` (+ `:205-214` partials) | **`InputConnection` is used from the background thread — not thread-safe.** `stop()` moves the finale into `scope.launch` (scope=`Dispatchers.Default`); there `controller.stop()` → `writeHypothesis` → `DeltaTyper.revise` → `InputConnectionSink.replaceBeforeCursor` → `ic.deleteSurroundingText`/`ic.commitText` run. These editor accesses thus run on a non-UI thread (InputConnection is bound to the IME main thread). The same path already applied to partials (the `consume` coroutine). DESIGN §2.3-1: "all InputConnection operations" on main/UI. | `DictationCoordinator.kt:238-243` `scope.launch { consumerJob?.join(); controller.stop(); … }`; `controller.stop()` calls `writeHypothesis()` → `typer.revise()` → `sink.replaceBeforeCursor()`. | Keep the decode (production of the `Hypothesis`) on the worker, but apply the **commit/delta** (InputConnection) via `withContext(Dispatchers.Main)` or a main handler — the worker only delivers the result, the editor write happens on the UI thread. |
| N-2 | **P1** | `DictationCoordinator.kt:243` → `VoiceKeyboardService.kt:210` | **The `onStopped` callback runs on `Dispatchers.Default` and mutates views → `CalledFromWrongThreadException`.** `onStopped = { updateStatus() }` is invoked at the end of the worker coroutine; `updateStatus()` sets `view.statusText.text`/`micButton.isEnabled/isActivated`. View mutation from a non-UI thread ⇒ crash (IME). | `DictationCoordinator.kt:243` `onStopped?.invoke()` (in the `scope.launch` context); `VoiceKeyboardService.kt:210` `onStopped = { updateStatus() }`; `updateStatus` (line 280 ff.) writes directly into views. | Invoke `onStopped` via a main dispatcher (the coordinator gets a main-dispatch handle, or the callback posts into `runOnUiThread`/`handler.post`). |
| C-3 | **P1** | `SetupActivity.kt:10-31` (placeholder) + `VoiceKeyboardService.kt:197-208` | **C-3 is not closed — `SetupActivity` is still a stub.** The IME-side reader (`getSharedPreferences("vk_permissions")` → `denied_forever_*`) is correct, but **nobody writes the marker**: `SetupActivity` handles neither the `REQUEST_PERMISSION` extra nor does it call `requestPermissions` (no `onRequestPermissionsResult`), nor does it set `denied_forever_*`. Consequence: `getBoolean(..., false)` always stays `false` ⇒ `permanentlyDenied` empty ⇒ the `Ask` path keeps opening a placeholder ("Setup to follow", translated from German), the system dialog **never** appears, the settings path is unreachable. The coder's note ("SetupActivity must set the marker") explicitly confirms the missing write path. | `SetupActivity.kt` (1025 B, only two TextViews, no `requestPermissions`/`onRequestPermissionsResult`/`putBoolean`); `VoiceKeyboardService.kt:201-208` reads `prefs.getBoolean(PERMISSION_DENIED_FOREVER_PREFIX + it, false)`. | Implement `SetupActivity` (or the dialog-carrying activity): read the `REQUEST_PERMISSION` extra → `requestPermissions`; in `onRequestPermissionsResult`, on a second denial write `denied_forever_<perm>=true`. Only then is C-3 substantively done. |
| N-3 | P2 | `VoiceKeyboardService.kt:106-114` (`onDestroy`) | **Use-after-close race in `onDestroy`.** `stopDictationIfRunning()` calls `c.stop()` (launches the final worker, returns immediately); directly afterwards `coordinator?.shutdown()` → `abort()` + `engine.close()` + `serviceScope.cancel()`. `engine.close()` thus runs **unsynchronized** against a possibly already-started `controller.stop()`→`engine.transcribe`. Usually `controller.abort()` (state→IDLE) saves the worker, but the ordering is not guaranteed; moreover, the just-started final worker is killed immediately by `serviceScope.cancel()` — a final decode started for nothing. | `VoiceKeyboardService.kt:106` `stopDictationIfRunning()`, `:110` `coordinator?.shutdown()`, `:113` `serviceScope.cancel()`; `DictationCoordinator.shutdown()` → `abort()` + `engine.close()`. | In `onDestroy`, do **not** first `stop()` (final worker) and then `shutdown()`: either `abort()`/`shutdown()` directly (no finale at service end) or `shutdown()` after a `join` of the worker (close the engine only after the decode has finished). |
| N-4 | P2 | `VoiceKeyboardService.kt:278-289` (`updateStatus`) | **C-2 visibility only half fixed.** The new `visibleError` getter (`lastError ?: controller.lastErrorCode`, `DictationCoordinator.kt:90-92`) is **not** used — `updateStatus` still reads `c?.lastError` (only audio/start errors). A mid-run ASR error (`controller.lastErrorCode = asr_partial_error`/`asr_final_error`) remains invisible → the status shows "Bereit". The C-2b half (stop the capture when `state==IDLE` via `flush`) is correctly fixed. | `DictationCoordinator.kt:90-92` `val visibleError get() = lastError ?: controller.lastErrorCode`; `VoiceKeyboardService.kt:281` `c?.lastError != null` (instead of `c?.visibleError`). | Switch `updateStatus` to `c?.visibleError`; test "engine throws ⇒ status/error visible". |
| — | ✓ | `DictationCoordinator.kt:130-152` (`start`) | C-5 fixed: `engine.load`/`audioSourceFactory` in try/catch, throw → `lastError` + `active=false` + `return false` (no FGS leak, no touch-listener crash). `@Volatile engineLoaded` + `active.compareAndSet` prevent a double load (double tap runs into the `active` gate). C-1 (`runBlocking`) fully removed. | Lines 132-145 try/catch; line 66 `@Volatile engineLoaded`; line 93 `active.compareAndSet(false,true)`. | — |

---

## Point-by-point answers

1. **Worker finale + InputConnection:** **P1 (N-1)** — `controller.stop()` → the commit runs on `Dispatchers.Default`; InputConnection is not thread-safe and belongs on the main thread. Correct: the worker produces the finished `Hypothesis`, then apply the commit/delta via `withContext(Dispatchers.Main)`/handler on the UI thread.
2. **`onStopped`:** runs on `Dispatchers.Default` → `updateStatus()` (view mutation) ⇒ `CalledFromWrongThreadException` (**N-2**, P1). Race against `onWindowHidden`/`onDestroy`: the callback reads `rootView`/`coordinator`, which may already be `null` — secondary, the crash dominates.
3. **`shutdown()` in `onDestroy`:** yes, a race is possible — `engine.close()` vs. a running `controller.stop()`→`transcribe` (**N-3**, P2).
4. **`engineLoaded` visibility:** `@Volatile` + `active` atomic gate ⇒ no double load, thread-safe.
5. **try/catch in `onMicDown`:** reports visibly (`updateStatus("Start fehlgeschlagen …")`), does not swallow silently — C-5 correct; **no** C-2 pattern regression. But N-4 remains (ASR mid-run error still without `visibleError` display).
6. **Tests:** 6 `DictationCoordinatorTest` tests, **0 new** for the 5 fixes. A revert would **not** turn red for: C-1 (worker finale), C-4 (`shutdown`/`engine.close`), C-2 visibility (`visibleError`), C-2b (IDLE stop), C-5 (try/catch). **The 3 most important missing tests:** (a) `stop()` returns immediately + `onStopped` fires exactly 1× after IDLE (C-1); (b) `shutdown()` closes the engine and no `transcribe` runs afterwards (C-4/N-3); (c) engine throws ⇒ `visibleError` ≠ null (N-4/C-2).

---

## Answer to R-2 (error message disappears on release)

**Severity: P2** (UX, no data/privacy risk). The coder shows "Model not installed – open Setup" (translated from German) via `updateStatus(extra)` on mic **down**; on mic **up** `stopDictationIfRunning()` → `updateStatus()` runs (without `extra`, `lastError==null`, state `IDLE`) → "Bereit", exactly in the moment the user wants to read the message.

**Decision proposal (minimal rule):**

> **A blocking/"last" message stays put** ("Model not installed", "Permission needed", "Start failed" — translated from German) **until the next deliberate action** — i.e. until the next mic **down** or until a success overwrites it. The mic **up** (a mere release) may **not** reset the status to "Ready" if an error message has just been displayed.

- **Special case "Bereit after a successful finale":** on success the opposite applies — "Verarbeite…" (FINALIZING) may drop to "Bereit" after the finale completes. Distinction: `lastError`/error code set ⇒ sticky; `lastError == null` ⇒ transient ("Verarbeite…" → "Bereit").
- **Minimal implementation:** `updateStatus()` clears a set error text only if (a) a new mic down comes (reset the error) or (b) an error code is active and only a success replaces it — never solely through `onMicUp`. Persist via `coordinator.visibleError != null` instead of an `extra`-only flag.

---

## Verdict

**Release only with fixes** — 3 P1 (N-1 InputConnection on the background thread, N-2 `onStopped`→view crash, C-3 still open because `SetupActivity` lacks the write path), 2 P2 (N-3 use-after-close race, N-4 visible ASR error). C-1 (`runBlocking` gone), C-5 (FGS leak fixed) and the C-2b part are cleanly solved.

## Residual risks

1. Editor crash/corruption from InputConnection use off the main thread (N-1/N-2) — occurs immediately on the first successful dictation.
2. C-3 remains functionally open as long as `SetupActivity` delivers neither a dialog nor the `denied_forever` marker — a fresh installation can never grant `RECORD_AUDIO` through this flow.
3. The `onDestroy` race (N-3) will rarely appear in the field, but as an OrtException/aborted finale.

## Top-5 actions

1. **N-1** — Move the commit/delta (InputConnection) to the main thread; the worker only delivers the `Hypothesis`.
2. **N-2** — Dispatch `onStopped`/`updateStatus` via a main handler (no view access from the Default dispatcher).
3. **C-3** — Complete `SetupActivity`: `requestPermissions` + `onRequestPermissionsResult` + write the `denied_forever_*` marker.
4. **N-3/N-4** — Disentangle the `onDestroy` ordering (no `stop()` before `shutdown()`); switch `updateStatus` to `c.visibleError`.
5. Add three tests: `stop` non-blocking + `onStopped` 1×, `shutdown` without use-after-close, engine error ⇒ `visibleError` visible.
