# Plan: dictation reliability on the real device (v0.1.6)

**Status:** 2026-10-28 · **Role:** analysis/plan (read-only, no code changes)
**Basis:** the user's 🐞 export (API 37, arm64) + report + our inventory.
**Key number that explains everything:** `peak_pct: 2` — that is **not** "quiet
speech", but an input signal **~30× weaker** than in our emulator path.

## 0. Two resolutions first (evidence, not guessing)

**(a) `peak_pct` is an RMS value, not a sample peak.** `AudioRecordSource.rms()`
computes `sqrt(mean((s/32768)²))` (`audio/AudioRecordSource.kt:143-150`) and
reports that to `levelListener` (`:119`); `LevelPeak.note()` stores the maximum
and `percent()` returns `(max*100).toInt()` (`debug/LevelPeak.kt:27/32`). The
field name in the export is misleading, but the number is unambiguous:

| Location | `peak_pct` | RMS (0..1) | int16 RMS | dBFS |
|---|---|---|---|---|
| Phone (user) | **2** | 0.020 | **≈ 655** | **−34 dB** |
| Emulator, M3 test | ~6–65 (depending on signal) | 0.06–0.65 | 1,930 … 19,800 | −24 … −4 dB |

**−34 dBFS RMS** lies below everything a phone microphone delivers at normal
speaking distance (typically −18…−26 dBFS). That is only understandable with a
very low noise floor — and precisely then an ASR with a "silence prior" tips
into fantasy text.

**(b) The engine knows no language setting.** Neither
`asr/ParakeetAsrEngine.kt` nor `asr/OnnxSessions.kt` contains a `language`
parameter (full-text search: no hits). Parakeet TDT 0.6B v3 is multilingual and
decides on its own; the keyboard language affects **only** the layout
(`CaseMapper`, `LAYOUT_REQUIREMENTS`), **not** the ASR. The globe therefore
cannot improve anything during dictation — and that is why it is locked.

---

## 1. Cause separation per symptom

### A — First dictation returns only "Yeah."
| # | Hypothesis | For | Against | Decisive experiment | File if confirmed |
|---|---|---|---|---|---|
| A1 | **Hallucination on (near) silence** — documented: 0.3 s silence ⇒ `Yeah.` (`docs/ASR_CONTRACT.md` §4.3/§4.4, measurement `silence_0.3s`) | `peak_pct=2` = −34 dBFS; `min_audio_s=2.0` decodes the first 2 s of **audio**, whether speech is in it or not | per the user, "Yeah." came after *one* first dictation with words — then the level must have been briefly higher | **Device:** same sentence, note `peak_pct` **and** text together (2×) | `asr/` + level gate (new) |
| A2 | **Wrong capture path** (source/route) attenuates by ~30 dB | `MediaRecorder.AudioSource.VOICE_RECOGNITION` (`audio/AudioRecordSource.kt:93`) — heavily processed on some devices (NR/AGC/limiter) | The emulator uses the same code and delivers loud audio | **A/B on device:** `VOICE_RECOGNITION` vs. `MIC` vs. `UNPROCESSED` (test build only), compare `peak_pct` | `audio/AudioRecordSource.kt:93` |
| A3 | Routing to the wrong device (BT-SCO/headset) | `AudioRouter` knows `WIRED > BLUETOOTH_SCO > BUILTIN` | The user mentions no headset | Read the status row/`route` in the export | `audio/AudioRouter.kt` |

### B — English sentences: no output at all
| # | Hypothesis | For | Against | Experiment | File |
|---|---|---|---|---|---|
| B1 | **Level too low** → hypothesis consists only of `<blk>` (empty text) | same cause as A1/A2; English has more sibilants/high frequencies, which disappear first under attenuation | German worked "relatively well" — at the same level? | **Device:** identical level check with an English sentence; `peak_pct` + text | `audio/AudioRecordSource.kt` |
| B2 | **No commit because E8 is too strict for short runs** | E8: commit only from **3** hypotheses, last **2** words unprotected (`COMMIT_RULE_DECISION_2.md` §3 C1/C2) | The **final decode** always writes (D3 rule) — "no output at all" would then be impossible | **Emulator:** 6-s English fixture ⇒ expect text; if text comes, it is device-specific | `commit/`, `dictation/DictationController.kt:144-172` |
| B3 | **Ownership lost** (editor made corrections/composing) → `revise()` writes nothing | Target app in English (e.g. with autocorrect) actively changes text; `DeltaTyper.revise()` returns `null` on a `regionMatches` failure (`editor/DeltaTyper.kt:64-72`) | The German case would have the same problem | **Device:** compare in a "dumb" app (AOSP messenger/notes) against a "smart" one (Signal/Gmail) | `editor/DeltaTyper.kt` |
| B4 | Model quality for English at *this* level is worse | multilingual model, but SNR-poor | unknown | see B1 | — |

### C — "Input is frequently ignored"
| # | Hypothesis | For | Against | Experiment | File |
|---|---|---|---|---|---|
| C1 | **RTF 0.80**: a 10 s dictation needs 8 s of compute → hypotheses arrive too late/are skipped | `rtf: 0.80`, `threads: intra=4 inter=1`; `DEFAULT_HYPOTHESIS_MIN_INTERVAL_MS = 2000` (`AsrThreadConfig.kt:62`) | The final decode still runs | **Device+emulator:** measure `intra=6` vs. `4` (RTF, first hypothesis) | `asr/AsrThreadConfig.kt` (`MAX_THREADS = 6`, `:64`) |
| C2 | **Level gate missing**: empty/blind windows are decoded (cost) or displayed as "Yeah." | no level gate in the code; `min_audio_s` checks only **length** | — | **Unit+emulator:** window with RMS < 1 % ⇒ no decode (test) | `dictation/DictationController.kt` |
| C3 | User starts speaking too early (FGS/STARTING) → first words missing | Recording starts only after `startFgs()` + coordinator start (`ime/VoiceKeyboardService.kt:306-320`) | — | **Device:** 6-s fixture with 2 s lead-in silence vs. speaking immediately | `ime/VoiceKeyboardService.kt` |
| C4 | `notif=no` → FGS notification invisible, user thinks something "is running" that is not | `permissions: record=yes notif=no` | does not directly affect the ASR | Check the status row | `ime/` |

### D — Globe rejected during dictation
**By design, not an error:** `VoiceKeyboardService.switchToNextLanguage()` aborts
when `state != IDLE` and shows `R.string.language_switch_blocked_recording`
(`ime/VoiceKeyboardService.kt:680-682`, text `res/values/strings.xml:57`).
**Reason:** a switch mid-run would switch the layout **and** the case mapping
while the committed text stands in the old orthography and the hypothesis is
decoded from the old audio → mixed-script revisions and rollbacks across language
boundaries break the commit invariants (E8/E9.2). **Since the ASR has no language
setting anyway (§0b), a switch during dictation brings nothing.** → **no
change**, at most a wording clarification ("only after releasing").

### Order by effort/benefit
1. **A2/B1 (level/capture path)** — 10 min test, biggest effect (30-dB deficit).
2. **A1/C2 (level gate + "Yeah." suppression)** — 1–2 h, prevents fantasy text.
3. **C1 (threads/interval)** — 30 min measurement, RTF 0.80 is borderline.
4. **B3 (editor ownership)** — 1 h comparison test in two apps.
5. D: no change (doc text).

---

## 2. `peak_pct: 2` — calculation and consequence

- RMS 0.02 ⇒ **−34 dBFS**. A 16-bit sample with RMS 655 is **not** plausible at
  30 cm speaking distance (typically 2,000–8,000).
- Our emulator path (M3 test,
  `DictationPipelineTest.m3_realEngineFromMicrophone_commitsGoldenText`,
  `OK (1 test)`) sees RMS ≈ 1,930 (≈ 6 %) and with loud playback up to ~60 % —
  so **30× more level** with identical code.
- **Conclusion:** not the app logic, but the **capture path** is the suspect.
  Concrete candidates in this order:
  1. **`MediaRecorder.AudioSource.VOICE_RECOGNITION`**
     (`AudioRecordSource.kt:93`) — heavily processed in a device-specific way;
     on Pixel devices this can attenuate significantly with noise
     suppression/AEC. **Test:** `MIC` and `UNPROCESSED` (API ≥ 24) against each
     other, same sentence, note `peak_pct`.
  2. **Routing** (`AudioRouter`: `WIRED > BLUETOOTH_SCO > BUILTIN`) — if SCO is
     active, the communication microphone is often quieter; carry the route in
     the export.
  3. **Device/OS level** (microphone cover, case, `voice_communication` path) —
     can be narrowed down via (1)/(2).
- **Important:** per the comment, `VOICE_RECOGNITION` is "AGC yes, NR depending
  on device" — precisely this "depending on device" is the problem. An A/B test
  decides in 10 minutes.

---

## 3. Measurement plan (emulator **and** device, 3 minutes for the user)

| # | Measured quantity | Measurement point | Threshold (go) |
|---|---|---|---|
| M1 | **`peak_pct`** (max RMS per session) | `debug/LevelPeak` → debug export | **≥ 8 %** (−22 dBFS) for speech |
| M2 | **RTF** = processing/audio | `DictationCoordinator:96` | **≤ 0.5** (today 0.80) |
| M3 | **First hypothesis latency** | `firstHypothesisLatencyMs` (`:70`) | ≤ **1.5 s** (today 1.12 s — ok) |
| M4 | **Hypothesis gaps** (≥ 2 s without a new hypothesis while recording) | counter in `DictationCoordinator` | 0 gaps > 2 s |
| M5 | **Discarded windows** (empty text at RMS > threshold) | counter | 0 for speech |
| M6 | **Discarded revisions** (ownership/60 characters) | `DeltaTyper` counter | 0 |
| M7 | **Idle at the end** (last text → status row "Ready") | timestamps | ≤ 3 s |
| M8 | **Language-ID switches** (German ↔ English within the same dictation) | compare text vs. expected language | 0 unexpected switches |
| M9 | **Threads/interval** (`intra`, `hypMs`) | export | documented per run |

**3-minute protocol for the user:** (1) 6 s German, 2 s pause beforehand; (2) 6 s
English; (3) 12 s continuous; (4) copy the 🐞 export after each run. From M1/M2/M4
the decision follows whether level or compute time is the cause.

---

## 4. Test cases (emulator with tone injection **and** device)

| # | Case | Expectation | Allowed deviation | Emulator reproduction |
|---|---|---|---|---|
| T-a | German 6 s, 2 s silence beforehand | §4.1-close: word identity ≥ 95 %, **no** "Yeah." | CED ≤ 2 % | `testdata/de_smoke.wav` with a 2 s silence prefix (script) |
| T-b | English 6 s | English text, ≥ 90 % word identity | CED ≤ 5 % | **missing** → new fixture `testdata/en_smoke.wav` (espeak `-v en`) |
| T-c | German with an English word ("Das ist ein Test-Build") | German scaffold + English word preserved | 1 word may deviate | `testdata/de_smoke.wav` (contains "Dikti-Funktion") |
| T-d | 12 s continuous | text complete, commit backlog ≤ 2 words | 1 word | `de_pause.wav` (9.9 s) + `de_part2` concatenated |
| T-e | Release + press again immediately | second dictation writes **after** the first, no loss/duplication | 1 character | two sequences in one instrumented test |
| T-f | Language switch before / during / after the dictation | before/after: switch ok; during: hint, **no** abort | — | emulator + UI test |

---

## 5. What is different on the real device — and the 3 next candidates

**The difference is not the app logic** (M3 proves the same code succeeds on the
emulator), but **level + compute time**:

1. **Microphone path (level):** `VOICE_RECOGNITION` + device DSP + possibly
   routing ⇒ −34 dBFS. → **First:** A/B `VOICE_RECOGNITION` / `MIC` /
   `UNPROCESSED`, measure `peak_pct`. **That is the cheapest test with the
   biggest effect.**
2. **Level gate before the decode** (prevents "Yeah." and saves compute time):
   windows with RMS < 1 % are **not** decoded; status row optionally "no sound
   recognized". Prevents the documented silence hallucination at the root.
3. **Compute time:** `intra=4` → `6` (`AsrThreadConfig.MAX_THREADS = 6`),
   measure the hypothesis interval on the device (2000 ms default). RTF 0.80 at
   10 s ⇒ 8 s lag; with 6 threads ≤ 0.5 is realistic.

**Not** as a first measure: a language setting for the ASR (the engine knows no
language, §0b), relaxing the commit rule (E8 was just calibrated), unlocking the
globe (pointless without a language parameter).

## ASSUMPTION lines
- `ASSUMPTION: peak_pct is RMS-based (code evidence above) — the emulator comparison`
  `figure "65 %" then corresponds to RMS 0.65, i.e. a very loud, partially clipped signal.`
- `ASSUMPTION: the English test fixture does not exist yet; one must be created for T-b`
  `(espeak-ng -v en).`
