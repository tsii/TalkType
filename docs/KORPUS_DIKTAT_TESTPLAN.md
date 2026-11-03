# Corpus dictation test plan — real (public-domain) audio input in the
emulator/on the phone

**Goal:** decide whether the dictation abort/corpus case is **sensibly testable
now** in the simulator/emulator (<device>, headless) — including feeding
real audio from a corpus. Documentation/plan only, no code/build/git. RULE 9:
no audio/text content in logs; corpus = existing project test assets (already
used in M4d) or synthetic.

## 0) Current situation (evidenced, with paths)

- **AVD/emulator:** `vk-api36` = **x86_64** / 4 cores / 1536 MB / API 36
  `google_apis` (`<workdir>/.android/avd/vk-api36.avd/config.ini`); container
  without a display ⇒ headless operation only; the IME is not shown there
  (`mInputShown=false`), `adb`/`connectedDebugAndroidTest` work. Emulator
  currently not started.
- **Engine:** `onnxruntime-android:1.31.1` x86_64 `.so` → **SIGSEGV in dlopen**
  (arm64/device ok); candidate **F32-B** (core 1.28.2 from the
  sherpa-onnx-1.13.8 AAR + 1.31.1 bridge) is being checked as a probe (result
  open; probe material `/tmp/sherpa_tree.json` present). Model is on hand:
  `.models/` (int8 ONNX, 671 MB).
- **Seams:** `AudioSource` interface (`app/src/main/java/de/voicekeyboard/audio/
  AudioSource.kt:24`), debug `FakeWavAudioSource` (F30, time-accurate 10-ms
  blocks, silence/dropouts/`stopAfterMs`), androidTests
  `dictation/DictationPipelineTest.kt` (S1: WAV → `feedAudio` 320-sample blocks
  → real engine, M4d 6/6 green **on arm64**; M3: virtual microphone recipe),
  `MicCaptureTest.kt` (real AudioRecord + level).
- **Measurement fields (all present):** `cap_blocks`, `cap_stall_ms`,
  `loud_s`, `mic_rel`, `status_shown`, `abort_reason`, `hyp_count`,
  `hyp_gap_ms`, `buffer_s`, `decode_ms_max/p95`, `audio_s`, `first_hyp_ms`
  (`debug/DebugInfoFormatter.kt`).

## 1) Four routes (effort / risk / expressive power)

| | A) androidTest + FakeWavAudioSource + real engine (service/coordinator level) | B) androidTest reads WAV (`/sdcard`/assets) → `AudioSource` seam, realtime cadence 30–60 s, assertions on hypothesis growth | C) emulator virtual microphone (host audio, `-allow-host-audio`) | D) engine stub (no ONNX), only coordinator/cadence/buffer |
|---|---|---|---|---|
| **Checked** | F30 source + real coordinator + real engine, full 🐞 fields; IME service wiring | exactly the M4d S1 path scaled: decode quality/backlog (C2), cadence, final quality; `hyp_count`/`buffer_s`/`decode_ms_*` over 30–60 s | **real AudioRecord + FGS + routing** (the layer that export #2 suspects); level via `LevelPeak` | coordinator/watchdog/silence/status deterministically (F30/F31 inventory), CI-capable |
| **NOT checked** | real microphone, api 37 policy | microphone, FGS, IME UI | engine only if F32-B is green; IME UI in headless (see below) | **no transcription**, no backlog, no level |
| **Blocker** | **F32-B** (x86_64 SIGSEGV); headless IME UI | **F32-B** for the emulator — **on the phone (arm64) it runs IMMEDIATELY** (M4d 6/6 green) | F32-B + headless audio (see below) | **none** — runs today |
| **Effort** | ~2–4 h (test + wiring, after F32-B) | ~2 h (fixture + 1 test class; the S1 pattern exists) | ~2 h setup (recipe exists: `tools/emulator_audio_up.sh`) + orchestration | ~1–2 h (extend the F30 tests, e.g. 30/60-s backlog curves) |
| **Risk** | medium (emulator audio/timing) | **low** (no audio stack, deterministic) | high (Qt/audio stack in the container, only on 5560) | **zero** (JVM test) |
| **CI-capable** | yes (after F32-B, offline) | yes (offline) | **no** (host PulseAudio, exclusive emulator) | yes |

**C headless check (the important question):** the measured recipe in
`tools/emulator_audio_up.sh` (checks 2026-10-30) says explicitly: **`-no-window`
does not work** (the headless branch has no `pa` driver, "Could not init `pa'
audio driver" **and** without `-allow-host-audio` the microphone is zeroed).
**But** the intended replacement — `QT_QPA_PLATFORM=offscreen` +
`-qt-hide-window` — needs **no physical display** (the offscreen QPA renders
into a memory buffer). ⇒ **C is in principle possible in the displayless
container**, but: (1) the emulator must start **without** `-no-window` (adjust
the current headless invocation), (2) the PulseAudio host stack must run, (3)
the **IME UI stays closed** (`mInputShown=false`) — a real mic hold gesture
cannot be driven headless; in C only the **capture half**
(AudioRecord→FGS→level→coordinator) is testable in an instrumentation test
(M3 pattern), not the full IME session.

## 2) Corpus — available OFFLINE (all paths checked, measured today)

| Asset | Length | Format | Note |
|---|---|---|---|
| `testdata/de_smoke.wav` | **6.4 s** | 16 kHz / s16 / mono | M4d golden source (legitimate as a test asset) |
| `testdata/de_part1.wav` | **2.8 s** | ditto | part 1 |
| `testdata/de_part2.wav` | **3.1 s** | ditto | part 2 |
| `testdata/de_pause.wav` | **9.9 s** | ditto | **loud** (MaxAbs 27281 ≈ **−1.6 dBFS**) — not a silence fixture despite the name |
| `testdata/cut/*.wav` | **41 files, total 88.9 s** (1–6 s segments) | ditto | cut windows from `tools/make_cut_windows.py` |
| `/tmp/seg_de_multi.wav` | 9.6 s | **22,050 Hz** | resample or drop |

**Total: ~111 s of real 16-kHz German audio available offline** ⇒ 30/60-s
fixtures are trivially generated without network: **concatenation**
`de_smoke + de_part1 + de_part2 + de_pause` = 22.2 s, then **loop with 0.3 s
of synthetic silence** between repetitions up to 30 s or 60 s (a one-time
Python step, no asset commit needed — build the fixture in the test scaffold).
**Silence sections** (for F31 tests) are **generated synthetically** (zero
blocks) — `de_pause` is continuously loud despite its name.
**What looping distorts:** (a) TDT greedy sees repeated hypotheses on a
repeated sentence → assertions must **not** check text novelty but counters/
curves (rule 9 anyway); (b) loop boundaries can artificially stabilize
decoding boundaries (same sentence, same contexts) — **backlog/cadence
measurement remains unaffected** (that is precisely the C2 question); (c) the
level stays constant (no natural speech dynamic range) — irrelevant for
silence/watchdog tests, a disadvantage for quality comparison. For real quality
tests: **phone + M4d golden** stays the reference; loop fixtures answer
**timing and abort questions**.

## 3) Test protocol (concrete, goal: route B on the emulator or phone)

**Flow (path per tick):** fixture (`testdata/de_long30.wav` / `de_long60.wav`,
generated in the test scaffold per §2, 16 kHz mono s16) → 10-ms blocks (320
samples), **realtime-paced** (10-ms cycle, `SystemClock` reference; tolerate
jitter < 5 ms, otherwise note the tick margin) → `DictationCoordinator`
consumer (`AudioSource` seam, see current situation) → `DictationController` →
**real engine** → counters. **Variant "without service"** = the S1 pattern
from `DictationPipelineTest.kt` (proven, headless-capable); variant "with
service" = A (after F32-B, with `FakeWavAudioSource` cadence).

**Duration/cadence:** 2 fixtures (30 s, 60 s) × 2 runs (stability). Cadence
parameters as in production (`DEFAULT_CHUNK_MS` 2000, pace brackets 2000/6000,
guard 2.0 s). **Measurement points:** after every tick (2 s) a 🐞 snapshot of
the fields from §0 (in the test directly from the coordinator/controller
getters — no clipboard path needed); final snapshot after `stop()` (end state).

**Success criteria (all apply per run, otherwise = FAILURE with field
snapshot):**

| # | Criterion | Value |
|---|---|---|
| E1 | `hyp_count` grows monotonically over 30 s | ≥ 10 (≈ 14 ticks × guard 2 s) or ≥ 20 at 60 s; **no** window of > 4 s without a new hypothesis |
| E2 | `hyp_gap_ms` | < 4000 at the end (measured as in the 🐞 export) |
| E3 | **No C2 backlog:** `buffer_s` vs. `audio_s` | `buffer_s ≤ audio_s + 1.0` **at the end**; intermediate snapshots: `buffer_s` must not rise above 5 s |
| E4 | `decode_ms_max` / `decode_ms_p95` | < 4000 / < 2000 (RTF ≪ 1 on the 4-core emulator; calibrate the limits on the first run and record them here) |
| E5 | `abort_reason` | stays "-" during the run, at the end **`final`** (only via the test's `stop()`); `lastError` = "-" |
| E6 | Watchdog false alarms | **0**× `audio_error:stall`, **0**× `audio_error:silence` with real corpus audio (F31b `everLoud` regression) |
| E7 | `loud_s` / `cap_blocks` | `loud_s ≈ audio_s ± 1.0`; `cap_blocks ≈ audio_s × 100 ± 5 %` |
| E8 | Text golden (phone variant only) | end text contains the §4.1 golden (M4d pattern, word identity ≥ 95 %) — do **not** attempt this in the emulator on a loop fixture (see §2) |

**Abort criteria of the test run (kill switch, not "test failed"):**
`decode_ms_max > 8000` twice in a row → stop the run and record it as "engine
too slow on x86_64" (then route D for timing questions); native SIGSEGV →
F32-B protocol (document the candidate, no debugging in the test).

## 4) What the test CANNOT do (and what stays phone-only)

- **Real microphone hardware/routing DSP** (AGC/noise suppression,
  `VOICE_RECOGNITION` source behavior on the device): emulator virtual
  microphone = clean host signal, no DSP — the **−56.2-dBFS user case is not
  reproducible here** (it is hardware/OS policy, not an app path).
- **FGS microphone permission api 37 + `POST_NOTIFICATIONS`:** emulator =
  api 36 with a lenient policy ⇒ the main suspect from
  `ANALYSE_DIKTAT_ABBRUCH_2.md` (system-side muting after ~1 s) stays
  **phone-only**.
- **IME UI/stop gestures** headless: `mInputShown=false` ⇒ hold gesture,
  `mic_rel` paths, status display not drivable (the F30 tests cover the logic
  without UI).
- **Thermal/memory on the phone** during continuous runs — only approximately
  on the emulator.
⇒ **Stays phone-only:** 🐞 export #3 (decision tree
`ANALYSE_DIKTAT_ABBRUCH_2.md` §9) remains the only valid evidence for the real
abort; the test plan only eliminates the **app half** (decode backlog,
watchdog false alarms, stop paths) as cause candidates.

## 5) Order / recommendation (with the F32-B branch)

| Step | Content | Effort | Prerequisite |
|---|---|---|---|
| 1 | **Extend route D** (today, without engine): 30/60-s "backlog curves" from the `FakeAudioScenarioTest` pattern (stub engine with a constant 150-ms latency) ⇒ CI net for cadence/buffer/watchdog, independent of F32-B | ~1–2 h | — |
| 2a | **F32-B green:** route **B on the emulator** (fixture §2 + S1 pattern, criteria §3) — complete decode/C2 answer on x86_64 | ~2 h | F32-B |
| 2b | **F32-B red:** route **B on the phone** (arm64, M4d 6/6 green) via `connectedDebugAndroidTest` — same fixture, same criteria, plus E8 golden; the emulator engine route waits for the next runtime candidate | ~2 h + 1 adb run | — (phone) |
| 3 | **Route C** only if 2a/2b are green and the **FGS/AudioRecord layer** is additionally to be secured on the emulator (offscreen Qt start without `-no-window`, host PA, M3 pattern, capture half without IME UI) | ~2 h + orchestration | 2a/2b green |
| 4 | **Export #3 from the phone** (in parallel, independent of all steps) — apply the decision tree §9 | user run | — |

**Decision point:** 2a vs. 2b is triggered **exclusively** by the F32-B result
(candidate core 1.28.2 dlopen test → SIGSEGV gone = 2a, otherwise 2b). Route C
is built only after green decode runs — otherwise a second risk (emulator
audio stack) is stacked on an engine basis that is not yet proven.
