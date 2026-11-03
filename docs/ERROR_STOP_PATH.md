# A15 — Why "Audio start failed" appears **only on release**

**Status:** 2026-10-28 · **Role:** analysis (purely read-only, no code change)
**Symptom:** status row "Ready" → user holds the mic, speaks, **releases** → only
now "Audio start failed – try again". The start was therefore successful.

**Core finding in one sentence:** the text `dictation_error_audio_start` is the
**fallback branch** of `mapVisibleError` (`VoiceKeyboardService.kt:543`);
**every** error code that arises after the start — in particular that of the
**final decode** (`DictationController.kt:164/166` → `lastErrorCode =
"asr_final_error"`, constant `:268`) — falls into it and is displayed as a
*start* error.

## 1. All raw codes that trigger `dictation_error_audio_start`

| Code | Origin | Phase | Probability for "only on release" |
|---|---|---|---|
| *(start return `false`)* | `VoiceKeyboardService.kt:317-322` (start path) | **Start** | **0** — would be visible immediately (user saw "ready") |
| `asr_final_error` | `DictationController.kt:164` (ISync) / `:166` (exception) → `fail()` `:212-216` | **Stop/final** | **high — fits exactly** |
| `asr_partial_error` | `DictationController.kt:117/119` (during RECORDING) | Run | low (message would come mid-dictation) |
| `invalidOperation:…` | `AudioRecordSource.kt:127-128` (`read()` returns `ERROR_INVALID_OPERATION` after `stop()`) | **Stop** | **high — exactly at the moment of release** |
| `systemStop:…` | `AudioRecordSource.kt:138` (exception in the read thread) | Run/stop | medium |
| `maxDuration:durationMs=300000` | `AudioRecordSource.kt:107` (auto-stop after 300 s) | Run | low (needs 5 min) |
| `model:…` | `DictationCoordinator.kt:165` (engine load **at start**) | Start | 0 |
| `audioInit:…` | `DictationCoordinator.kt:174` (source factory/start) | Start | 0 |
| `startFailed` | `DictationCoordinator.kt:185` (`src.start()` false) | Start | 0 |
| `aborted` | `DictationController.kt:178` (`abort()`, among others editor drift) | Stop | medium |
| `oom:…` | `DictationCoordinator.kt:270/314` | Run/stop | low (own text) |

**Mapping gap (the actual cause):** `mapVisibleError` (`:521-543`) knows only
`audioInit:SecurityException`, `permission`, `model:`, `btLost`/`route:bluetooth`,
`noMic`/`audioInit:noInputDevice`, `alloc|bad_alloc|oom`, `unknown|internal` —
**no** `asr_final_error`, **no** `asr_partial_error`, **no** `invalidOperation`,
**no** `systemStop`, **no** `maxDuration`, **no** `aborted`. All of those land in
`else -> dictation_error_audio_start` (`:543`).

## 2. The stop path, strand by strand (what can still throw after release)

1. **`VoiceKeyboardService.onMicUp`** (`:334-336`) → `stopDictationIfRunning()` →
   `updateStatus()`.
2. **`DictationCoordinator.stop()`** (`:299-330`): `active=false`, **`src.stopAndDrain()`**
   (`:305`) — `AudioRecord.stop()` is called here **while the reader thread is
   blocked in `read()`** ⇒ `read()` returns `ERROR_INVALID_OPERATION` →
   `errorListener` with `invalidOperation` (`AudioRecordSource.kt:127-128`). No
   throw, but an **error code** → lands in the `else` branch.
3. **Final decode in `scope.launch`** (`:313-330`): `controller.stop()` →
   `engine.transcribe(window(),…)`. Possible throws: **ORT exception** (any
   `Exception`) → `fail(ASR_FINAL_ERROR)`; **`IllegalStateException`** from the
   editor sink (`InputConnectionSink`) → if `isEditorSyncTimeout`
   (`DictationController.kt:209-210`) → `abort()` + **rethrow** (`:158-162`);
   **`OutOfMemoryError`** → own text.
   ⚠️ The `try` in `:316-320` catches **only** `OutOfMemoryError` — a rethrown
   `IllegalStateException` **escapes** into the coroutine. `lastErrorCode` then
   stands at `"aborted"` (`:178`) → `else` → **audio_start**.
4. **`src.release()`** (`:322`) → `AudioRecord.stop()/release()`,
   `IllegalStateException` is swallowed (`AudioRecordSource.kt:179/192`) → **no**
   code. Good.
5. **`maxDurationMs`** (`AudioRecordSource.kt:27/38`): after 300 s the source
   reports `maxDuration` and ends the reader → `stopAndDrain` delivers the
   remainder; the code sticks to `lastError` (`DictationCoordinator.kt:137`) →
   `else` → audio_start.
6. **`InterruptedException`** (`AudioRecordSource.kt:135`, `:157`, `:172`) is
   swallowed — no user text. Good.
7. **Commit/`DeltaTyper`:** `InputConnectionSink` throws only
   `IllegalStateException` (timeout) or `EditorUnavailableException` — both run
   into the final path (3.).

## 3. Ranking of explanations (reasoned, not guessed)

1. **Final decode fails → `asr_final_error` → fallback text.** Evidence:
   `DictationController.kt:164/166` sets the code, `:268` defines it,
   `visibleError` (`DictationCoordinator.kt:116`) passes it up, `:543` turns it
   into "Audio start failed". **Fits exactly:** message only after release, the
   run-up worked.
2. **Read race at stop → `invalidOperation`/`systemStop` → fallback text.**
   Evidence: `stopAndDrain()` ends the recording while `read()` is running
   (`AudioRecordSource.kt:127-128`; `DictationCoordinator.kt:305`). Also exactly
   at the moment of release — and possible *in addition* to the final decode.
3. **Editor drift in the finale → `abort()` + rethrow → `aborted` → fallback
   text.** Evidence: `:157-162` (rethrow) + `:316-320` (only OOM caught) + `:178`
   (`aborted`). Probable if the user dictates into an app that changes the
   context (autocorrect/composing).

**Ruled out:** the start path (`:317-322`) — the message would have had to come
**immediately**; and `model:`/`audioInit:` likewise (start phase).

## 4. Cheapest point of proof (one line)

**Include the raw code in the debug snapshot** (A14 field `last_error`):
`DebugInfo`/`diagnosticsLine` gets `last_error=<raw>` instead of only the mapped
text. The raw code already exists and today is **only** logged
(`VoiceKeyboardService.kt:517` `Log.w(TAG, "visibleError=$raw")`). One field, no
logcat, no content (rule 9: code, no plaintext) → the user's case is decidable in
10 s: `asr_final_error` vs. `invalidOperation` vs. `aborted` vs. `maxDuration`.

## 5. Decision clause for the user texts (E5 continuation)

**Rule:** the text must reflect the **phase**; "Audio start" is permissible
exclusively for the start phase. New/extended strings (all ≤ 45 characters):

| Raw code | User text | Length |
|---|---|---|
| `asr_final_error` | `Text nicht übernommen – erneut versuchen` | 40 |
| `asr_partial_error` | `Erkennung fehlgeschlagen – weiter sprechen` | 42 |
| `invalidOperation:*`, `systemStop:*` | `Aufnahme gestoppt – erneut versuchen` | 36 |
| `maxDuration:*` | `Aufnahme-Limit 5 Minuten erreicht` | 33 |
| `aborted` (editor drift) | `Editor geändert – Diktat verworfen` | 34 |
| everything else (start phase) | `Diktat-Start fehlgeschlagen` | 27 |

This leaves rule 9 untouched (only codes, no content), the 45-character limit is
respected, and the misclassification case ("start error" after release)
disappears — the `else` branch will in future show `Diktat-Start fehlgeschlagen`
**only** in the start path, because the phase lives in the code.

## ASSUMPTION lines

- `ASSUMPTION: The observed case is (1) or (2); without the raw code in the snapshot`
  `the decision is impossible — therefore §4 as the first step.`
- `ASSUMPTION: Editor drift (3) is possible with the user's target apps, but`
  `rarer than an ORT failure in the final decode.`
