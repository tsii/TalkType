# Review v0.1.34 — A5 (session reset) + W package (warming)

Reviewer: DeepSeek V4 Pro 0813 (`cortecs/deepseek-v4-pro-0813`), 2026-11-02 21:10.
Basis: vc35 working state (Debug 579/0), debugger root-cause report (ownSpan corpse,
toggle evidence logs `/tmp/phone_toggle_{sess,asr}.log`).

## Verdict (review run 1)

**P0 = 1, P1 = 0, P2 = 2.** Push: NO until the P0 fix.

### P0 — A5 has no effect in production: `CountingSink` does not delegate `resetSession()`
- `VoiceKeyboardService.kt:1008` builds `CountingSink(InputConnectionSink(...))`;
  `DictationController.start():373` calls `sink.resetSession()` → wrapper;
  `CountingSink` (`:1081–1096`) did not override `resetSession` → interface default
  (`EditorSink.kt:61`) = no-op → ownSpan corpse still active in production.
- `InputConnectionSinkSessionTest` injected the inner sink directly → false green.
- **FIX (orchestrator, 21:11):** `CountingSink.override fun resetSession() = inner.resetSession()`
  + cross-check `produktions-wrap … delegiert Sitzungsreset` (new test, wrapper path).

## P2 (non-blocking)
1. A5(b) relies on an incidental `Graphemes.isBoundary` `require` instead of an explicit
   `deleteLength ≤ ownSpan` guard. Recommendation for a later round.
2. `coordinator` field (`VoiceKeyboardService.kt:175`) now has two writers (Main via
   preload `:555`, worker `:754`), not `@Volatile`; timing + single-flight mitigate,
   the pattern already existed. Optional `@Volatile`.

## Verified as correct
- A5 location/reset (`start():373` directly after `tracker.reset`), `InputConnectionSink.kt:126–128`
  synchronous @Volatile, `EditorSink.kt:61` default no-op (fakes unchanged).
- Race: `start()` only from IDLE; sink writes synchronous via `onEditExecutor`;
  A1 timeout sets `abandoned` before throwing → no apply-in-flight window.
- W1: `loadEngineOnce` synchronized + double-check (`DictationCoordinator.kt:417–431`);
  error path `engineLoaded=false`, counters/trigger only on success; `preload()` swallows;
  `start():309` `loadEngineOnce("mic")` (lazy fallback genuine).
- Service: `preloadDue` pure (`:1973`), `preloadTried` 1×/lifetime, preload in
  `serviceScope.launch` (never Main).
- W3: 5 KEYS at the back (`:288–292`), 68+header=69 (`check :365`), 0/1 encoding, sanitizer,
  collector null-safe (`DebugInfoCollector.kt:177–181`). Rule 9: `load_trigger`
  closed {mic, ime_show, "-"}, no paths.
- W2: `shutdown()` is the only `close()` path (`:1091–1099`, CAS), no idle unload.
- `EnginePreloadTest` (3 tests) genuine: `CountingEngine` throws on second load; warm(<500)/
  cold(≥500) real measurement; preload failure ⇒ lazy 2nd load (`loadCalls=2`).

## Status after fix (orchestrator verification, 21:14)
- **P0 fix in place:** `CountingSink.resetSession()` delegates to `inner`
  (`VoiceKeyboardService.kt` :1094ff, KDoc with review reference) + **cross-check**
  `produktions-wrap … delegiert Sitzungsreset` in `InputConnectionSinkSessionTest`
  (wrapper path like production: reset survival, DELTA in session 2, counters ok).
- Release authenticity restored: compilation damage (F56 stub in `src/debug`,
  test in `src/test`) fixed by moving the test to `testDebug` —
  **Release 544/0 fresh** (had been stale-old-482 for days due to an up-to-date illusion; XML mtime
  now 21:13, 67 files). Release APK 21:13 BUILDS WITH the P0 FIX.
- Debug round 2 (full test incl. wrapper test + assembleDebug with fix) running
  (`/tmp/b35_dbg2.log`, end marker D2_DONE; expected 580/0).
- Review run 2 (P0 re-assessment + P2 decisions): steer on sa-b7b53, result
  to be appended below.

## Review run 2 (follow-up assessment, 21:17 — DeepSeek, sa-b7b53)
**P0 fixed, correctly and completely.** Delegation chain `Controller.start():373 →
CountingSink.resetSession() → InputConnectionSink.resetSession()` closed;
cross-check `CountingWrapper` + wrapper two-session test confirms ownLength 5→0,
writeReason=delta, commits=2. Reviewer's note (accepted): the test replicates the
wrapper structure (private inner class), production code read and verified directly.

### Final verdict: **P0 = 0, P1 = 0, P2 = 2 (follow-ups 0.1.35) — Push: YES.**
- P2-1: explicit length guard in `replaceBeforeCursorSync` (instead of the incidental
  Graphemes `require`) — candidate for 0.1.35.
- P2-2: `coordinator` field `@Volatile` / build in `serviceScope` — follow-up.

## Delivery 0.1.34 (21:20)
Debug **580/0** + Release **544/0 fresh** = **1124/0**. dist: voice-keyboard-0.1.34.apk
`92ca385f…`, -diag-debug.apk `d948dbc2…`; 0.1.33 rotated. Phone evidence pending
(hold→toggle scene, warm start; criteria requested from the debugger).

## Device assessment criteria 0.1.34 (debugger conv-6cf9903c, 21:2x — phone evidence chain)
- **PASS ownership:** H→T in the same field; from `audio_s=2` `write=delta/same`, no `w1`
  without proven foreign grab; `current_len/output_chars` grow; toggle ends normally.
- **PASS warm:** keyboard visible beforehand ⇒ `warm_hit=1`, `load_trigger=ime_show`,
  `engine_loads=1` across both sessions, `start_ms<250`, PID unchanged; model PSS resident.
- **Fallback valid (not a bug):** new PID ⇒ process loss; `warm_hit=0`, `load_trigger=mic`,
  renewed load = correct lazy fallback.
- **Genuine error:** same PID + provably prior IME show, but `warm_hit=0`
  (check preload ordering/`preloadTried`/finished); any unexplained W1 corpse pattern.
- **Borderline candidate, not a fail:** W3d with DELTA recovery, pacing skips, minor
  RSS fluctuations, `start_ms≥250` without prior keyboard show.
