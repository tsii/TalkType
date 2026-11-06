# Concept: push-to-talk dictation with local ASR

**Purpose of this document:** transferable description of a proven architecture
for push-to-talk dictation (hold the key → speak → text appears at the cursor
position). The mechanisms were implemented on a Linux desktop (ThinkPad,
CPU-only), tested, and iteratively debugged. This document describes **what
works, why, and which pitfalls must be avoided** — so that the same principle
can be implemented on another platform (e.g. Android) without repeating the
mistakes.

Reference implementation: `stt_button` (Python 3.12, ~3,300 LOC, 179 tests).

---

## 1. Overview

```
 ┌──────────────┐    ┌───────────────┐    ┌──────────────────┐    ┌──────────────┐
 │ Trigger      │───▶│ Audio-Capture │───▶│ ASR (Streaming)  │───▶│ Text-Ausgabe │
 │ (Taste halten)│    │ (16 kHz mono) │    │ (lokales Modell) │    │ (Cursor)     │
 └──────────────┘    └───────────────┘    └──────────────────┘    └──────────────┘
      press/release      Ringpuffer         Transkribiert den       Differenz-Tippen
      = Start/Stop       + Thread           wachsenden Puffer       + Backspace-Revision
```

**Core principle:** the system is a **dictation tool**, not a chat interface.
The text must emerge *live*, be correctable, and must not destroy the user's
text.

---

## 2. The seven core mechanisms (all proven)

### 2.1 Push-to-talk as a state machine

```
IDLE ──press──▶ RECORDING ──release──▶ FINALIZING ──▶ IDLE
                    │
                    └── abort() (Fehler/Timeout) ──▶ IDLE
```

* **Hold the key = record, release = finalize.** No toggle, no double click.
* `start_recording()` is **idempotent** (a second key press does not restart).
* A lock (`threading.RLock`) serializes accesses from the key thread and the
  ASR loop.

> **Android:** start `AudioRecord` on `ACTION_DOWN`, stop on `ACTION_UP`.
> `onKeyDown` can fire multiple times → idempotency is mandatory.

### 2.2 Trigger detection separated from the microphone

The trigger key is detected **independently** of the capture path.

* Desktop: physical key → `keyd` remap → `F24` on a virtual device → direct
  reading of `/dev/input/event*` (pure Python, no C dependency).
* **Filter autorepeat:** ignore events with `value == 2`, otherwise the
  recording restarts on every repeat.
* **Handle press and release separately** — release detection is the critical
  path (see 2.3).
* Resolve the device **by name**, not by path (numbers change on reboot).

> **Android:** hardware key via `KeyEvent` (e.g. `KEYCODE_HEADSETHOOK` for
> headset keys, or a `MediaButtonReceiver`). Note: volume keys are reserved by
> the system; headset/Bluetooth keys are the more reliable route.

### 2.3 Key detection MUST run in its own thread ⚠️

**This is the most important architecture point and was a real bug.**

Problem: ASR inference blocks (CPU-only: 1–3 s per transcription). If key
detection runs in the same loop, **releasing the key during inference is not
processed** → the recording continues even though the user is done.

Solution (two stages):

```python
# 1) Key listener in its own thread
threading.Thread(target=self._listener.run, name="key-listener", daemon=True).start()

# 2) Audio capture in YET another thread that writes into a queue
def _capture_loop(self):
    while self.state == RECORDING:
        chunk = source.read(block=False)
        if chunk:
            self._audio_queue.put(chunk)   # decoupled from the inference
```

The main thread now only consumes the queue and transcribes.

**Why three threads?** Key thread (never block) · capture thread (audio keeps
flowing even while inference computes) · main thread (inference + output).

> **Android:** `AudioRecord.read()` in a `HandlerThread`/coroutine, KeyEvent
> handling on the UI thread (only set flags, never block). Inference in
> `Dispatchers.Default`.

### 2.4 Audio pipeline: 16 kHz mono, s16le

* **Format:** 16 kHz, 1 channel, 16-bit signed little-endian (`s16le`).
  That is the native input format of Whisper/Parakeet — no resampling needed.
* **Capture as a subprocess/stream**, chunks of ~0.2–1 s into a ring buffer.
* On stopping, **mind the order** (⚠️ real bug, see 2.8).

### 2.5 ASR: model choice and decoding parameters

| Model | Type | Parameters | CPU latency (RTF) | Recommendation |
|---|---|---|---|---|
| **Parakeet TDT 0.6B v3** | transducer (non-autoregressive) | 0.6 B | **~0.1–0.5** | ✅ **best choice** |
| faster-whisper large-v3-turbo | seq2seq (autoregressive) | 0.8 B | ~0.5–1.8 | fallback |
| Qwen3-ASR 1.7B | LLM (autoregressive) | 1.7 B | ~2–3 | only for maximum quality |

**Why Parakeet:** transducers decode **greedy and in parallel** (no
token-by-token sampling) → on CPU 5–10× faster than Whisper with better mixed
DE/EN quality. Recognizes 25 European languages automatically.

**Decoding parameters that proved themselves:**

```python
model.transcribe(
    audio,
    language = None,                      # None = automatic detection ("auto")
    task = "transcribe",
    beam_size = 1,                        # greedy: throughput > marginal quality
    temperature = 0.0,                    # deterministic (important for streaming!)
    condition_on_previous_text = False,   # prevents repetition loops
    vad_filter = True,                    # filter silence (with the Whisper engine)
    initial_prompt = None,
)
```

**`temperature = 0.0` is critical:** in streaming, the same buffer is
transcribed repeatedly. With sampling, each pass would yield different text →
flickering. Determinism is the precondition for the commit logic (2.6).

### 2.6 Streaming: "growing buffer" + LocalAgreement commit

**The heart of it.** Without this mechanism you get either delay or
flickering.

```
Audio: [============ wachsender Puffer ============]
                ↓ alle asr_interval_s (1.0 s Audio)
Transkription: "So, da bin ich"  →  "So, da bin ich mal"  →  "So, da bin ich mal gespannt"
                ↓ LocalAgreement: only commit a stable prefix
Getippt:       "So, da bin ich"  →  " mal"                →  " gespannt"
                └── committed ──┘   └───── korrigierbar (Partial) ─────┘
```

**Algorithm (LocalAgreement):**

1. Keep a history of the last `commit_agreements = 2` transcriptions.
2. Compute the **common prefix** of all history entries.
3. Commit only **up to the last word boundary** (the running word stays
   correctable).
4. The committed text is **never taken back** — that prevents flickering.

```python
common_len = len(history[0])
for candidate in history[1:]:
    common_len = common_prefix_len(history[0][:common_len], candidate)
common_str = history[0][:common_len]
cut = common_str.rfind(" ")          # only up to the word boundary
committed = common_str[:cut + 1]
```

**Why 2 agreements?** A single run can be a hallucination (especially with
short chunks). Only when two consecutive runs deliver the same text does it
count as stable.

**Why only up to the word boundary?** Otherwise a half-spoken word would be
cut off and frozen — exactly the bug that produced "Das müsste jetzt **de**"
instead of "Das müsste jetzt **deutlich**".

### 2.7 The delta output (DeltaTyper)

The transcription stream repeatedly delivers **the whole text**. Only the
**difference** may be typed.

```python
old = last_typed_text
committed = len(committed_prefix)         # inviolable

# Safety net: the committed region must never be lost
if len(new) < committed: return

# Compare ONLY the not-yet-committed tail
old_tail, new_tail = old[committed:], new[committed:]
common = common_prefix_len(old_tail, new_tail)
to_remove = len(old_tail) - common        # Backspaces
to_add    = new_tail[common:]             # new text

if to_remove: backspace(to_remove)
if to_add:    type_text(to_add)
```

**Three protection rules that proved necessary:**

1. **Never touch the committed prefix** — prevents stable text from
   flickering.
2. **`max_revision_chars` (60):** if more than 60 characters would have to be
   taken back, the cursor is usually no longer behind our text (the user
   clicked) → then **only append** instead of destroying.
3. **The final transcription is fully corrective** (`force_revision=True`,
   `committed_len=0`): on release the model may correct everything, because
   the user has finished dictating and the cursor is safely behind our own
   text.

### 2.8 Shutdown order on release ⚠️

**Real bug that swallowed audio.** Wrong order:

```python
rest = source.stop()        # ❌ newest bytes first
transcriber.feed(rest)
drain_queue()               # older bytes afterwards → time axis scrambled!
```

**Correct:**

```python
state = FINALIZING          # 1) Capture-Thread beendet sich
capture_thread.join(1.0)    # 2) wait until no more chunks arrive
drain_queue()               # 3) OLDER bytes first
rest = source.stop()        # 4) NEWEST bytes last
transcriber.feed(rest)
```

A twisted audio timeline → hallucinations and missing sentence parts.

---

## 3. Pitfalls (learned from real bugs)

### 3.1 `max_new_tokens` must scale with the audio length ⚠️

A **fixed** token limit hard-truncates long dictations. Measured: the same
60-s audio delivered only **123 characters** with `max_new_tokens=256`, and
**294 characters** with 2048.

```python
def token_budget(audio_seconds: float) -> int:
    # German ≈ 12 subword tokens/second (2–4 tokens/word,
    # ~2.5 words/s + reserve)
    budget = int(audio_seconds * 12) + 64
    return max(256, min(budget, 4096))    # minimum and maximum bound
```

**Extra trap:** if a slice is transcribed with left context, the context burns
token budget even though it is discarded afterwards → always compute the
budget on the **total length of the audio handed in**.

### 3.2 Set the recording duration limit deliberately

A default of 60 s aborts dictations. For dictation **300 s** (5 min) is a
good compromise (RAM: 16 kHz × 2 bytes = 32 kB/s → 300 s ≈ 9.6 MB).

### 3.3 A VAD guard must not discard real speech ⚠️

Silero-VAD returns `False` for audio **< 1 s, always**. A naive guard "if VAD
False → do not transcribe" then swallows short dictations completely.

**Rule:** discard only **short** remainders (< 2 s) on VAD-False. Transcribe
long remainders **anyway** (data loss is worse than hallucination risk), but
log a warning.

### 3.4 Parakeet hallucinates on very short chunks

Measured: a 1-s chunk delivered `"So it's uh big"` (English!) for German
text; at 1.5 s then correctly `"So, jetzt bin ich gespannt."`.

**Consequence:** `min_audio_s` (minimum audio before the first transcription)
**≥ 1.0 s**, better 2.5–3.0 s. And: the **first** commit needs agreement
(2.6), otherwise a hallucination of the first chunk freezes in place.

According to the NVIDIA model card, Parakeet is *"not recommended for
word-for-word/incomplete sentences"* — that is the reason.

### 3.5 Two error patterns in text output (keyboard emulation) ⚠️

When typing via keyboard emulation (uinput), **modifier states** can corrupt.
The two patterns are **distinguishable character-for-character** — that is
diagnostically valuable:

| Error pattern | Example | Cause | Detection feature |
|---|---|---|---|
| **Shift stuck** | `SO; DA BIN ICH` | shift latch | punctuation **transformed too**: `,`→`;` `,`→`:` |
| **CapsLock on** | `dAS sKRIPT` | CapsLock inverted | punctuation **unchanged** |

**Diagnosis recipe:** send the expected text once through the shift layer or
through a CapsLock inversion and compare it with the visible text. If it
matches character-for-character, it is **not an ASR error** but the lock state
of the output pipeline.

**Hardening:**
* **Typing timing configurable and generous:** `typedelay`/`typehold`
  (desktop: 20/15 ms). Too small → key events get lost under CPU load →
  modifiers stick.
* **Log the lock state** (before/after each typing operation) — otherwise the
  source cannot be attributed.
* **Set the correct keyboard layout** (desktop: `DOTOOL_XKB_LAYOUT=de`,
  `DOTOOL_XKB_VARIANT=nodeadkeys`). Without it: `z`↔`y` swapped, `?`→`_`.

> **Android:** this error pattern does **not** exist there — text is inserted
> via `InputConnection.commitText()` / `AccessibilityService`, not via key
> emulation. That is a real advantage.

### 3.6 Summary: do's & don'ts

| ✅ Do | ❌ Don't |
|---|---|
| key detection in its own thread | key handling in the inference loop |
| `temperature=0.0` (deterministic) | sampling during streaming |
| scale the token budget with the audio length | fixed `max_new_tokens=256` |
| commit only up to the word boundary | freeze half words |
| never touch the committed prefix | let stable text flicker |
| VAD only on **new** segments | VAD on left context (always True) |
| transcribe long silent remainders anyway | discard long remainders as "silence" |
| shutdown: queue → then the stop() remainder | feed the stop() remainder before the queue |
| log the lock state | suspect the ASR on text errors |

---

## 4. Configuration (reference values)

```toml
# Trigger
input_device_name = "keyd virtual keyboard"   # device with the trigger key
key_codes         = ["KEY_F24"]               # Linux keycode(s)

# Audio
audio_source = "auto"        # PipeWire source name or "auto"
sample_rate  = 16000         # 16 kHz mono s16le (native ASR format)

# ASR
backend      = "parakeet"    # "parakeet" | "whisper" | "qwen"
model        = "nvidia/parakeet-tdt-0.6b-v3"
language     = "auto"        # "auto" | "de" | "en"
compute_type = "int8"        # CPU: int8/float32; GPU: float16
cpu_threads  = 8             # = physical cores (no hyperthreading)

# Streaming
streaming_mode   = "whole_buffer"  # robuster als "windowed" (siehe 5.)
min_audio_s      = 3.0   # erst ab 3 s Audio transkribieren (Halluzinations-Schutz)
asr_interval_s   = 1.0   # re-transcription every 1 s of audio
max_record_s     = 300.0 # 5 minutes
commit_agreements = 2    # LocalAgreement confirmations

# Ausgabe
typer            = "dotool"   # "dotool" | "wtype" | "clipboard"
xkb_layout       = "de"
xkb_variant      = "nodeadkeys"
typedelay_ms     = 20
typehold_ms      = 15
append_space     = true   # space after the dictation
max_revision_chars = 60   # guard bound for backspace revisions
```

---

## 5. What did NOT work (important for the re-implementation!)

### 5.1 Window-based streaming (`windowed`)

Attempt: sliding windows `[4 s left context | 2 s chunk | 1 s right context]`,
adopting only tokens from the commit horizon (modeled on NVIDIA's "Buffered
Chunked Inference", 10-2-2).

**Result: too many deadlocks.** Concrete problems:

1. **Pause stall:** the window was capped at `committed_s + 3 s`. With a
   speech pause ≥ 3 s the VAD permanently reported "no speech" and
   `committed_s` never advanced → **everything after the pause was lost.**
   (Fixed by silence skip, but see 2.)
2. **Window ↔ commit coupling:** the window could not reach beyond
   `committed_s + n`. If the commit stalled, the window stalled → the
   remaining audio was never seen.
3. **Agreement deadlock:** the commit point advanced with every commit, so
   the agreement cache never collected 2 hits → permanent standstill.

**Recommendation:** for a dictation tool the **simpler `whole_buffer`
approach** (2.6) is more robust. It has no state machine that can run into a
deadlock.

**Measurement comparison** (13.4 s of test audio with a 4-s pause, reference =
single transcription of the whole audio):

| Mode | Result |
|---|---|
| reference (whole audio) | 256 characters ✅ |
| `whole_buffer` | **256 characters** ✅ (identical) |
| `windowed` | 137–139 characters ❌ (parts missing) |

**Trade-off:** `whole_buffer` delivers text in blocks (every ~1.5 s), not word
by word. For dictation that is acceptable; whoever wants true word-for-word
output must carefully finish the windowed approach (with the deadlock fixes
above).

### 5.2 "Normalizing" the text afterwards

Attempt to correct UPPERCASE words via regex.

**Rejected**, because it fights the symptom instead of the cause (see 3.5)
and destroys legitimate capitalization (acronyms, proper names).

---

## 6. Transfer to Android — concrete guidance

### 6.1 What can be adopted 1:1

| Mechanism | Android implementation |
|---|---|
| state machine (2.1) | `enum` + `MutableStateFlow` |
| LocalAgreement commit (2.6) | unchanged (pure text logic) |
| DeltaTyper logic (2.7) | unchanged (pure text logic) |
| token budget (3.1) | unchanged |
| VAD guard rules (3.3) | unchanged |
| model choice Parakeet (2.5) | via ONNX Runtime Mobile / TensorFlow Lite / NNAPI |

### 6.2 What must be replaced

| Desktop | Android |
|---|---|
| `/dev/input` + keyd | `KeyEvent` / `MediaButtonReceiver` / `AccessibilityService` |
| `pw-record` (PipeWire) | `AudioRecord` (16 kHz, MONO, PCM_16BIT) |
| `dotool` (uinput) | `InputConnection.commitText()` or `AccessibilityService.ACTION_SET_TEXT` |
| systemd user service | `ForegroundService` (mandatory for the microphone from Android 14) |
| Python + transformers | Kotlin/Java + ONNX Runtime (`parakeet-tdt-0.6b-v3` exists as ONNX/int8) |

### 6.3 Android-specific advantages

* **No keyboard emulation problem** → 3.5 drops out completely.
* **No threading problem with key events** → KeyEvents come from the system
  UI thread.
* **NNAPI/GPU** → significantly faster inference than the CPU-only reference.

### 6.4 Android-specific pitfalls

1. **Microphone permission + ForegroundService** (from Android 14
   `FOREGROUND_SERVICE_MICROPHONE`).
2. **Battery optimization** can kill the service →
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
3. **Latency of `AudioRecord`:** determine the buffer size via
   `getMinBufferSize()`; too small → dropouts, too large → latency.
4. **Text injection into foreign apps** is possible only via
   `AccessibilityService` (mind the Play Store policies!). For own apps:
   `InputConnection`.
5. **Model size:** Parakeet 0.6B int8 ≈ 700 MB – too large for an app bundle.
   Options: download on first start, or a smaller model (Parakeet 0.6B is
   already the small variant; alternatives: `whisper-small` ONNX ≈ 250 MB).

---

## 7. Test strategy (what proved itself)

**All core mechanisms are testable without microphone, model, and keyboard** —
via dependency injection of `AudioSource`, `Engine`, and `Typer`.

| Test level | What | How |
|---|---|---|
| unit: commit | LocalAgreement, word boundaries | fake token lists with timestamps |
| unit: DeltaTyper | revision, backspace, protection limits | fake typer that records calls |
| unit: token budget | scaling with audio length | pure function |
| integration | pipeline with a fake engine | predefined transcriptions per buffer length |
| end-to-end | real path | a saved WAV through the pipeline |

**Especially valuable:** send a saved test audio containing a **pause**
through the pipeline and compare the result with the **single transcription
of the whole audio**. Exactly this test uncovered the windowed defects (256
vs. 137 characters).

**Debug aids that paid off:**
* Dump the audio of every recording to `/tmp/stt_last.wav` → reproducibly
  analyzable.
* Log every transcription step (`audio_seconds → text, committed_len`).
* Log the lock state (CapsLock) before/after each typing operation (3.5).

---

## 8. Short checklist for the re-implementation

```
[ ] Push-to-talk state machine, idempotent, with lock
[ ] Key-Erkennung: eigenener Thread, Autorepeat (value==2) filtern
[ ] Audio-Capture: eigener Thread → Queue → 16 kHz mono s16le
[ ] ASR: Parakeet TDT (oder Whisper), temperature=0, deterministisch
[ ] Streaming: wachsender Puffer, Re-Transkription alle ~1 s Audio
[ ] Commit: LocalAgreement (2×), nur bis Wortgrenze
[ ] Output: DeltaTyper with committed-prefix guard + max_revision_chars
[ ] Finale Transkription: voll korrigierend (force_revision)
[ ] Shutdown: join → Queue drainen → stop()-Rest
[ ] Guards: min_audio_s ≥ 1 s, Token-Budget skaliert, VAD nur kurze Reste
[ ] max_record_s bewusst setzen (z. B. 300 s)
[ ] Tests ohne I/O (Fake AudioSource/Engine/Typer)
[ ] Test-Audio MIT PAUSE gegen Einzeltranskription vergleichen
```

---

## 9. Reference measurements (Intel Lunar Lake, CPU-only, 8 cores)

| Model | 20 s audio | RTF | Note |
|---|---|---|---|
| Parakeet TDT 0.6B (int8/float32) | ~2.5 s | **0.12** | ✅ recommended |
| faster-whisper large-v3-turbo (int8) | ~8.5 s | 0.43 | fallback |
| Qwen3-ASR 1.7B | ~23 s | 1.15 | too slow for live |

**Model load time:** Parakeet ~2 s (keep warm! Preload at service start, not
on the first key press).

**Quality comparison (German dictation with English technical terms):**

| Model | Transcript | Rating |
|---|---|---|
| **Parakeet** | "…ganz schön shocking… die Vibes in dem Raum…" | ✅ English terms correct |
| faster-whisper (`language="de"`) | identical | ✅ (only with forced language!) |
| faster-whisper (`language="auto"`) | completely translated to English | ❌ |
| Granite Speech 4.1 | "…die **Weibchen** im Raum…" | ⚠️ misrecognition |

**Important:** with Whisper set `language` **explicitly** when the language is
known — auto detection tips into the wrong language with mixed DE/EN.

---

## Documented refinement F43 (2026-11-01) — content above unchanged, only
supplemented here

**Auto-stop after ~2.5 s of speech pause** (`TRAILING_SILENCE_MS = 2500`,
`ime/DictationCoordinator.kt:97/436-439`) is a **refinement of the dictation
flow explicitly requested by the user** (0.1.28/F43): silence **after speech
onset** closes the session regularly via `stop()` (final decode over the whole
buffer, no error path). **c-gate "silence ≠ stall":** the auto-stop applies
**only to streaming silence** (`capStallMs < TRAILING_SILENCE_MS` — blocks
keep flowing, just quiet); a **dead source** (no blocks) remains under the
`ERR_STALL` contract (F28b1/F30) and is **not** preempted by the auto-stop
(evidenced: `FakeAudioScenarioTest` case c). Shorter pauses (< 2500 ms) never
interrupt.

## Documented refinement T23 (2026-11-05) — content above unchanged, only
supplemented here

**Typing suggestions, suggestion-only** (V0.1.47 chain per
`docs/PLAN_V0147.md`, user command 2026-11-05) is a **refinement of the
"no autocorrect in v1" rule**: the keyboard gains a suggestion ROW above the
keys, but **nothing is ever replaced automatically**. A correction applies
ONLY on an explicit wand press (`RegionReplacer`: batch edit with suffix
proof before and after, single-level undo until the next keystroke). No
learning, no persistence of typed text (rule 9: no typed content in
logs/exports — the 🐞 export carries counters/codes only). The lists are
STATIC word lists (hermitdave/FrequencyWords content 2018, CC BY-SA 4.0 —
`assets/wordlists/NOTICE.txt`), NOT adaptive dictionaries. Typed-region
tracking stays local and bounded (≤ 500 UTF-16 units, oldest dropped) and
resets on field start, dictation commit, selection/cursor moves, editor
change and suffix mismatch. Word keyboards ship for **de + en only**
(`LanguageAvailability`, §16); all 15 layouts keep dictation capability.
Full semantics: PLAN_V0147 §0/§3–§8; quality goldens:
`testdata/typing_goldens` (fix-rate 30/32 ≥ 90 % gate, clean-corpus FP 0/117,
corrector p95 0.53 ms per region).
