# Review F13a — Status bar unfreezing (StatusBarText, watchdog, R-2/R-2b)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `ime/StatusBarText.kt` (new), `ime/StatusBarTextTest.kt` (new), `ime/VoiceKeyboardService.kt` (changed), `res/values/strings.xml` (appended). No build/emulator.

---

## Findings

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| **P2** | `VoiceKeyboardService.kt:250, 553` + `StatusBarText.kt:56-59` (`extra != null && !extraTransient → sticky`) | **Over-broad "extra⇒sticky" freezes non-errors.** The pure rule turns **every** `extra` that is not the start text into a sticky error. But two callers pass hints/confirmations: line 250 `emoji_blocked_recording` and line 553 `"Diagnostics copied — share dialog opened"` — neither is an error, yet both now remain as R-2 stickies. The B1 core (start text) is fixed, but the rule misses "hint/confirmation vs. error". | A dedicated transient/hint signal instead of generic `extra`→sticky: only error keys (`dictation_error_*`, `setup_error_*`) become sticky; `emoji_blocked`/`Diagnostics copied` via the transient channel (or `extraIsSticky=false`). |
| **P2** | `VoiceKeyboardService.kt:396, 838` | **`SystemClock.uptimeMillis()` instead of `elapsedRealtime()`.** `uptimeMillis` **pauses during deep sleep** — the 15-s watchdog (`START_DEADLINE_MS`) and the clarity comparison `lastErrorSeenAtMs >= transientSetAtMs` would stretch across a screen-off/display-off. Rare in practice during dictation, but the "deadline" semantics (15 s) is then not strict. `currentTimeMillis` is used nowhere (good, no wall-clock jump). | `SystemClock.elapsedRealtime()` for the deadline and both timestamps (monotonic + spans sleep). |
| **P2** | `StatusBarTextTest.kt` (7 tests) vs. `VoiceKeyboardService.kt` wiring | **Tests prove the pure function, not the wiring.** B1 was a wiring error (old: `if (extra != null) stickyError = extra` + start text as `extra`). The 7 tests only check `StatusBarText.select`; a regression in the **service** wiring (start text again as `extra` instead of as `transientStatus`, line 395) would turn **no** test red. Concretely: the test "extra is only sticky if it is not transient text" would catch `select(extra=start, transientText=start)→extraIsSticky=true`, but not `updateStatus(start)` in the service. | A narrow wiring test (or an assert that `updateStatus` never receives the start text as `extra`): e.g. "onMicDown sets transientStatus, calls updateStatus() without extra". |

---

## Answers to the six questions

1. **Start text sticky/later obscuring?** **No.** Since F13a the start text is set exclusively via `transientStatus = getString(R.string.dictation_starting)` (line 395) and `updateStatus()` is called **without** `extra` (line 397). `StatusBarText.select` never treats `transientText` as sticky (`extraIsSticky=false`), and `stickyError = extra` (line 877) only runs when the pure function reports `extraIsSticky`. B1 is thus closed; only the side effect described above (hints/confirmations unintentionally becoming sticky) remains.

2. **Watchdog cancelled/zombie.** `startDeadlineJob` gets cancelled: in the `finally` of the async start (line 482), before re-start (line 403), and implicitly via `serviceScope.cancel()` in `onDestroy`. **No zombie:** if the deadline expires, the watchdog sets `startAbandoned=true`; the subsequently completed start checks `abandoned` → `coordinator?.abort(); micRecording=false; stopFgs()` (lines 443-454) — a late success is torn down, not silently continued. `abortDictation()`/`stopDictationIfRunning()` need no own `cancel()` because they only run once `startingUp=false` (the `finally` has already cancelled the watchdog by then).

3. **Stale values for the next start?** No: `transientStatus/transientSetAtMs/lastErrorSeenAtMs/startAbandoned/successJustFinished` are reset in `onMicDown` (lines 371-375, 399) and in `finally` (line 485); `successJustFinished` is a one-shot flag (line 875) and is cleared directly after `select`. Clock = `SystemClock.uptimeMillis` (P2 above), **not** `currentTimeMillis` — no wall-clock jump. `lastErrorSeenAtMs` is "time of the first error"; that suffices for the `>=` comparison against the transient start text (the first error is always ≥ the start text).

4. **Orderings `startingUp=false` vs. a running coordinator.** Both (watchdog body and async-start `finally`) hop via `withContext(Main)` and are **atomic** on Main (no suspension in between) → no partial interleaving: (a) watchdog first ⇒ `startingUp=false, abandoned=true`, the later start is aborted in the `abandoned` branch; (b) start first ⇒ `startingUp=false, started=true, micRecording=true, updateStatus()`, the `finally` cancels the watchdog before it fires. **No "mic without feedback" state found.** (Not pinned as a concurrency test — open, see below.)

5. **Would the 7 tests have caught B1?** **Partially — only the pure function, not the wiring.** B1 was a wiring error in the service; the new test "extra is only sticky if it is not transient text" would catch a regression **inside** `StatusBarText.select`, but not a regression of the service wiring (start text again as `extra` in `updateStatus`). See P2 above.

6. **Rules 9/10.** All new/changed status strings (`dictation_starting`, `dictation_start_too_long`, `dictation_no_text`) contain no audio/text content, no path, no URL, no autocorrection; `strings.xml` is only appended (`dictation_start_too_long` new, count 12→13). The diagnostics short form in `updateStatus` remains `FLAG_DEBUGGABLE`-protected and shows only numbers (lines 856-868). No violation.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 3** (hints/confirmations unintentionally sticky; `uptimeMillis` instead of `elapsedRealtime`; pure-function tests without wiring coverage).

**Not provable (open):**
- The watchdog/`finally` race is evidenced only by code inspection, **not** pinned as a concurrency test.
- I read the 7 `StatusBarTextTest`s, did not run them (no build).
- Behavior under genuine deep sleep (screen off) of the `uptimeMillis` deadline is a plausibility statement, not device evidence.
- The device test of the original B1 cause (streaming freeze) was not reproduced here (analysis only).
