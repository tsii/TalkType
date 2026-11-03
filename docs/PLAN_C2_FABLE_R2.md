# PLAN C2 round 2 — "long recordings impossible" despite v0.1.31

**Author role:** debugging advisor (read-only analysis; this plan is the
working document for coder/orchestrator).
**Status:** 2026-11-01, v0.1.31/vc32. Predecessors:
`docs/DEBUGGER_FABLE_BERATUNG_2026-11-01.md`, `docs/PLAN_V0130.md`.
**Code basis:** `VoiceKeyboardService.kt` (onStartInput 374–388, hold gate
349–363/930–945, buildCoordinator 795–840, cap 1550–1566),
`DictationCoordinator.kt` (watchdogs 480–545, consumer/segmenter 585–640),
`DictationController.kt` (finalizeSegment 548–590), `OwnershipTracker.kt`,
`InputConnectionSink.kt`, `KeyboardView.kt` (mic hold 855–962),
`FakeWavAudioSource.kt`, `DebugInputActivity.kt`, `DebugInfoFormatter.kt`.

---

## 0. Answer to the core question first

**Yes, there is a workable emulator simulation — and it does NOT hinge on the
first-session law, not on F37 and not on F32-B.** Reasoning:

1. **The first-session law concerns only the AudioRecord layer.** Everything
   above it (coordinator, watchdogs, segmenter, controller, engine,
   editor sink, IME lifecycle) can be driven any number of times per boot
   with the **existing debug seam** `VoiceKeyboardService.debugAudioSourceFactory`
   + `FakeWavAudioSource` (time-accurate 10-ms blocks from a WAV). Only an
   **external switch** is missing (`setprop`/intent) — today the field is not
   set anywhere.
2. **The real int8 engine runs in the x86_64 emulator** (first-session
   transcripts: 93 characters, −17 dBFS). F32-B is thereby moot; F37 is
   needed only for *deterministic CI assertions*, not for a real-time
   reproduction (a 600-s run = 10 min, acceptable).
3. **The two most likely killers lie in the editor layer** — and exactly that
   layer was never stressed in the emulator, because `DebugInputActivity` is
   a benevolent standard `EditText` (§3, H-E1/H-E2). A "hostile editor" as a
   debug activity is the missing building block; it needs **no** microphone.
4. The first-session law remains as a tool for **one** layer: real
   AudioRecord + FGS over 600 s (H-A1/H-A2). For that 1 cold start = 1 attempt
   suffices (§1.4).

**New code finding (high relevance):** `onStartInput(info, restarting)` calls
`coordinator.onEditorChanged()` **regardless of `restarting`** →
`abort("editor_change")` during RECORDING/FINALIZING. Many target apps call
`InputMethodManager.restartInput()` **on text changes** (WebView/Chrome,
Compose TextField < 1.6, Telegram, note apps with rich text) — i.e.
**triggered by our own `commitText`/`deleteSurroundingText`**. Consequence: the
session dies silently (no error text — `abort` sets no `visibleError`, status
row → "Bereit"), the text already written stays, "recording just ends". In
the export this would be **`abort_reason=editor_change`,
`mic_rel=editor:<ms>`, `fgs_stopped_by=abort:editor_change`** — three fields
that already exist today. The longer the dictation, the more commits, the
more likely the hit → fits exactly with "only long recordings affected".

**Second finding:** `OwnershipTracker.onUpdateSelection` sets `lost=true` on
every *unexpected* selection callback. Apps with autocorrect, spell-check
spans, Compose selection normalization, or batch-edit duplicate callbacks
produce exactly those. Afterwards `isOwned` returns false → `writeHypothesis`
returns silently → **the session keeps running (mic green), but no more
text**. In the export: `had_output=1`, `output_chars` stagnates far below what
`audio_s` suggests, `abort_reason=final`, `hyp_count` keeps growing. The
export can already distinguish this signature today, but only coarsely —
`owner_lost_at_s` (§4, AP3) makes it unambiguous.

---

## 0.1 Addendum (2026-11-02): user symptom "up to the first punctuation mark →
'Bereit'" — reweighting

**Symptom (verbatim):** status "Diktat (…)…" → first 1–2 s transcribed,
typically up to the first punctuation mark → after a **short, unreadable
intermediate display** → **"Bereit"**. Everything spoken afterwards is not
transcribed.

### 0.1.1 Code verification: "Bereit" is a **stop with finale**, not an abort

| End path | Status row afterwards | Intermediate display | Fits? |
|---|---|---|---|
| `coordinator.stop()` (UP, `hidden`, **F43 auto-stop**) → FINALIZING → `onDictationStopped` → `successJustFinished`/idle | **"Bereit"** (`status_res=status_ready`) | **"Verarbeite…"** (FINALIZING, final decode ~0.3–1 s) | **✔ exactly** |
| `abort("editor_change")` via `onStartInput` → `controller.abort()` → `lastErrorCode="aborted"` → the consumer branch `DictationCoordinator.kt:692–698` overwrites `lastAbortReason` with `audio_error:aborted`, `notifyAborted` → `updateStatus` with `visibleError="aborted"` → `mapVisibleError` → **else fallback** | **"Audio-Start fehlgeschlagen"** (sticky; `ERROR_STOP_PATH.md` §2.3 confirms the same) | none (no FINALIZING) | ✘ |
| `abort("michold_cancel")` (ACTION_CANCEL) | ditto error text | none | ✘ |
| `fail(ASR_*/EDITOR_SYNC_TIMEOUT)`, `audio_error:*`, `maxDuration` | error text | none | ✘ |
| Ownership loss (H-E2) | session keeps running: **"Diktat…"** stays, mic green | — | ✘ |

**Conclusion:** the reading "H-E1 = plain text" does **not** survive the code
check — every abort path ends with a visible (sticky) error text, not with
"Bereit". "Bereit" after a short intermediate text = `stop()` with finale
("Verarbeite…" is the unreadable intermediate status). Of the three `stop()`
triggers (UP, IME-hidden, F43) only **one** works without user/app action:
**F43 auto-stop after 2.5 s of speech pause**. "Up to the first punctuation
mark" is exactly the first speech pause: Parakeet punctuates utterance ends,
2.5 s later `stop()`, final decode over the first phrase, "Bereit", mic off —
the user keeps speaking into the void ("no matter how much I speak into it").
**No LocalAgreement/DeltaTyper mechanism hinges on punctuation marks**
(LocalAgreement commits at word boundaries on 2 confirmations); punctuation
marks are only input to the segment rule (a) (`segmentBoundary`,
`lastHypothesisEndsWithPunct`), which triggers a *mini finale*, never a
session end.

**Why does F43 fire despite the F49 hold gate?** Two candidates; export +
follow-up question separate them:
- **W1a — the user taps (toggle) instead of holding.** Toggle runs with an
  open gate ("pause = end", V12) — F43 is *intended* there. Then the symptom
  is **product behavior**: 2.5 s is too short for dictation pauses, toggle is
  thereby factually limited to one sentence. Evidence: `mic_rel` without
  `holdup`, `fgs_stopped_by=autostop`.
- **W1b — gate defect while holding.** `holdGestureActive` is set at **two**
  sources (status bar button `VoiceKeyboardService.kt:377/:382`, footer-row
  hold `:979/:984`) and reset at three places (`:632` onAborted, `:924`
  onDictationStopped, `:964` abortDictation) — none of them is bound to the
  **session**. A late `onAborted`/`onStopped` callback of a *previous* session
  (F50 collision residue, deadline overshoot) opens the gate of the
  **running** session. Evidence: `mic_rel=holdup:<ms>` with **ms ≫
  audio_s·1000** (the finger was still down when the session ended) **and**
  `fgs_stopped_by=autostop`.

### 0.1.2 New weighting

| # | Hypothesis | before | **now** | Reasoning |
|---|---|---|---|---|
| **H-W1** | F43 auto-stop at the first speech pause (W1a toggle use / W1b gate defect) | 5 % | **50 % → after AP0: W1b 70 %** (§0.1.5) | the only path "Verarbeite…"→"Bereit" without user action; timing = first pause = first punctuation mark |
| H-A2 | app hides the IME → stop with finale | 7 % | 12 % | also "Bereit"; but the keyboard would be gone — the user would have mentioned that |
| H-E1 | `restartInput` → `editor_change` abort | 30 % | **12 %** | the status row would be "Audio-Start fehlgeschlagen" (sticky); stays in the race if the user remembers the message as "Bereit" — `status_res` decides |
| H-E2 | ownership loss, session keeps running | 20 % | 5 % | the status would stay "Diktat…" |
| H-E3 | editor sync timeout | 10 % | 5 % | error text |
| H-A1/H-T1/H-G1 | system stop / third-party access / CANCEL | 21 % | 10 % | all with error text |
| H-D1/H-S1 | decode/segmenter | 7 % | 6 % | no session end |

### 0.1.3 Discrimination in the export (3 fields, all in 0.1.31)

| Candidate | `fgs_stopped_by` | `mic_rel` | `status_res` / `abort_reason` |
|---|---|---|---|
| **W1a toggle** | `autostop` | **no** `holdup`/`holdcancel` | `status_ready` / `final` |
| **W1b gate defect** | `autostop` | `holdup:<ms>` with ms ≫ `audio_s`·1000 | `status_ready` / `final` |
| hidden (H-A2) | `stop` | `hidden:<ms>` ≈ `audio_s`·1000 | `status_ready` / `final` |
| H-E1 | `abort:audio_error:aborted` | `editor:<ms>` | `dictation_error_audio_start` / `audio_error:aborted` |

Addendum for W1: `audio_s` ≈ 3–6, `hyp_count` 1–3, `loud_s` < `audio_s` − 2.5.
⚠ **Export defect found:** `DictationCoordinator.kt:696` overwrites
`lastAbortReason="editor_change"` with `audio_error:aborted` (the consumer
branch runs after `controller.abort()`). In today's export H-E1 would be
recognizable **only** via `mic_rel=editor` → fix in AP3.

### 0.1.4 Consequences for the order

1. **AP0 (immediately, 0 code): two follow-up questions to the user** — (a)
   *tapping or holding?* Which key (footer row 🎤 / status bar)? (b) Was
   there briefly "Verarbeite…" before "Bereit"? Plus a 🐞 export directly
   after the end. Answer (a)="tapping" ends the bug hunt: C2 becomes a
   **product decision** (toggle pause threshold 2.5 s → 6–8 s, or toggle
   without auto-stop and with segmenter; hold contract unchanged).
2. **Pull AP3 forward + extend it (0.1.32-diag):** `start_src`
   (`footer-hold`/`bar-hold`/`toggle`), `gate_at_stop` (0/1), `stop_cause`
   unified, **fix the `lastAbortReason` overwriting (:696: set only if still
   `-`)**, `hint:<res-name>`. VK-SESS: `gate=<0|1> src=<…>` on every state
   change.
3. **Pull AP7-W1 forward (0.1.32):** `holdGestureActive` session-bound — set
   only by the start path of the same session, reset only by the end of
   *that* session (compare session ID); one source instead of two. Small,
   low-risk, test at the service level (`DictationHoldGateTest` extended: a
   late `onAborted` of an old session must not open the gate).
4. **AP7-E1 (`restarting=true` ⇒ no abort)** remains as a robustness fix, but
   **not** as a 0.1.32 proof candidate — the evidence argues against H-E1 as
   the symptom cause.
5. **AP6 matrix, extra rows (exact symptom):**
   - `plain` + **toggle tap** + corpus "Satz. ⟨3 s pause⟩ Satz…": expected
     `fgs_stopped_by=autostop`, `status_res=status_ready`, `audio_s≈4–5`,
     `hyp_count≤3`, `mic_rel` without `holdup` → **W1a signature**
     (immediately reproducible, S-B, no mic needed).
   - `plain` + **hold (footer row, `motionevent DOWN` without UP)** + the
     same corpus: expected **no** stop until the corpus end; if the session
     still ends with `autostop` + `holdup:<ms≫audio_s>` → **W1b proven**,
     bisect via `start_src` and a preceding collision session
     (DOWN/UP/DOWN in quick succession, then hold).
   - `restart:40` + hold: expected `status_res=dictation_error_audio_start`,
     `mic_rel=editor` → H-E1 signature (delimitation, not the symptom).

### 0.1.5 AP0 answers (2026-11-02) and classification: **W1b**

User: (1) the key was **held continuously**; (2) **saw "Verarbeite…"**, then
"Bereit".
⇒ `stop()` with finale **during** a held gesture. UP/`hidden` are ruled out
(finger down, keyboard stayed) ⇒ the stop came from **F43** ⇒
`autoStopSuppressed()` returned `false` with the finger held ⇒ **gate defect
(W1b)**. W1a (toggle by design) is **out**. New weighting: **H-W1b 70 %**,
H-A2 5 %, H-E1 8 %, rest 17 %.

**Concrete race path (code, `VoiceKeyboardService.kt`), which fits the user
report "2nd attempt":**
1. Attempt 1: DOWN → cold start slow → deadline/supersede → session 1 is
   ended via `coordinator.abort("start_superseded")` resp.
   `abort("start_abandoned")`. `DictationCoordinator.abort()` calls
   `notifyAborted()` → **`scope.launch(mainDispatcher) { onAborted(reason) }`**
   — the callback runs **asynchronously, later** on main.
2. Attempt 2: touch DOWN sets **synchronously** `holdGestureActive = true`
   (`:381` resp. `:994`), then `onMicDown()` → F50 `SUPERSEDE` →
   `coordinator?.abort("start_superseded")` → callback **posted**, a new
   coordinator built.
3. Next main tick: the posted `onAborted` of the **old** session runs (`:637`)
   → **`holdGestureActive = false`** — the finger is down, session 2 now runs
   with an **open gate**.
4. First speech pause ≥ 2.5 s (= first punctuation mark) → F43 → `stop()` →
   "Verarbeite…" → "Bereit". Everything after that into the void. Identically
   possible via `onDictationStopped` (`:939`) of a late-finalizing
   predecessor session and via `abortDictation` (`:979`).

Cause in one sentence: **the gate is a global boolean that every session end
nulls — without knowing whether it is the session of the current gesture.**
Fix = AP7-W1 (§0.1.6).

### 0.1.6 Fix specification AP7-W1 (for the coder; 2 files + 1 test)

**File 1 — `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`**

1. Replace: `@Volatile private var holdGestureActive = false` with
   ```kotlin
   private val holdGenSeq = java.util.concurrent.atomic.AtomicLong(0)   // monotonic, never back
   @Volatile private var holdActiveGen = 0L                              // 0 = no gesture active
   @Volatile private var holdSrc = "-"                                   // "footer-hold" | "bar-hold"
   @Volatile private var sessionHoldGen = 0L                             // gesture under which the ACTIVE session started
   @Volatile private var gateForeignResets = 0                           // 🐞: rejected foreign resets
   ```
2. **The only set source:**
   ```kotlin
   private fun beginHold(src: String): Long {
       val g = holdGenSeq.incrementAndGet(); holdActiveGen = g; holdSrc = src
       sess("gate set gen=$g src=$src"); return g
   }
   ```
   Callers: `micButton` ACTION_DOWN (`:381`, `src="bar-hold"`) and
   `onMicHoldStart()` (`:994`, `src="footer-hold"`). Both remember the return
   value (`barTouchGen` / `footerTouchGen`) for their UP.
3. **Generation-bound reset:**
   ```kotlin
   private fun endHold(gen: Long, cause: String) {
       if (gen != 0L && holdActiveGen == gen) { holdActiveGen = 0L; sess("gate reset gen=$gen cause=$cause ok=1") }
       else { gateForeignResets++; sess("gate reset gen=$gen cause=$cause ok=0 active=$holdActiveGen") }
   }
   ```
   Callers and their `gen`:
   - `micButton` UP/CANCEL (`:386`) → `endHold(barTouchGen, "up")`;
     `onMicHoldEnd` (`:999`) → `endHold(footerTouchGen, "holdup"|"holdcancel")`.
   - `sess("start_ok")` place (start successful): `sessionHoldGen =
     holdActiveGen` (bind the session to the gesture).
   - `onAborted` closure (`:637`): capture the generation **before** the
     `launch` — in the start coroutine `val genAtStart = holdActiveGen` and
     in the closure `endHold(genAtStart, "aborted")`.
   - `onDictationStopped` (`:939`) → `endHold(sessionHoldGen, "stopped")`.
   - `abortDictation` (`:979`) → `endHold(sessionHoldGen, "abort")`.
   - No further assignments to `holdActiveGen` — `grep holdActiveGen =` must
     yield exactly 2 hits (beginHold/endHold).
4. Gate: `autoStopSuppressed = { holdActiveGen != 0L }` (`:859`).
5. Toggle path (`micToggleAction` START, `:1363 ff.`) calls **no**
   `beginHold` → `holdSrc` stays `-` resp. `start_src="toggle"` if
   `holdActiveGen == 0` at `start_ok`.
6. Export supply (collector call, existing pattern `noteMicRel`): `start_src`
   (`footer-hold`/`bar-hold`/`toggle`), `gate_open_at_stop` (0/1 =
   `holdActiveGen == 0L` **at the moment of the stop**, i.e. in
   `stopDictationIfRunning` before `c.stop()` resp. in `onDictationStopped`
   before the `endHold`), `gate_foreign_resets` (counter, session reset at
   `start_ok`).

**File 2 —
`app/src/main/java/de/voicekeyboard/debug/DebugInfoFormatter.kt` (+
`DebugInfoCollector.kt`, if it carries fields):** three fields `start_src`,
`gate_open_at_stop`, `gate_foreign_resets` (codes/numbers, rule 9). If AP3
already changes the field carriers: append there, do not duplicate.

**Test — `app/src/test/java/de/voicekeyboard/ime/HoldGateGenerationTest.kt`**
(JVM, pure logic — lift the `beginHold/endHold` decision as an
`internal`/`companion` function
`holdGateAfterReset(activeGen, resetGen): Long`, pattern
`micHoldReleaseAction`):
- `reset(gen=1)` with `active=2` → stays 2 (foreign reset rejected).
- `reset(gen=2)` with `active=2` → 0.
- Sequence DOWN₁(1) → abort₁ late → DOWN₂(2) → `endHold(1)` → gate still 2 →
  `autoStopSuppressed()==true`.
- `DictationHoldGateTest` unchanged green (coordinator side).

**Proof on the device (0.1.32-diag, the user repeats their scenario):**
- **VK-SESS:** before the fix `gate reset gen=1 cause=aborted ok=1` appears
  **after** `gate set gen=2`; after the fix `… ok=0 active=2` (reset
  rejected).
- **Export:** before the fix `fgs_stopped_by=autostop`, `gate_open_at_stop=1`,
  `mic_rel=holdup:<ms ≫ audio_s·1000>`; after the fix `fgs_stopped_by=stop`,
  `gate_open_at_stop=0`, `gate_foreign_resets ≥ 1` (the race occurred and was
  caught — that is the positive proof), `audio_s` ≈ hold duration.

### 0.1.7 AP6 matrix — W1b cell (S-B, no mic needed)

| Run | Editor | Gesture | Lead-in | Corpus | Expectation **without** fix | Expectation **with** fix |
|---|---|---|---|---|---|---|
| W1b-1 | `plain` | footer-row hold (`motionevent DOWN`, no UP) | DOWN₁ → 200 ms → UP₁ → 300 ms → DOWN₂ (F50 supersede/abort residue of session 1 pending) | 120 s, "Satz. ⟨3 s⟩ Satz…" | `fgs_stopped_by=autostop`, `gate_open_at_stop=1`, `audio_s≈4–6`, `status_res=status_ready`, VK-SESS `gate reset gen=1 … ok=1` after `gate set gen=2` | no stop until the corpus end, `gate_open_at_stop=0`, `gate_foreign_resets=1`, `fgs_stopped_by=stop` after UP |
| W1b-2 | `plain` | footer-row hold | DOWN₁ with `debug.vk.engine=stub:38.5:16000` (cold start > 15 s ⇒ deadline/`start_abandoned`), UP₁, DOWN₂ with `stub:38.5:300` | ditto | like W1b-1 (path `start_abandoned`) | like W1b-1 |
| W1b-3 | `plain` | bar hold (status bar button) | like W1b-1 | ditto | like W1b-1, `start_src=bar-hold` | ditto |
| W1b-0 (control) | `plain` | footer-row hold | no lead-in | ditto | no stop (gate intact) | no stop |

Invalid if W1b-0 stops without the fix (then another gate path exists — the
VK-SESS `gate reset` line names the `cause`).

## 0.2 Addendum (2026-11-02, 11:4x): "abort ≤ 2 s, always at the sentence end,
fragment grammatically closed"

**User (verbatim):** "…it always breaks off after two seconds at the latest,
sometimes a bit faster. It was always the end of the sentence. The sentence …
was usually a fragment …, but it was transformed in such a way that it could
have ended grammatically correctly at that point."
**Looker finding vc32 screenshot:** status row "Audio-Start fehlgeschlagen –
erneut versuchen" ⇒ the user apparently calls the sticky else fallback of an
**abort** path "Bereit". With that the §0.1 premise "Bereit ≠ abort" as a
discriminator **drops away**; "Verarbeite…" (AP0) nevertheless remains a real
signal for a finale — the two reports are only compatible if **several** end
paths occur (§0.2.4).

### 0.2.1 Timeline of the first 2.5 s (code, vc31/32)

| t (from AudioRecord start) | Event | Source |
|---|---|---|
| 0 | `start()`: clocks zeroed, `lastLoudAtMs = now`, `everLoud=false`, `tracker.reset()` (ownership baseline open) | `DictationCoordinator.start` |
| 0–3.0 s | ERR_SILENT window: without a loud block (≥ −40 dBFS) until 3.0 s → `abort("audio_error:silence")` | `SILENT_START_LIMIT_MS=3000` |
| first loud block (~0.1–0.3 s) | `everLoud=true`; the F43 clock `lastLoudAtMs` runs from here | captureLoop |
| 2.0 s audio | first consumer tick (`chunkMs=2000`) → `feedAudio` → `audioSeconds ≥ 2.0` (`MIN_AUDIO_SECONDS_PARTIALS`) → **1st decode** (device ~0.1–0.4 s) → **1st hypothesis** → `writeHypothesis` → `isOwned` (expectedText empty ⇒ true) → `policy.onHypothesis` (agreement 1/2 ⇒ **no commit**) → **`typer.revise()` writes the full hypothesis text as a revisable partial into the editor** (`replaceBeforeCursor` = `deleteSurroundingText`+`commitText` in a batch) → `tracker.onOwnEdit` | `DictationController.feedAudio/writeHypothesis` |
| +0–50 ms | the app responds to the **first third-party commit**: `onUpdateSelection` (expected, `ownEditPending`), possibly additional callbacks (autocorrect/spans → `lost=true`), possibly **`restartInput()`** → `onStartInput(restarting=true)` → `onEditorChanged()` → `abort("editor_change")` | `OwnershipTracker`, `VoiceKeyboardService.onStartInput` |
| 4.0 s audio | 2nd tick → 2nd hypothesis → agreement 2/2 → **first LocalAgreement commit** (word boundary, holdback 2 words, min 3) | `CommitPolicy` |
| ≥ 2.5 s after the last loud block | F43 (only with an open gate) → `stop()` → "Verarbeite…" → "Bereit" | captureLoop |

**Reading "~2 s, sometimes faster":** that matches the **first editor write
at t ≈ 2.0 s + decode** — not with the first *commit* (t ≈ 4 s) and not with
F43 (earliest 2.5 s after the first pause, so ≥ 3–4 s for a spoken fragment).
"Faster than 2 s" is impossible with F43, and with the partial write path
only if the device sees the first tick earlier (block aggregation ≥
`tickSamples`, overshoot) — or if the time is felt from the key press rather
than from the audio start (cold-start `start_ms` comes on top, which speaks
for "felt").

### 0.2.2 "Fragment grammatically closed" — pure engine behavior

Yes: Parakeet-TDT punctuates every utterance at the **decode window end**
(the model sees the buffer as complete and sets punctuation/capitalization so
that what it saw is a sentence). Every partial hypothesis therefore ends
"grammatically correct", no matter where the audio was cut. **In the code
there is no path that makes a punctuation mark a commit/stop signal:**
LocalAgreement cuts at word boundaries, `lastHypPunct` feeds only the segment
rule (a) (mini finale, session keeps running), F43 measures silence, not
characters. The user therefore observes the **first partial written into the
editor** (fragment of the first 2 s with a model period), after which the
session ends — the only question is *which* end path fires at t ≈ 2.1–2.4 s.

### 0.2.3 Which paths can end ≤ 0.5 s after the first partial?

| # | Path | Trigger | Status afterwards | Export (vc33) |
|---|---|---|---|---|
| **P1 (H-E1)** | app → `restartInput()` on the first third-party text → `abort("editor_change")` | 1st `replaceBeforeCursor` | **"Audio-Start fehlgeschlagen"** (sticky; ⚠ `lastAbortReason` gets overwritten with `audio_error:aborted`, :696 → AP3 fix) | `mic_rel=editor:<~2100>`, `restart_inputs ≥ 1`, `stop_cause=abort:editor_change` (after fix), `status_res=dictation_error_audio_start`, `hyp_count=1`, `had_output=1`, `output_chars` ≈ fragment length |
| **P2 (H-E2′)** | app → unexpected selection callbacks → `lost=true`; **no** session end, but from the 2nd hypothesis `isOwned=false` → nothing written anymore | 1st write | **"Diktat…" stays, mic green** until UP → then "Verarbeite…" → "Bereit" (the final decode is refused too) | `sel_unexpected ≥ 1`, `owner_lost_at_s ≈ 2–4`, `hyp_count` grows, `output_chars` frozen, `stop_cause=stop:holdup`, `status_res=status_ready` |
| P3 (H-E3′) | `commitText`/`deleteSurroundingText` returns `false` → `EditorWriteException` (ISE) → `fail(ASR_PARTIAL_ERROR)` | 1st write in an app without an active InputConnection focus / read-only phase | error text (fallback "Audio-Start") | `ctl_failed=1`, `last_error=asr_partial_error`, `mic_rel` without `editor` |
| P4 (H-E3) | main-thread stall > timeout on the first edit → `editor_sync_timeout` | heavy app; the F56 emulator side finding shows the same signature (there hardware drift at 16 s) | "App reagiert gerade nicht" (`dictation_error_editor_busy`) | `last_error=editor_sync_timeout`, `status_res=dictation_error_editor_busy` |
| P5 (W1b) | gate open + ≥ 2.5 s pause | only **after** supersede/abort residue and only with a real speech pause | "Verarbeite…" → "Bereit" | `fgs_stopped_by=autostop`, `gate_at_stop=1`, `audio_s ≥ 4` |
| P6 | ERR_SILENT (3 s without a loud block) | level < −40 dBFS (device −56 dBFS measured, ANALYSE_2 §2!) | "Kein Signal" | `abort_reason=audio_error:silence`, `loud_s=0`, `had_output=0` — **contradicts** "fragment was written" → out |

### 0.2.4 Weighting final

Two user reports, two signatures: **(A)** "up to the punctuation mark, then
≤ 2 s end, error text (remembered as 'Bereit')" = **P1**; **(B)** "held,
'Verarbeite…', 'Bereit', sometimes it holds" = **P5/P2**. The combination is
the most consistent explanation: **P1 is the normal case** (every dictation
dies at the first write, app-dependent), **W1b explains the outliers**
("sometimes it does hold" = the app happened not to restart, instead a gate
race after a supersede) — W1b is closed cheaply by AP7-W1 anyway and stays
in the plan.

| Hypothesis | §0.1.5 | **now** | Reasoning |
|---|---|---|---|
| **H-E1 / P1** restartInput abort | 8 % | **45 %** | t ≈ 2 s = first write; Looker sees abort text; "every dictation"; app-specific |
| **H-E2′ / P2** ownership loss on the 1st edit | 5 % | **20 %** | same clock; explains "Verarbeite…→Bereit" + "fragment" with the finger held (session lives silently until UP) |
| H-W1b / P5 gate race | 70 % | 15 % | only after a preceding session, needs ≥ 4 s audio — does not fit "≤ 2 s, every time" |
| H-E3 / P3+P4 | 5 % | 12 % | F56 shows the signature occurs in reality; heavy app ⇒ main drift |
| Rest (A1/A2/T1/D1/S1) | 12 % | 8 % | — |

**Decisive remains ONE user export** directly after the end: `mic_rel`
(`editor:` ⇒ P1), `sel_unexpected`/`owner_lost_at_s` (⇒ P2), `last_error`
(⇒ P3/P4), `fgs_stopped_by=autostop` (⇒ P5). Plus the name of the target app
— P1/P2 are app behavior; the emulator can only *imitate* them.

### 0.2.5 Emulator decision table (vc33, `emu_c2_run.sh`, FakeWav k120 "Satz.
⟨3 s⟩ Satz…", engine real, hold)

Every run: hold via `motionevent DOWN`, end by UP after 120 s or earlier by a
session end; 🐞 → export file.
Target "stop timing" = VK-SESS line `state=IDLE`/`aborted:` relative to
`start_ok`.

| Cell | Mode | Expectation (timing, status row) | Export signature that makes the result UNAMBIGUOUS | ⇒ hypothesis |
|---|---|---|---|---|
| T0 | `plain` | no end until UP; `output_chars` ≈ golden | `stop_cause=stop:holdup`, `restart_inputs=0`, `sel_unexpected=0`, `gate_at_stop=0` | control — must be green, otherwise the runner/audio chain is invalid |
| **T1** | `restart:20` | **end at ~2.1–2.4 s**, error text `dictation_error_audio_start` | `mic_rel=editor:<2000–2600>`, `restart_inputs=1`, `stop_cause=abort:editor_change`, `hyp_count=1`, `had_output=1`, `output_chars` ≈ 15–40 | **P1 reproduced = user signature (A)** |
| T1b | `restart:200` | end only after ~4–8 s (not until the 200th character) | like T1, `mic_rel=editor:<4000–9000>`, `hyp_count ≥ 2` | P1, shows the app threshold dependency ("sometimes faster") |
| **T2** | `cursor:25` | **no** end; mic green; text freezes after ~2 s; UP → "Verarbeite…" → "Bereit" | `sel_unexpected ≥ 1`, `owner_lost_at_s ≈ 2–4`, `output_chars` ≪ golden, `hyp_count` ≈ 55, `stop_cause=stop:holdup`, `status_res=status_ready` | **P2 = user signature (B) with the finger held** |
| T3 | `spans` | like T2 (setting a span usually triggers a selection callback) or no effect | like T2 resp. T0 | P2 (spell-checker variant) / neutral |
| T4 | `slowmain:1500` | end at the first edit (~2 s + 1.5 s), text "App reagiert gerade nicht" | `last_error=editor_sync_timeout`, `status_res=dictation_error_editor_busy`, `ctl_failed=1` | P4 (H-E3); **same signature as the F56 side finding** — on the device only credible if the user names this text |
| T4b | `slowmain:300` | no end (below the timeout) | like T0, `hyp_gap_ms` increased | H-E3 not at a moderate stall |
| T5 | `hideime:3` | end at 3 s with a finale → "Bereit" | `mic_rel=hidden:<3000>`, `stop_cause=stop:hidden` | H-A2 (delimitation: the keyboard visibly disappears) |
| T6 | `webview` | **open** — real Chrome behavior: expected end like T1 or ownership loss like T2 | like T1 or T2 | P1/P2 with a real app stack (most important cell after T1/T2) |
| W1b-1 | `plain` + preceding session (DOWN₁/UP₁/DOWN₂) | §0.1.7 | `gate_at_stop=1`, `fgs_stopped_by=autostop` | P5 |

**Acceptance criterion for "the simulation reproduces the user symptom":** T1
delivers `mic_rel=editor` at ≤ 2.6 s and `output_chars` = length of the first
partial; T2 delivers frozen `output_chars` with a running session. Both
signatures are then directly comparable with the next user export (one look
at `mic_rel`/`sel_unexpected`).

**Fix implications (AP7, per finding):**
- P1 → **AP7-E1**: `onStartInput(restarting=true)` during RECORDING ⇒ **no**
  abort; instead a `tracker` rebaseline (keep expectedText,
  `baselineTaken=false`), VK-SESS `input restarting=1 kept=1`. A real editor
  switch (`restarting=false` or a different `EditorInfo.fieldId`/`packageName`)
  stays an abort.
- P2 → **AP7-E2**: `lost` not as a one-way latch on the 1st edit; on an
  unexpected callback do a suffix resync (`isOwned` check without the `lost`
  short-circuit, lose only on actual divergence); selection ranges
  (`newSelStart != newSelEnd`) stay hard.
- P3/P4 → diagnosis only (text choice), no pipeline fix.

---

## 1. Emulator simulation (core duty) — step by step

### 1.1 Principle: three tracks, one matrix

| Track | Audio source | Engine | Editor | Repeatable per boot | Covers |
|---|---|---|---|---|---|
| **S-A "pipeline"** | FakeWav (600-s corpus) | real int8 | DebugInputActivity (benevolent) | ∞ | segmenter, controller, decode backpressure, RSS, watchdogs with real decode times |
| **S-B "editor"** | FakeWav | real int8 **or** latency stub | **HostileEditorActivity** (variants) | ∞ | H-E1 restartInput, H-E2 ownership loss, H-E3 sync timeout, hold gesture over 600 s |
| **S-C "microphone"** | real virtual microphone (`paplay`) | real int8 | DebugInputActivity | **1 (first-session law)** | AudioRecord 600 s, FGS long run, level, `maxDuration`, system stops |

Order: **S-B first** (highest prior probability, cheapest runs), then S-A (side
findings RSS/segmenter), S-C last (expensive, 1 attempt/boot).

### 1.2 Prerequisites (AP1, AP2 — each ≤ 4 files, debug source set)

**AP1 — debug hooks from outside (no production behavior):**
- `app/src/debug/.../debug/DebugHooks.kt` (new): reads on coordinator
  construction
  - `debug.vk.fakewav` (system property, `adb shell setprop`) → path of a WAV
    under `/data/local/tmp` or `files/` → sets
    `debugAudioSourceFactory = { FakeWavAudioSource(file, scenario="throughput") }`;
  - `debug.vk.engine` = `real` | `stub:<alpha_ms_per_s>:<base_ms>` → in debug
    the `engineFactory` is replaced by `LatencyStubEngine` (see below);
  - `debug.vk.export_file=1` → every 🐞 export is additionally written to
    `files/debug_export.txt` (identical content → rule 9 unchanged; pull via
    `adb exec-out run-as de.voicekeyboard cat files/debug_export.txt`).
- `app/src/debug/.../asr/LatencyStubEngine.kt` (new): `transcribe()` sleeps
  `base + α·buffer_s` ms on the **real-time clock**, delivers deterministic
  pseudo text with punctuation marks every ~8 words (so the segment rule (a)
  fires) and length ∝ `buffer_s` (so commit/delta become realistically
  large). α default **38.5** (device curve I3), stress variants 80 and 150.
  **Answer to "does α simulate safely enough?":** For
  watchdog/segmenter/editor questions yes — the pipeline sees only decode
  duration and text length. For RSS/OOM and transcript quality **no** → use
  the real engine for those (S-A). Both in the plan.
- `VoiceKeyboardService.kt`: 3 lines — `DebugHooks.apply(this)` before
  `buildCoordinator()` (debug class only, in release via an empty stub in
  the `release/` source set like `LiteRtBridgeSetup`).
- One additional **debug-only logcat tag `VK-SESS`** (codes/numbers):
  `state=<IDLE|STARTING|RECORDING|FINALIZING> cause=<code> audio_s=<n>
  out=<chars> seg=<n>` on every state transition, on every mini finale, on
  `onStartInput(restarting=…)`, `onUpdateSelection(lost=…)`. Rule 9: no
  texts.

**AP2 — hostile editor (`app/src/debug/.../debug/HostileEditorActivity.kt` +
debug manifest entry):**
an `EditText` whose behavior is chosen via the intent extra `mode`:

| mode | Behavior (imitates real apps) | Target hypothesis |
|---|---|---|
| `plain` | like DebugInputActivity | control |
| `restart:N` | after every N characters of growth `imm.restartInput(view)` | **H-E1** |
| `cursor:N` | after every N characters `setSelection(len-1)` then `setSelection(len)` (Compose/autocorrect pattern) | **H-E2** |
| `spans` | sets a `SuggestionSpan`/`UnderlineSpan` on every text change (spell-checker pattern) | H-E2 |
| `slowmain:MS` | `TextWatcher.afterTextChanged` blocks main for MS ms (e.g. 300/1500) | **H-E3** |
| `hideime:S` | after S seconds `imm.hideSoftInputFromWindow` | H-A2 |
| `webview` | `WebView` with a `<textarea>` (Chrome restart behavior real instead of imitated) | H-E1 |

### 1.3 Procedure S-B/S-A (script `tools/emu_c2_run.sh`, AP5)

```
1  Preconditions: <device> boot_completed=1; APK -diag-debug installed; model under files/models/versions/<v>.
2  adb push testdata/korpus/k600_p3s_-17.wav /data/local/tmp/k.wav
3  adb shell setprop debug.vk.fakewav /data/local/tmp/k.wav ; setprop debug.vk.engine real ; setprop debug.vk.export_file 1
4  adb shell am force-stop de.voicekeyboard            # fresh process (hooks are read at coordinator construction)
5  adb shell am start -n de.voicekeyboard/.debug.HostileEditorActivity --es mode restart:40
6  Show the IME (ime set + tap into the field), screenshot as evidence anchor
7  Start HOLD:  adb shell input motionevent DOWN <micX> <micY>          # stays down (no UP)
8  Sampler in parallel (every 30 s, into CSV): date; dumpsys meminfo de.voicekeyboard | grep -E "TOTAL PSS|Native Heap";
   logcat -d -s VK-SESS | tail -1 ; dumpsys audio | grep -A3 "Recording"   (relevant for S-C only)
9  Wait until: logcat VK-SESS shows state=IDLE  OR  t > corpus_len + 60 s
10 If still RECORDING: adb shell input motionevent UP <micX> <micY>  → regular finale
11 Tap 🐞 → pull the export file → tools/export_to_csv.py appends a line to reports/c2_runs.csv
12 Count the editor content (uiautomator dump → text length, do NOT log content) → output_len_editor
13 Compare with the golden length of the corpus (sidecar JSON) → completeness ratio
```

**Measurement points per run (all rule-9 compliant):**
- Export: `abort_reason`, `fgs_stopped_by`, `mic_rel`, `status_res`,
  `last_error`, `ctl_failed`, `audio_s`, `loud_s`, `had_output`,
  `output_chars`, `hyp_count`, `hyp_gap_ms`, `decode_ms_max/p95`,
  `buffer_s`, `dropped`, `cap_stall_ms`; **new (AP3):** `stop_cause`,
  `owner_lost_at_s`, `segment_finalizations`, `max_pause_ms`,
  `restart_inputs`, `sel_callbacks_unexpected`.
- logcat `VK-SESS`: timing of the last state change, count of
  `onStartInput(restarting=true)`.
- RSS curve: PSS/native heap over time (S-A); expectation with the segmenter:
  sawtooth ≤ 35-s window, no trend.
- Editor text length vs. golden length.

**Abort/success criteria (the reproduction counts as successful if ONE
applies):**
- R1: the session ends before the corpus end and `stop_cause ∉ {holdup, up}`
  → abort path identified (code in the field).
- R2: the session runs to the end, but `output_chars`/golden < 0.6 **and**
  `owner_lost_at_s ≥ 0` → silent write stop.
- R3: `ctl_failed=1` with `last_error ∈ {editor_sync_timeout, asr_*}` → error
  path.
- Run **invalid** if `cap_blocks` < 0.9·expected (the fake source did not run)
  or `audio_s` < corpus length without R1.
- Matrix S-B: 7 modes × {real, stub:38.5} × 1 corpus (600 s, pauses 3 s) = 14
  runs à ≤ 11 min ≈ 2.5 h. Only after a hit: refinement (vary N, corpus 120 s
  for a quick bisect).

### 1.4 Track S-C (the first-session law as a tool)

Per attempt: `emulator -no-snapshot-load` (cold boot) → `emulator_audio_up.sh`
→ **precondition assert** (`pactl info | grep "Default Sink: vkmic"`,
`XDG_RUNTIME_DIR` set) → install → `DebugInputActivity` → hold via
`motionevent DOWN` → `paplay k600.wav` → sampler → after 3 s an export
intermediate check is **not** possible (export only at the end), therefore a
**level gate via logcat `VK-SESS first_loud_ms`** within 5 s, otherwise abort
the run (law violated / chain wrong). Goal: two valid 600-s runs.
Expectation: if S-B/S-A show no killer and S-C runs through cleanly, the cause
is device-specific (H-A1/H-T1) → the user evidence chain (§2) decides.

### 1.5 Corpus (AP4, Python, no Kotlin)

`tools/make_korpus.py`: piper-tts (`de_DE-thorsten-medium`, offline) resp.
`espeak-ng` fallback; 600 s = ~70 sentences, pauses 1.5–4 s mixed (sidecar
JSON: sentence boundaries, pauses, golden length in characters), level −17
dBFS, pauses with −60 dBFS noise (never digitally silent). Variants: 120 s
(bisect), 600 s (main run), 900 s (cap test).
`tools/watchdog_twin.py`: computes block RMS like `captureLoop` → where F43
(toggle only), the segment rule (a), `max_pause_ms` would fire — an oracle for
the export values.

### 1.6 What F37 still means here

Not needed for the reproduction. Needed for **AP7**: the same runs as JVM
tests in seconds (path B deterministic). For that F37 "broad" as in the prior
consultation §1.2 (controller clock, thread factory, service dispatcher).

---

## 2. Parallel user evidence chain (minimal, reasonable to ask)

**Instructions (one message to the user):**
1. Start a long recording as usual (holding), in the **same app as before**.
2. As soon as it "stops" (text no longer grows **or** mic no longer green):
   **tap nothing else**, lift the finger if needed, then **immediately press
   🐞** and send the export. (Every further mic tap overwrites the session
   fields.)
3. Two yes/no answers with it: (a) Was the mic still green when the text
   stopped? (b) Which app (name only — it is deliberately not in the export)?

**The three decisive fields** (all present in 0.1.31):

| Field | Value | Meaning |
|---|---|---|
| `abort_reason` | `editor_change` | **H-E1** confirmed (the app triggered restartInput) |
| | `final` + `output_chars` ≪ expected | **H-E2** (ownership lost, silent write stop) |
| | `audio_error:<code>` | audio/system layer (`systemStop`, `invalidOperation`, `stall`, `maxDuration`) |
| | `michold_cancel` | the gesture was cancelled by the system (H-G1) |
| `mic_rel` | `editor:<ms>` / `hidden:<ms>` / `holdup:<ms>` / `holdcancel:<ms>` | **who** ended it, **when** (ms since start) |
| `fgs_stopped_by` | `abort:…` / `stop` / `autostop` / `fgs-timeout` | cross-check against `abort_reason` |

Secondary: `audio_s` vs. `loud_s` (was speech still going?), `ctl_failed`/
`last_error` (H-E3), `status_res`.

---

## 3. Hypothesis tree — what kills long recordings on the real device

Probabilities = my assessment **without** export #3; sum ≈ 100 %.

| # | Hypothesis | p | Mechanism in code | Kills it if | It survives if |
|---|---|---|---|---|---|
| **H-E1** | target app calls `restartInput()` on a text change → `onStartInput(restarting=true)` → `onEditorChanged()` → `abort("editor_change")`, silent (no error text) | **30 %** | `VoiceKeyboardService.kt:374–388` ignores `restarting` | `abort_reason≠editor_change` **and** `mic_rel` not `editor:` | `abort_reason=editor_change`; S-B `restart:N` reproduces; fix: with `restarting=true` do **not** abort, only re-anchor ownership (`tracker` rebaseline) |
| **H-E2** | ownership loss through app selection callbacks (autocorrect, spans, Compose normalization) → `isOwned=false` → hypotheses silently not written; session keeps running | **20 %** | `OwnershipTracker.onUpdateSelection` → `lost=true`; `writeHypothesis` returns false without a counter | `output_chars` grows to the end ≈ golden | `had_output=1`, `output_chars` stagnates, `hyp_count` rises, mic green; S-B `cursor:N`/`spans`; fix: resync instead of loss (re-read the text before the cursor; ownership holds if our own text is still a suffix — the check exists, only `lost` is a one-way latch) |
| H-E3 | editor sync timeout: the IME's main thread busy (large commits in a heavy app, long `deleteSurroundingText`) → `fail(editor_sync_timeout)` | 10 % | `InputConnectionSink.onEditExecutor` timeout; `DictationController` fail path | `ctl_failed=0` | `last_error=editor_sync_timeout`, `status_res=dictation_error_editor_busy`; S-B `slowmain` |
| H-A1 | the system stops the recording (GrapheneOS microphone indicator/scoped mic, OEM FGS kill, `AudioRecord.read<0`, client silenced when the IME is partly covered) | 10 % | `AudioRecordSource.captureLoop` → `ERR_SYSTEM_STOP`/`ERR_INVALID_OP` → `audio_error:*`; ERR_STALL after 3 s without blocks | `abort_reason` not `audio_error:*`, `cap_stall_ms` small | `audio_error:systemStop|invalidOperation|stall`; S-C 600 s; `dumpsys audio` recording status |
| H-G1 | hold gesture cancelled by the system (`ACTION_CANCEL`: gesture navigation at the edge, a second finger/palm, window resize by the app) → `abort("michold_cancel")` without a finale | 8 % | `KeyboardView` 939–962, `onMicHoldEnd(cancelled=true)` | `mic_rel≠holdcancel` | `mic_rel=holdcancel:<ms>`, `abort_reason=michold_cancel`; note: here **the entire uncommitted segment is lost** — even if p is small, CANCEL→finale instead of abort is a cheap robustness measure |
| H-A2 | app hides the IME (fullscreen, scroll-hide, dialog) → `onFinishInputView`/`onWindowHidden` → stop with a finale | 7 % | `VoiceKeyboardService.kt:395–413` | `mic_rel≠hidden` | `mic_rel=hidden:<ms>`, `fgs_stopped_by=stop`; S-B `hideime:S` |
| H-W1 | our own watchdogs: F43 despite hold (gate flag tips over on a pointer switch), ERR_STALL on a brief BT dropout, ERR_SILENT wrong | 5 % | `holdGestureActive` set in two places (button touch **and** KeyboardView hold) — a UP of the *other* path sets it false | `fgs_stopped_by≠autostop`, `abort_reason` not stall/silence | `fgs_stopped_by=autostop` with hold; S-B run with both mic inputs |
| H-D1 | decode backpressure/OOM despite the segmenter (mini finale error → `return false` → the segment keeps growing → from ~60 s the window is superlinear) | 5 % | `finalizeSegment` throws transient → the request is discarded; the next attempt only at the next pause/length rule | `segment_finalizations ≈ audio_s/35`, `decode_ms_max < 3000`, RSS flat | `decode_ms_max ≫`, RSS trend, `last_error=oom`; S-A 600/900 s |
| H-T1 | third-party microphone access (call, assistant, voice-note widget) / AudioFocus | 3 % | like H-A1 (`systemStop`) | — | user context only |
| H-S1 | the segmenter seam loses/duplicates text (prefix reconstruction, `trimEnd` seam), looks like "incomplete" | 2 % | `withSegmentPrefix`, `finalizeSegment` sets `segmentPrefix=typer.currentText` | S-A text length ≈ golden over 600 s | difference at segment boundaries (log lengths/positions only) |

**Reading aid:** H-E1+H-E2+H-E3 = 60 % lie in a layer that the emulator fully
covers **without a microphone**. That justifies S-B as the first step.

---

## 4. Work packages (order, each ≤ 4 files, reviewable)

| AP | Content | Files | Effort | Depends on |
|---|---|---|---|---|
| **AP1** debug hooks | `DebugHooks.kt` (setprop → FakeWav/engine/export file), `LatencyStubEngine.kt`, 3 service lines, `VK-SESS` log | debug/DebugHooks.kt, debug/asr/LatencyStubEngine.kt, VoiceKeyboardService.kt, release/…/DebugHooks.kt (empty) | 0.5 day | — |
| **AP2** hostile editor | `HostileEditorActivity` with 7 modes | debug/HostileEditorActivity.kt, debug/AndroidManifest.xml | 0.5 day | — |
| **AP3** export v2 (part 1) | `stop_cause` (unified from mic_rel/abort_reason/fgs), `owner_lost_at_s`, `segment_finalizations`, `max_pause_ms`, `restart_inputs`, `sel_unexpected`; `hint:<res-name>` in `status_res` also for hint events | DictationCoordinator.kt, DictationController.kt (owner_lost counter), DebugInfoCollector.kt, DebugInfoFormatter.kt | 0.5 day | — |
| **AP4** corpus tools | `make_korpus.py` (piper/espeak, sidecar), `watchdog_twin.py`, `export_to_csv.py` | tools/ (3 files) | 0.5 day | pip piper-tts |
| **AP5** emulator runner | `emu_c2_run.sh` (procedure §1.3 incl. sampler, asserts, CSV), README section | tools/emu_c2_run.sh, docs/EMULATOR_DIKTAT_HARNESS.md | 0.5 day | AP1, AP2, AP4 |
| **AP6** runs S-B → S-A → S-C | matrix §1.3/1.4, result `reports/c2_runs.csv` + `docs/C2_R2_BEFUNDE.md` | — | 1 day | AP5 |
| **AP7** fixes per finding (each its own review) | **E1:** `onStartInput`: with `restarting=true` no abort, re-anchor ownership. **E2:** `OwnershipTracker`: `lost` not as a one-way latch — on an unexpected callback try a suffix resync, lose only on divergence; counter. **G1:** `ACTION_CANCEL` during hold → finale instead of abort (no text loss). **W1:** `holdGestureActive` set from ONE source only. | 1–2 files each + tests | 0.5 day each | AP6 |
| **AP8** export v2 (part 2) | session ring buffer (5 lines), `start_phase_ms` | VoiceKeyboardService.kt, DebugInfoCollector.kt, DebugInfoFormatter.kt | 0.5 day | AP3 |
| **AP9** F37 broad + JVM path B | controller clock/thread factory/dispatcher injectable; KorpusTaktTest with a 600-s corpus and a latency stub | coordinator, controller, service, test | 1 day | after AP7 |

**Build 0.1.32 (minimum content):** AP1 + AP2 (debug source set only,
release unchanged) + AP3 (`hint:<res-name>`, `segment_finalizations`,
`stop_cause`, `max_pause_ms`, `owner_lost_at_s`). If AP6 has confirmed
H-E1/H-E2 by then: additionally the respective AP7 fix. **Not** in 0.1.32:
ring buffer (AP8), F37 (AP9).

**Decision point after AP6:** if none of the three tracks shows R1/R2/R3
**and** there is still no user export → then (and only then) a 3-expert
round. Before that it would be speculation without data.

---

## 5. Risks and limits of this plan

- `input motionevent DOWN` without UP over 10 min: works from API 30; if the
  emulator input stack drops the pointer after a while (ACTION_CANCEL), that
  is **itself** a finding (H-G1 signature in the export) — do not dismiss it
  as a tool error, cross-check with a `uiautomator` alternative
  (`--duration` swipe).
- FakeWav feeds **before** AudioRecord: H-A1/H-T1 stay reserved for S-C (1
  attempt/boot).
- The stub engine imitates text length and decode duration, not hypothesis
  instability (LocalAgreement behavior) — use the real engine for
  CommitPolicy questions.
- Paths/modes/counters are rule-9 compliant; in the runner the editor
  content is **only counted**, never logged.
- All debug hooks run via system properties — not compiled into release
  (source set), no attack vector.
