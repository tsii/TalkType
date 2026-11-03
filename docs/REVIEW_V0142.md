# Review v0.1.42 — T11 r2 boundary grid + T12 symbol redistribution + T11b word backspace
Reviewer: DeepSeek V4 Pro 0813 (sa-c8062c2e, read-only), 2026-11-03 20:0x.

## Merge verdict
| Cut | Findings | Verdict |
|---|---|---|
| T11 r2 grid (14 assets) | — | OK |
| T12 symbols (15 assets) | — | OK |
| T11b word backspace | — | OK |

**P0 = 0, P1 = 0, P2 = 0 (report-only x2). Push: JA. V0.1.42 bereit.**

## (1) T11 geometry (STICHPROBE: en-us, cs-cz, es-es, ru-ru)
- 13 std: r2 = shift 1.22224 + 8x0.94444 + enter 1.22224 = Sigma10 exact; center chain
  symmetric about 5.0 (all pairs sum 10). Period id 'period' value '.' in r2 base+shift,
  F54 menu , ! ? : ; ( ). Footer r3 = 1.5+1+1+7.5+2 = Sigma13, period out.
- cs-cz (B'): shift 2.025 + 7x0.85 + enter 2.025 = Sigma10 (6 letters + period).
- ru-ru: byte-identical except symbol layers — base/shift untouched (Sigma12, 6-key
  footer, @ on period[0]).

## (2) T12 (char inventory HEAD==worktree, verified en-us/cs-cz/ru-ru/es-es)
- symbols r1 '- + / ( ) & @ = # %', r2 ': ; , \' " _ * ! ? .' (all 15 identical).
- symbolsShift id-rule: colon/semicolon slots down, two rightmost r2 cells up, r2 mirrored.
- es/fr/it/nl/pt: no colon/semicolon ids in symbolsShift -> no exchange (vacuous),
  conform PLAN sec.3/sec.9.

## (3) Backspace (T11b)
- Char ticks <2000 ms (70 ms), word ticks >=2000 ms (333 ms, 3 w/s); first word tick
  exactly 2000. Mode reset on UP (down() -> CHARACTER); deleteSurroundingText units
  correct (never crosses \n); no timer leaks (removeCallbacks); InputConnection
  main-thread-conform. Tests cover edge cases (test 6/7/8/9).

## (4) Test sync: 4 layout test files + BackspaceTest consistent with main.

## Report-only (P2)
1. es/fr/it/nl/pt symbolsShift not redistributed (id-rule vacuous) — documented.
2. Word deletion bounded to 256 UTF-16 units (BACKSPACE_WORD_CONTEXT_UNITS,
   VoiceKeyboardService.kt:1850) — intentional, documented.

## Limitation
No Gradle run (MUST NOT DO); test counts confirmed separately by the orchestrator.
