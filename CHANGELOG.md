# Changelog — TalkType (formerly Voice-Keyboard)

All notable changes per release. Newest on top. Compiled 2026-11-03 from
HANDOFF.md, state/progress.md and the review documents; kept current from now on.
`versionCode` in parentheses. APKs older than the current dist rotation are removed
from `dist/` (checksums of the last rotations: `dist/SHA256SUMS-*.txt`).

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
