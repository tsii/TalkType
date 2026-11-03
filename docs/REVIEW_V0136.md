# Review v0.1.36 vc37 — T5 status band overlay + T6 symbol grid
Reviewer: DeepSeek V4 Pro 0813 (sa-77441537), 2026-11-02 23:0x. Full text persisted by paraphrase.

## Verdicts
- **T5 status band overlay: OK** (no P0/P1). Band zone invariant `LongPressMenu.kt:125`
  (`<=` conformant with the spec: lower edge touches, does not cover); guard fallback giant menu
  → two-zone rule (`:122-128`, test menuH=400→0); default `statusBandPx=0` byte-identical
  (regression protection `LongPressMenuTest.kt:529-534`); elevation 2f only when top<0, reset at
  BOTH closing spots (`:709-712`, `:1033-1036`; `popup = null` only there — no third path;
  panel/language switch via rebuildModel→discardLongPress); no translationY;
  negative-y touch direction-open (`cellWins :181`, test `:510-522`).
- **T6 symbol grid: OK with notes** (no P0/P1). Sample of 5 assets Σ=[10,10,10,13],
  ⌫ r1 end 1.0, ⏎ r2 end 1.0, ⇧ r2[0] 1.5, r0 10 digits, footer without ⏎/no text character;
  €-migration da-dk/el-gr/de-de evidenced; ‰ in all 15 in the percent menu (el-gr direct key
  remains); script `rebuild_symbol_layers.py:181-216` writes ONLY two sections,
  json-validated, non-destructive; before/after cross-check genuine (`:224-231`).

## P2 (report-only)
1. Anchor list provenance `SymbolGridAcrossAssetsTest.kt:49-75`: hard-coded, not independently
   verifiable without git; the genuine protection lay in the script; value = a future guard.
   → With a git history: verify the anchoredSets against the vc36 assets.
2. T5 boundary case `top+menuH==rowBelowTop` untested (code correct, documentation/test gap).

## Push verdict: **YES** (P2 without release need)
