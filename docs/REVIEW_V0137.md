# Review v0.1.37 vc38 — T7 menu clamp + M rule
Reviewer: DeepSeek V4 Pro 0813 (sa-90301160), 2026-11-02 23:5x. Full text persisted by paraphrase.

## Verdict: **OK — Push YES** (P0/P1 = 0, P2×3 report-only)

## Core checkpoints (PASSED)
- `menuLeftClamped` delegates to `menuLeftPx` (math identical, clamp at [pad, width−pad−menuW]);
  guard `menuW > width` ⇒ the menu is never cut off, only suppressed (existing behavior F24e5).
- KeyboardView, both finding spots (popup :539-542, arming :915-923) use the same source;
  popup width ≤ arming width ⇒ no dead zones; the T5 band zone untouched.
- M rule in the script: R2_MAX_CELLS=10, overflow assert + before==after-CharSet check (:258-283) genuine;
  assets re-measured: da/fi r2 10 cells at 0.75, … in the dot menu (hyphen menu [–,—] stays ≤8);
  el-gr 10 cells, – in the hyphen-/… in the dot menu, ‰ direct key; T4 hygiene everywhere ≤8 (maximum 7).
- Clamp test: genuine utils (menuWidthPx/menuLeftClamped); grid unit equivalent to KeyboardModel
  (KEY_GAP_DP=0, padding 0 ⇒ unit=viewWidth/sum). The T6 cross-check covers … ” ª º ‰ ± fully.
- **User finding fixed (YES):** the vc37 overflow = el-gr 12 / da/fi 11 direct cells in r2 (plan width
  0.75 violated, cells ran over Enter/footer) ⇒ the M rule caps at 10 at 0.75; popup edge clamp
  against menu overlap (example hyphen: without clamp left≈−94 ⇒ now 0). The "8-cell menu"
  hypothesis refuted (maximum 7).

## P2 (report-only)
1. Assignment-text arithmetic error 8-cell menu: correctly 8·126+9·11=1107 px (not 1085) — documentation only.
2. MAX_CELLS=8 at 1080 px/2.625 physically unreachable (8-cell=1107>1080 ⇒ guard suppresses;
   effectively max 7 = 970 px); the KDoc "at most eight" deviates. Latent, no asset has ≥8.
3. The test re-models the grid unit inline (equivalent, but drift risk with KEY_GAP_DP/padding≠0);
   same family as REVIEW_V0136 P2#1.

## Condition open
"12 assets byte-identical" not independently verified without git (adopted as claimed).
