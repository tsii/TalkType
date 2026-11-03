# Voice-Keyboard project status (working file for continuation)

**Status: 2026-11-03 07:50 · v0.1.39 / versionCode 40** (next build 40 /
0.1.39). Current details see `HANDOFF.md`, verification status see
`state/progress.md`, order see `reports/NEXT.md`.

Goal: Android IME for GrapheneOS (API 36), 15 languages, offline dictation
(Parakeet TDT 0.6B ONNX). The user's assignment: design = GPT 6.1 Sol, plan
= Kimi K3, code = GLM 5.3 (cortecs/glm-5.3), review of GLM work = **always**
DeepSeek V4 Pro 0813 (cortecs/deepseek-v4-pro-0813). The orchestrator does no
substantive work itself, only commissioning/verification. GLM sessions break
off after ~20–25 min → cut tasks small (≤ 4 files).

## Model IDs (verified present)
- openai-codex/gpt-6.1-sol (design + final emulator review)
- moonshot/kimi-k3 (plan + analyses)
- cortecs/glm-5.3 (coder)
- cortecs/deepseek-v4-pro-0813 (reviewer of the coder's work)

## Environment
JDK 17, Gradle 8.10.2, /opt/android-sdk (API 35/36, NDK 27), KVM present.
- `<device>` (own AVD `vk-api36`, API 36, IME installed). **Blocker:**
  it kills the IME process natively with a **SIGSEGV** while loading
  `libonnxruntime.so` (dlopen/call_constructors, `am_anr 0`, worker thread
  only) → **dictation/engine cannot be validated there**; key/gesture
  delivery works (`q` → `qq`). Audio bridge **OFF**
  (`tools/emulator_audio_down.sh`), peak = 0.
- `<device>` (a foreign AVD, API 35) — **do not touch**.
- Models/test WAVs on 5560 under `/data/local/tmp/…` (documented
  project-relatively in `docs/PLAN_AUDIO_UND_LAYOUT.md`).

## Delivery (status v0.1.31, delivered 2026-11-02 01:40)
| File | Bytes | SHA-256 (short) |
|---|---|---|
| `dist/voice-keyboard-0.1.31.apk` | 40,399,584 | `367165ea…` |
| `dist/voice-keyboard-0.1.31-diag-debug.apk` | 102,459,938 | `2ee37322…` |

Both APKs with **both ABIs**; `dist/` contains the 0.1.31 APKs +
`SHA256SUMS-0.1.31.txt` (0.1.30 rotated; aapt: versionCode 32 /
versionName 0.1.31).
**Version table:** 0.1.28 interim = F38–F44, 0.1.29 = +F45, 0.1.30 =
F46+F49–F52 (both rotated), **0.1.31 = F53 + 900-s hold limit + F50/error
path fixes** (user contract continuation). The diag build is bigger because
debug has `abiFilters += listOf("x86_64","arm64-v8a")`
(`app/build.gradle.kts:35`); the x86_64 `libonnxruntime.so` alone weighs
40.8 MB — the release build carries only arm64-v8a (line 33).

Download: `http://<download-server>/api/file?path=Voice-Keyboard/dist/<name>&download=1`
(`curl -I` → 200). Older APKs were removed from `dist/`.

**GitHub:** `tsii/Voice-Keyboard`, `main` = `dc3b4f0` (231 files, content
F13a-e + D11/F15); **v0.1.14 … v0.1.31 not yet pushed (rule 10, waiting for
user approval).** Signing password redacted; `keystore/`, `dist/`, `state/`,
`testdata/`, `.models-fp*` ignored via `.gitignore`; 11.8 GiB of `.git`
garbage removed. **Pushing further changes only after user approval (rule
10).**

## Test inventory (measured)
`app/build/test-results/testDebugUnitTest`: **513 tests / 0 failures** +
`app/build/test-results/testReleaseUnitTest`: **482 tests / 0 failures**
= **995 / 0** total (evidence: sum of the result XMLs, status 0.1.31).
Layout status: **15/15 layouts** (base+shift) with a **unified footer**
(Σ 13, no `cursor-*` keys, dot menu with `cellActions` — F24e, target design
`docs/LAYOUT_REQUIREMENTS.md` §2.3); globe language switch F25 wired
(`ime/VoiceKeyboardService.kt:979/:1022`); next build **33 / 0.1.32**.

## F27 stock geometry (done)
All **15 layouts** on ONE grid (letter = 1u = **90 px** at 1080 px width):
- **r0** = 10 letters + ⌫ 2u
- **r1** = letters + MARGIN (9-key rows 1.5u / 10-key 1.0u / 11-key 0.5u)
- **r2** = ⇧ 2u + letters + ⏎ 2u (+ MARGIN)
- **r3** = symbols 1.5 / language 1 / emoji 1 / **space 7** / **period 1** /
  **mic 1.5** (Σ 13, **NO** enter in the footer)
- Accents as **`alternates[0]`** instead of direct keys (**D26**); **ru-ru**
  digit row ⇧1 + 10 digits + ⏎1 (**D27**).

Device evidence (emulator, VK-HIT): `z`/`a`/`b` = **90 px** row jump each;
space **582 px** (7u); **period 872–955 px** directly before **mic 955–1080
px**.

## F28 dictation diagnosis (in 0.1.18/0.1.19)
Short list of facts:
- **F26a/b measurement fields** `abort_reason` + `hyp_last_buffer_s` (names
  in `debug/DebugInfoFormatter.kt`, drift check active).
- **Kimi analysis** `docs/ANALYSE_DIKTAT_ABBRUCH_2.md` on export #2
  (0.1.17, api 37): **no 1-s timer in the code**; the stop ran via a **real
  UP path at ~1.2 s** (arithmetically evidenced from `first_hyp_ms`/flush
  semantics); **−56.2 dBFS real** (measured on the raw capture, not a
  measurement error); `read()=0` loops **endlessly without an error** (code
  gap); `rtf 0.00` = **measurement blind spot** (the final decode does not
  count into `processingMs`).
- **F28a/a2 popup geometry:** r0 palette **above the key** (root overlay),
  footer via **space detection** instead of a hard-coded index, r3 top ≈
  **265 px** (0.1.16 position).
- **F28b1 stall watchdog** **3000 ms** without a capture block → a visible
  error instead of an "eternal dictation"
  (`ime/DictationCoordinator.kt`: `ERR_STALL`/`STALL_LIMIT_MS = 3000`); new
  🐞 fields **`cap_blocks`** / **`cap_stall_ms`**.
- **F28c FGS-ready handshake** before the `AudioRecord` start
  (`ime/VoiceKeyboardService.kt`: `fgsReady` condition), **notif gate from
  api 33** + `notif=-` bugfix.
- **F30 (in 0.1.23+):**
  `app/src/debug/java/de/voicekeyboard/audio/FakeWavAudioSource.kt`
  (debug-only, time-accurate 10-ms blocks from a WAV; scenarios
  continuous/silence/dropouts/`stopAfterMs`) + `FakeAudioScenarioTest` (8
  cases a–h, including case d = the user's case `stopAfterMs=1200` →
  `abort_reason final`, `audio_s ≈ 1.2`) — the user's case deterministically
  reproducible; engine decode on the emulator remains blocked by the
  x86_64 SIGSEGV.
- **F31 (in 0.1.23+):** silence detection — `lastLoudAtMs`,
  **`ERR_SILENT "silence"`** when blocks keep coming but no loud signal for
  > 3000 ms (`DictationCoordinator.kt:337-338`, `SILENCE_RMS = 0.01` ≈ −40
  dBFS `:100`), message `dictation_error_no_signal`, new 🐞 field
  **`loud_s`**.
- **F31b (in 0.1.24, review P1 fix):** `everLoud` latch
  (`DictationCoordinator.kt:218/300/330-332`, gate `:361-362`) — `ERR_SILENT`
  fires only while **no loud block has ever arrived** ⇒ no false aborts on
  natural speech pauses; residual risk documented as a note (a single loud
  crack permanently switches off the early diagnosis). Additionally:
  **`FGS_READY_TIMEOUT_MS` 2 s → 4 s** (`VoiceKeyboardService.kt:149`) and
  **layout gates on single values** (r1[0]=0.5u, exactly one "@" per layer,
  ru-ru without an @ key + `period.alternates[0]="@"`, r2[0] shift=1, space
  6.5/mic 2/period 1) — 3 new tests.
- **Review** `docs/REVIEW_F28_F31.md` (F28–F31): originally P0=0/P1=1/P2=3 →
  after F31b **P0=0 · P1=0 · P2=1** (open: popup clip/`isFooterRow` edge
  case) — verdict **pushable: YES**.

**Open points:** the 🐞 **export #3 is missing** (decisive for the
microphone/FGS cause, decision tree in
`docs/ANALYSE_DIKTAT_ABBRUCH_2.md` §9); **device check of the r3 geometry
open** (emulator AVD evidenced); **the microphone question to the user
open** (hold duration with export #2, microphone in other apps —
`ANALYSE_…` §10); P2 note: popup clip/`isFooterRow` edge case
(`REVIEW_F28_F31.md`).

## F35 "Weg D" — corpus cadence tests (green, in 0.1.27)
- **`KorpusFixture`** (30 s/60 s from `testdata/de_smoke.wav` + `de_part1/2`
  + `de_pause`, 0.3-s silence loops) + **stub engine with a constant 150-ms
  latency** (injectable clock) + **`KorpusTaktTest` with 7 assertions**
  (`app/src/test/java/de/voicekeyboard/dictation/`): `hyp_count` grows,
  `hyp_gap_ms < 4000`, `buffer_s == audio_s`, `decode_ms_max < 1000`,
  `cap_blocks`/`loud_s > 0`, `abort_reason` only at the end, no
  `stall`/`silence`.
- **Findings:** (1) **watchdog clock race in the 60-s case** — fixed: the
  stub latency now pumps its **own clock**; (2) the **controller clock is
  NOT injectable in the coordinator** → **F37 follow-up** (needed for path B,
  see `docs/KORPUS_DIKTAT_TESTPLAN.md` §5).
- **Review F35:** P0/P1 = 0, P2 = 2 — push recommendation **YES**.

## F36 — V2 keyboard (green, in 0.1.27)
- **1u = 108 px** (previously 90); **r0–r2 Σ 10u** (r0 = 10 letters edgeless
  without ⌫, r1 = 0.5 + 9×0.9444 + @1.0, r2 = ⇧1.8889 + 7×0.8889 +
  **⌫0.8889** + ⏎1.0 — ⌫ between M and ⏎); the footer stays Σ 13u.
  Surfaces/gaps/radius: 2-px gap (`KEY_FUGE_DP`), radius 10 px, theme colors
  as `docs/DESIGN_TASTATUR_V2.md` §7; **⌫ glyph = a pure left arrow with a
  base** (path icons ⌫/⇧/⏎).
- **Special cases (rendered separately,
  `docs/preview/keyboard_v2_impl_sonderfaelle.png`):** fr-fr = the de
  pattern (0.5 + 9×0.9444 + @1.0, p in r0), cs-cz r1 = 0.5 + 10×0.85 + @1.0
  and r2 = 204 + 6×112 + ⌫96 + ⏎108 (center 540), ru-ru 11-key rows 11×96 +
  2×12 px and **r2 stays D27/Σ 12** (⇧1 + 10 + ⏎1, ⌫ only on the symbols
  layer).
- **Implementation vs. spec check: 6/6 ✓**
  (`docs/DESIGN_TASTATUR_V2.md` §10, evidence `layouts/*.json` +
  `keyboard/KeyboardTheme.kt:82-86/131-155` + `KeyboardView.kt`); previews
  `docs/preview/keyboard_v2_impl_{hell,dunkel}.png` + `.gif` (5 frames).
- **Review F36:** P0/P1 = 0, P2 = 1 — push recommendation **YES**.

## F46 + F49–F52 (in 0.1.30; plan `docs/PLAN_V0130.md` slice A + §6)
- **F46:** ⇧ up arrow glyph in (V9).
- **F49 (A1) = auto-stop hold gate:** `autoStopSuppressed` — during **hold**
  the F43 auto-stop (2.5 s) is **suppressed** (hold = record continuously,
  **user contract**); the **toggle path stays "pause=end"** (2.5 s). Oracle
  proof: `DictationHoldGateTest`
  (`app/src/test/java/de/voicekeyboard/ime/`).
- **F50 (A2) = start serialization:** startJob cancel, supersede,
  `lastStartMs` protection (no double start); **F50a:** UP hint
  "Loslassen vorgemerkt" (`dictation_start_pending_stop`); F49 wiring
  `holdGestureActive`.
- **F51 (K2):** 4 strings into `strings.xml` (including the FGS title).
- **F52 (A3) = `status_res`:** StatusKind enum + 🐞 export field +
  `status_res` in the formatter; hint abbreviation = **P2 follow-up** for
  0.1.31.
- **Review A1–A3:** **P0 = 0 / P1 = 0, P2 = 2** (follow-ups for 0.1.31:
  defensive `holdGestureActive` reset on `onAborted`; `hint:<res-name>`);
  push recommendation **YES**.
- **Smoke transparency (0.1.30):** the emulator app microphone path is
  unstable after ~2 sessions (tinycap/host OK, app stream −84 dBFS) → the
  **182-s hold proof was not delivered on the emulator**; mini sessions with
  **real transcription** (66 characters, 251 blocks) green; final
  confirmation via the user's 🐞 export (`status_res`,
  `fgs_stopped_by=up` when holding).

## F53 + hold upper limit + error path fixes (in 0.1.31; user contract
continuation)
- **F53 = intermediate segment finalization:** `segmentFinalizeMs = 35,000`
  **live** (`dictation/DictationController.kt`); α = **38.5 ms/s** curve ⇒
  decode window ≤ 35 s ⇒ hypothesis latency ≤ **~1.4 s**, **no RSS/time
  exponential**; **§5.1-conformant**: `whole_buffer` is merely **segmented**
  (no violation of the blocked windowed-streaming question); chain golden:
  **`SegmentGoldenTest` "alpha. bravo. charlie." exact**;
  (b) forced-cut trade-off = **exactly one space** (documented in the KDoc).
- **Hold upper limit 900 s** for **hold + toggle** with a **one-time warning
  hint from 780 s** (`holdWarnDue` in the coordinator; **`HoldWarnTest`
  5/0**).
- **F50 fix:** the `ConsumerFaultTest` load flakiness fixed **at the root** —
  the poll on the null sentinel was a no-op → **final-state poll**; 2× full
  suite green.
- **finalizeSegment error path:** **transient ⇒ discard the request, the
  session lives** (only OOM is hard) — review P1 fixed. **B2b:**
  `onAborted` resets (the F49 P2 follow-up from 0.1.30).
- **Reviews A/B1/B2: P0/P1 = 0** — push recommendation **YES**.
- **Smoke transparency (0.1.31) — emulator audio LAW:** **only the FIRST
  app session after a cold start delivers sound** (every subsequent one:
  app stream −84 dBFS; tinycap/host OK; audioserver restart/`emu mic`
  ineffective) → the **92-s hold proof was not delivered on the emulator**;
  the vc32 first session transcribed **real audio** (93 characters, peak
  −17 dBFS, `cap_blocks` 280). The Google onboarding plague cleaned up:
  `messaging`/`gms`/`calendar`/`quicksearchbox` now `disabled-user`. Final
  hold proof via the user's 🐞 export (`fgs_stopped_by`, `status_res`).

## F38–F45 (in 0.1.28 interim / 0.1.29)
- **F38–F44 (0.1.28, interim, dist-rotated):** dictation debugging packages
  around the auto-stop diagnosis (including **F42 = 6 new 🐞 fields**:
  `had_output`, `output_chars`, `first_loud_ms`, `first3s_loud_blocks`,
  `first3s_peak_dbfs`, `fgs_stopped_by` — catalog in
  `docs/DEBUG_INFO_FIELDS.md`; sanitizer maps `:`→`=`,
  `DebugInfoFormatter.kt:250`; export = header + **42 fields = 43 lines**,
  drift check `check(lines.size == KEYS.size + 1)`); **F43 = auto-stop
  after ~2.5 s of speech pause** (`TRAILING_SILENCE_MS = 2500`,
  `ime/DictationCoordinator.kt:97/436-439`, c-gate: **only on streaming
  silence** `capStallMs < TRAILING_SILENCE_MS` — "silence ≠ stall", the
  `ERR_STALL` contract F28b1 intact; a documented refinement, see
  `KONZEPT.md`); **F44 = `fgs_stopped_by`** cause codes.
- **F45 (0.1.29):** **FGS leak on the auto-stop path fixed** —
  `stopFgs("autostop")` in the onStopped finale
  (`VoiceKeyboardService.kt:1356-1360`; idempotent, does not overwrite an
  existing reason).
- **Concept fidelity (F43):** the auto-stop after ~2.5 s of speech pause is
  a **refinement explicitly requested by the user** (documented in
  `docs/KONZEPT.md` as "Dokumentierte Präzisierung F43", the other KONZEPT
  contents unchanged).

## Finished artifacts in docs/
- `KONZEPT.md` (the user's specification, **do not change**)
- `DESIGN.md`, `IMPLEMENTATION_PLAN.md` (M0–M8), `TECH_FACTS.md`
- `ASR_CONTRACT.md` (rev 3: tensor contract, TDT greedy, token ID goldens,
  §4.4 → `min_audio_s = 2.0 s`)
- `LAYOUT_REQUIREMENTS.md` (rev 3 + rule 4a symbols-layer return path),
  `LAYOUT_SCHEMA_REF.md`, `LAYOUT_AUDIT.md`
- `COMMIT_RULE_DECISION.md`, `COMMIT_RULE_DECISION_2.md` (E8, rev 2)
- `SETUP_UI_DECISIONS.md` (E1–E4), `ERROR_UX_DECISIONS.md` (E5–E7),
  `LANGUAGE_EMOJI_DECISIONS.md` (E9/E10)
- `DEBUG_INFO_FIELDS.md` (🐞 export, 29 fields), `MULTILANG_PLAN.md`,
  `PLAN_AUDIO_UND_LAYOUT.md`
- `DESIGN_KEY_MULTIPLEX.md` (A19 rev 2, AOSP evidence), `DESIGN_KEY_POPUP.md`
  (D12 menu), `DESIGN_DEDE_ZIELBILD.md` (F23 data template),
  `LAYOUT_REQUIREMENTS.md` §2.2 (R8 exception de-de)
- `GPU_ACCELERATION_ANALYSIS.md`, `RUNTIME_SWITCH_NPU_ASSESSMENT.md`,
  `NPU_LITERT_FEASIBILITY.md`, `MODEL_VARIANT_SWITCH_MEMO.md`,
  `VARIANT_B_QUALITY.md`, `ASR_MODEL_SURVEY_DE.md`
- `DICTATION_RELIABILITY_PLAN.md`, `ERROR_STOP_PATH.md`,
  `STREAMING_FREEZE_ANALYSE.md`
- `CONTINGENCY_PLAN.md` (§12/§13 operating rules), `MODEL_SHA256.txt`
- Reviews: `REVIEW_STAGE1…6` + `_FIXES`, `REVIEW_STAGE4B/4C`,
  `REVIEW_STAGE2B1`, `FINAL_REVIEW_M7`,
  `REVIEW_F17_MESSBARKEIT.md` (P0=0/P1=0/P2=2 fixed),
  `REVIEW_F21_DECODE_PACE.md` (0/0/1, P2 fixed), `REVIEW_F22_POPUP.md`
  (0/0/0), `REVIEW_F23_HINT_LAYOUT.md` (0/0/1; **the only P2 = FALSE
  POSITIVE, checked and rejected** — the base/shift gate is present in
  `KeyboardView.kt:393`)
- Tools: `tools/reference_decode.py`, `tools/make_cut_windows.py`,
  `tools/measure_min_audio.py`, `tools/arm_litert_reference_decode.py`,
  `tools/normalize_symbol_shift.py`, `tools/emulator_audio_down.sh`

## Verified code status (app/src/main/java/de/voicekeyboard/)
- `ime/`: VoiceKeyboardService, ImeRootView, **StatusBarText**,
  **StatusRouting**, **MicToggle**, **DictationOutputCounter**,
  DictationCoordinator, DictationPermissions, SetupActivity (a real setup UI
  with download/language selection/permissions)
- `keyboard/`: KeyboardView (layers base↔symbols↔symbolsShift,
  accessibility, pointer capture, **long-press menu F22**), KeyboardModel,
  **KeySurface** (a pure seam for key press/caps optics, D11/F15),
  **LongPressMenu** (F22, `MAX_CELLS = 8`), KeyboardTheme, KeyboardHeight,
  EmojiPanelView
- `layouts/`: LayoutAsset, LayoutParser, LayoutRepository (asset scan),
  CaseMapper
- `asr/`: ParakeetAsrEngine, OnnxSessions, TdtGreedy, Hypothesis (the
  `AsrEngine` interface), AsrThreadConfig, TokenBudget, Vocabulary
- `commit/`: LocalAgreement, CommitPolicy (E8 hysteresis)
- `editor/`: DeltaTyper (rollback W3c), InputConnectionSink, OwnershipTracker
- `dictation/`: DictationController (contains `StopOutcome`), **DecodePace**
  (F21 cadence), DictationState
- `model/`: ModelStore, ModelManifest, ModelDownloader, DownloadController,
  ModelVariant, ModelDownloadService (dataSync FGS), ModelError
- `capture/`: CaptureForegroundService (`microphone`)
- `debug/`: DebugInfoFormatter (29 KEYS; F21 fields
  `decode_pace_ms`/`decode_skips`, export = header + 29 = 30 lines),
  DebugInfoCollector, LevelPeak (RMS/dBFS)

## Open points (status 2026-10-30)
| # | Content | Status |
|---|---|---|
| C2 | **engine backpressure** (`rtf 0.44`, first hypothesis 5,339 ms) as the explanation for missing text after 10–15 s of continuous dictation; cause: `window()` = `PcmWindow(pcm.copyOf(totalSamples), sampleRateHz)` decodes the complete audio so far on every tick → quadratic overall (`dictation/DictationController.kt` `window()`) | **mitigated, cause open** — the F21 cadence is IN as of 0.1.15 (`dictation/DecodePace.kt`, gate only partial `DictationController.kt:255-259`, finale free `:333`, `decodePacing=true` `DictationCoordinator.kt:130`); effect to be measured on the phone (`decode_skips`/`decode_pace_ms`); the real countermeasure (windowed streaming) waits for user approval (`KONZEPT` §5.1) |
| — | M7 screenshots (caps, pressed key, emoji panel, second opening, light + dark) | **done** (8 acceptance cases, light + dark; images only under `/tmp/m7_*.png`, deliberately not in the repo) |
| — | fp16 variant | **checked and rejected** (`docs/FP16_ABWEGUNG.md`): 1.90× size (1,275,463,785 B), quality **identical** to int8 (same token IDs), compute RTF **1.30×** and start time **2.13×** slower, maxRSS during loading **924 → 3,838 MB**; moreover the existing `.models-fp16/` files are **not loadable** (3 cast nodes in `pre_encode`, type conflict) |
| — | English/typing across the language boundary | **user wish, deferred** → `docs/MULTILANG_PLAN.md` |
| — | NPU/GPU | finished: unreachable without Google services (three analyses) |
| — | Variant B (Arm/LiteRT) | **rejected** (50.2 % word identity, language-unstable) |

## Clarified (do not investigate again)
- The microphone level on the phone is **fine** (`peak_dbfs −22.0`,
  `peak_pct 7`, `route intern`, `src VOICE_RECOGNITION`) → the AudioSource
  experiment is moot.
- No sample loss with a healthy consumer (`Channel.UNLIMITED`).
- No suspicion of a `maxDuration` shutdown.

## History (superseded)
- v0.1.0 … v0.1.14 were earlier deliveries; v0.1.3 (40,242,852 B,
  versionCode 4) was the documented status until 2026-10-28 — **replaced by
  v0.1.15**.
- The test inventories 157 / 204 / 213 / 217 / 279 / 373 / 353 / 726 / 377 /
  357 / 734 belonged to earlier stages — **today 513 debug / 482 release =
  995/0** (status 0.1.31).
- `SetupActivity` was a placeholder until stage 5 — **today a real setup
  UI**.
- `LAYOUT_SCHEMA_REF.md` was considered "not yet reviewed" — **meanwhile
  audited** (`LAYOUT_AUDIT.md`).
