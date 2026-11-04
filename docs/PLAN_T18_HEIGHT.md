# PLAN T18 — keyboard occasionally one row too low (planner: Opus 5.5)
Symptom (user phone, gesture nav): footer row (space/mic) sometimes sits INSIDE
the system IME-switch globe strip (nav zone); switching keyboards restores.
Root cause (code analysis, to be confirmed by measurement): nav bottom inset
taken from VISIBILITY-dependent APIs (systemGestureInsets / systemWindowInsetBottom,
ImeRootView.kt:271-283). When the app temporarily hides system bars (browser
fullscreen/scroll), insets report 0 -> padding 0 -> footer drawn into the nav
strip. Listener fires only on CHANGE; stale 0 persists until view re-creation
(matches: switching IME fixes it, picture 3). Rejected: painting the strip
transparent — stock keyboards paint it in keyboard color; this is a HEIGHT bug.
Rule (2 files):
1. ImeRootView: pure navBottomPx(): API>=30
   max(insetsIgnoringVisibility(navigationBars).bottom, insets(tappableElement).bottom);
   API29 fallback as today; 120dp cap kept; 'exactly once' contract unchanged.
   Debug log VK-INSETS nav= vis= ign= tap= gest= pad= (numbers only).
2. VoiceKeyboardService: onStartInputView + onWindowShown ->
   rootView?.requestApplyInsets() (re-derive on every show, not only on change).
Tests (ImeRootViewLayoutTest): navBottomPx table (132 vis / 132 hidden-ign / 0 /
400->cap); late insets 0 then 132 -> padding 132, root height +132, stack height
unchanged; requestApplyInsets seam called (spy/counter).
Measurement plan (emu 5564): gesture overlay; DebugInputActivity toggle invoking
WindowInsetsController.hide(navigationBars()) with transient-behavior (browser
analogue). Steps A) normal show: pad>0, footer above globe line. B) bars hidden
then focus field: 0.1.43 -> pad=0 footer IN strip (cause proven); fix -> pad
stays >0. C) bars back, hide/show keyboard: pad correct WITHOUT IME switch.
D) rotate both ways. Evidence per step: dumpsys window IME mFrame,
InsetsSource type=navigationBars frame, touchable region, screenshot edge
profile: footer bottom edge at H-pad.
Open for user: does it also happen on 0.1.42 (pre-T15)? after fullscreen video /
scrolling in IronFox? Phone-side proof needs one-time debug log line (user choice).
Interplay: T15 untouched (top edge independent of bottom padding); T17 same file,
different function — sequence the two coder tasks, claim_files.
