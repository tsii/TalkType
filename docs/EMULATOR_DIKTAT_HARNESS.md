# Emulator dictation harness — plan for a reproducible reproduction of the abort

**Purpose:** deterministically reproduce the dictation abort in the emulator
(<device>, api 36) ("put a real speech message into the emulator") to see
when/why the recording or transcription aborts. **Analysis/plan only** — no code,
no build, no git. RULE 9: no audio content; test WAVs may be synthetic
(sine/silence), golden audio stays `testdata/de_smoke.wav`.

## 1) Current state of testability (evidenced)

| Building block | State | Evidence |
|---|---|---|
| Pipeline test with real WAV + real engine | **exists** (M4d, S1: `de_smoke.wav` → `controller.feedAudio` in 320-sample blocks → final text = §4.1 golden; S2–S4 editor sinks; M3: audio **from the virtual microphone**) | `app/src/androidTest/java/de/voicekeyboard/dictation/DictationPipelineTest.kt` (brackets S1/M3, `feedAudio` ~line 99); 6/6 GREEN on **device** (`state/progress.md` M4d) |
| Real AudioRecord path + level channel | **exists** (M1/M2/M4: samples + `LevelPeak` proof) | `app/src/androidTest/java/de/voicekeyboard/MicCaptureTest.kt` |
| Emulator virtual microphone recipe | **exists and measured** (`-allow-host-audio` — without it: **mic is zeroed**; no `-no-window` (headless has no `pa` driver), instead offscreen + `-qt-hide-window`; PulseAudio + null sink; operating sensor **65 %** ⇒ peak ~19800, RMS ~1900) | `tools/emulator_audio_up.sh` (comment block 1–25), `tools/emulator_audio_down.sh` |
| Engine run on 5560 | **Blocker:** native **SIGSEGV in `libonnxruntime.so`** (dlopen/call_constructors, worker thread only) — dictation/engine in the IME process there is **not validatable** | `docs/PROJECT_STATE.md` "environment" (main evidence: `HANDOFF.md`) |
| Injection seam for the source | **exists**: `interface AudioSource` (`start/read/stopAndDrain/release`, `app/src/main/java/de/voicekeyboard/audio/AudioSource.kt:24`); in production `AudioRecordSource` in `buildCoordinator` (`ime/VoiceKeyboardService.kt:651`); engine behind the `AsrEngine` interface (`asr/`) | — |
| Measurement fields for the scenarios | **all present** (F26a/b + F28b): `cap_blocks`, `cap_stall_ms`, `mic_rel`, `status_shown`, `abort_reason`, `hyp_count`, `buffer_s` (🐞 export, `debug/DebugInfoFormatter.kt`, drift check) | `docs/ANALYSE_DIKTAT_ABBRUCH_2.md` §5 |
| Stall watchdog | **IN**: `ERR_STALL`/`STALL_LIMIT_MS = 3000` (`ime/DictationCoordinator.kt:71/73`) → `audio_error:stall` path | `docs/PROJECT_STATE.md` "F28" |

**Most important constraint:** the engine SIGSEGV affects **every** process that
loads the native ONNX lib on x86_64. **Hypothesis (to be checked before A/B run
seriously):** the 5560 AVD is x86_64 and uses the x86_64 `libonnxruntime.so` — the
`dlopen/call_constructors` crash pattern is typical of an inconsistent x86_64
library, not of the ONNX model content (the same runs 6/6 green on the
device/arm64). Litmus test: swap in a known-good x86_64 ONNX runtime build (one
build run, not a feature).

## 2) Three options (effort / risk / expressive power)

### A) Instrumented WAV feed through the pipeline (AudioRecord bypassed)
**What is reproduced:** a 30–60-s WAV (synthetic: speech equivalent = sine tones,
silence sections, level ~-20 dBFS — no real voices, rule 9) in **real-time
cadence** (10-ms blocks, `SystemClock`-paced) → `DictationCoordinator` consumer →
real `DictationController` + **real engine** → final text/counter asserts.
**What is NOT:** mic driver, route, FGS, `LevelPeak` from real capture, stop/abort
via UI events (the test triggers stop/abort directly), pointer paths.
**Files/classes:** 1 new androidTest class (`dictation/LongDictationHarnessTest.kt`),
WAV fixtures 30 s/60 s (`testdata/`), possibly a pacing feed helper; ~1 file.
**Duration:** 1× 30 s + 1× 60 s real time ⇒ **~2 min/run**. **CI-capable:** yes
(offline) — **but only on device or only after fixing the x86_64 runtime crash**
(engine!).
**Scenarios:** continuous dictation (30/60 s), silence in between, decode
quality/backlog (`decode_ms_max`, `buffer_s` over 60 s — C2 progression!). Release/
focus loss **not** (no UI event).

### B) Emulator virtual microphone (host audio → real IME)
**What is reproduced:** the **whole chain**: host PulseAudio → `paplay` loop (WAV)
→ virtual mic → **real AudioRecord** → FGS handshake (F28c) → coordinator →
**real IME session** with real hold gestures (adb input events: DOWN/UP on a
schedule, `wm` commands for focus loss) → complete state machine + 🐞 fields.
**What is NOT:** the real phone microphone, the **api 37 FGS microphone policy**
(emulator = api 36 — the main suspicion area of the user's case is exactly there,
different), real routing/mute states.
**Files/classes:** none new (recipe exists: `emulator_audio_up.sh`, M3 driver in
`DictationPipelineTest`); only a new orchestrator script with a schedule (paplay +
`input swipe`/`wm` + 🐞 export via the adb clipboard).
**Duration:** ~5–10 min per scenario (manual orchestration). **CI-capable:**
**no** (host PulseAudio + exclusive adb use). **Risk:** medium/high — emulator
audio on 5560 notoriously flaky (see script comment), **engine crash remains** ⇒
the transcription half (decodes, `hyp_count`) stays blind in the emulator as long
as the §1 hypothesis is not fixed. B then covers only: capture field, FGS
handshake, stop/abort paths, status.

### C) Debug-only fake AudioSource (deterministic, without emulator audio)
**What is reproduced:** `FakeAudioSource : AudioSource` (script: time blocks from a
synthetic WAV, configurable **silence / dropouts (0 blocks) / abort after N ms**),
injected via a debug flag in `buildCoordinator` (seam
`VoiceKeyboardService.kt:651`); optionally `FakeAsrEngine : AsrEngine` (counts
decodes, constant latency) ⇒ **without ONNX loading** → also runs **on the
SIGSEGV-5560**. The UI is driven: adb DOWN/UP/hide events on a schedule; assertion
via 🐞 fields (all present).
**What is NOT:** mic driver/route/FGS real behavior, real decode quality (only
with the real engine, which brings back the x86_64 problem), api 37 policy.
**Files/classes:** `audio/FakeAudioSource.kt` (~120 lines, **debug source set**),
debug entry point (query param/intent extra) in VKS, 1 test class per scenario;
2–3 files.
**Duration:** seconds to ~1 min per scenario (time-available!). **CI-capable:**
**yes** (instrumented, offline, deterministic) — the only route to a
**regression-capable** abort test fleet.
**Scenarios:** **all five** (see §4), including dropouts > 3 s (watchdog) and
release during start — deterministically repeatable.

## 3) Recommendation (order + rationale)

1. **C immediately** — deterministic regression network over coordinator/watchdog/
   state machine; the only way to test all abort paths **repeatably and
   CI-capably**, and the only one that runs on 5560 despite the engine SIGSEGV.
2. **Before A: check the x86_64 runtime hypothesis** (1 build, swap the lib) — if
   green: **A for decode quality/C2 progression** (30/60-s continuous dictation
   with the real engine, `decode_ms_max`/`buffer_s` curves) — this also answers the
   open C2 question "does the backlog grow over 60 s?" without the phone.
3. **B only if 2. is green** — then as the final proof of the AudioRecord/FGS stop
   chain in the emulator (F28c handshake, `mic_rel` paths). Otherwise wait: without
   the engine, B has only half the expressive power and the worst risk per hour.
4. **The phone stays ground truth** for the user's case itself (api 37, real mic) —
   the harness does not replace export #3, but makes it evaluable (fields +
   comparison expectations from §4).

## 4) Scenario table (target values = expected, measured via 🐞 fields; 10-ms capture blocks)

| Scenario | Input (schedule) | Expected measurements | Abort criterion (error if…) |
|---|---|---|---|
| **S1 continuous dictation 30 s** | Fake/mic WAV (tones, ~-20 dBFS) 30 s; UP at 30 s | `cap_blocks` ≈ 3000; `cap_stall_ms` < 1000; `mic_rel` = `up:≈30000`; `status_shown` = dictation until finalizing; `abort_reason` = **final**; `hyp_count` ≥ 10 (2-s cadence) | `hyp_count` < 10 OR `decode_ms_max` grows per tick > 2× (C2 backlog) OR `abort_reason` ≠ final |
| **S2 silence 60 s** | 60 s zero-level WAV; UP at 60 s | `cap_blocks` ≈ 6000, `cap_stall_ms` < 1000 (silence ≠ stall!); `hyp_count` ≥ 8; **no** commit (empty hypotheses); `abort_reason` = final | `cap_stall_ms` > 3000 (wrong stall) OR watchdog false alarm `audio_error:stall` |
| **S3 dropout > 3 s** | 5 s tone, **4 s no blocks** (fake: 0 delivery), 5 s tone | `cap_stall_ms` runs > 3000; watchdog (3000 ms) fires; `abort_reason` = **audio_error:stall**; `status_shown` = error; `mic_rel` = `-` (no UI event) | Watchdog does not strike within 3.5 s OR session runs on forever ("dictation eternity" = the user's bug) |
| **S4 release during start** | DOWN t=0, UP t=0.5 s (engine start > 0.5 s) | `mic_rel` = `up:≈500`; `abort_reason` = **final** (late stop, `VKS:450-469`); `audio_s` < 1.0; `hyp_count` ≤ 1 | `abort_reason` = `michold_cancel` (wrong CANCEL) OR session hangs in STARTING |
| **S5 focus loss at 10 s** | 10 s tone, then IME `onWindowHidden`/editor switch | (hide) `mic_rel` = `hidden:≈10000`, `abort_reason` = final; (editor) `abort_reason` = **editor_change** | Cause ≠ the matching path OR `status_shown` stays dictation after the event |

## 5) What this teaches about the user's case (1.2-s stop + −56.2 dBFS) — and what not

**Provable in the emulator (with C/A/B):**
- **S3:** whether the stall watchdog (F28b1) correctly translates the user's
  "1-s-silent microphone" state into `audio_error:stall` — the gap that was still
  open in export #2.
- **S4/S5:** whether the stop/abort paths (`mic_rel` tags, `abort_reason` semantics,
  `status_shown` coherence) behave exactly as the analysis says
  (`docs/ANALYSE_DIKTAT_ABBRUCH_2.md` §1/§4) — i.e. whether the 1.2-s `final` in
  export #2 is explainable **only** by a real UP, or whether a hidden/deferred path
  can also produce it.
- **S1/S2:** whether the capture counters/stall measurement (F28b) are correct at
  all (sanity controls) before they are interpreted on the phone.
- **F28c handshake:** FGS-ready before AudioRecord in emulator api 36 behavior.

**In principle measurable only on the phone (the harness is no replacement):**
- **The −56.2-dBFS silent microphone itself:** virtual mic = synthetic signal, no
  hardware/mute/routing states; whether the api 37 system silences the mic after
  ~1 s (FGS microphone permission without `POST_NOTIFICATIONS`) is an **OS policy
  question** that cannot be replicated on the api 36 emulator.
- **The user's actual UP time** (H-A/H-B from the analysis): needs export #3 with
  `mic_rel` + `cap_stall_ms` from the real device.
- **Real decode quality** on the user's model/environment (memory, thermal, api 37)
  — except when A runs in the emulator after an x86_64 fix (then: a good
  approximation).

**Consequence:** the harness (C→A→B) makes the **app half** of the case
reproducible; the **system half** (mic/FGS api 37) remains a question for export
#3 — but the harness then shows exactly what to look for in the export (target
values §4).

## 6) Implementation F30 (status)

- **Option C partially implemented (2026-10-31, during builds 23/24):**
  `audio/FakeWavAudioSource.kt` in the **debug source folder** — WAV feed
  (`testdata/de_smoke.wav`, 16 kHz mono s16, looped) as 10-ms blocks, time-accurate
  via an injectable clock; scenarios `throughput/silence/gap/short`
  (`silenceFromMs`, `gapFromMs/gapToMs`, `stopAfterMs`).
- Injection: field `VoiceKeyboardService.debugAudioSourceFactory` (release: always
  null → `AudioRecordSource`, production unchanged); the device side (adb UI
  schedule) is still open.
- 4 determinism tests in `testDebug/.../FakeAudioScenarioTest.kt`
  (continuous/silence/dropout/1.2-s-UP) — all green, including the user's case as
  evidence (`final`, audio_s ≈ 1.2).
