# Changelog — TalkType (formerly Voice-Keyboard)

All notable changes per release. Newest on top. Compiled 2026-11-03 from
HANDOFF.md, state/progress.md and the review documents; kept current from now on.
`versionCode` in parentheses. APKs older than the current dist rotation are removed
from `dist/` (checksums of the last rotations: `dist/SHA256SUMS-*.txt`).

## [0.1.47] - 2026-11-06 (versionCode 48) — pushed 232a9e5
### Added
- Experimental second dictation model Qwen3-ASR 0.6B (gated: BASE_URL_QWEN empty, card
  hidden until server upload; mutually exclusive with U2; honest label).
- Typing suggestion row (suggestion-only: wand press required, undoable via second press,
  no learning, no persistence). Rules for all 15 layouts + word-list neighbour corrector
  for de-de/en-us only; other 13 languages greyed ("coming soon", toast).
- Setup: "Show suggestions" switch + "Correct in all active languages" switch (default off).
- Correction assets CC-BY-SA 4.0 (NOTICE.txt; de 100k / en 150k forms, sweep-proven cut).
### Fixed
- VK-8 (P0): word lists never loaded on device (double-prefixed asset path) — corrector
  was silently dead outside JVM tests.
- VK-9 (P1): silent character drop when IME relayout (300 ms correction publish) landed
  between key DOWN/UP — model rebuild now early-returns on identical stamp
  (asset.id|layer|width|fit), tap identity preserved.
### Notes
- Tests 888/0 debug + 850/0 release (fresh, --rerun-tasks). Review R3: P0=0 P1=0 P2=3.
- P2 backlog 0.1.48: UNDO dictation-idle gate, compute_abort counter field, NOTICE SA line,
  "Adresse" exclusion decision (user open point).

## [0.1.47] — in preparation (48) — typing suggestions, suggestion-only (wand row)

User command 2026-11-05 (`docs/PLAN_V0147.md`): a suggestion row above the keys
refines the "no autocorrect in v1" rule — NOTHING is ever replaced
automatically. Not built yet (tests/docs in progress; dist + vc48 bump + review
follow in T23j).

- **Suggestion row with magic wand** (T23f): 40-dp row in the statusRow slot —
  eligible text fields only (never passwords/URI/e-mail/NO_SUGGESTIONS, never
  numeric/phone/NULL); suggestion text left (changed words bold+underlined,
  dimmed when empty), wand button right (vector icon, no emoji — minSdk 29).
  Applies ONLY on an explicit wand press, single-level undo until the next
  keystroke, glyph switch on the wand. Setup switch "Typing suggestions"
  (default ON, `corr_row_enabled`).
- **Correction pipeline, pure Kotlin/JVM** (T23a–T23e): typing rules (sentence
  caps, spacing, repeated-word drop), neighbour-key model from the real layouts,
  lexicon corrector (weighted edit distance, calibrated threshold 1.6, ambiguity
  brakes), German compound acceptance, typed-region tracker (≤ 500 chars,
  suffix proof), replacement over the InputConnection (batch edit, re-verify).
  Quality over the gold corpus: lexicon fix-rate 30/32 = 93.8 % (≥ 90 % gate),
  clean-corpus false positives 0/117, corrector p95 0.53 ms per region; two
  documented wrong-fixes remain as U1 context-scorer candidates.
- **Word lists, static, CC BY-SA 4.0** (T23b): hermitdave/FrequencyWords content
  2018 — German top 100k, English top 150k; `assets/wordlists/NOTICE.txt` +
  `tools/wordlists/SOURCES.md`; cut-off and typo-filter evaluation documented
  in `tools/wordlists/SWEEP_*.md` (deeper lists degrade the corrector). No
  learning, no persistence of typed text (rule 9: counters/codes only in the
  🐞 export — 9 new `corr_*` fields).
- **Correction language mode** (T23k/T23l): CURRENT vs ALL_ACTIVE, toggled by
  LONG-PRESS on the language key (2-cell popup, slide selection); in ALL_ACTIVE
  the space bar shows "All languages" in the device display language and the
  current language moves to the row as a prefix tag.
- **Word keyboards limited to de + en** (T23m, §16): the other 13 languages stay
  greyed in Setup ("coming soon", clickable hint, saved choices survive) and
  drop out of the globe cycle until the §16 runbook makes them available.

## [0.1.46] — 2026-11-05 (47) — second ASR model: Qwen3-ASR 0.6B (experimental), switchable
Dist: release `d28c66691a728c206334789e0e4e7c71f7fb6584e185acd8bd3b084a6854ed24`,
diag-debug `58215f6aac3c3ef4f745c49b51819156182d63c37aed13b66bcaf3b56e0bffd6`.
Tests debug **732/0** + release **691/0** (one load-flaky rerun green: SegmentFinalizeTest
under full-suite wall-clock pressure, single run 7/7 — same lineage as DictationHoldGateTest);
review `docs/REVIEW_V0146.md` P0=0/P1=0 after fix round (VK-1 etag per variant, VK-2 empty
baseUrl = not offered, VK-3 contract doc).
- **Honest scope note (user asked for “Qwen 1.6B”)**: delivered model is **0.6B** — the
  1.7B decoder export exceeded this host's RAM (needs ≥ 24 GB free in one process;
  SIGKILL/OOM twice). The export tooling is staged and the extra set id
  `qwen3-asr-1.7b-int8` can ship later on a bigger host without any code change.
  Numbers and decision: `docs/QWEN_ASR_CONTRACT.md`, planner decision `docs/PLAN_V0146.md` §7.
- **Model management per set** (T22c): activating one model never deletes another
  (pointer/marker/partial per variant), `deleteSet()` for the non-active set, legacy
  installs unaffected (defaults keep variant A byte-identical incl. `.etag`/pointer names).
- **Download per set** (T22b′/d): Qwen set pinned IN THE APP (6 files, sizes + sha256,
  1.704.302.777 B total); server host intentionally EMPTY until upload — card shows
  “not available yet” instead of offering a 404 (VK-2). RAM gate ≥ 8 GiB, space floor
  3.2 GB; `EXTRA_VARIANT` routes service/downloader/whitelist per set.
- **Qwen engine on the existing ONNX runtime** (T22e/f): own Kotlin Mel frontend
  (Bluestein 400-pt DFT, golden-checked), block attention mask, chat prompt with
  forced `language German`, encoder + prefill + KV-cache step graphs, greedy with
  EOS {151643,151645}, cap 128 (`incomplete=true`), repeat guard, silence guard
  (1 s silence ⇒ empty text — model alone hallucinates “systemschluss”; see contract §5).
  No new native runtime, no APK growth; tokenizer decode golden against HF tokenizer (20 pairs).
- **Switching** (T22g): setup writes the pref only for READY sets; a RUNNING DICTATION IS
  NEVER ABORTED — switch defers to dictation end; exactly ONE engine resident (shutdown
  before rebuild); broken target falls back to Parakeet (`switch_fallback` code + 🐞 field
  `engine_set_used`). Parakeet stays the default for everyone (pref absent ⇒ unchanged path).
- **Setup UI** (T22h): “Dictation model” section — “Parakeet TDT 0.6B (recommended) · 0.67 GB”
  and “Qwen3-ASR 0.6B (experimental)” with honest body copy (slower, less accurate on our
  German tests, may invent words during silence); Select/Download/Delete per card; network
  invite now keyed per active set id (A users keep their existing id).
- **Emu duty 5566 (partial, environment-degraded)**: version line `TalkType 0.1.46 (47) · debug`,
  model cards live, Parakeet download path PROVEN end-to-end on-device (671 MB, 29 s,
  etag + part in own set, READY), VK-HIT/VK-INSETS green. ENGINE LOAD crashes in
  libonnxruntime via ARM-translator **with 0.1.45 too (control test)** — host RAM
  pressure day (exports + swap); retried 4 configurations, deterministic. Parakeet
  dictation regression + all Qwen timing therefore move to the phone, which is the
  real gate anyway (planner: 35 s window must finalize ≤ 10 s on device before the
  Qwen UI unlocks). Emulator left stopped to heal the host.

## [0.1.45] — 2026-11-04 (46) — version label, network-revoke invite, notifications optional
Dist: release `082fa82f2c8e787a8f2e45861c70bbbc71814853d8b388430a443cfafb8ff258`,
diag-debug `42e3e9db263869afa4ab2cca6bf572c8387c94a85dae874b6319c93580bdcc52`.
Tests debug **673/0** + release **638/0** = **1311/0**; review
`docs/REVIEW_V0145.md` P0/P1=0 (P2 VK-1/VK-2 fixed pre-build, VK-3 documented).
- **T19 version label**: Setup header shows `TalkType <versionName> (<versionCode>)`
  (debug appends `· debug`) from ONE shared `AppVersion.read` reader, now also used
  by the 🐞 debug export. Emu-proven `TalkType 0.1.45 (46) · debug`.
- **T20 network-revoke invite** (user clarification 2026-11-04): the app never
  touches the network after the model download; ONE card per downloaded model in
  Setup offers `Revoke network permission`, opening the Android system App-Details
  page directly (`ACTION_APPLICATION_DETAILS_SETTINGS`, `package:` data —
  ShadowIntent-proven, emu-proven: SpaActivity opened, card then hidden forever;
  `Later` also silences per model). Old E2 in-app network switch removed
  (`setup_network_switch`, `DownloadPolicy.shouldTurnNetworkOff` retired); privacy
  copy now honest about system-side revoke; manifest comment revised (ACCESS_NETWORK_STATE
  comes from the onnxruntime AAR, unused by our code).
- **T21 notifications optional (measured)**: `docs/PLAN_V0145.md` §T21a protocol —
  3×60 s dictation with POST_NOTIFICATIONS REVOKED ran clean on api36 (silent-guard
  and notif gate bridged debug-only), 2×60 s grant control identical. F28c's
  “recording dies” reproduces as our OWN silence guard (`audio_error:silence` ~2 s),
  not the OS. Dictation now requires RECORD_AUDIO only; POST_NOTIFICATIONS stays
  optional (setup ask, download-progress UX) and in the manifest. F28c tests
  inverted with measurement reference; obsolete error string removed.

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
