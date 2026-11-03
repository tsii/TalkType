# Debugging consultation — restructuring (status v0.1.29-diag, 2026-11-01)

**Role:** debugging consultant (read-only, only this file written). No build, no git, no emulator.
**Basis:** code reading `VoiceKeyboardService.kt` (start path 407–572, `stopDictationIfRunning`
640–668, `onDictationStopped` 740–770, `mapVisibleError` 969–1001, `updateStatus` 1040–1112),
`DictationCoordinator.kt` (start 208–297, captureLoop/watchdogs 359–454, stop 565–612, abort
714–735), `DictationController.kt` (feedAudio 248–296, stop 320–366, fail 417–422),
`StatusBarText.kt`, `OwnershipTracker.kt`, `AudioRecordSource.kt`, `DebugInfoFormatter.kt`
(field list), `FakeWavAudioSource.kt`, `tools/`, `.venv-asr`.

---

## 0. Core message in five sentences

1. The three device symptoms are **state machine/timing problems**, not ASR quality problems.
   They can be reproduced **without a device** if time, engine latency, and dispatcher are
   **injectable** — that is F37, but F37 must be framed **more broadly** than "controller clock
   in the coordinator" (see §1.2).
2. For symptom 3 (sentence abort), **F43 (2.5 s trailing silence) is by far the most probable
   cause**; the "hypothesis-triggered" variant has **no code path** (not a single `stop()` call
   depends on hypothesis content). "Every segment ended with punctuation" is a *consequence*
   of the final decode (Parakeet punctuates utterance ends), not a trigger. Export #3 can
   prove this **immediately** with `fgs_stopped_by=autostop`.
3. For symptom 1 (start hang), the **engine cold start on a fresh installation** (page cache
   empty, 670 MB mmap + ORT session init) in combination with two **non-serialized start
   coroutines** (`onMicDown` twice during the `startingUp` window/deadline) is the main thesis —
   reproducible in the JVM with a fake engine `load()`=7…20 s virtual.
4. For symptom 2 (untranslated message), **every** UI path that was read goes through
   `getString(...)` — the cause therefore lies in one of the four candidates in §3.3 (debug
   diagnostics suffix, hard-coded non-resource strings, third-party toast, special tokens in
   the editor). A **static audit + a Robolectric error-path enumerator + a new export field
   `status_res`** settles it definitively, no device needed.
5. The emulator is needed **only** for the layer "real AudioRecord + FGS + level + long corpora
   with the real engine". Everything else (state machine, watchdog timing, error mapping,
   start collisions) belongs in **deterministic JVM tests** with virtual time — there the runs
   take seconds and are CI-capable.

---

## 1. Debugging architecture (layers, order, F37)

### 1.1 Target design: four layers, each with its own determinism guarantee

| # | Layer | What it proves | Determinism source | Status today |
|---|---|---|---|---|
| **S0** | **Host analytics (Python, `.venv-asr`)** — engine cost curve, corpus generation, level/pause statistics of a WAV, "watchdog twin" (pure recomputation of F31/F43 on block RMS) | How does decode_ms scale with buffer length? Where *would* F43 fire for a given WAV? What pause distribution does real dictation have? | pure functions on files | `reference_decode.py`, ORT 1.31.1 present in the venv; the rest is missing |
| **S1** | **JVM state machine with virtual time** (JUnit, no Android) — coordinator + controller + fake engine with **configurable latency** + fake audio source from a corpus WAV | watchdog order, auto-stop timing, hypothesis pacing, stop/abort races, StopOutcome | **virtual clock** (F37) + stub latency on the same clock | Weg D (KorpusTaktTest) exists, but with real-time sleeps → slow, not fully deterministic |
| **S2** | **Robolectric service layer** — `VoiceKeyboardService` with a fake coordinator factory, `TestDispatcher`, DOWN/UP/CANCEL sequences, status row text assertions | start hang, deadline race, collision of two starts, "which text is in `statusText`", sticky/transient | `StandardTestDispatcher` + Robolectric looper | Robolectric 4.17 is **already** in `testImplementation`, but unused for the service |
| **S3** | **Emulator corpus run with the real engine** (x86_64-ONNX runs since F41b) — virtual microphone (`emulator_audio_up.sh`) + debug APK + automated 🐞 pull | real AudioRecord/FGS/routing, level in dBFS, **long dictations 60–180 s**, RSS/OOM behavior, final decode duration | script with **precondition asserts** (PulseAudio sink visible — the F41 pitfall must never pass silently again) | short runs ≤ 8 s done; long never |
| **S4** | **Device (user, 1 export/session)** | confirmation, not exploration | export must be **self-explanatory** (§5) | 42 lines, single session |

**Order of hardening:** S1 → S2 → S0 (can run in parallel, no code intervention) → S3 → S4 protocol.
Rationale: S1/S2 need code refactoring (broad F37), but deliver the *reproducibility* of symptoms
1+2 and the causal chain of symptom 3; S0 is doable immediately and yields numbers for the C2
decision; S3 then confirms with the real engine; S4 gets the new fields.

### 1.2 F37 must be framed larger ("clock injection" is not enough)

Finding from the code:
- `DictationCoordinator` **already has** `nowMs: () -> Long` injectable (line 59) — the watchdogs
  run on it.
- But `controller = DictationController(engine, sink, tracker, decodePacing = true)` (line 171) gets
  **no** `clock` → the controller uses `System.nanoTime()`: `hyp_gap_ms`, `decode_ms_*` and — more
  importantly — the **decode pacing** (`paceAllowsDecode()` on `lastDecodeMs`) run on real time,
  while the watchdogs run on virtual time. That is exactly the F35 "clock race" in the 60-s case.
- The **service** has three more hard clocks/dispatchers: `serviceScope = Dispatchers.Default`,
  `withContext(Dispatchers.Main)`, `SystemClock.elapsedRealtime()` (transient clarity),
  `delay(START_DEADLINE_MS)`.
- The coordinator starts `captureThread` as a **real thread** with `src.read(buf, 50 ms)` → in S1
  the fake source must tick *virtually* (FakeWavAudioSource can already do `nowMs`, good), but the
  thread itself runs real.

**F37 (recommended scope, ~0.5–1 day of code, no production behavior changed):**
1. Thread it through: `DictationCoordinator(…, controllerClock: () -> Long = { nowMs() * 1_000_000 })`.
2. `DictationCoordinator`: `captureLoop` as a `Runnable` with an injectable `threadFactory`
   (default: `Thread`), so S1 can drive the loop **synchronously** on the test thread (one tick =
   one `read`).
3. `VoiceKeyboardService`: `ioDispatcher`/`mainDispatcher`/`elapsedClock` as `internal var`
   (defaults as today), `START_DEADLINE_MS`/`FGS_READY_TIMEOUT_MS` as `internal var`.
4. Fake `AsrEngine` with **latency-controlled `load()` and `transcribe()`** on the *same* virtual
   clock (the stub advances the clock itself — adopt the F35 pattern).

What this unlocks: all tests in §2 run in milliseconds instead of minutes, flakes disappear, and
the two start races (§2.2) become formulable at all for the first time.

---

## 2. Reproduction plan for the three symptoms

### 2.1 Symptom 3 — "longer dictations break off at sentence ends"

**Hypothesis ranking (with falsification criterion from today's export fields):**

| Rank | Hypothesis | Mechanism | Export #3 refutes it, if … |
|---|---|---|---|
| **H3a (high)** | F43 fires at a natural sentence pause ≥ 2.5 s | `everLoud && capStallMs<2500 && now-lastLoudAt>2500 → stop()`; final decode punctuates → "ends with punctuation" | `fgs_stopped_by ≠ autostop` **and** `mic_rel` shows `holdup:<ms>` with ms ≈ audio_s |
| **H3b (medium)** | Level on the device just above −40 dBFS: quiet syllables count as "silent" → auto-stop even at shorter pauses or mid-sentence | `SILENCE_RMS = 0.01` is block RMS over 128 ms (2048 samples) | `peak_dbfs ≥ −25` **and** `loud_s/audio_s ≥ 0.6` → level is not the problem |
| H3c (low) | Ownership loss (`OwnershipTracker.lost`) → hypotheses are no longer written *silently*; the session keeps running, the user sees the text "abort" | `isOwned` false → `writeHypothesis` returns false, no error, no stop | `output_chars` grows until the end **or** `had_output` + status "dictation" kept running → no abort, but a write stop (then a **separate** problem!) |
| H3d (low) | Editor sync timeout during a long decode (main thread blocked) | `fail(EDITOR_SYNC_TIMEOUT)` → "app is not responding" | `ctl_failed=0`, `last_error` empty |
| H3e (very low) | OOM in the encoder at large T | ERR_OOM → "out of memory" | `last_error ≠ oom` |

**Important:** H3a and H3b cannot currently be told apart **with certainty** in the export —
both end in `autostop`. That's what §5 is for (`autostop_gap_ms`, `max_pause_ms`, `pause_gt2s`,
`loud_ratio`, `rms_p50_dbfs`).

**Repro plan (S0 → S1 → S3):**

1. **S0 — build the corpus** (`tools/make_korpus.py`, ~2–3 h):
   - TTS: **piper-tts** (pip, voice `de_DE-thorsten-medium`, offline, natural enough for
     Parakeet) as the main source; `espeak-ng` (already installed) as fallback/robotic variant.
   - Construction kit: sentence list (20–40 German sentences, 5–15 words) × pause grid
     **1.5 / 2.0 / 2.4 / 2.6 / 3.0 / 4.0 s** × level **−17 / −30 / −38 dBFS** (ffmpeg `volume=`)
     × duration **60 / 120 / 180 s**.
   - Mix quiet noise (−60 dBFS) into the pauses (a device microphone is never digitally silent;
     otherwise one tests `capStallMs` paths instead of F43).
   - Every WAV gets a **sidecar JSON**: sentence boundaries (ms), pause lengths, expected golden
     text (via `reference_decode.py` on the individual sentences). → ground truth for "where
     *would* F43 fire".
   - 16 kHz mono s16le, output to `testdata/korpus/` (check gitignore — size).
2. **S0 — watchdog twin** (`tools/watchdog_twin.py`, ~1 h): computes per WAV the block RMS
   (2048 samples) like `captureLoop`, simulates F31/F43 and outputs: `autostop_at_ms`,
   `max_pause_ms`, `loud_ratio`. Not a Kotlin replacement but an **oracle** for the expected
   values of the S1/S3 runs (and an immediate answer: "does corpus X break off at 2.5 s?" — yes,
   by construction at every pause ≥ 2.6 s).
3. **S1 — extend KorpusTaktTest** (after F37): corpus WAV via FakeWavAudioSource with virtual
   time, stub engine with the latency model `decode_ms = a + b·buffer_s` (a, b from the S0
   measurement), assertions: auto-stop time = oracle ± 1 block; `hyp_count` monotonic;
   `StopOutcome.FinalRan`; ownership never lost.
4. **S3 — emulator long runs with the real engine** (`tools/emu_korpus_run.sh`, ~3–4 h including
   the pull):
   - **Precondition assert** (lesson from F41): `pactl info | grep -q "Default Sink: vkmic"` and
     `XDG_RUNTIME_DIR` set, otherwise abort with a clear message. Additionally a level check:
     after 3 s of playback, `first3s_peak_dbfs` in the export must be > −30, otherwise the run is
     invalid.
   - Procedure: focus the IME (DebugInputActivity), mic tap (adb tap), `paplay korpus.wav`, wait
     until `fgs_stopped_by` is set, pull the export.
   - **Export pull without clipboard:** the debug build additionally writes the 🐞 text to
     `files/debug_export.txt` (identical content → rule 9 unchanged); pull with
     `adb exec-out run-as de.voicekeyboard cat …`. Reading the clipboard is unreliable from
     API 29 for a background shell.
   - Matrix: 3 pause grids × 2 levels × 120 s = 6 runs à ~3 min → one evening. Result as CSV
     (`reports/korpus_runs.csv`) with the oracle column alongside.
   - Additional measurement per run: `adb shell dumpsys meminfo de.voicekeyboard | grep TOTAL` at
     the end → RSS vs. audio_s (H3e, C2).

**Expectation:** all runs with a pause ≥ 2.6 s stop at the first such pause — this proves H3a,
and the discussion shifts from "find the bug" to a **product decision on the F43 threshold**
(data: pause distribution of real dictation; 2.5 s is very tight for thinking pauses while
dictating; typical values in dictation apps are 4–6 s or adaptive "silence length grows with
session duration"). That is not my decision, but the measurement makes it decidable.

### 2.2 Symptom 1 — start hang ("start recording", releasing has no effect, 2nd press → "ready")

Code path: `onMicDown` → `postEvent(Starting)` → `startingUp=true` → coroutine: `awaitFgsReady(4 s)`
→ `coordinator.start()` (contains `engine.load()` **in the start path**, cold start ≈ 7 s on the
device per `start_ms`; on a **fresh installation** without a page cache plausibly significantly
longer) → main: `startingUp=false`, delayed UP handled via `cancelStartRequested` →
`KEEP_RUNNING`.

**Observed gaps (from the code, not from the device):**

1. **Two start coroutines are not serialized.** After `START_DEADLINE_MS=15 s`, `startingUp=false`
   is set, but the *first* start keeps running (no cancel of `engine.load`). A second DOWN starts a
   second coroutine; it sets `startAbandoned=false`. If now the *first* start completes, it sees
   `abandoned=false`, `started=true` → continues the *old* session as valid; the *second*
   `coordinator.start()` fails at `active.compareAndSet` → `started=false` → error text. Depending
   on the order you get (a) "ready" with a zombie microphone running, (b) an error, (c) an
   inconsistent UI/status. In addition, the second run overwrites `lastStartMs` (`finally`) →
   **`start_ms` in the export may be the collision value, not the cold start** — be careful when
   interpreting export #3.
2. **UP during STARTING is a silent flag** — the status row stays "Starting recording…" (transient);
   there is no feedback "release queued". This explains the user's impression "releasing changes
   nothing", but it is by design (P0 finding 2(a)) and need not be a bug. Without an export field
   you cannot tell whether the session then **ran** (green) or not.
3. **FGS-ready 4 s + engine.load ≥ 7 s + deadline 15 s** — on GrapheneOS with a fresh app (first FGS
   use of the `microphone` type, notification channel new, model not in the page cache), exceeding
   15 s is realistically possible → "start takes too long" (sticky). That would be *translated*;
   but the follow-on collision from point 1 is then the actual problem.

**Repro (S2, Robolectric, after F37 dispatcher injection, ~1 day):**
- Fake coordinator factory with `start()` latency L ∈ {3 s, 7 s, 16 s, 25 s} virtual.
- Sequences: `DOWN; +2s UP` / `DOWN; +2s UP; +4s DOWN; +5s UP` / `DOWN; +16s DOWN; +17s UP` /
  `DOWN; +1s CANCEL`.
- Assertions on **`statusText.text` (resource ID match)**, `micRecording`, `fgsRunning`, number of
  active sessions (`coordinator.isActive`), `fgsStoppedBy`.
- Expected red cases: the collision from point 1 (zombie or double start). After that, the fix
  (start mutex / cancel of the orphaned coroutine / `abort` of the stale session **before** the
  second `start`) is a 20-line patch — but prove first, then patch.

**Reproduce the cold start as a timing issue (S3, 1 h):** emulator `adb root && adb shell "sync;
echo 3 > /proc/sys/vm/drop_caches"` before the first mic tap → `start_ms` cold vs. warm (second
tap after an IME restart). Expectation: cold ≫ warm; if cold > 15 s on the emulator (slower than
the device is not excluded), the deadline collision is reproducible **directly** on the emulator.

### 2.3 Symptom 2 — untranslated error message

**Finding of the code audit:** all paths that set `statusText` go through `StatusBarText.select`
with inputs from `getString(...)` or `mapVisibleError(...)` (always `getString`). There is **no**
path that writes `lastError`/`lastErrorCode`/an enum raw into the status row. However, the mapping
is *semantically* coarse: `asr_final_error`, `asr_partial_error`, `audio_error:stall`,
`audio_error:systemStop`, `audio_error:init`, `audio_error:maxDuration`, `consumer:*`,
`startFailed` → they all fall through to **"Audio-Start fehlgeschlagen"** ("audio start failed")
(the fallback branch). Wrong, but translated.

**Four candidates for what the user saw (in order of plausibility):**

| # | Candidate | Where | Check without a device |
|---|---|---|---|
| K1 | **Debug diagnostics suffix** in the dictation row: `Diktat… (intern) · Diagnose: erst=-s RTF=- T=4 Peak=…` (only in the **-diag-debug** APK that the user installed; `updateStatus` 1070–1081) — truncated in a narrow status row this looks like "raw code" | `DictationCoordinator.diagnosticsLine` | ask the user: "did it say `RTF=`/`erst=`?" — or the `status_res` field (§5) |
| K2 | **Hard-coded non-resource strings** ("Verarbeite…", "Bereit", "Diktat… (…)", "Diagnose kopiert — Teilen-Dialog geöffnet", toasts 1212/1216, FGS notification "Diktat läuft") — not raw code, but a lint audit `HardcodedText`/`SetTextI18n` finds them in 10 minutes | service, CaptureForegroundService | `./gradlew lint` may not be possible offline → `rg '"[A-ZÄÖÜ][^"]*"' --type kotlin app/src/main` as a substitute |
| K3 | **Special tokens in the editor text** (e.g. `<unk>`, `▁`) — the user may have read a faulty output *in the text field* as an "error code" | `Vocabulary.isSpecial`, `TdtGreedy` | golden test on a corpus with noise/pauses in S3; `output_chars` vs. golden length |
| K4 | **System/GrapheneOS toast** (microphone access indicator, sensors permission, FGS hint) — not our UI | — | eliminable only by asking back |

**Methodology (S2, 0.5 day):**
1. **Error code enumerator test:** collect all codes from the code (`rg 'const val ERR_|fail\(|lastError = "'`)
   → list of ~18 strings → `mapVisibleError(code)` for each → assertion: result ∈ values from
   `strings.xml` (Robolectric reads resources). Automatically covers K1-like regressions in the
   future and at the same time documents the coarse fallback mappings (review fodder).
2. **Lift `mapVisibleError` into a pure function `errorTextRes(raw): Int`** (the pattern
   `mapErrorTextRes` already exists for `editor_sync_timeout`) — then JVM-testable without
   Robolectric, and the service can remember the **resource ID of the last displayed text** →
   export field `status_res` (§5). The next export then says itself which text was shown
   (`dictation_error_audio_start`) or that it was **no** resource text (`raw`).

---

## 3. Methodology against the "3-attempts pattern"

The pattern is typical of **state that outlives the session**: page cache, `engineLoaded`,
orphaned start coroutine, FGS state, `OwnershipTracker` baseline, ORT arena growth. Procedure:

1. **Define and measure cold/warm cleanly** (S3, 1–2 h): matrix {process fresh (`am force-stop`),
   page cache empty (`drop_caches`), IME window new (`onCreate` of the InputView), coordinator
   present} × {1st, 2nd, 3rd session}. Measurements: `start_ms`, `first_hyp_ms`, `decode_ms_max`,
   result. Expectation: attempt 1 = page cache + ORT session init, attempt 2 = arena growth/first
   inference (ORT allocates large on the first `run`), attempt 3 = stationary. If the pattern
   appears **in the emulator too**, it is not device-specific — a big win.
2. **Engine preload experiment** (debug flag, ~2 h of code): `engine.load()` + **one warm-up
   inference on 2 s of silence** at `onStartInputView` in the background (not in the mic path).
   Then repeat the matrix. If attempt 1 disappears → product proposal "preload + warm-up", and
   symptom 1 is decoupled from the touch gesture. The memory cost (~860 MB native) is the
   counter-argument — quantify it with `dumpsys meminfo`.
3. **Session history in the export** (§5): without a ring buffer, you always see only attempt 3
   on the device (the one that worked) — the two failed attempts are overwritten. That is the
   main reason why the pattern has not been tangible so far.
4. **Test start serialization as an invariant** (S2): "at any time at most one start coroutine is
   running; an expired one is aborted or its result discarded." Property-style over random
   DOWN/UP sequences with virtual time (a simple fuzzer, 200 sequences, seconds of runtime).

---

## 4. New tools / infrastructure (prioritized, with effort)

| Prio | Tool | For what | Effort | Installation |
|---|---|---|---|---|
| **1** | **piper-tts** (pip in `.venv-asr`) + voice `de_DE-thorsten-medium` + `tools/make_korpus.py` | synthetic 60–180-s corpora with controlled pauses/levels (S0/S1/S3); sidecar JSON as oracle | 2–3 h | `pip install piper-tts`; one-time model download (~60 MB). `espeak-ng` is already there (fallback) |
| **1** | **`tools/watchdog_twin.py`** (numpy/soundfile present) | prediction "where do F31/F43 fire for this WAV" + pause statistics; oracle for tests | 1 h | nothing |
| **1** | **`tools/engine_cost_curve.py`** (uses `reference_decode.py` sessions) | decode_ms and RSS vs. buffer length 5…180 s on the host → quantify the C2 thesis (the shape of the curve transfers, absolute values do not) | 1–2 h | nothing (ORT 1.31.1 in the venv) |
| **2** | **Robolectric for `VoiceKeyboardService`** (dependency already present) + `kotlinx-coroutines-test` (check whether it is offline in the Gradle cache; otherwise fetch once with network) | S2: start races, status row text, error path enumerator | 1 day including F37 dispatcher injection | possibly `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<version like coroutines-core>")` |
| **2** | **`tools/emu_korpus_run.sh`** + debug export file (`files/debug_export.txt`) + `tools/export_to_csv.py` | S3 long runs reproducible, F41 pitfall excluded by assert, results tabular | 3–4 h | nothing new; `adb`, `paplay`, `pactl` present |
| **3** | **ONNX Runtime JVM (linux-x64) `onnxruntime-1.31.1.jar`** manually to `tools/hostlibs/` + `flatDir` in a *separate* Gradle test source set | Kotlin pipeline (controller/coordinator) **with the real engine in the JVM** without an emulator — the fastest "Weg B" of all | 0.5 day (Gradle change, mind the build mutex) | `curl` from Maven Central (network for packages allowed) |
| 3 | `rg`-based **hard-coded string audit** as a script (`tools/audit_ui_strings.sh`) | K2 from §2.3, prevents regressions | 0.5 h | nothing |
| — | **Not recommended:** Paparazzi (screenshot tests) — solves none of the three symptoms; edge-tts — online service, contradicts the offline doctrine; Perfetto/Systrace on the emulator — only useful once S1/S2 leave a concrete thread question open | | | |

All scripts: path/audio/text contents only in `testdata/`/`reports/` (not in logs) — rule 9 covers
logs and exports, not test artifacts, but golden texts do not belong in commit messages/docs.

---

## 5. Changes to the 🐞 export (one export → a clear layer)

Principle: **every field is a number or a closed code**, never content — rule 9 remains fully
preserved. The goal is that a single export answers the question "audio layer / watchdog /
engine / editor / UI state machine?" without asking back.

### 5.1 New fields of the current session (≈ +12 lines)

| Field | Content | Answers |
|---|---|---|
| `start_outcome` | `ok` / `fgs_timeout` / `abandoned` / `collision` / `failed=<code>` | symptom 1: which start path |
| `start_phase_ms` | `fgs=<ms> load=<ms> audio=<ms>` (three numbers) | cold start vs. FGS vs. AudioRecord |
| `start_attempts` | number of `onMicDown` since IME window creation | multiple DOWN in the start window |
| `up_during_start` | 0/1 (+ms) | "release queued?" |
| `stop_cause` | `up` / `holdup` / `autostop` / `hidden` / `editor` / `abort=<reason>` / `maxdur` | replaces the interpretation from `mic_rel`+`fgs_stopped_by` |
| `autostop_gap_ms` | measured silence gap at the auto-stop (0 = no auto-stop) | H3a directly |
| `max_pause_ms`, `pause_gt1s`, `pause_gt2s` | longest gap between loud blocks; number of pauses > 1 s / > 2 s | pause profile of the dictation |
| `loud_ratio` | `loud_s/audio_s` | H3b (level just above the threshold) |
| `rms_p50_dbfs`, `rms_p90_dbfs` | percentiles of the block RMS of **loud** blocks | H3b, without a curve/content |
| `owner_lost_at_s` | second from which `isOwned` returned false (−1 = never) | H3c silent write stop |
| `final_ms`, `final_chars_delta` | duration of the final decode; character change caused by the finale (a number) | finale quality without text |
| `status_res` | **resource name** of the last displayed status text (`dictation_error_unknown`) or `raw`/`diag` | symptom 2 definitively |
| `engine_loads`, `coord_gen` | number of `engine.load()` and coordinator instances since process start | cold/warm classification |

### 5.2 Session ring buffer (last 5 sessions, 1 line each)

Format per line (codes/numbers, ~90 characters):
`s-2: start=ok/7120ms stop=autostop audio=38.2 loud=31.0 maxpause=2710 hyp=14 dmax=2100 err=- res=idle out=1/412`

Why: the "3-attempts pattern" is currently **invisible** on the device because every start zeroes
the counters. Five lines suffice for attempts 1–3 plus context; the content stays rule-9
compliant (no texts, no paths, no times as wall clock — only relative ms). Storage: an
`ArrayDeque<String>(5)` in the service.

### 5.3 Small things that prevent interpretation errors

- Write `start_ms` only from the **first** `start()` of a session (the collision run must not
  overwrite it).
- `threads`/`api_level`/`model_*` to the end (unchanged, but session data first — the user
  sometimes copies only the beginning).
- Debug build: additionally write the export as a file (S3 pull); the header with `build=debug`
  is already there.

---

## 6. Warnings, limits, dead ends

1. **The F43 sentence-pause thesis is correct, but only half formulated.** Correct: auto-stop at
   2.5 s fires at natural dictation pauses. Wrong/unnecessary: a "hypothesis-triggered"
   component — no code path exists. **Dead end:** invest time in finalize/hypothesis interaction
   before export #3 has ruled out `fgs_stopped_by=autostop`. The real work is a **product
   decision** (threshold, adaptive, or auto-stop only in toggle mode, not with a held key — the
   hold gesture already means "I'm not done yet").
2. **The quadratic decode thesis (C2) explains latency, not aborts.** Total work per session is
   Σ decode(T) ≈ O(N²) — correct — but decode pacing throttles, and no path aborts because of
   long decodes (except indirectly editor sync timeout → would be `ctl_failed=1`,
   `editor_sync_timeout`). **Measure first** (S0 cost curve, S3 RSS), then decide on windowed
   streaming (KONZEPT §5.1). Before that, any discussion about it is speculation — and it
   needlessly blocks the release question.
3. **Emulator times are not device times.** x86_64-int8 kernels (AVX2/VNNI on the host CPU) vs.
   arm64-NEON scale differently; `decode_ms` absolute values do not transfer, only **ratios**
   (cold/warm, 30 s/120 s). On the emulator, possibly **shorten deadlines artificially**
   (injectable, F37) instead of waiting real seconds.
4. **The emulator cannot "just show" symptom 1/2** — without device state (fresh installation,
   GrapheneOS FGS policy) the triggers are missing. That's why S1/S2 first: race conditions are
   enforced there by construction, not hoped for.
5. **Rule 9 is read too narrowly.** Percentiles, histogram counters, pause lengths, resource
   names, state transitions are metadata with no inference to content. What remains taboo:
   hypothesis text, samples/curves, `e.message`, paths, target app. A 5-line ring buffer violates
   none of it.
6. **Weg B via the emulator is the expensive way to the goal "real engine in the test".** The
   cheap way is the JVM ONNX runtime (priority 3 in §4): same Kotlin pipeline, same models,
   seconds instead of minutes per run, no PulseAudio chain. The emulator then only for
   AudioRecord/FGS/level.
7. **"Fresh install" is a state the emulator can reproduce** (`pm clear` + `drop_caches` + model
   copy + first start). This one run is missing so far and is cheap (30 min).
8. **The status mapping fallback "Audio-Start fehlgeschlagen" for ASR/consumer/stall errors** is
   a diagnosis eater: the user reports "audio start", we search in the audio stack, but the cause
   was `asr_final_error`. At least three texts must be separated (audio / recognition / system
   stopped) — not part of the debugging, but a direct consequence of it.
9. **Do not repeat:** read watchdog logic off the status row. `status_shown` is the side effect;
   the truth is `stop_cause`/`start_outcome` (§5). And: never again accept an emulator level
   finding without a precondition assert on the audio chain.

---

## 7. Proposed order (one week, parallelizable)

| Day | Work | Layer | Result |
|---|---|---|---|
| 1 | piper + `make_korpus.py` + `watchdog_twin.py` + `engine_cost_curve.py` | S0 | 3 corpus sets (60/120/180 s), oracle JSONs, cost curve decode_ms/RSS vs. T |
| 1–2 | F37 broad (controller clock, thread factory, service dispatcher/deadlines injectable) | code | 483 tests still green, no behavior change |
| 2–3 | KorpusTaktTest on virtual time + corpus; error code enumerator; `errorTextRes` pure | S1/S2 | auto-stop times = oracle; mapping gaps documented |
| 3–4 | Robolectric service tests: start races, DOWN/UP fuzzer | S2 | symptom-1 race red → fix → green |
| 4 | Export v2 (§5) + debug file dump | code | the next user export is self-explanatory |
| 5 | `emu_korpus_run.sh`, 6 long runs, fresh-install run, cold/warm matrix | S3 | CSV with auto-stop times, RSS, start_ms cold/warm |
| afterwards | product decisions: F43 threshold/hold semantics, preload, windowed streaming | — | data-driven |

**Immediately and without code (today):** read the user's export #3 for `fgs_stopped_by` and
`mic_rel`. If it says `autostop`, symptom 3 is explained, and the week starts at S0 with the
pause statistics instead of a bug hunt.
