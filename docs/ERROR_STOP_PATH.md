# A15 — Warum „Audio-Start fehlgeschlagen" **erst beim Loslassen** erscheint

**Stand:** 2026-10-28 · **Rolle:** Analyse (rein lesend, keine Code-Änderung)
**Symptom:** Statuszeile „Bereit" → Nutzer hält Mic, spricht, **lässt los** → erst jetzt
„Audio-Start fehlgeschlagen – erneut versuchen". Start war also erfolgreich.

**Kernbefund in einem Satz:** Der Text `dictation_error_audio_start` ist der
**Fallback-Zweig** von `mapVisibleError` (`VoiceKeyboardService.kt:543`); **jeder**
Fehlercode, der nach dem Start entsteht — insbesondere der des **finalen Decodes**
(`DictationController.kt:164/166` → `lastErrorCode = "asr_final_error"`, Konstante
`:268`) — fällt dort hinein und wird als *Start*fehler ausgegeben.

## 1. Alle Rohcodes, die `dictation_error_audio_start` auslösen

| Code | Entstehung | Phase | Wahrscheinlichkeit für „erst beim Loslassen" |
|---|---|---|---|
| *(Start-Rückgabe `false`)* | `VoiceKeyboardService.kt:317-322` (Start-Pfad) | **Start** | **0** — wäre sofort sichtbar (Nutzer sah „bereit") |
| `asr_final_error` | `DictationController.kt:164` (ISync) / `:166` (Exception) → `fail()` `:212-216` | **Stop/Final** | **hoch — passt exakt** |
| `asr_partial_error` | `DictationController.kt:117/119` (während RECORDING) | Lauf | niedrig (Meldung käme mitten im Diktat) |
| `invalidOperation:…` | `AudioRecordSource.kt:127-128` (`read()` liefert `ERROR_INVALID_OPERATION` nach `stop()`) | **Stop** | **hoch — genau im Loslass-Moment** |
| `systemStop:…` | `AudioRecordSource.kt:138` (Exception im Read-Thread) | Lauf/Stop | mittel |
| `maxDuration:durationMs=300000` | `AudioRecordSource.kt:107` (Auto-Stop nach 300 s) | Lauf | niedrig (braucht 5 min) |
| `model:…` | `DictationCoordinator.kt:165` (Engine-Load **im Start**) | Start | 0 |
| `audioInit:…` | `DictationCoordinator.kt:174` (Source-Factory/Start) | Start | 0 |
| `startFailed` | `DictationCoordinator.kt:185` (`src.start()` false) | Start | 0 |
| `aborted` | `DictationController.kt:178` (`abort()`, u. a. Editor-Drift) | Stop | mittel |
| `oom:…` | `DictationCoordinator.kt:270/314` | Lauf/Stop | niedrig (eigener Text) |

**Mapping-Lücke (die eigentliche Ursache):** `mapVisibleError` (`:521-543`) kennt nur
`audioInit:SecurityException`, `permission`, `model:`, `btLost`/`route:bluetooth`,
`noMic`/`audioInit:noInputDevice`, `alloc|bad_alloc|oom`, `unknown|internal` — **kein**
`asr_final_error`, **kein** `asr_partial_error`, **kein** `invalidOperation`, **kein**
`systemStop`, **kein** `maxDuration`, **kein** `aborted`. Alles davon landet in
`else -> dictation_error_audio_start` (`:543`).

## 2. Stop-Pfad Strang für Strang (was nach dem Loslassen noch werfen kann)

1. **`VoiceKeyboardService.onMicUp`** (`:334-336`) → `stopDictationIfRunning()` → `updateStatus()`.
2. **`DictationCoordinator.stop()`** (`:299-330`): `active=false`, **`src.stopAndDrain()`**
   (`:305`) — hier wird `AudioRecord.stop()` gerufen, **während der Reader-Thread in
   `read()` blockiert** ⇒ `read()` liefert `ERROR_INVALID_OPERATION` → `errorListener`
   mit `invalidOperation` (`AudioRecordSource.kt:127-128`). Kein Wurf, sondern ein
   **Fehlercode** → landet im `else`-Zweig.
3. **Final-Decode in `scope.launch`** (`:313-330`): `controller.stop()` → `engine.transcribe(window(),…)`.
   Mögliche Würfe: **ORT-Exception** (jede `Exception`) → `fail(ASR_FINAL_ERROR)`;
   **`IllegalStateException`** aus dem Editor-Sink (`InputConnectionSink`) → wenn
   `isEditorSyncTimeout` (`DictationController.kt:209-210`) → `abort()` + **rethrow**
   (`:158-162`); **`OutOfMemoryError`** → eigener Text.
   ⚠️ Der `try` in `:316-320` fängt **nur** `OutOfMemoryError` — ein rethrown
   `IllegalStateException` **entkommt** in die Coroutine. `lastErrorCode` steht dann auf
   `"aborted"` (`:178`) → `else` → **audio_start**.
4. **`src.release()`** (`:322`) → `AudioRecord.stop()/release()`, `IllegalStateException`
   wird geschluckt (`AudioRecordSource.kt:179/192`) → **kein** Code. Gut.
5. **`maxDurationMs`** (`AudioRecordSource.kt:27/38`): nach 300 s meldet die Quelle
   `maxDuration` und beendet den Reader → `stopAndDrain` liefert Reste; der Code
   bleibt an `lastError` hängen (`DictationCoordinator.kt:137`) → `else` → audio_start.
6. **`InterruptedException`** (`AudioRecordSource.kt:135`, `:157`, `:172`) wird
   geschluckt — kein Nutzertext. Gut.
7. **Commit/`DeltaTyper`**: `InputConnectionSink` wirft nur `IllegalStateException`
   (Timeout) bzw. `EditorUnavailableException` — beide laufen in den Final-Pfad (3.).

## 3. Rangfolge der Erklärungen (begründet, nicht geraten)

1. **Final-Decode scheitert → `asr_final_error` → Fallback-Text.** Beleg:
   `DictationController.kt:164/166` setzt den Code, `:268` definiert ihn,
   `visibleError` (`DictationCoordinator.kt:116`) reicht ihn hoch, `:543` macht daraus
   „Audio-Start fehlgeschlagen". **Passt exakt**: Meldung erst nach dem Loslassen,
   Vorlauf lief.
2. **Read-Race beim Stop → `invalidOperation`/`systemStop` → Fallback-Text.** Beleg:
   `stopAndDrain()` beendet die Aufnahme, während `read()` läuft
   (`AudioRecordSource.kt:127-128`; `DictationCoordinator.kt:305`). Ebenfalls exakt
   im Loslass-Moment — und *zusätzlich* zum Final-Decode möglich.
3. **Editor-Drift im Finale → `abort()` + rethrow → `aborted` → Fallback-Text.**
   Beleg: `:157-162` (rethrow) + `:316-320` (nur OOM gefangen) + `:178` (`aborted`).
   Wahrscheinlich, wenn der Nutzer in eine App diktiert, die den Kontext ändert
   (Autokorrektur/Composing).

**Ausgeschlossen:** Startpfad (`:317-322`) — dann hätte die Meldung **sofort** kommen
müssen; und `model:`/`audioInit:` ebenso (Startphase).

## 4. Billigste Nachweisstelle (eine Zeile)

**Den Rohcode in den Debug-Snapshot aufnehmen** (A14-Feld `last_error`):
`DebugInfo`/`diagnosticsLine` bekommt `last_error=<raw>` statt nur des gemappten Texts.
Der Rohcode existiert bereits und wird heute **nur** geloggt
(`VoiceKeyboardService.kt:517` `Log.w(TAG, "visibleError=$raw")`). Ein Feld, keine
Logcat, keine Inhalte (Regel 9: Code, kein Klartext) → der Nutzerfall ist in 10 s
entscheidbar: `asr_final_error` vs. `invalidOperation` vs. `aborted` vs. `maxDuration`.

## 5. Entscheidungsklausel für die Nutzertexte (E5-Fortschreibung)

**Regel:** Der Text muss die **Phase** widerspiegeln; „Audio-Start" ist ausschließlich
für die Startphase zulässig. Neue/erweiterte Strings (alle ≤ 45 Zeichen):

| Rohcode | Nutzertext | Länge |
|---|---|---|
| `asr_final_error` | `Text nicht übernommen – erneut versuchen` | 40 |
| `asr_partial_error` | `Erkennung fehlgeschlagen – weiter sprechen` | 42 |
| `invalidOperation:*`, `systemStop:*` | `Aufnahme gestoppt – erneut versuchen` | 36 |
| `maxDuration:*` | `Aufnahme-Limit 5 Minuten erreicht` | 33 |
| `aborted` (Editor-Drift) | `Editor geändert – Diktat verworfen` | 34 |
| alles Übrige (Startphase) | `Diktat-Start fehlgeschlagen` | 27 |

Damit bleibt Regel 9 unberührt (nur Codes, keine Inhalte), die 45-Zeichen-Grenze ist
eingehalten, und der Fehlklassifikations-Fall („Startfehler" nach dem Loslassen)
verschwindet — der `else`-Zweig zeigt künftig `Diktat-Start fehlgeschlagen`
**nur** im Startpfad, weil die Phase im Code steckt.

## ANNAHME-Zeilen

- `ANNAHME: Der beobachtete Fall ist (1) oder (2); ohne den Rohcode im Snapshot ist`
  `die Entscheidung nicht möglich — deshalb §4 als erster Schritt.`
- `ANNAHME: Ein Editor-Drift (3) ist bei den Ziel-Apps des Nutzers möglich, aber`
  `seltener als ein ORT-Fehler im Final-Decode.`
