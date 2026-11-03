# Review v0.1.35 (vc36) — T1/T2/T3/T4
Reviewer: DeepSeek V4 Pro 0813 (sa-ba715e7e), 2026-11-02 22:2x. Full text in the session transcript.

## Merge verdict
| Cut | Findings | Verdict |
|---|---|---|
| T1 overlay two-zone | — | OK |
| T2 glyphs/font | P2×4 stale comments | OK with notes |
| T3 tap/hold | — | OK |
| T4 menus/parity | — | OK |

**P0 = 0, P1 = 0, P2 = 4 (report-only). Push: YES.**

## Core checkpoints (all PASSED)
- T1: two-zone rule `LongPressMenu.menuTopPx:100-110` (zone A above / zone B top=0, never below);
  arming + position change together (`KeyboardView.kt:967-971 menuBelowKey=false`);
  tests genuine (real numbers, assertNotEquals 0/232/171/-168, no tautology pattern).
- T2: SP 26/24 `KeyboardTheme.kt:73,76`; ShiftGlyph 0.30/0.18/0.12; ⌫ correspondence verified
  against the code (0.465−0.285=0.18); grid fit recomputed (68.25/63 px in a 108-px cell);
  hierarchy test migration justified; hint caps consistent.
- T3: `micHoldReleaseDecision` complete (all DictationStates, boundary >300: 300=tap/301=hold);
  HOLD_STOP_PENDING→cancelStartRequested→micStartFinishedAction(STOP_AFTER_START)→stop
  (`:800-830/:1851-1855`); TOGGLE_ON sets no wantedStop (`:1210`); @Volatile on both sides, main
  (belt + braces); listener migration complete (only override `:1191`); finding 2a =
  clean additive migration. Evidence `/tmp/vk_phone_e2.log` lines 75-97.
- T4: samples of 5 assets + full grep access exactly per plan §2 (11 sets; dollar menu only in
  the 7 $-bearing assets); digits without menus; el-gr/semicolon-[·] documented exception OK;
  PunctuationCoverage mutation extension justified (counter-tautology);
  SymbolLongpressAcrossAssetsTest checks against hard-coded plan sets + genuine 15-asset parity.
- Rule 9/10: VK-HIT only numbers/ids, debuggable() gate; no git, no Gradle runs in the review.

## P2 (report-only → comments updated, see below)
1. `KeyboardTheme.kt:29` KDoc 24/22 → 26/24
2. `KeyboardView.kt:113` comment 24/22 → 26/24
3. `KeyboardView.kt:428` `// 22 sp` → `// 24 sp`
4. `KeyboardView.kt:429` `// 24 sp` → `// 26 sp`

## Completed after the review
- P2×4 comment sync built in (lines 2026-11-02 22:2x); verification via testDebugUnitTest
  after the build series (comments are compile-only, full run as the net).
- The reviewer's caveat (test numbers without his own Gradle run) verified by the orchestrator
  on the XML side: Debug 589/0 fresh; the release count follows after V36_DONE.
