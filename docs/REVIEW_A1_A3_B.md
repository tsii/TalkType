# REVIEW — v0.1.33 Sections A1, A3, P2 microfix, B1, B2 (DeepSeek V4 Pro 0813)

**Date:** 2026-11-02 · **Reviewer:** cortecs/deepseek-v4-pro-0813 (sa-f6a102b2, read-only) · **Verdict: OK with notes — P0/P1 = 0 · Push YES**
(Adopted verbatim; slot without write access, file saved by the orchestrator.)

## Correct (core evidence)
- **A1**: `DictationCoordinator.kt:333–336` shared baselines AFTER `src.start()` (success check line 322), BEFORE `captureThread.start()` (344–347); start-duration diagnostics `lastStartMs` unchanged (line 290/488ff); thresholds 3000/3000/2500 unchanged; contracts covered by A1 tests (DictationCoordinatorTest 562–586, 594–617) + F28b1 (420–449, exactly one stall).
- **A3**: `SegmentSessionLog` pure (Service 1836–1853: baseline −1, log only `lastSeg>=0 && current>lastSeg`); thin wiring (lines 601, 1416–1418); segmenter counters unchanged.
- **P2 microfix**: `DictationController.kt:613–620` — `ownershipDeniedCount/AtS` now INSIDE `if (!rebased || !tracker.isOwned(...))`; a successful re-baseline does not count. AP3-P2 test (422–451) proves counter 0→1 + seconds value.
- **B1**: `row0MenuBelowKey` (LongPressMenu 111–117) = `keyTop − gap − menuH < 0`, pad/cell/spaceAbove without decision power; menuTopPx branch 90–95, middle/Footer bit-identical; tests 682/265/115/232 + real geometry 171 instead of −168.
- **B2**: `BackspaceRepeat.kt` pure logic (450/70, down() one action, tick does not catch up, cancel idempotent); KeyboardView DOWN 844–851, UP/CANCEL 995–999 (removeCallbacks, no double delete, tap exclusion lines 1030–1032); repeat → `onAction(BACKSPACE)` (line 822) = service branch `backspaceDeleteOnce` (Service 1752–1765: selection FIRST ⇒ `commitText("",1)`, otherwise grapheme/DEL fallback); `onFinishInputView` line 695 + `onDetachedFromWindow` 688–691 cancel.
- **Release gate**: `build.gradle.kts:14–15` versionCode 34 / 0.1.33 ✓.

## Findings
**P0: none. P1: none.**
**P2 (report, not blocking):**
1. Stale comment `LongPressMenu.kt:94` ("may be negative") — since B1 never negative there; correct the comment.
2. Theoretical arming special case `KeyboardView.kt:941` (`k.top <= 0f` guard): row 0 with keyTop∈1..167 px — unreachable in real geometry (keyTop=0); optional comment.
3. Redundant early baseline setting `DictationCoordinator.kt:326–340` before `engine.load()` — overwritten on the success path, error path without watchdog; dead-redundant, harmless.

## Acceptance PLAN vs. code
| Section | Acceptance | Evidence | Status |
|---|---|---|---|
| A1 | Baselines after start()/before thread | DC 333–336 | ✓ |
| A1 | Start diagnostics + thresholds unchanged | line 290/488ff; 3000/3000/2500 | ✓ |
| A1 | 3-s-stall exactly once | F28b1 + A1 test 2 | ✓ |
| A3 | baseline −1 / only genuine increase | Service 1836–1853, 601, 1416 | ✓ |
| A3 | no segmenter interference | segmentFinalizations coordinator-internal | ✓ |
| B1 | above>=0 rule, pad/cell/spaceAbove without power | LongPressMenu 117 | ✓ |
| B1 | Geometry+arming one source; 682/265/115/232 | 90–97, arming 941, tests | ✓ (P2-#2) |
| B2 | 450/70, DOWN one action, no double delete | BackspaceRepeat; KV 844–851, 995–999 | ✓ |
| B2 | Repeat=service branch; selection first, 1× commitText | KV 822; Service 1752–1765 | ✓ |
| B2 | no timer after editor switch | 695, 688–691 | ✓ |
| P2 | Counter only on genuine denial | DC 613–620 | ✓ |

## Migration audit (3 existing-test changes) — all correct
1. `LongPressMenuTest` F54 a) removed → replaced by B1 test 1 (`assertNotEquals(-168)` + `top+147<=318`) + edge cases 168/167; F54 b)/F38 e) consistent.
2. `foreignSelectionBlocksFurtherWrites` (DCT 224–249): cursor grab ⇒ re-baseline+write; genuine foreign content ⇒ W1 without sink, `lost` remains — A2b contract carried correctly.
3. `AP3-P2` (DCT 422–451): rewritten to genuine foreign content (suffix gone ⇒ re-baseline fails ⇒ 1 hit + 4.0 s) — consistent with the P2 microfix.

## Concurrency/timer
`removeCallbacks` with identical Runnable object (835/998); Runnable rescheduled via `scheduleBackspaceRepeat`, with `armed=false` immediate return. No leak path.

**Verdict: P0/P1 = 0, Push YES.** (Reviewer could not cross-check test counts — Gradle mutex; orchestrator XML-verified 573/0.)
