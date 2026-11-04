# Changelog — TalkType (formerly Voice-Keyboard)

All notable changes per release. Newest on top. Compiled 2026-11-03 from
HANDOFF.md, state/progress.md and the review documents; kept current from now on.
`versionCode` in parentheses. APKs older than the current dist rotation are removed
from `dist/` (checksums of the last rotations: `dist/SHA256SUMS-*.txt`).

## [0.1.44] — 2026-11-04 (45) — Enter fixes + keyboard-height robustness
Dist: release `96e776dda1584c76169b4e5e002f794a18f9bd1cad2360b354a40e3efe477e2d`,
diag-debug `00218788438132a79f01b17372db69a97350d57e055ee0e59a78605a75aad40a`.
- **T15b fullscreen window**: `onEvaluateFullscreenMode=false` (no extract UI in
  this product) and the touchable region now anchors to the keyboard stack bottom
  instead of the root bottom. Chrome search/tab-transition state previously grew
  the IME window (h≈2204) and left the footer row touch-dead. Emulator verified:
  corrupt window size gone, footer tappable live (VK-HIT).
- **T17 Enter**: multiline fields (e-mail body) now insert a line break and keep
  the keyboard open; single-line action fields (URL/search) still fire the editor
  action. Pure seam `applyEnterKey(ic, info)`: newline when info null, action
  NONE/UNSPECIFIED, TYPE_TEXT_FLAG_MULTI_LINE set, or IME_FLAG_NO_ENTER_ACTION;
  otherwise performEditorAction. AOSP LatinIME rationale (review VK-R1: UNSPECIFIED
  is the default action, NOT a newline — fixed pre-release). EditorActionEnterTest
  7/0; emulator: Chrome URL loads + closes, multiline field gets \n with
  mInputShown=true.
- **T18 keyboard height**: nav-bottom padding now derived via
  `insetsIgnoringVisibility(navigationBars)`/`tappableElement` (visibility-robust;
  stale 0 when an app transiently hides system bars caused the footer to sit in
  the IME-switch strip — user screenshots) + `requestApplyInsets` on every
  `onStartInputView`. Numbers-only `VK-INSETS` evidence log (release-safe) and a
  Hide/Show-nav-bars switch in the debug bench (T18-A).

## [0.1.43] — 2026-11-04 (44) — long-press popups above the finger, 6 words/s
Dist: release `2735a99f05f9cbc443e5a09d10611ae31a51f9151fed9570346e945e67e14d20`,
diag-debug `14056297048c21e9c6ebf3e45d26e19bc35a512c7654ad844d2df67529edcc72`.
- **T15 popup headroom** (Opus 5.5 design, option H): the IME window gained a
  transparent 64 dp headroom band above the keyboard (`onComputeInsets` LatinIME
  pattern: content/visible insets at the keyboard top, touchable region keyboard-only
  — touches above pass through to the app, gestures stay in the window). Long-press
  menus now open **above the pressed key**, into the app area (stock behaviour),
  never inline under the finger. Two follow-up fixes found on the emulator: theme
  windowBackground painted the band (runtime transparent window background) and
  `drawColor` filled the unclipped window (bounded `drawRect`). Robolectric
  LongPressMenuTest 57/0 + ImeRootViewLayoutTest 16/0; release-build emulator
  evidence + planner acceptance loop in `docs/EVIDENCE_T15_OVERLAY.md`.
- **T14 word backspace 3 → 6 words/s** (hold > 2 s, period 167 ms).
- **T16 layout rollback** (user decision): the T13 experiment (letter row centered
  under home-row centers, period beside space) was short-lived — assets + 3 layout
  tests restored byte-identical to 0.1.42, period key stays **between M and ⏎**
  (T11 variant B). `tools/rebuild_r2_t13.py` kept, marked deprecated.
- Planner/verifier: Claude Opus 5.5 (`docs/PLAN_T13_T15.md`, T15 acceptance
  iteration 2). Tests **626 debug + 591 release = 1217/0**.

## [0.1.42] — 2026-11-03 (43) — new letter row & symbol page + word backspace
- **T11 letter row rebuilt** (variant B, user choice): 8 equal keys (102 px) —
  y x c v b n m + **new period key before ⏎** (F54 menu moved up with it) — evenly
  distributed, shift/enter equal at 132 px, block mirror-symmetric. Footer: period
  removed, **space widened 540 → 623 px**. cs-cz own variant (7×0.85); ru-ru exempt (D27).
- **T12 symbol page redistributed** (all 15 languages): bottom row `: ; , ' " _ * ! ? .`
  (period right before ⏎, mirrored), top row `- + / ( ) & @ = # %` (plus beside minus,
  percent up); symbolsShift analog where slot ids exist. Pure permutation, char
  inventory proven identical.
- **T11b word backspace**: holding backspace > 2 s deletes **whole words at 3/s**
  (was single chars); word boundaries + leading spaces handled, resets on release.
- Review `docs/REVIEW_V0142.md`: P0=0, P1=0, push YES. Tests **617 debug + 582 release
  = 1199/0**. Tools: `rebuild_r2_t11.py`, `rebuild_symbol_t12.py` (idempotent, guarded).

## [0.1.41] — 2026-11-03 (42) — TalkType
- Product renamed **Voice Keyboard → TalkType**: app label centralized in
  `@string/app_name` (was hardcoded in AndroidManifest), README/HANDOFF titles,
  GitHub repo `tsii/TalkType`, dist pattern `talktype-<version>.apk`.
- `applicationId` stays `de.voicekeyboard` (upgrade keeps models/data on device).
- Full test run after rename: 613 debug / 578 release = **1191/0**.

## [0.1.40] — 2026-11-03 (41) — English UI & public readiness
- **App UI switched to English** (strings.xml values, notifications, accessibility
  descriptions, exception/diagnostic texts); test expectations synced byte-exact.
- Remaining German documentation fragments translated (incl. historical quotes,
  “translated from German” where they were source wording).
- **Repo sanitized for public release**: internal IPs/endpoints, SSH alias, home
  paths, device identifiers → placeholders; git history rewritten
  (git-filter-repo, two passes, force-push); residual scan over all commits = 0.
- Deliberately German still (documented): language endonyms (Deutsch/Español/…),
  ASR golden corpus data, layout assets, log field keys.
- Review: `docs/REVIEW_PUBLIC_SWEEP.md` (P0=0; all P1 fixed pre-push). 1191/0 tests.

## [0.1.39] — 2026-11-03 (40)
- Repo translation wave 1 (~101 markdown + ~150 Kotlin comment files + assertion
  messages DE→EN; UI still German at this point).
- **New adaptive app icon**: shield + waveform, monochrome layer for Material You.
- **T10**: automatic space after dictation ending in punctuation
  (`TrailingSpace.kt`, `trail_sp` diagnostic field). 1191/0 tests.

## [0.1.38] — 2026-11-02 (39)
- **T8 Y-row centering**: `@` key moved to long-press on `m` (German `ß` slot next
  to `µ`); ⇧ 96.5 px / ⏎ 150.5 px, 7×119 px letters — Y exactly on the A/S gap,
  M exactly on the K/L gap (13/9 and 145/18, pixel-exact; planner-approved,
  review recomputed). cs/ru documented exceptions; geometry test hardened.
- **T9**: status row + 🐞 button **debug-only** (release: GONE, FLAG_DEBUGGABLE
  seam) — clean stock look for release builds. 1173/0 tests; installed on device.

## [0.1.37] — 2026-11-02 (38)
- **T7 overlapping long-press menus fixed**: edge clamp (`menuLeftClamped`, popup +
  arming) + M-rule (row 2 ≤ 10 keys à 0.75) — el-gr/da-fi overflows capped,
  overflow keys go to the … menu; character protection (… ” ª º ‰ ±). 1159/0.

## [0.1.36] — 2026-11-02 (37)
- **T5** long-press menus may rise into the status band (band zone + elevation) —
  no more covering key rows; **T6** symbol layers unified across all 15 layouts
  (Σ[10,10,10,13], ⌫/⏎ on letter slots, €↔Shift level da/fi/el, ‰→percent menu),
  rebuilt by script with before/after cross-check. 1151/0.

## [0.1.35] — 2026-11-02 (36)
- **T1** two-zone long-press overlay (Q-menu above the keyboard, pixel-proven);
  **T2** shift glyph foot offset + font sizes; **T3** tap/hold race healed (tap
  ≤ 300 ms always starts); **T4** long-press symbol menus in all 15 layouts
  (¿ ¡ [{< }] „“”… ‰ ± €£¥). 1143/0.

## [0.1.34] — 2026-11-01 (35)
- **A5** `ownSpan` corpse fix = root cause of toggle misbehavior (found on device);
  **W package**: engine preload on first keyboard show, resident engine, 5 new
  export fields. Release-truth fix: release unit tests had been in the wrong
  source set (days of up-to-date illusion) — now really executed (544). 1124/0.

## [0.1.33] — 2026-11-01 (34)
- **A1** cold-start watchdog baselines from real capture start; **A2** write-stop
  reasons {SAME,W1,W3d,DELTA}, W3d ≤ 1 hypothesis, atomic finale; **A3**
  `seg_final` artifact fix; **A4** 10 export fields + `event=window` (17 metrics,
  release-visible); **B1** digit menu row 0; **B2** backspace repeat 450/70 ms +
  selection delete. Emulator evidence E1–E5 (incl. 116 s hold, 20 restarts).

## [0.1.32] — 2026-11-01 (33)
- Diagnostics E1c/E1d; interim full run 1028/0.

## [0.1.31] — 2026-10-31 (32)
- User-contract continuation: **F53** intermediate-segment finalization (35 s live);
  hold-contract refinements.

## [0.1.30] — 2026-10-31 (31)
- **F46 + F49–F52**: auto-stop hold gate (`autoStopSuppressed`) — **hold means
  continuous dictation, no abort** (the user contract); plan slice A
  (`docs/PLAN_V0130.md`).

## [0.1.29] — 2026-10-31 (30)
- **F45**: foreground-service leak on the auto-stop path fixed (`stopFgs` in the
  onStopped finale). 935/0.

## [0.1.28] — 2026-10-31 (29)
- **F38–F44** dictation auto-stop diagnostics: 6 new 🐞 export fields
  (`had_output`, `output_chars`, `first_loud_ms`, …, `fgs_stopped_by`), sanitizer,
  **F43** auto-stop after ~2.5 s trailing silence.

## [0.1.27] — 2026-10-31 (28)
- **F35** dictation-tick fixture (30/60 s corpus, watchdog clock race fixed);
  **F36** V2 keyboard grid (1u = 108 px, ⌫ between M and ⏎, fr/cs/ru specifics).

## [0.1.26] — 2026-10-30 (27)
- P0 pack: status row + 🐞 button, mic state display, path icons ⌫/⇧/⏎;
  r2 grid centered; mic reset + 🐞 Z-order + path cache fixes. 473/0 + 442/0.

## [0.1.14 … 0.1.25] — October 2026 (14–26)
- Early series: 15 keyboard layouts + schema parser, Parakeet int8 ASR engine
  (TDT greedy, streaming), model download/store with hash verification, dictation
  pipeline (capture service, commit policy), emoji panel, theme. Details in
  `docs/` reviews per milestone.
