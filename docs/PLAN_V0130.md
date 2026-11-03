# PLAN V0130 — immediate release v0.1.30 + infrastructure week

**Planner status:** 2026-11-01 · **Author:** planner (read-only; no code
changed, no git)
**Basis:** `docs/DEBUGGER_FABLE_BERATUNG_2026-11-01.md` (debugging report,
340 lines) — **every core statement was verified at the cited code locations
before being taken into this plan** (evidence in §0).
**Base:** v0.1.29 / versionCode 30 · tests debug **483/0**, release **452/0**
(935/0).
**Goal:** a new version with a download link **as soon as possible** (user
directive), without shipping the C2 symptoms unsolved.

---

## 0. Verified fact base (evidence, all re-read in the worktree)

| # | Fact | Evidence (file:line) |
|---|---|---|
| V1 | **F43 exists exactly as described:** `TRAILING_SILENCE_MS = 2500`; auto-stop **only** on streaming silence (`capStallMs < 2500`), firing via the regular `stop()` (finale, no ERR_*). | `ime/DictationCoordinator.kt:97/436–453` |
| V2 | **There is NO hypothesis-triggered stop path:** in the coordinator `stop()` is called only from the F43 watchdog condition; hypothesis content flows into no stop decision anywhere. | `ime/DictationCoordinator.kt:359–454` (captureLoop read completely) |
| V3 | **Controller clock:** `DictationController` **already has** an injectable `clock` parameter (default `System.nanoTime()`), the coordinator does **not** pass it through → `lastDecodeMs`/DecodePace run on real time, watchdogs on `nowMs`. **Fable impulse corrected** (see §5, deviation 1): no new API needed, only pass-through. | `dictation/DictationController.kt:80,137,179–193` · `ime/DictationCoordinator.kt:172` |
| V4 | **Start race symptom 1 is evidenced in the code:** `onMicDown` guard `if (startingUp) return` only until the 15-s deadline; after that the first coroutine keeps running (no cancel of `engine.load()`), a second DOWN starts a second coroutine and **resets `startAbandoned = false`** → the first (old) session can keep running as "valid" (zombie), the second `start()` fails at `active.compareAndSet`, **and the second run overwrites `lastStartMs` in `finally`** (the export's `start_ms` can be a collision value). | `ime/VoiceKeyboardService.kt:139,407–411,447–455,493–510` · `ime/DictationCoordinator.kt:208–215,296–297` |
| V5 | **UP in the STARTING window is a silent flag** (`cancelStartRequested = true`, no UI feedback; making it up later as KEEP_RUNNING is the intended P0 finding 2(a) design). | `ime/VoiceKeyboardService.kt:572–583` |
| V6 | **Two start gestures exist:** status bar = hold (DOWN starts / UP stops, `VoiceKeyboardService.kt:321–327`), footer mic = **toggle** (tap starts/stops, `micToggleAction`, `:1155–1161`). Today F43 fires **mode-independently** on every pause ≥ 2.5 s — even while the finger is down. | `ime/VoiceKeyboardService.kt` as cited |
| V7 | **Symptom 2:** every status row path runs through `getString(...)`/`mapVisibleError` (the fallback "Audio start failed" (translated from German "Audio-Start fehlgeschlagen") is **translated** — the raw-code suspicion applies to the four candidates K1–K4). Hardcoded non-resource strings (K2) evidenced (German originals at review time, translated): `"Processing…"`, `"Ready"`, `"Dictation… ("` (`updateStatus`), `"Diagnostics copied — share dialog opened"` (`:637`), FGS title `"Dictation running"` (`capture/CaptureForegroundService.kt:102`). `mapErrorTextRes` already exists as a pure function (`:156`). | `ime/VoiceKeyboardService.kt:969–1001,1040–1112,637` · `capture/CaptureForegroundService.kt:102` |
| V8 | **The export has no `status_res`** and no session ring buffer; `mic_rel`, `status_shown`, `fgs_stopped_by` exist; the collector takes service parameters (a pattern for new fields exists). | `debug/DebugInfoFormatter.kt:110–160,217–218` · `debug/DebugInfoCollector.kt:42–54,121–122` |
| V9 | **F46 (⇧ = up arrow) is code-ready** (stroked path shaft+head+base bar, equal-height gate with ⌫/⏎) — only a visual check in the build needed. | `keyboard/KeyboardView.kt:693–740` |
| V10 | **Infrastructure is ready:** Robolectric 4.17 in `testImplementation`; `kotlinx-coroutines-test` 1.10.2 **in the Gradle cache** (usable offline); a JVM auto-stop test scaffold with a virtual clock exists (`DictationAutoStopTest` with `VClock` + scripted fake source). | `app/build.gradle.kts:134` · `~/.gradle/caches` · `ime/DictationAutoStopTest.kt` |
| V11 | **Safety net if the auto-stop is dropped in hold mode:** `MAX_DURATION_MS = 300_000` (300 s) ends every session in a controlled way; the F31 silent and F28b1 stall watchdogs remain untouched. | `audio/AudioRecordSource.kt:38,108` |
| V12 | **Windowed streaming is BLOCKED** (KONZEPT §5.1: documented defects, release up to the user); F43 is documented as "a refinement explicitly requested by the user" — globally raising the threshold would tip over the desired "pause = end" flow. | `docs/KONZEPT.md` §5.1, §506–514 |

**Not evidenced / OPTION (not in slice A):** H3b (level right at the
threshold), H3c/H3d/H3e, K1 as the actual cause (the diagnostics suffix is
debug-only and intended — whether the user saw *it* is clarified by
`status_res`), K4 (system toast — excludable only by asking), cold start >
15 s on the device (plausible, but `start_ms` cold is not yet available).
**The user's export #3** (0.1.29-diag, F42 fields) is not yet available — all
F43/symptom-1 assignments remain high-ranked hypotheses until it is read.

---

## 1. Slice A — release v0.1.30 (buildable today)

**Scope:** 3 coder tasks (A1 ∥ A2, A3 after the A2 merge), 1 review per task
(DeepSeek V4 Pro), 1 build + emulator smoke + delivery. **F46 needs no coder
task** (V9) — only a visual check in the build.

### A1 — F43 hold gate + F49 minimal repro (JVM)

- **ID:** A1 / feature numbers F49 (gate) + F49a (repro test) — called "F49"
  below, since F47/F48 are assigned to A2/A3 (number conflict with Fable's
  identifiers deliberate: see the "Alias" line).
  *Alias to Fable: symptom-3 hardening + §2.1 repro step 3.*
- **Goal:** the F43 auto-stop becomes **mode-dependent**: it stays
  unchanged-active for toggle sessions ("pause = end", 2.5 s — V12), but is
  **suppressed as long as the recording runs via the hold gesture and the
  finger is still down** (the hold gesture signals "I'm not done yet"; Fable
  §6.1). With that the toggle flow does not tip over (V12), and sentence
  pauses during hold dictation no longer end the session.
- **Mechanism (binding, extending the §6 hold contract):** new coordinator
  parameter `holdGestureActive: () -> Boolean = { false }`. **Two**
  silence-based ends are exempted:
  1. **F43 auto-stop** (condition in `captureLoop` extended with
     `!holdGestureActive()`) — the toggle "pause = end" (2.5 s) stays (V12).
  2. **F31/ERR_SILENT** (3-s start window) is also exempted — otherwise the
     hold contract (§6.1) violates the case "finger down, user thinks > 3 s
     before starting to speak". ERR_STALL (dead source) stays **untouched**
     as a system event (FakeAudioScenarioTest case e tests the default path
     — tests run without the gate, default `false`, stay green; case c
     silence≠stall contract V1 untouched).
  Default `false` ⇒ **all existing tests stay green without changes**.
- **Files (exactly, 4):**
  1. `app/src/main/java/de/voicekeyboard/ime/DictationCoordinator.kt`
     (~15 lines: param + gate + comment)
  2. `app/src/test/java/de/voicekeyboard/ime/DictationAutoStopTest.kt`
     (extension)
  3. `app/src/test/java/de/voicekeyboard/ime/DictationHoldGateTest.kt`
     (new, minimal repro F49a)
  4. — (reserve; only if `FakeAudioScenarioTest` must be touched — goal: it
     must not be)
- **Minimal repro test (F49a, permitted release share):** JVM, `VClock` +
  scripted fake source following the `DictationAutoStopTest` pattern: corpus
  "speech—2.0 s pause—speech—3.0 s pause—speech" × {gate=false, gate=true}.
  Assertions: gate=false → auto-stop at the 3-s pause (oracle), the finale
  runs (the `StopOutcome` equivalent: the session ends via the regular stop,
  `lastError == null`); gate=true → **no** auto-stop, the session runs to the
  source end; **hold + continuous silence > 3 s → no ERR_SILENT, the session
  lives** (contract case §6.1); in all runs: ownership never lost,
  `capStallMs` contract untouched. **No emulator/long-run parts** — those
  stay INFRA (slice B).
- **Acceptance:** debug tests ≥ **490/0** (483 + at least 7 new); existing
  auto-stop/stall tests unchanged green; exactly **1** Gradle run
  (`ANDROID_HOME=/opt/android-sdk ./gradlew --offline testDebug`).
- **Risk:** low — additive parameter, default behavior identical. *Rollback:*
  remove the param (a single commit revert, no data migration).
- **Review need:** yes (DeepSeek, P0/P1 must be 0) — focus: gate race (can
  the lambda switch while firing? answer: irrelevant, a one-way decision per
  block), comment completeness.

### A2 — service: start serialization (F48) + UP feedback + gate wiring +
string audit (K2)

- **ID:** A2 / feature numbers **F50** (start serialization = Fable
  symptom-1 fix), **F50a** (UP feedback in the STARTING window), F49 wiring,
  **F51** (K2 string audit).
  *Alias to Fable: §2.2 fix ("20-line patch") + §2.3 K2.*
- **Goal (F50):** invariant "at any time at most one start coroutine runs; an
  expired one is cancelled or its result discarded" (Fable §3.4) — fixes the
  verified race V4: no zombie mic after deadline expiry, no double start,
  `lastStartMs` is written by the **first** start of a session (Fable §5.3
  minor item pulled forward, 3 lines).
- **Mechanism (binding):**
  1. Keep a `startJob: Job?` in the service; the deadline timer (15 s)
     **cancels** the start job instead of only setting flags; in the start
     coroutine after `engine.load()`/before `coordinator.start()` an
     activity check (cooperative cancel), so an expired cold start does not
     produce a zombie dictation.
  2. `onMicDown`: `startAbandoned = false` **is dropped** (only the deadline
     owner sets it); a second DOWN after deadline expiry: old
     `startJob.cancel()` + `coordinator?.abort("start_superseded")` +
     `stopFgs(...)`, then a fresh start.
  3. `lastStartMs`: write only if the previous value was 0 (the reset in
     `start()` already exists — it is zeroed per recording there; a collision
     run must not overwrite).
  4. **F49 wiring:** a `holdGestureDown` flag (set in the status bar DOWN
     handler `:321`, cleared in UP/CANCEL/`onDictationStopped`);
     `buildCoordinator()` passes `holdGestureActive = { holdGestureDown }`
     (only hold gestures set the flag; toggle taps pass through DOWN/UP in
     pairs, the flag stays false).
  5. **F50a (UP feedback):** on UP during `startingUp` →
     `postEvent(StatusEvent.Hint(getString(
     R.string.dictation_start_pending_stop)))` — the hint runs into the
     transient channel per StatusRouting, overwriting the "Starte…" line
     with "Loslassen vorgemerkt…". Fixes the confusion impression "UP
     ignored" (C2 symptom 1) without changing the KEEP_RUNNING design (P0
     finding 2(a)).
  6. **F51 (K2 string audit, evidenced V7):** `"Verarbeite…"`, `"Bereit"`,
     `"Diagnostics copied — share dialog opened"` (translated from German), FGS title `"Dictation running"`
     (translated from German) → `strings.xml` resources (`getString`). Only `values/` present
     (one locale) — no translation effort. K1 (diagnostics suffix) **stays**
     (debug-only, intended; clarity comes via `status_res`); K3/K4 are
     OPTION (§0) and do not go into this task.
- **Files (exactly, 4):**
  1. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`
     (~90 lines net)
  2. `app/src/main/java/de/voicekeyboard/capture/CaptureForegroundService.kt`
     (~3 lines)
  3. `app/src/main/res/values/strings.xml` (+4 strings)
  4. `app/src/test/java/de/voicekeyboard/ime/StartGateTest.kt` (new — pure
     decision logic "deadline expired? / supersede? / flag transitions" as
     an extracted function `micStartGate(...)` in the pattern
     `micToggleAction`/`micStartFinishedAction`, JVM-testable without
     Robolectric; the Robolectric race proof follows in slice B)
- **Acceptance:** debug tests ≥ **492/0** (at least +3: StartGate scenarios
  deadline-expired/supersede/UP-during-start); `StatusRoutingTest`,
  `MicToggleTest`, `StatusBarTextTest` unchanged green; ≤2 Gradle runs
  (`testDebug`, possibly a follow-up).
- **Risk:** medium — serialization touches the cold-start path (symptom-1
  core). Mitigation: the invariant as a pure function + test; review with a
  focus on cancel/abort order and FGS double stop. *Rollback:* the task is
  isolately revertible (no schema/format impact).
- **Review need:** **high** (mandatory review, DeepSeek, P0/P1=0 before the
  build) — this is the most delicate task of the release.

### A3 — export: `status_res` field (F47)

- **ID:** A3 / feature number **F52**. *Alias to Fable: §5.1 `status_res` +
  §2.3 "errorTextRes pure".*
- **Goal:** the next 🐞 export itself says which status row text was showing
  (symptom 2 finally clarifiable): `status_res` = code of the last status
  text shown — `starting` / `recording` / `finalizing` / `idle` /
  `hint:<res-name>` / `error:<res-name>` / `diag` (dictation line with debug
  suffix) / `raw` (must never occur — assertion in the test). Rule 9 stays
  intact: resource **names** only, never contents (Fable §5 principle).
- **Mechanism (binding):** extend `StatusBarText.SelectResult` with
  `kind: StatusKind` (enum) (pure function, JVM-testable —
  `mapErrorTextRes` pattern V7); the service stores the `kind` after every
  `updateStatus` (2–3 lines, seam in `VoiceKeyboardService.kt`) and passes
  it through to `DebugInfoCollector.collect(statusRes = …)`; the formatter
  appends `status_res` (field list + line, pattern `status_shown`).
- **Files (exactly, 3 + seam):**
  1. `app/src/main/java/de/voicekeyboard/ime/StatusBarText.kt` (~20 lines)
  2. `app/src/main/java/de/voicekeyboard/debug/DebugInfoCollector.kt`
     (+1 parameter, ~5 lines)
  3. `app/src/main/java/de/voicekeyboard/debug/DebugInfoFormatter.kt`
     (field list + line, ~8 lines)
  4. Seam (2–3 lines):
     `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt` —
     **that is why A3 starts only after the A2 merge** (same file,
     disjoint places).
  - Tests: `StatusBarTextTest.kt` (+kind assertions),
    `DebugInfoFormatterTest.kt` (+field).
- **Acceptance:** debug tests ≥ **495/0** (at least +3); export header+43=44
  lines (debug format test updated); ≤2 Gradle runs.
- **Risk:** low — additive, format-tested. *Rollback:* drop the field
  (formatter test back).
- **Review need:** yes (standard, DeepSeek) — focus: no text content in the
  field (rule 9).

### F46 — ⇧ up arrow: no task, only a build check

Code-ready (V9). In the build's emulator smoke take a **light/dark
screenshot** and check it against `docs/DESIGN_TASTATUR_V2.md` §8
(equal-height gate with ⌫/⏎, stroked style). Deviation → a mini follow-up
task on `KeyboardView.kt` only.

### Review, build, delivery (orchestrator)

1. **Review per task** by DeepSeek V4 Pro (roles per HANDOFF); P0/P1=0 is a
   build precondition.
2. **Merge order:** A1 ∥ A2 → A3. Before the build: a full run of `testDebug`
   + `testRelease`.
3. **Build:** `app/build.gradle.kts`: `versionCode = 31`, `versionName =
   "0.1.30"`;
   `ANDROID_HOME=/opt/android-sdk ./gradlew --offline assembleDebug
   assembleRelease`. Target: debug ≈ 495–501/0, release ≈ 452/0 (+ possibly
   shared tests).
4. **Emulator smoke** (exclusive, **<device>**, never <other-device> = a foreign
   project, never parallel to the build; `tools/emulator_audio_up.sh`, F41
   precondition: the PulseAudio sink visible):
   start, short dictation (toggle: auto-stop on pause ✓), hold dictation
   with a 3-s sentence pause (**no abort ✓**), **hold dictation 180–240 s
   with 2× 3-s sentence pauses → NO abort, text grows beyond the pauses
   (`output_chars`), finale after UP, export `fgs_stopped_by=up`,
   `mic_rel=holdup`** (§6.4; deliberately staying below 300 s — the v0.1.30
   upper limit),
   UP during cold start (hint "vorgemerkt" ✓), F46 screenshot, 🐞 export with
   `status_res`.
5. **Delivery:** dist rotation, download link following the pattern
   documented in HANDOFF
   (`<download-server>/api/file?path=Voice-Keyboard/dist/<name>&download=1`);
   update `docs/SESSION_LOG.md`, `HANDOFF.md`, `docs/PROJECT_STATE.md`.
   **No push** without user approval (rule 10).

**Slice A test balance (expectation):** debug 483 → **~496–502/0**, release
452/0 (+0–2) — 3 tasks, each ≤4 files, each ≤2 Gradle runs, A1/A2
parallelizable.

**Contract note (E1, binding):** slice A fulfills the hold product contract
(§6.1) except the upper limit (v0.1.30 stays at 300 s — contractually
allowed, a UX weakness; raising it §6.2 in v0.1.31). All **silence-based**
ends (F43 + F31/ERR_SILENT) are exempted for hold sessions.

---

## 2. User decisions (before the build resp. before slice B)

| # | Decision | Option A | Option B | Recommendation |
|---|---|---|---|---|
| **E1** | **F43 semantics — BINDINGLY DECIDED (user, 2026-11-01):** product contract "continuous dictation" (§6.1) | implementation as a hold gate (A1/A2): toggle keeps "pause = end" (2.5 s); hold ends only by releasing/abort/system event/upper limit (§6). | (rejected) raise the threshold globally to 4–6 s — it would tip over the toggle flow. | **Decided as A** — no more veto window; fine-tuning of the toggle threshold (2.5 → 4–6 s) remains a data request from I3/I5, not a release blocker. |
| **E2** | **windowed streaming release** (KONZEPT §5.1) | keep blocked until the cost curve (I3) + 6 long runs (I5) quantify the O(N²) thesis. | release immediately and rework. | **A** — Fable §6.2 is right: before measurement every discussion is speculation; the documented defects (§5.1) are serious. |
| **E3** | **preload/warm-up strategy** (symptom "only attempt 3 delivers text", symptom-1 cold start) | no preload in 0.1.30; experiment as a debug flag in the infrastructure week (I4) + quantify the `dumpsys meminfo` cost (~860 MB native); `engine_loads`/ring buffer make attempts 1–3 visible. | product preload immediately in 0.1.30. | **A** — B blows up the immediate release (effort + memory/OOM risk with no measurement basis). |

---

## 3. Slice B — infrastructure week (after release v0.1.30, prioritized)

Order roughly per Fable §7, adjusted for the corrections from §5. Everything
parallelizable is marked; emulator runs are exclusive and never parallel to
the build.

| Prio | ID | Work | Files/places | Effort | Dependency |
|---|---|---|---|---|---|
| 1 | I1 | **F37 broad** (Fable §1.2, **corrected** see deviation 1): a) pass `controllerClock` through to `DictationController` (the param already exists, V3 — mind the unit ns: `nowMs()*1_000_000`); b) `captureLoop` over an injectable `threadFactory`/synchronizable; c) service: `ioDispatcher`/`mainDispatcher`/`elapsedClock`/`START_DEADLINE_MS`/`FGS_READY_TIMEOUT_MS` as `internal var` with today's defaults; d) engine fake with latency-controlled `load()`/`transcribe()` on the same virtual clock (pattern F35). | `DictationCoordinator.kt`, `DictationController.kt` (only indirectly affected), `VoiceKeyboardService.kt`, `FakeWavAudioSource.kt`, test fakes | 0.5–1 day | after the release; blocks I2/I4 |
| 2 | I2 | **Export v2** (Fable §5.1/§5.2): `start_outcome`, `start_phase_ms`, `start_attempts`, `up_during_start`, `stop_cause`, `autostop_gap_ms`, `max_pause_ms`, `pause_gt1s/2s`, `loud_ratio`, `rms_p50/p90_dbfs`, `owner_lost_at_s`, `final_ms`, `final_chars_delta`, `engine_loads`, `coord_gen` + **session ring buffer (last 5)** + order (session data at the top). | `DebugInfoFormatter.kt`, `DebugInfoCollector.kt`, `DictationCoordinator.kt`, `VoiceKeyboardService.kt` + tests | 0.5–1 day | ∥ possible with I1 (define the contract first) |
| 3 | I3 | **S0 tools:** piper-tts in `.venv-asr` + voice `de_DE-thorsten-medium`; `tools/make_korpus.py` (sentence list × pause grid 1.5–4.0 s × level −17/−30/−38 dBFS × 60/120/180 s, sidecar JSON oracle); `tools/watchdog_twin.py` (F31/F43 oracle on block RMS); `tools/engine_cost_curve.py` (decode_ms/RSS vs. buffer length, uses `reference_decode.py`). | `tools/`, `testdata/korpus/` (check gitignore) | 1 day | none — **immediately possible in parallel with the release** |
| 4 | I4 | **Robolectric S2** (dependency present, V10): start race tests (formal proof/verification of the F50 fix: fake coordinator factory, start latency 3/7/16/25 s virtual, DOWN/UP/CANCEL sequences, DOWN/UP fuzzer 200 sequences), **error path enumerator** (all ~18 codes → result ∈ strings.xml), K1 safeguard via `status_res`. | `app/src/test/…` new + `VoiceKeyboardService.kt` (dispatcher injection from I1) | 1–1.5 days | **after I1** |
| 5 | I5 | **S3 long runs:** `tools/emu_korpus_run.sh` (precondition asserts: `pactl` sink, `first3s_peak_dbfs > −30`), debug export as a file `files/debug_export.txt` + `adb exec-out run-as … cat`, `tools/export_to_csv.py`; **6 long runs** (3 pause grids × 2 levels × 120 s) + **fresh-install cold/warm matrix** (`pm clear`, `drop_caches`, Fable §3.1/§6.7) + RSS per run. | `tools/`, `reports/korpus_runs.csv` | 0.5 day scripts + 1 evening of runs | after I3 (corpus) + I2 (fields); emulator exclusive |
| 6 | I6 | **separate the fallback texts** (Fable §6.8, P2 from §2.3): `mapVisibleError` — "Audio / Erkennung / System gestoppt" instead of the collective fallback "Audio-Start fehlgeschlagen" (a diagnosis eater evidenced in V7). | `VoiceKeyboardService.kt`, `strings.xml` + tests | 0.25 day | after I4 (the enumerator covers regression) |
| 7 | I7 | **ONNX Runtime JVM** (`onnxruntime-1.31.1.jar` to `tools/hostlibs/`, a separate test source set — mind the build mutex) + `tools/audit_ui_strings.sh` (hardcoded text regression protection, replaces the offline-impossible lint). | `tools/`, Gradle test setup | 0.5 + 0.1 days | ∥ anytime; network needed once for the package |

**Explicitly NOT in slice B (following Fable):** Paparazzi, edge-tts,
Perfetto/Systrace (§4 "not recommended"). Windowed streaming stays blocked
until E2 (user) on the basis of I3+I5.

---

## 4. Order & dependencies (slice A)

| Step | Work | depends on | parallel to | done criterion |
|---|---|---|---|---|
| 0 | user veto window E1 (F43 semantics) | — | — | no objection until A1 start ⇒ the recommendation applies |
| 1 | **A1** F49 hold gate + repro tests | 0 | **A2** | `testDebug` ≥489/0, review P0/P1=0 |
| 2 | **A2** F50 serialization + F50a feedback + F49 wiring + F51 strings | 0 | **A1** | `testDebug` ≥492/0, review P0/P1=0 |
| 3 | **A3** F52 `status_res` | A2 (2-line seam in the service) | — | `testDebug` ≥495/0, review ok |
| 4 | merge + full check | A1, A2, A3 | — | debug+release completely green |
| 5 | build v0.1.30 (vc 31) | 4 | — | APKs in `dist/`, F46 screenshot ok |
| 6 | emulator smoke (<device>, exclusive) | 5 | no build | smoke checklist §1 "build" fulfilled |
| 7 | delivery + download link + docs | 6 | — | link to the user; SESSION_LOG/HANDOFF updated |

*Slice B starts after step 7; I3 (corpus tools) may explicitly run in
parallel with slice A already (no app code, no Gradle, no emulator).*

---

## 5. Deviations and corrections vs. Fable (for the reader's knowledge)

1. **F37 is smaller than claimed:** Fable (§1.2) says the controller "gets no
   clock". In fact `DictationController` **already owns** an injectable
   `clock` parameter (default `System.nanoTime()`) — only the pass-through
   in the coordinator is missing (V3). Effort for the core item: ~1 line +
   tests instead of "a new API". The remaining F37 parts (threadFactory,
   service dispatchers) stay unchanged.
2. **F43 is not treated as an open measurement question but delivered as a
   mode gate:** Fable (§2.1/§6.1) postpones the "threshold product decision"
   behind the S0/S3 measurements. Because of the release directive I decide
   with justification **before** the measurements: hold gate (A1/A2),
   toggle stays at 2.5 s — that is the only solution that addresses symptom
   3 **without** tipping over the documented user wish "pause = end" (V12).
   The measurements (I3/I5) remain as validation, and E1 keeps the threshold
   question open as a user decision.
3. **Symptom-1 fix (F50) without a prior Robolectric proof:** Fable (§2.2)
   wants to first "prove, then patch" the race in S2. The race is fully
   evidenced by code reading (V4), and the user wants the release today. We
   patch in 0.1.30 (with a pure-function test) and deliver the formal race
   proof (Robolectric fuzzer) afterwards in I4 — residual risk documented,
   the review requirement for A2 raised accordingly.
4. **Addition beyond Fable:** UP feedback "Loslassen vorgemerkt" (F50a).
   Fable considers the silent UP flag "not a bug" (§2.2 point 2) — correct
   for the mechanics, but the C2 finding "UP ignored" shows that the silence
   confuses the user; a transient hint fixes that without a design change to
   P0 finding 2(a).
5. **E1 goes beyond Fable's option "A" (§6):** Fable saw only the
   threshold/semantics question and wanted to postpone it behind
   measurements; the user has decided it as a **product contract** (§6.1).
   The plan lifts it in §6 into a technology package with its own upper-limit
   and long-term strategy — including the consequence that **F31/ERR_SILENT**
   too must be exempted for hold (A1 extension), which neither Fable nor the
   original A1 draft foresaw.

**Marked as OPTION (not evidenced, not in slice A):** H3b/H3c/H3d/H3e (§0),
K1 as a cause, K4, cold start > 15 s on the device, "3 attempts"
causalities (page cache/arena are plausible models, but the I5 matrix is
still missing). None of this is written into a task as a fact.

---

## 6. Continuous dictation (hold) — technology package (E1 binding)

E1 is no longer an option but a **product contract of the user** (2026-11-01):
"As long as the microphone button is held, the user speaks continuously and
gets continuous transcription — **no abort**." This section defines the
contract technically, the remaining gap in v0.1.30 (upper limit) and the
technology stage v0.1.31. **Slice A stays as planned** — only A1 was extended
by one condition (§6.1, F31 gate), A2/A3 unchanged.

### 6.1 Product contract (acceptance criterion)

> **A hold session (hold gesture on the status bar, finger down) ends
> exclusively via:
> 1. **release (UP)** → regular finale, export `stop_cause=up` /
>    `fgs_stopped_by=up`;
> 2. **explicit abort** (CANCEL gesture `michold_cancel`, a user action in a
>    menu);
> 3. **system event** (editor/app switch `onStartInput`/`onWindowHidden`, FGS
>    system stop, loss of rights, **dead source ERR_STALL** — the silence ≠
>    stall contract V1 remains);
> 4. **a defined upper limit** (§6.2).
>
> It does **NOT** end through silence (F43 **and** F31/ERR_SILENT exempted
> for hold — A1 extension), **not** through hypothesis content (no code path
> exists for that, V2), and **not** through decode latency/memory alone (no
> time-based abort path; excluding OOM is the goal of the technology stage).

**Contract fulfillment v0.1.30:** the "silence" and "hypotheses" items are
fulfilled by A1/A2. The upper limit stays at 300 s in v0.1.30 (V11) — covered
contractually (item 4), but the UX weakness of the "5-minute shoot-down in
the middle of a dictation" remains; fix §6.2 in v0.1.31. **The contract is
thereby half to fully fulfilled from v0.1.31.**

**Known residual risk (documented):** hold + broken microphone (only noise)
+ exempted ERR_SILENT = a silent 15-min session with no text. Catchable: a
"Kein Text erkannt" toast on UP with 0 characters exists (around
`VoiceKeyboardService.kt:732`), and the `first3s_peak_dbfs` export shows the
defect in retrospect. Accepted, since E1 prioritizes the user's will.

### 6.2 Upper limit: MAX_DURATION_MS for hold sessions

Today `MAX_DURATION_MS = 300_000` (V11, `AudioRecordSource.kt:38`) kills
**every** session after 5 min — in the middle of a dictation, via the same
abort channel as an error. For the E1 contract this is the only remaining
hard limit.

| | Option A | Option B |
|---|---|---|
| Content | raise to **900,000 ms (15 min)** + **transient warning hint from 13 min** ("upper limit in 2 min — release in time") | **no** upper limit at all for hold, instead **stricter watchdogs** (RSS threshold with a gentle controlled end instead of OOM; ERR_STALL stays) |
| Pro | uses the existing `maxDurationMs` infrastructure (already a constructor parameter, `AudioRecordSource.kt:27`); the user keeps control; covers a real dictation need; revisable after the technology stage | takes the contract "unlimited" literally |
| Contra | a limit stays a limit | an RSS threshold is **unmeasured** (I3/I5 are still missing), a wrong threshold = an abort exactly when the contract forbids it; on GrapheneOS an OOM takes the app down with it |

**Recommendation: option A** — B without a measurement basis is the only way
to violate the contract *worse* (an abort without user involvement). A is a
~30-line micro change and goes as **B2 into the technology stage v0.1.31**
(no longer in 0.1.30 — slice A stays frozen; the 300-s limit is
contractually item 4, and in the smoke §6.4 we deliberately stay under it
with 180–240 s).

**Which tests change (option A):**
- **No existing ones break:** no test file asserts `MAX_DURATION_MS =
  300_000` (verified — the constant appears in no test; `FakeAudioScenarioTest`
  case e tests ERR_SILENT on the default path, untouched).
- New: `holdWarnDue(sessionMs): Boolean` as a pure function + test (13-min
  threshold, hysteresis-free); a service timer analogous to
  `startDeadlineJob` (coroutine) → `postEvent(StatusEvent.Hint(...))` —
  transient routing (V7 seam, `StatusRoutingTest` covers regression).
- Wiring: at the start of the hold gesture the service passes
  `maxDurationMs = 900_000` to the source (the parameter already exists);
  toggle sessions can get the same limit (practically irrelevant: F43 stops
  toggle at the first 2.5-s pause anyway).

### 6.3 Long-term sustainability — two stages (the real technology problem)

**Problem:** the partial path decodes the **complete** buffer
(`DictationController.window()`, `:462`) — the cost per decode grows linearly
with T, the total work quadratically (C2 thesis; Fable §6.2 confirms it for
latency, not aborts). Two measurement points exist, with a wide span:
- Device (C2 export #1): decode_ms_max **583 ms @ 11.9 s** buffer ⇒ ≈ **49 ms
  per audio second**.
- **User measurement: decode_ms_max 4102 ms @ ~8 s** — **not evidenced in the
  repo** (no file names this number); presumably a different variant (fp32) /
  a different device / a host run. ⇒ precisely therefore: **measure first.**

Extrapolation with 49 ms/s: decode(60 s) ≈ 2.9 s · decode(300 s) ≈ 15 s ·
decode(900 s) ≈ 44 s. With a 4102-ms slope (~512 ms/s), decode(30 s) ≈ 15 s
already. In both cases: **without intervention the hypothesis latency visibly
violates the contract from a few minutes on** (hypos run behind the speaker),
and the PCM buffer grows linearly (~1.92 MB/min → 15 min ≈ 28.8 MB —
uncritical; critical is the **compute time**, not the memory).

**Stage 1 — v0.1.30 (slice A unchanged):** hold gate + start fix +
`status_res`. The contract is thereby half fulfilled ("silence no longer
aborts"). The upper limit keeps sessions at ≤ 5 min, where per today's
state the cost curve should *just about* remain controllable — but that is
an assumption, not a proof. Therefore, **starting immediately, in parallel
with the release**:
> **Measure first (I3, no app code):** `tools/engine_cost_curve.py` delivers
> decode_ms and RSS for buffers 5…**≥ 300 s (mandatory), target 900 s** and
> **clarifies the 4102-vs-583 discrepancy** (variant fp32/fp16, device vs.
> host). Decision rule from the curve: α = decode(T)/T in ms per audio second
> ⇒ segment length of the technology stage W = 1,500 ms / α (so that
> decode(W) ≤ 1.5 s).

**Stage 2 — v0.1.31 (technology stage), data-driven from the curve. Three
candidates:**

| | (a) DecodePace scales with buffer length | (b) rolling decode window with overlap-free text handover | (c) intermediate segment finalization without a sound gap |
|---|---|---|---|
| Mechanism | open up `DecodePace.intervalMs` further (clamps 2000/6000 ms — `AsrThreadConfig.kt:72/73` — raise/abolish; the cadence follows decode_ms) | decode only over `[T−W, T]`; handover only of the **new** text; a fixed overlap strip only for word-boundary stitching; **finale on UP over the TOTAL buffer** (28.8 MB @ 15 min — controllable) | a periodic "mini finale": segment [T−W, T] is finalized like a `stop()` and **permanently committed**; the decoder starts at 0; segment boundary **only on silence** (sentence/word boundary from the already existing block RMS class) or a punctuation mark in the hypothesis |
| §5.1 conformity | conformant (cadence, no window) | **breaks the block** — needs the three documented defect fixes (see below) + formal user release E2 | **conformant**: every segment is a `whole_buffer` run — exactly the approach recommended in §5.1, only segmented in time; **no new window state machine, no agreement cache** |
| Contract fulfillment "hypothesis latency ≤ 2 s @ 10 min" | **NO**: decode(600 s) ≈ 29–300 s depending on α — hypos run minutes behind; (a) only buys time until the contract visibly breaks | yes, if W is small enough | yes: decode(W) ≤ 1.5 s by construction (W from the curve), independent of total duration |
| Text correctness | full (unchanged pipeline) | risk at segment boundaries (word splitting); the finale heals everything, but **only on UP** — visible seam errors possible during the session | segment boundary in silence ⇒ low seam risk; every segment concatenation testable against golden (I3 sidecar) |
| Effort | ~0.25 day (constants/formula only + tests) | 2–3 days + E2 release + defect fixes; highest regression risk (doctrine "no deadlock state machine") | ~1 day core (segmenter + buffer reset) + 0.25 day cap/warning (B2) |

**§5.1 defects and how (b) would have to counter them** (EXACTLY from
`KONZEPT.md` §5.1, verified there):
1. **Pause stall:** the window was capped at `committed_s + 3 s`; on a pause
   ≥ 3 s the VAD kept delivering "no speech", `committed_s` never advanced →
   **everything after the pause was lost**. → (b) countermeasure: position
   the window **only by time/bytes** (never by commit progress), no VAD as a
   forward condition. *Note: for hold sessions the case is more harmless
   today because the contract allows silence — but the basic construction
   stays error-prone.*
2. **Window ↔ commit coupling:** the window could not extend beyond
   `committed_s + n` → a stuck commit = a stuck window. → (b): decoupling —
   the decode window follows the recording time, the commit point = window
   end minus overlap strip (deterministic, not negotiable).
3. **Agreement deadlock:** with every commit the commit point advanced ⇒
   the agreement cache never collected 2 hits ⇒ a permanent standstill. →
   (b): **drop the agreement procedure entirely**; text handover via a
   deterministic word boundary in the overlap strip.

**Wording for KONZEPT §5.1 ("refined, not broken"):** the recommendation
"whole_buffer is more robust" stays untouched — (c) **applies** it instead of
breaking it. Should (b) be chosen later: **the user contract "continuous"
(E1) stands above the block — the release per KONZEPT §5.1 is the user's
decision** (to be obtained formally via E2 with the I3/I5 data); the §5.1
table is then to be documented as "refined": same defects, named
countermeasures (1–3), the measurement comparison against golden to be redone.

**Recommendation: (c) intermediate segment finalization** — reasoning: the
only option that guarantees the hypothesis latency **acceptance** at 10 min,
is §5.1-conformant (no new deadlock-capable state machine — exactly the
doctrine's lesson), and is built from existing blocks (the finale path, the
block RMS silence classification from F43 are already there). (a) as an
**accompanying measure within the segments** (the cadence stays cost-aware,
unchanged). (b) only if (c) seam errors in the golden tests turn out
unexpectedly too coarse — then with the defect fixes 1–3 and after E2.

**Tasks v0.1.31 (each ≤4 files, ≤~150 lines):**
- **B1 — segmenter core:** `DictationCoordinator.kt`,
  `DictationController.kt`,
  `app/src/test/java/de/voicekeyboard/dictation/SegmentFinalizeTest.kt`
  (new), `KorpusTaktTest.kt` (extension: 600-s corpus, 2× 3-s pauses). The
  silence/punctuation boundary function `segmentBoundary(...)` as a pure
  function (JVM-testable, DecodePace pattern). The segment finale via the
  existing finale path, then reset the buffer/decode stats per segment
  (careful: the export counters `output_chars`/`had_output` are **session**
  counters — do not average them).
- **B2 — upper limit/warning (option A, §6.2):** `VoiceKeyboardService.kt`,
  `strings.xml`, `AudioRecordSource` wiring via `audioSourceFactory` (do not
  change the source itself), `StartGateTest` extension resp. a new
  `HoldWarnTest`.
- **B3 — proof:** golden comparison per segment + total against the I3
  sidecar (JVM with a stub engine, KorpusTaktTest pattern); the **S3
  emulator run 600 s only AFTER the technology stage** (§6.4).

**Acceptance technology stage (v0.1.31, hold 10 min = corpus 600 s with 2×
3-s pauses):**
- `hyp_gap_ms` (median/p95) **≤ 2,000 ms**, `decode_ms_max ≤ 2,000 ms` per
  segment;
- **no abort**: `stop_cause=up` resp. `fgs_stopped_by=up`, `last_error`
  empty, `ctl_failed=0`;
- **ΔRSS < 150 MB** over 10 min (`dumpsys meminfo` TOTAL, measurement basis
  I5; the PCM share ≈ 19.2 MB — everything above that is arena/codec growth
  and then addressable);
- **text continuously correct**: per segment 100 % golden, concatenation =
  the golden total text;
- the upper-limit run 15 min ends in a controlled way with the `maxdur` code
  **after** a prior warning (13 min).

### 6.4 Emulator smoke checklist (extended, v0.1.30 and v0.1.31)

| Version | Test item | Expectation |
|---|---|---|
| **v0.1.30** | **hold dictation 180–240 s** (corpus/playback via `paplay`), with **2× 3-s sentence pauses** built in | **NO abort**; `output_chars` grows beyond both pauses; text continuous after the pauses; **finale only after UP**; export: `fgs_stopped_by=up`, `mic_rel=holdup`, `status_res` switches recording→idle; `last_error` empty; `ctl_failed=0`. Deliberately **below 300 s** (the v0.1.30 upper limit, §6.2) |
| **v0.1.30** | short hold with > 3 s **silence before speaking starts** (finger down, not speaking) | **no ERR_SILENT** — the session lives until UP; afterwards the "Kein Text erkannt" toast (A1 gate + contract case §6.1) |
| **v0.1.31** | **10-min corpus run (600 s)** via `tools/emu_korpus_run.sh` + acceptance §6.3 | only **AFTER** the technology stage — in v0.1.30 it would run into the 300-s cap and be "expectedly red" (not a bug finding!) |

Framework conditions as §1 build step 4: **<device> exclusive**, never
parallel to the build, precondition asserts (`pactl` sink visible,
`first3s_peak_dbfs > −30` after 3 s), debug APK, export pull as a file (I5
pattern, once available, otherwise the 🐞 clipboard).

---

*End PLAN V0130. No git, no code changes — only this file.*
