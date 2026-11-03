# HANDOFF — Voice-Keyboard (project root, checkpoint for the next phase)

**Status:** 2026-11-03 ~12:05 UTC · **Delivered version: v0.1.40 / versionCode 41** (English UI language across app + repo; docs fully translated incl. last German fragments; repo sanitized for public release: git-grep internal-endpoint/alias/home-path/device-id patterns = 0. Tests **613/0 + 578/0 = 1191/0**; dist 0.1.40 `94111cf5…`/diag `561fb1ad…`. History rewritten (filter-repo, force-push done)).
Before that v0.1.39 / versionCode 40 (REPO TRANSLATED DE→EN: ~101 markdown + ~150 Kotlin comment files + test assertion messages; UI strings/literals/logic unchanged German. NEW ADAPTIVE APP ICON shield+waveform (design sipgate-ultra, user choice) incl. monochrome layer. T10: auto space after dictation ending on punctuation (trail_sp field, TrailingSpaceTest 9/0). Tests **613/0 + 578/0 = 1191/0**; dist 0.1.39 `ac9c8341…`/diag `41a369f8…`. Translator preference qwen-38-flash-next; coder primacy glm-5.3-verda (user order).
Before that v0.1.38 / versionCode 39 (T8 Y-row centering — @ key removed (→ long-press m, de next to µ), ⇧ 96.5 px, ⏎ 150.5 px, Y exactly on the A/S gap, M exactly on the K/L gap (13/9, 145/18; pixel-exact, planner sipgate-ultra approved, review independently recomputed); cs/ru documented exceptions; checkGeometry newly hardened; T9 status row+🐞 ONLY in debug (release GONE, FLAG_DEBUGGABLE seam). Tests 604/0+569/0=1173/0; Review docs/REVIEW_V0138.md P0/P1=0 Push YES; dist 0.1.38 `2a379f2f…`/diag `fca9592a…`; **installed on the user's phone via adb -r (same signature, model remained)**; Y evidence image w_dbg3.png). Planner confirmation by user 2026-11-03: sipgate-coding-ultra.
Before that v0.1.37 / versionCode 38 (T7 user finding "overlapping menus" (translated from German) healed: edge clamp menuLeftClamped (popup+arming) + M rule r2 max 10 à 0.75 — overflows el-gr 12/da-fi 11 cells capped, …→dot menu, –→hyphen menu; invariant test: no menu ever runs off the view (red evidence 1381>1080 px) + character protection … ” ª º ‰ ±; clamp evidence image u_clamp.png; Review docs/REVIEW_V0137.md P0/P1=0 Push YES; Tests 597/0+562/0=1159/0; dist 0.1.37 `0421519d…`/diag `418bc77a…`).
Before that v0.1.36 / versionCode 37 (T5 long-press menu in the STATUS ROW — band zone top=−statusBandPx + elevation 2f, final emulator pixel evidence k3_hold: 6 tiles y1700–1838, no key row covered; T6 symbol unified grid Σ[10,10,10,13] in all 15 assets — ⌫/⏎ on letter slots, footer without ⏎ and without characters next to the space key, €↔Shift level da/fi/el, ‰→percent menu; Review docs/REVIEW_V0136.md P0/P1=0 Push YES (P2×2 report-only); Tests 593/0+558/0=1151/0; dist 0.1.36 `26220180…`/diag `650162bd…`; T6 bulk via script tools/rebuild_symbol_layers.py, before/after cross-check real).
Before that v0.1.35 / versionCode 36 (T1 overlay two-zone — Q menu above the keyboard, emulator pixel evidence; T2 ⇧ arrow foot 0.18·s offset + letters 26/digits 24 sp; T3 tap/hold race healed — Tap≤300 ms now starts regardless of start time, evidence log /tmp/vk_phone_e2.log; T4 long-press menus symbol levels in all 15 assets: ¿ ¡ [{< }]‚ „“”… ‰ ± €£¥; Review docs/REVIEW_V0135.md P0/P1=0 Push YES; Tests 589/0+554/0=1143/0; dist 0.1.35 `438ec03d…`/diag `83c58dc0…`).
Before that v0.1.34 / versionCode 35 (A5 ownSpan corpse fix = toggle root cause from the device; W package: preload on first keyboard show + engine resident + 5 export fields; Review P0→fix→P0=0 Push YES, `docs/REVIEW_A5_W.md`; Tests 580/0+544/0=1124/0; release authenticity damage discovered & fixed: test was in the wrong source set — release tests showed a days-long up-to-date illusion, actually now 544). Before that v0.1.33/vc34 (A1–A4+B1/B2, C-emu E1–E5; `docs/PLAN_V0133.md`, `REVIEW_A2_A4.md`, `REVIEW_A1_A3_B.md`) and v0.1.32/vc33 (E1c/E1d, 1028/0).
**Protocol:** `docs/CONTINGENCY_PLAN.md` · Rules: `reminders/ALWAYS.md` · Details: `state/progress.md`, `docs/PROJECT_STATE.md`

## Who may do what (roles, fixed)
- **Design/UI:** `my/work-sipgate-coding-ultra` (provider My) — **since 2026-11-01, replaces Sol** (`openai-codex/gpt-6.1-sol`, permanently down: Codex limit). Effort levels: low/high/max.
- **Plan:** `my/work-sipgate-coding-ultra` (provider My, <llm-provider-host>) — **since 2026-11-01, previously Kimi**. Effort levels: low/high/max.
- **Analyses/docs:** Kimi K3 (`moonshot/kimi-k3`) — additionally writes design/graphics as the Sol replacement.
- **Code:** `my/work-zai-org-glm-5.3-verda` (provider My) — **since 2026-11-01, previously cortecs/glm-5.3**. Effort: low/high/max.
- **Review of every code task:** DeepSeek V4 Pro 0813 (`cortecs/deepseek-v4-pro-0813`).
- **Orchestrator:** commissions + verifies mechanically; no own specialist work, no own reviews.

## Version history (delivered, all in `dist/`, SSH link via `<download-server>/api/file?path=Voice-Keyboard/dist/<name>&download=1`)
| Version | Build | Note |
|---|---|---|
| 0.1.14 … 0.1.25 | — | historic, removed from `dist/` (rotated per build) |
| **0.1.26** | 27 (07:43) | P0 pack (status row+🐞, mic state, path icons ⌫/⇧/⏎) + F33-r2 grid (center 450px) + F34-P2 (mic reset, 🐞 Z-ordering, path cache). Tests debug **473/0**, release **442/0**. |
| **0.1.27** | 28 (16:33) | F35 "path D" (translated from German) green (KorpusFixture 30/60 s, stub 150 ms, `KorpusTaktTest` 7 assertions; finding: watchdog clock race in the 60-s case fixed — stub latency pumps its own clock) + **F36 V2 keyboard** (1u = 108 px, r0–r2 Σ10, ⌫ between M/⏎, surfaces/gaps/radius, ⌫ glyph left arrow with base; fr = de pattern, cs r1 = 0.5+10×0.85, ru r2 remains D27/Σ12). Tests debug **475/0**. Review F35 (P0/P1=0, P2=2) + F36 (P0/P1=0, P2=1), each push recommendation YES. |
| **0.1.28** | 29 (interim, dist-rotated) | **F38–F44:** dictation auto-stop diagnostics package (F42 = 6 new 🐞 fields `had_output`/`output_chars`/`first_loud_ms`/`first3s_loud_blocks`/`first3s_peak_dbfs`/`fgs_stopped_by`, sanitizer `:`→`=`, export header+42=43 lines; F43 = auto-stop after ~2.5 s speech pause `TRAILING_SILENCE_MS=2500`, c-gate only streaming silence; F44 = `fgs_stopped_by` codes). |
| **0.1.29** | 30 (rotated) | **+F45** = FGS leak on the auto-stop path fixed (`stopFgs("autostop")` in the onStopped finale, `VoiceKeyboardService.kt:1356-1360`). Tests 483/0 + 452/0 = 935/0. |
| **0.1.30** | 31 (rotated) | **F46 + F49–F52** (plan `docs/PLAN_V0130.md` slice A): auto-stop hold gate (`autoStopSuppressed`, hold = continuous, user contract), start serialization + UP hint, K2 strings, `status_res`, ⇧ up arrow (V9). Tests 496/0 + 465/0 = 961/0. |
| **0.1.31** | 32 (01:40) | **CURRENT — user contract continuation:** **F53 intermediate segment finalization** (`segmentFinalizeMs = 35 000` live; α = 38.5 ms/s curve, decode window ≤ 35 s ⇒ hypothesis latency ≤ ~1.4 s, no RSS/time exponential; §5.1-compliant: whole_buffer only segmented; chain golden: `SegmentGoldenTest` "alpha. bravo. charlie." exact; (b) forced-cut trade-off = exactly one space, documented in KDoc) + **hold upper limit 900 s** (hold+toggle, one-time warning hint from 780 s; `holdWarnDue`, `HoldWarnTest` 5/0) + **F50 fix:** `ConsumerFaultTest` load flaky at the root (poll on null sentinel was a no-op → final-state poll; 2× full suite green) + **finalizeSegment error path:** transient ⇒ discard the application, session lives (only OOM is hard) — review P1 fixed + **B2b** `onAborted` resets. Tests **513/0 + 482/0 = 995/0**. Reviews A/B1/B2 **P0/P1 = 0**. |

## Verified overall state
- **F24a–e, F25, F26, F27, F28a/c, F29, F30, F31/F31b, F33, F34, F35, F36, F38–F46, F49–F53:** green, all device/graphics evidence present (F36: comparison 6/6 ✓, `docs/DESIGN_TASTATUR_V2.md` §10; F43 concept refinement in `docs/KONZEPT.md`; F49: `DictationHoldGateTest`; F53: `SegmentGoldenTest` golden; 900-s hold limit: `HoldWarnTest`).
- **Reviews:** `docs/REVIEW_F28_F31.md` (P0=0/P1=0/P2=1 → P1 fixed), `docs/REVIEW_F29_F33.md` (P0=0/P1=0/P2=3 → all 3 fixed in 0.1.26), F35 (P0/P1=0, P2=2) + F36 (P0/P1=0, P2=1) + A1–A3 (0.1.30: P0/P1=0, P2=2) + A/B1/B2 (0.1.31: P0/P1=0) each with **push recommendation YES**. Push-ready: **YES**.
- **GitHub:** `main` = `dc3b4f0`. **NOTHING pushed** (0.1.14–0.1.31 exist only locally). Push only after user approval (rule 10).
- **M7 screenshots** (light/dark, 8 pieces) → confirmed by the user.

## Open work (in this order)
1. **F37 = clock injection in the coordinator** (follow-up from F35): the **controller clock is NOT injectable in the coordinator** — without it, **Route B** (corpus + real engine, emulator/phone) does not run deterministically. Evidence/finding: F35 test run (60-s case). Plan: `docs/KORPUS_DIKTAT_TESTPLAN.md` §5.
2. **C2/dictation fix:** NOT solved (longer recordings incomplete; variance now measurable via `output_chars`/`had_output`/`status_res`). Waiting for **🐞 export #3** from the user (with **0.1.31-diag**; evidence points: `status_res`, `fgs_stopped_by`, hold evidence chain). Decision tree: `docs/ANALYSE_DIKTAT_ABBRUCH_2.md` §"decision tree export #3" (translated from German). What is proven: stop via the real UP path at ~1.2 s, level −56 dBFS (really measured), no 1-s timer, no decode stall. Route B (emulator + real engine) depends on the ONNX x86_64 probe (F32-B, abandoned/open) and on F37.
3. **0.1.32 TODOs:** `hint:<res-name>` (StatusEvent.Hint code field), remaining P2 follow-ups; **I5 hold 600-s run MUST run as the FIRST session after cold start** (see LAW below).
4. **Push** after user approval (0.1.14–0.1.31); **final documentation** (Kimi).

**Smoke transparency 0.1.31 — emulator audio LAW (mandatory for all hold runs):**
**Only the FIRST app session after cold start delivers sound** (every further session: app stream −84 dBFS; tinycap/host remain OK; audioserver restart + `emu mic` commands ineffective) → 92-s hold evidence on the emulator NOT delivered. The vc32 first session transcribed **for real** (93 characters, peak −17 dBFS, `cap_blocks` 280). Google onboarding plague cleaned up: `messaging`/`gms`/`calendar`/`quicksearchbox` now `disabled-user`. Final hold evidence chain via user 🐞 export (`fgs_stopped_by`, `status_res`).

**Retest transparency (F41, valid for all level findings):** In the F41 repro, `paplay` ran against the **WRONG PulseAudio server** (empty `XDG_RUNTIME_DIR`) → the app never got speech; **F41 level interpretations invalid**, the control-path findings (auto-stop absence, `ERR_SILENT` 3-s window, FGS leak) valid. Retest 0.1.28 with correct chain: peak −17 dBFS, transcription ≈ golden, auto-stop 2.1–2.5 s ✓, FGS abort 13 ms ✓.

## Pulse/alarm
- Runs `*/5 * * * *` and is rewritten on every status change. **Never turn it off.** When the project is done → to `*/30 * * * *` (rest state).
- Keep only ONE own clock active. `task-ibps9w`/`task-192pn2` belong to OTHER projects (never touch). Emulator: `<other-device>` = another project.

## Important paths
- Layout assets: `app/src/main/assets/layouts/*.json` (15 pieces).
- Status/language endonyms: `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt:45-61` (`LanguageCycle.ENDONYMS`).
- Popup geometry: `keyboard/LongPressMenu.kt`, `keyboard/KeyboardView.kt`.
- Fake audio harness (debug): `app/src/debug/java/de/voicekeyboard/audio/FakeWavAudioSource.kt`.
- Measured metrics: `docs/MESSWERTE_VERGLEICH.md`.
- V2 keyboard: spec `docs/DESIGN_TASTATUR_V2.md` (incl. §10 comparison 6/6 ✓), previews `docs/preview/keyboard_v2*.png/.gif`, render scripts `tools/render_keyboard_v2*.py`.
