# PLAN T13/T14/T15 — letter row centers, 6 words/s, popup headroom
Planner: Claude Opus 5.5 (provider My, sa-b472203a), 2026-11-03 22:1x. Numbers
independently recomputed by the orchestrator (Python): center deltas to home-row
centers = 0.00000, row sums exact.

## T13 — r2 rebuild + period back to r3 (14 assets; ru exempt)
Variante V: 7 keys y..m width 0.94444, block centered under home block
(4.74998): start 1.44444 = a/s boundary, end 8.05552 = k/l boundary.
Centers of y..m sit EXACTLY on home centers s,d,f,g,h,j,k (a sits over shift,
l+backspace over enter). Gboard-QWERTZ puts y under s too.
  std:  shift 1.44444 | 7 x 0.94444 | enter 1.94448  (Sigma 10.00000; px 156/102x7/210)
  cs:   shift 2.20000 | 6 x 0.85000 | enter 2.70000  (x..m centers on d..k; Sigma 10)
  ru:   UNTOUCHED (D27; already target footer with space 6.5 + period + @ alternates)
Symmetry invariant of T11 is REPLACED by center-equality invariant.
r3 (14 assets, base+shift): ?123 1.5, globe 1, emoji 1, space 6.5, PERIOD 1, mic 2
(Sigma 13; space back to 540 px). Period key: id 'period', value '.', F54 alternates
",!?:;()" no hint, no @ (14 assets; ru keeps @ prefix; @ via m-menu as before).
Tests: LetterRowAlignment (7 text keys, centers == r1 centers s..k ±1e-3, cs 6 keys
d..k, shift/enter widths), LayoutCompleteness.checkGeometry (wantShift/wantEnter/
wantNT, period check moves r2->r3, symmetry block -> center equality),
FooterAcrossAssets (6 keys incl. period F54, space 6.5; ru branch shared).
Symbol layers keep space 7.5 (as before T11; space value differs per layer by design).

## T14 — word backspace 6/s
BackspaceRepeat.kt: WORD_PERIOD_MS 333 -> 167 (5.99 w/s). Threshold 2000 and char
grid 450/70 unchanged; absolute grid, no catch-up unchanged.
Comment updates BackspaceRepeat/KeyboardView/BackspaceTest. Test expectations: word
ticks [2000,2167,2334,2501,2668,2835] within t<=3000; rate exactly 6 in [2000,3000);
lag case nextDelayMs(2500)==167; boundary tests unchanged.

## T15 — long-press popup ALWAYS above the finger (Option H, PopupWindow excluded)
Root causes (code-proven):
  R1 IME window is keyboard-tall, no onComputeInsets override, root clips -> nothing
     can draw above the keyboard (top<0 forbidden by T1).
  R2 release: bandPx=0 -> r0 menu falls to top=0 = ON the pressed row (inline under
     finger); squashed rows worse (r1/r2 clamp to 0 too).
  R3 debug: band 126 -> popup still covers top 21 px of r0.
  R4 app-independent (notes and browser show same math).
Design (LatinIME pattern):
  1 KeyboardTheme: POPUP_HEADROOM_DP = 64 (168 px @2.625 density).
  2 ImeRootView: paddingTop=headroom, clipChildren/clipToPadding false; background
     moves to inner stack + InsetDrawable on root so headroom stays transparent.
  3 VoiceKeyboardService.onComputeInsets: top = keyboardTop; contentTopInsets =
     visibleTopInsets = top (no gap for the app); touchableInsets = REGION covering
     keyboard only -> headroom passes touches through to the app; gesture continues
     in the window it started in.
  4 LongPressMenu.menuTopPx gains headroomPx param (default 0 => old tests byte-
     identical); spaceAbove = statusBandPx + headroomPx; r0 takes true above
     position whenever >= -spaceAbove; mid rows/footer clamp to -spaceAbove instead
     of 0.
  5 KeyboardView passes headroom; elevation kept.
  6 Tests: LongPressMenuTest invariant top+menuH <= keyTop-gap for r0/r1/r2 with
     headroom=168; Robolectric onComputeInsets values; ImeRootViewLayoutTest headroom
     transparent. Risks to verify on emulator: window background transparency
     (no black band), fit height on small screens.
Emu evidence set (Opus acceptance, 7 items): r0 popup fully above r0 with >= 8 dp
gap and e fully visible (log popup-open top=-168); browser URL bar overlapped by
popup not keyboard; r1 popup inside headroom; r2 popup never below r0 edge; footer
period popup directly above key; idle state shows NO band and app text runs to
keyboard edge; touch-through 30 dp above keyboard places app cursor (dumpsys touchable
region = keyboard only); slide-select to accented cell works; debug status band +
emoji round trip clean. Acceptance loop: iterate until Opus 5.5 confirms on evidence.
