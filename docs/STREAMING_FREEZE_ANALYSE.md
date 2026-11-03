# Analysis: freeze at 10–15 s of continuous dictation (v0.1.10 / 11, API 37)

**Status:** 2026-10-28 · **Role:** analysis (no code/build changes)
**Data basis:** 🐞 export (`last_error "-"`, `rtf 0.44`, `first_hyp_ms 5339`,
`peak_dbfs −22.0`, `route intern`, `src VOICE_RECOGNITION`) + user report
("status row stuck at 'starte Aufnahme…', further mic presses ineffective,
short dictations **afterwards** correct again").
**Files examined (6):** `ime/VoiceKeyboardService.kt`,
`ime/DictationCoordinator.kt`, `dictation/DictationController.kt`,
`audio/AudioRecordSource.kt`, `audio/AudioRouter.kt`, `debug/LevelPeak.kt`
(level context only).

**Preliminary:** the orchestrator's preliminary measurement is **confirmed
in essence**, but **incomplete**: these are **two separate errors**, and the
status text has a cause **of its own, independent of `startingUp`** (§2,
finding B1).

---

## 1. Findings table

| # | Hypothesis | Prob. | Evidence (file:line) | How to prove |
|---|---|---|---|---|
| **A1** | `startingUp` stays `true` because the main block never runs (no `finally`, no deadline) | **high (code), but limited by the report** | `VoiceKeyboardService.kt:328` (`if (startingUp) return`), `:361` (`startingUp = true`), `:382` (the only reset in `withContext(Main)`), `:573` (`abortDictation`) | artificially block `start()` (test hook) ⇒ after X s a message must appear; today the text stays |
| **A2** | An `Error` **outside** `OutOfMemoryError` (e.g. `UnsatisfiedLinkError`, `StackOverflowError`, a native abort) escapes the coroutine | medium | `:375-386` catches **only** `OutOfMemoryError` + `Exception`; `serviceScope` = `SupervisorJob` (`:144`) swallows the error silently | unit test: `start()` throws a `LinkageError` ⇒ the status must not remain "starte Aufnahme…" |
| **B1** | **`stickyError` is set with the neutral start text** and afterwards overrides the RECORDING-/FINALIZING text **and** any error message | **very high** | `:361` `updateStatus(getString(R.string.dictation_starting))` → `:750` `stickyError = extra`; order in `updateStatus`: `:759` extra, **`:760` sticky**, `:762` visibleError, `:763` RECORDING, `:778` FINALIZING, `:782` only then `stickyError = null` | visual check: after a successful start "Diktat… (intern)" must appear; today "starte Aufnahme…" stays (the code path is unambiguous) |
| **C1** | A long session ends **silently**: a `feedAudio` exception ⇒ `fail(ASR_PARTIAL_ERROR)` ⇒ controller `IDLE`; further blocks are ignored, `stop()` delivers **no** finale | **very high (explains "no text")** | `dictation/DictationController.kt:91` (`if (state != RECORDING) return`), `:117/:119` `fail(ASR_PARTIAL_ERROR)`, `:210-216` `fail()` sets `IDLE`; `stop()` `:145` (`if (state != RECORDING) return`) ⇒ **no** final decode; afterwards `stopDictationIfRunning` → the `abort()` branch (`VoiceKeyboardService.kt:481-483`) ⇒ no toast/checkmark verdict | instrumentation `ctl_state` + `hyp_count` (§3): if `ctl_state` stays at IDLE while `audio_s` keeps running ⇒ proven |
| **C2** | backpressure: RTF 0.44 × a growing buffer ⇒ hypotheses fall behind real time | medium (explains **latency**, not "no text") | `DictationController.kt:96-99` (every hypothesis processes the **whole** window), `:265` min_audio 2 s; the channel `Channel.UNLIMITED` (`DictationCoordinator.kt:180`) loses **nothing** | `audio_s` vs. `hyp_gap_ms`; from ~15 s one window pass ≈ 6.6 s at a 2-s cadence |
| **C3** | the recording reader dies (error channel) ⇒ `lastError="audioSource…"` | low | `DictationCoordinator.kt:226` `errorListener.onError(ERR_AUDIO, …)`; it would set `visibleError` — the export shows `-` | export `last_error` directly after the freeze |
| **C4** | `maxDurationMs` (300 s) | **excluded** | `AudioRecordSource.kt:27/38` | 15 s ≠ 300 s |
| **D1** | queue full/chunk loss in `AudioRecordSource` | **excluded for this case** | `QUEUE_CAPACITY = 32` (`:39`) with `ArrayBlockingQueue`; the coordinator pulls via `read()` with a 50-ms timeout (`DictationCoordinator.kt:62/210`) and writes into an **unbounded** channel (`:180`) | a `chunks_dropped` counter (§3) would show it — today **no** such counter exists |

---

## 2. Ranked — they are **two** errors

### (a) Display/start state: "starte Aufnahme…" sticks

**Cause A1+B1, in this order:**

1. **B1 (certain, a pure code proof):** the start text is set as `stickyError`
   (`:361` → `:750`). In `updateStatus`, `stickyError` has **precedence**
   over `visibleError` (`:760` before `:762`) and over the RECORDING text
   (`:763`). It is deleted only in the `else` branch (`:782`) — unreachable
   at RECORDING/FINALIZING/error. Consequence: after the first mic-down
   "starte Aufnahme…" stands until the next **error-free IDLE** — and a later
   error is **masked** by this text (which is why `last_error "-"` in the
   export says nothing about the freeze).
2. **A1 (plausible, structural):** `startingUp` has **no `finally`, no
   deadline**. If the main block does not run (a hanging `engine.load()`, an
   escaped `Error`, a coroutine abort), `:328` blocks **every** further mic
   press — exactly the observed "further presses change nothing". That later
   short dictations **worked again** suggests A1 did **not** persist here
   (otherwise nothing would have worked afterwards either) — the persistent
   state is **B1**, A1 is the risk path.
3. **A2** remains as a residual risk (a silent coroutine death), but is
   **not** covered by the observation "it works again afterwards".

### (b) Missing text on long dictations

**Cause C1 (silent), C2 (amplifying):**

- The controller switches to `IDLE` **silently** on **every** partials
  exception (`DictationController.kt:117/119` → `fail()` `:210-216`). From
  then on the `feedAudio` guard `:91` applies: **every further block is
  discarded** — the recording optically keeps running (FGS, mic state), but
  text never appears again.
- On release `DictationController.stop()` `:145` applies: `state !=
  RECORDING` ⇒ **immediate `return`** ⇒ **no final decode**. Result: **no
  text, no message** (the `abort()` branch in
  `VoiceKeyboardService.kt:481-483` does not set `finalResultExpected` → no
  toast).
- **C2** (RTF 0.44 with a growing window) makes the trigger more likely: the
  longer the dictation, the longer the worker blocks in `engine.transcribe`,
  the greater the chance of an editor timeout ("Editor-Sync Timeout" →
  `isEditorSyncTimeout` → `abort()` `:158-162`) or an ORT
  `IllegalStateException`.
- **Why short dictations work afterwards:** the next start sets
  `lastErrorCode = null` (`:76`) and begins a new cycle — the old error has
  become **invisible**, exactly as the export (`last_error "-"`) shows.

---

## 3. Measurable instrumentation (5 🐞 fields, style like
`route`/`src`/`peak_dbfs`)

| Field | Meaning | Value range | Decides |
|---|---|---|---|
| `ctl_state` | state of the `DictationController` **at export time** | `IDLE/RECORDING/FINALIZING` | **C1**: `IDLE` during a running recording ⇒ silent fail proven |
| `hyp_count` | number of hypotheses produced this session | `0…n` | C1/C2: if it stops from second X on, it was the fail; if it keeps growing, it is backpressure |
| `hyp_gap_ms` | time since the last hypothesis | ms | C2 threshold: > 4000 ms = backpressure |
| `audio_s` | recorded audio duration of the session | s | separates "nothing recorded" from "not passed on" |
| `start_ms` + `starting` | duration of the last start / whether `startingUp` is still `true` | ms / `0|1` | **A1**: `starting=1` after > 8 s ⇒ watchdog case |

Optional (cheap, one counter): `chunks_in`, `chunks_dropped` (today **not**
present — `DictationCoordinator` counts nothing; does `AudioRecordSource`
discard silently on a `trySend` failure? No: the channel is unbounded,
**but** the source has `QUEUE_CAPACITY = 32` and `offer()` semantics — a
counter would definitively settle D1).

---

## 4. Minimal fix proposal (sketch, no code)

**(a1) Decouple the status text — the smallest, safest fix:**
- do **not** carry `dictation_starting` as `stickyError`, but as its own
  transient state (the `startingUp` flag is evaluated in `updateStatus`
  **before** `stickyError`: `if (startingUp) append(starting)`).
- use `stickyError` only for **error texts** (E5 classes).
- test: after a successful start the row shows `Diktat… (intern)`; after an
  error the error text; after stop `Bereit`.

**(a2) watchdog for `startingUp`:**
- `finally { startingUp = false }` in the start job **plus** a deadline
  (proposal **8 s**): if it expires, `startingUp=false`, `stopFgs()`, status
  `Start dauert zu lange – erneut versuchen` (≤ 45 characters), log with the
  class name only.
- test: `start()` artificially blocks for 10 s ⇒ message after 8 s, the mic
  operable again.

**(b1) make the silent controller fail visible (the core of (b)):**
- `fail()`/`abort()` must **inform** the coordinator (listener/callback):
  end the recording in a controlled way (`stopFgs()`, `micRecording=false`),
  the status row with the E5 text, `finalResultExpected=false` **plus** the
  toast "Kein Text erkannt".
- alternatively (at minimum): `stop()` must not silently return when
  `state == IDLE` and `totalSamples > 0` — then try a final decode anyway.
- test: `feedAudio` throws an `IllegalStateException` ⇒ the status shows the
  error, no persistent "Diktat…".

**(b2) backpressure visible/braked:**
- `hyp_gap_ms` into the status row (debug) and on > 4 s "Verarbeite…
  (Rückstau)".
- optional: an **adaptive** hypothesis interval (at RTF > 0.5 to 3 s), so
  the worker does not permanently lag behind.

---

## 5. Elimination list (what is **not** the cause)

| Suspicion | Why excluded |
|---|---|
| **Level / AudioSource** | 🐞: `peak_dbfs −22.0`, `route intern`, `src VOICE_RECOGNITION` — normal speech level, no attenuation finding (cf. A17: −34 dBFS was the old case) |
| **Permissions** | `record=yes`; `notif=no` affects only the **visibility** of the FGS notification (`TECH_FACTS §4.1`), not recording/ASR |
| **Model** | `model_status READY`, `model_bytes 670619706` (complete), `model_version` set |
| **`maxDurationMs` 300 s** | 15 s ≠ 300 s (`AudioRecordSource.kt:27/38`) |
| **Queue loss in the source** | the coordinator reads with a 50-ms timeout and writes into an **unbounded** channel (`DictationCoordinator.kt:62/180/210`) |
| **`first_hyp_ms 5339`, `rtf 0.44`** | indicators (slow), **no** findings — they explain latency, not the text loss; only `hyp_count`/`hyp_gap_ms` turn this into a cause |
| **E8 commit rule** | explains "text appears late" (3 hypotheses, 2 words back), but **not** "no text at all": the final decode always writes — **unless** C1 prevents it (§2b) |

## Open points (not provable with the available data)

1. **Which exception** C1 actually triggers (editor timeout vs. ORT
   `IllegalStateException`) — decidable only with `last_error` **directly
   after** the freeze (not later).
2. **Whether A1 occurred in this concrete case** — the report ("short
   dictations afterwards ok") argues against it; without a `starting` field
   in the export it stays open.
3. **Whether the editor (target app) causes the timeout** — needs the test
   in two different apps (a dumb vs. a "smarter" app).

## ASSUMPTION lines
- `ANNAHME: The export was generated after a later, successful short dictation —`
  `only in that way is last_error "-" compatible with a preceding fail (lastErrorCode`
  `is set to null on every start, DictationController.kt:76).`
- `ANNAHME: The trigger for C1 lies in the editor timeout path (:158-162) or in an`
  `ORT exception at ~15 s window size; both end in the same silent switch to IDLE.`
