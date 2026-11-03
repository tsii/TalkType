# Review v0.1.38 vc39 — T8 Y row centering + T9 release status row
Reviewer: DeepSeek V4 Pro 0813 (sa-7a11fe67), 2026-11-03 07:5x. Full text in the transcript.

## Verdict: **OK — Push YES** (P0=0, P1=0, P2×2 report-only)

## T8 (PASSED, independently recomputed)
- de-de r2: Σ=10.0000 u; Y center 1.444445≈13/9 ✓; M center 8.055545≈145/18 ✓ (Δ≈5·10⁻⁶);
  px 96.5+7×119+150.5=1080 (KEY_GAP=0). tr-tr identical position invariant.
- cs: Σ 10.00002 (±0.001), m→["@"], no @ in r2. ru D27 untouched (no em-@-invention ✓,
  @ only on the symbol layer + period).
- Script: splice only base/shift (assert adjacency), symbols remain; ru read-only; idempotence
  via @-removal/append-guard + before==after-CharSet assert.
- checkGeometry: genuine new invariants (c2[0]≈13/9, c2[6]≈145/18 ±0.01), cs/ru branches genuine,
  Σ guards remain — hardening rather than hollowing out.
- @ reachability 15/15 (de m→[µ,@], en m→[—,…,–,@] distinct, el μ→[@]).

## T9 (PASSED)
- Seam FLAG_DEBUGGABLE from applicationInfo initialized once (ImeRootView.kt:126-128), GONE :158;
  showEmojiPanel/hideEmojiPanel (:260-274) do NOT touch the statusRow; the service never sets
  .visibility anywhere ⇒ release GONE survives mode switches. The Robolectric bit explicitly
  set/cleared.
- T5 fallback: bandPx=this.top=0 when GONE ⇒ T1 two-zone rule (LongPressMenu.kt:157 guard).

## P2 (report-only)
1. DESIGN_TASTATUR_V2 §10 table F36 rows (109/112) outdated (F40/T8 obsolete; the addenda
   32-37/122-126 correct it) — pre-existing doc drift.
2. LetterRowAlignment cs comment cites the old 1.88887/1.88875 discrepancy; code/asset 1.88889.

## User-goal evidence
Y under A/S ✓ · M under K/L ✓ · @ reachable 15/15 ✓ · release without status row ✓
Still open as before: 14-assets-identical/ru-unchanged not independently diffable without git.
