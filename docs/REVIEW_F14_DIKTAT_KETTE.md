# Review F14 — Dictation chain (StopOutcome, watchdog, status routing, diagnostics export)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `dictation/DictationController`, `ime/DictationCoordinator`, `ime/VoiceKeyboardService`, `ime/StatusRouting`, `debug/DebugInfoFormatter`/Collector. No build/emulator.

---

## Findings

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| **P2** | `VoiceKeyboardService.kt:652-655` (`onDictationStopped`) | **`cleanSuccess` reads a live state, not a snapshot.** `lastStopOutcome` is set in the worker **atomically** before `onStopped` (`DictationCoordinator.kt:335`), but `onDictationStopped` additionally reads `c?.failedThisSession` (line 653) and `c?.visibleError` (line 658) — **mutable fields that the next `start()` resets** (`failedThisSession=false` in `DictationController.kt:123`). Sequence "quick release → immediate restart": between A-`controller.stop()` (IDLE, worker) and A-`onStopped` (Main), `start()` B runs and nulls `failedThisSession` → A-`cleanSuccess` is judged with B's state (false checkmark or suppressed error). | Bundle `failedThisSession` **and** `visibleError`/`finalError` together with `lastStopOutcome` in the worker **directly after `controller.stop()`** into a snapshot and pass that to `onStopped`; `onDictationStopped` must not re-read the controller. |
| **P2** | `DictationController.kt:100-101` (`currentHypGapMs`) | **`hyp_gap_ms` can become negative/absurd with an injected clock.** `(clock() − lastHypAt)/1e6` with `clock=System.nanoTime` is monotonic in operation (ok), but a test/injection `clock` running backwards yields negative values in the 🐞 export (rule: diagnostics should be robust). `nanoTime` is also relative, not session-suitable. | Clamp the value (`max(0, …)`) or switch to `SystemClock.elapsedRealtime` as the production default; report negative values in the formatter as `0`/`-`. |
| **open** | `DictationController.kt:157-160, 232-235` (`isEditorSyncTimeout` → `abort(); throw e`) | **Editor sync timeouts rethrow `IllegalStateException`** upward — I could **not prove** that `DictationCoordinator.consume/flush` catches this case (`flush` visibly catches only `OutOfMemoryError`). If uncaught ⇒ coroutine/app crash instead of an R-2 sticky message (that would then be P1). | Add an explicit `catch (e: IllegalStateException)` in the coordinator around `controller.feedAudio/stop` and set `lastError`/`visibleError` (or carry the timeout as a StopOutcome instead of an exception). |

---

## Answers

1. **Genuine error without consequence?** FeedAudio `fail()` → `failedThisSession=true` → `stop()` returns `NoFinal(lastErrorCode)` → `onDictationStopped` shows `mapVisibleError(code)` sticky (lines 655-658). Finale `fail()` → `FinalRan(finalError)` → likewise. Stop-on-IDLE with `failedThisSession` → `NoFinal`. Watchdog → `Error(…too_long)` (line 414). Abort (CANCEL/editor switch) is **not** an error (no sticky needed). The only unproven path: the editor-timeout `throw e` (see "open").

2. **`lastStopOutcome` race:** Within **one** session there is no race (`lastStopOutcome` is set in the same coroutine happens-before as `onStopped`). The race is **across sessions**: `onDictationStopped` reads `failedThisSession`/`visibleError` **live** and not as part of the outcome → quick release+restart falsifies the assessment of the previous session (finding P2).

3. **`failedThisSession` only in the next `start()`:** The *new* success is **not** obscured by that (start() nulls it, line 123) — the problem is the **opposite** direction (see 2): the *old* `onStopped` reads the already-nulled value. The core is the same unsynchronized live read.

4. **`droppedBlocks`/`hyp_count`:** `dropped` counts every `feedAudio` with `state != RECORDING` (line 144) — i.e. also remnants drained after `stop()`/`abort()`; that is **correct** (these are discarded blocks). `hyp_count` also counts the **finale** via `noteHypothesis()` (lines 284/236) and only on a genuine `transcribe` return — correct. `hyp_gap_ms` see finding.

5. **Diagnostics export:** The collector reads the 8 pass-throughs (`ctlState/ctlFailed/dropped/hypCount/hypGapMs/audioSeconds/lastStartMs/starting`, `DictationCoordinator.kt:364-380`); `DebugInfoFormatter` emits all of them as numbers/`0`/`1`/short code (no content/path, rule 9 ✓). Drift check `check(lines.size == KEYS.size + 1)` (line 137, KEYS=24 ⇒ 25 lines) turns a forgotten field or an extra field **red**.

6. **Rule compliance:** `StatusEvent.Error/Starting/Hint` → only mapped resource strings; `strings.xml` unchanged (13 `dictation_*`); no autocorrection/network/content in user-facing texts. No violation.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 2** (live `failedThisSession` read in `onDictationStopped`; `hyp_gap_ms` without clamp with an injected clock) · **open = 1** (catch guarantee for the editor-timeout `throw e` in the coordinator).

**Not provable:** the `IllegalStateException` catch in the coordinator (no complete reading of the `consume/flush` catch chain), genuine race behavior without a device (code inspection only), `hyp_gap_ms` under an injected backwards clock (no test run).
