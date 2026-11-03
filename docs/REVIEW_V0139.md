# Review vc39-Snack — KDoc reality + production geometry in the clamp test
Reviewer: DeepSeek V4 Pro 0813 (sa-bae9d8e4), 2026-11-03 01:0x. Full text in the transcript.

## Verdict: **OK — Push YES** (P0/P1 = 0; P2 a/b/c report-only)

| Condition | Status |
|---|---|
| V0137 #1 (arithmetic error 8-cell set: 1085→1107 px) | ✅ KDoc formula + mini test `menuWidthPx(8)=1107` |
| V0137 #2 (MAX_CELLS=8 unreachable at 1080 px) | ✅ KDoc: 1107>1080 ⇒ guard suppresses, effectively 7 (970 px) |
| V0137 #3 (inline grid drift in the clamp test) | ✅ Production `KeyboardModel` + cell parity anchor (108 px; red evidence KEY_GAP_DP=1 ⇒ 105.6 FAILED) |
| V0136 #1 (anchor provenance) | ✅ Provenance statement (T6 actual, genuine protection = script assert before==after) |

## P2 (report-only)
a) Test uses `keyHeightPx = POPUP_HEIGHT_DP·density` instead of `desiredRowHeightPx` (vertical only, harmless).
b) Pad double meaning 10 (formula default) vs 11 (production rounding 10.5→11) in the same file — no contradiction.
c) `horizontalPaddingPx` drift not covered by the anchor (declared as an assumption in the KDoc).

## Decision (orchestrator)
The snack is function-neutral (production only KDoc) → **no separate build**; vc38 remains the dist state,
the snack rides along in the next feature build (versionCode 39 / 0.1.38). Tests 598/0 debug.
