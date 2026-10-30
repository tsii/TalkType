# Analyse: Einfrieren bei 10–15 s Dauerdiktat (v0.1.10 / 11, API 37)

**Stand:** 2026-10-28 · **Rolle:** Analyse (keine Code-/Build-Änderung)
**Datenlage:** 🐞-Export (`last_error "-"`, `rtf 0.44`, `first_hyp_ms 5339`,
`peak_dbfs −22.0`, `route intern`, `src VOICE_RECOGNITION`) + Nutzerbericht
(„Statuszeile dauerhaft ‚starte Aufnahme…', weitere Mic-Drücke wirkungslos, kurze
Diktate **danach** wieder korrekt").
**Geprüfte Dateien (6):** `ime/VoiceKeyboardService.kt`, `ime/DictationCoordinator.kt`,
`dictation/DictationController.kt`, `audio/AudioRecordSource.kt`, `audio/AudioRouter.kt`,
`debug/LevelPeak.kt` (nur Pegel-Kontext).

**Vorab:** Die Vorab-Messung des Orchestrators ist **im Kern bestätigt**, aber
**unvollständig**: es sind **zwei getrennte Fehler**, und der Status-Text hat eine
**eigene, von `startingUp` unabhängige** Ursache (§2, Befund B1).

---

## 1. Befundtabelle

| # | Hypothese | Wahrsch. | Beleg (Datei:Zeile) | Wie zu beweisen |
|---|---|---|---|---|
| **A1** | `startingUp` bleibt `true`, weil der Main-Block nie läuft (kein `finally`, keine Frist) | **hoch (Code), aber durch Bericht eingeschränkt** | `VoiceKeyboardService.kt:328` (`if (startingUp) return`), `:361` (`startingUp = true`), `:382` (einzige Rücksetzung im `withContext(Main)`), `:573` (`abortDictation`) | `start()` künstlich blockieren (Test-Hook) ⇒ nach X s muss eine Meldung kommen; heute bleibt der Text stehen |
| **A2** | Ein `Error` **außer** `OutOfMemoryError` (z. B. `UnsatisfiedLinkError`, `StackOverflowError`, nativer Abbruch) verlässt die Coroutine | mittel | `:375-386` fängt **nur** `OutOfMemoryError` + `Exception`; `serviceScope` = `SupervisorJob` (`:144`) schluckt den Fehler still | Unit: `start()` wirft `LinkageError` ⇒ Status darf nicht „starte Aufnahme…" bleiben |
| **B1** | **`stickyError` wird mit dem neutralen Starttext belegt** und überstimmt danach RECORDING-/FINALIZING-Text **und** jede Fehlermeldung | **sehr hoch** | `:361` `updateStatus(getString(R.string.dictation_starting))` → `:750` `stickyError = extra`; Reihenfolge in `updateStatus`: `:759` extra, **`:760` sticky**, `:762` visibleError, `:763` RECORDING, `:778` FINALIZING, `:782` erst dann `stickyError = null` | Sichtprüfung: nach erfolgreichem Start muss „Diktat… (intern)" erscheinen; heute bleibt „starte Aufnahme…" (Code-Pfad eindeutig) |
| **C1** | Lange Sitzung endet **still**: `feedAudio`-Ausnahme ⇒ `fail(ASR_PARTIAL_ERROR)` ⇒ Controller `IDLE`; weitere Blöcke werden ignoriert, `stop()` liefert **kein** Finale | **sehr hoch (erklärt „kein Text")** | `dictation/DictationController.kt:91` (`if (state != RECORDING) return`), `:117/:119` `fail(ASR_PARTIAL_ERROR)`, `:210-216` `fail()` setzt `IDLE`; `stop()` `:145` (`if (state != RECORDING) return`) ⇒ **kein** Final-Decode; danach `stopDictationIfRunning` → `abort()`-Zweig (`VoiceKeyboardService.kt:481-483`) ⇒ keine Toast-/Häkchen-Bewertung | Instrumentierung `ctl_state` + `hyp_count` (§3): bleibt `ctl_state` bei IDLE, während `audio_s` weiterläuft ⇒ bewiesen |
| **C2** | Rückstau: RTF 0,44 × wachsender Puffer ⇒ Hypothesen fallen hinter Echtzeit | mittel (erklärt **Latenz**, nicht „kein Text") | `DictationController.kt:96-99` (jede Hypothese rechnet das **ganze** Fenster), `:265` min_audio 2 s; Kanal `Channel.UNLIMITED` (`DictationCoordinator.kt:180`) verliert **nichts** | `audio_s` vs. `hyp_gap_ms`; ab ~15 s ist ein Fenster-Pass ≈ 6,6 s bei 2-s-Takt |
| **C3** | Aufnahme-Reader stirbt (Fehlerkanal) ⇒ `lastError="audioSource…"` | niedrig | `DictationCoordinator.kt:226` `errorListener.onError(ERR_AUDIO, …)`; würde `visibleError` setzen — Export zeigt `-` | `last_error` direkt nach dem Einfrieren exportieren |
| **C4** | `maxDurationMs` (300 s) | **ausgeschlossen** | `AudioRecordSource.kt:27/38` | 15 s ≠ 300 s |
| **D1** | Queue-Voll/Chunk-Verlust in `AudioRecordSource` | **ausgeschlossen für diesen Fall** | `QUEUE_CAPACITY = 32` (`:39`) mit `ArrayBlockingQueue`; der Coordinator zieht per `read()` mit 50 ms Timeout (`DictationCoordinator.kt:62/210`) und schreibt in einen **unbegrenzten** Kanal (`:180`) | Zähler `chunks_dropped` (§3) würde es zeigen — heute **kein** Zähler vorhanden |

---

## 2. Ranked — es sind **zwei** Fehler

### (a) Anzeige/Startzustand: „starte Aufnahme…" bleibt stehen

**Ursache A1+B1, in dieser Reihenfolge:**

1. **B1 (sicher, reiner Code-Beweis):** Der Starttext wird als `stickyError` gesetzt
   (`:361` → `:750`). In `updateStatus` hat `stickyError` **Vorrang** vor
   `visibleError` (`:760` vor `:762`) und vor dem RECORDING-Text (`:763`). Er wird erst
   im `else`-Zweig (`:782`) gelöscht — der ist bei RECORDING/FINALIZING/Fehler
   **unerreichbar**. Folge: Nach dem ersten Mic-Down steht „starte Aufnahme…" bis zum
   nächsten **fehlerfreien IDLE** — und ein späterer Fehler wird von diesem Text
   **verdeckt** (deshalb `last_error "-"` im Export nichts über den Freeze sagt).
2. **A1 (plausibel, strukturell):** `startingUp` hat **kein `finally`, keine Frist**.
   Läuft der Main-Block nicht (hängender `engine.load()`, entkommener `Error`,
   Coroutine-Abbruch), blockiert `:328` **jeden** weiteren Mic-Druck — genau das
   beobachtete „weitere Drücke ändern nichts". Dass spätere kurze Diktate **wieder
   gingen**, spricht dafür, dass A1 hier **nicht dauerhaft** eintrat (sonst hätte auch
   danach nichts funktioniert) — der Dauerzustand ist **B1**, A1 ist der Risikopfad.
3. **A2** bleibt als Restrisiko bestehen (stiller Coroutine-Tod), ist aber durch die
   Beobachtung „danach geht es wieder" **nicht** gedeckt.

### (b) Ausbleibender Text bei langen Diktaten

**Ursache C1 (still), C2 (verstärkend):**

- Der Controller schaltet bei **jeder** Partials-Ausnahme still auf `IDLE`
  (`DictationController.kt:117/119` → `fail()` `:210-216`). Ab dann gilt
  `feedAudio`-Guard `:91`: **jeder weitere Block wird verworfen** — die Aufnahme läuft
  optisch weiter (FGS, Mic-Zustand), aber es entsteht nie wieder Text.
- Beim Loslassen greift `DictationController.stop()` `:145`: `state != RECORDING`
  ⇒ **sofortiges `return`** ⇒ **kein Final-Decode**. Ergebnis: **kein Text, keine
  Meldung** (der `abort()`-Zweig in `VoiceKeyboardService.kt:481-483` setzt
  `finalResultExpected` nicht → kein Toast).
- **C2** (RTF 0,44 bei wachsendem Fenster) macht den Auslöser wahrscheinlicher: je
  länger das Diktat, desto länger blockiert der Worker in `engine.transcribe`, desto
  größer die Chance auf einen Editor-Timeout (`Editor-Sync Timeout` →
  `isEditorSyncTimeout` → `abort()` `:158-162`) oder eine ORT-`IllegalStateException`.
- **Warum kurze Diktate danach gehen:** der nächste Start setzt `lastErrorCode = null`
  (`:76`) und startet einen neuen Zyklus — der alte Fehler ist **unsichtbar** geworden,
  genau wie der Export (`last_error "-"`) zeigt.

---

## 3. Messbare Instrumentierung (5 🐞-Felder, Stil wie `route`/`src`/`peak_dbfs`)

| Feld | Bedeutung | Wertebereich | Entscheidet |
|---|---|---|---|
| `ctl_state` | Zustand des `DictationController` **zum Exportzeitpunkt** | `IDLE/RECORDING/FINALIZING` | **C1**: `IDLE` bei laufender Aufnahme ⇒ stiller Fail bewiesen |
| `hyp_count` | Anzahl erzeugter Hypothesen dieser Sitzung | `0…n` | C1/C2: bleibt sie ab Sekunde X stehen, war es der Fail; wächst sie weiter, ist es Rückstau |
| `hyp_gap_ms` | Zeit seit der letzten Hypothese | ms | C2-Schwelle: > 4000 ms = Rückstau |
| `audio_s` | aufgenommene Audiodauer der Sitzung | s | trennt „nichts aufgenommen" von „nicht übertragen" |
| `start_ms` + `starting` | Dauer des letzten Starts / ob `startingUp` noch `true` ist | ms / `0|1` | **A1**: `starting=1` nach > 8 s ⇒ Watchdog-Fall |

Optional (billig, ein Zähler): `chunks_in`, `chunks_dropped` (heute **nicht** vorhanden —
`DictationCoordinator` zählt nichts; `AudioRecordSource` verwirft still bei
`trySend`-Fehlschlag? Nein: der Kanal ist unbegrenzt, **aber** die Quelle hat
`QUEUE_CAPACITY = 32` und `offer()`-Semantik — ein Zähler würde D1 endgültig klären).

---

## 4. Minimaler Fixvorschlag (Skizze, kein Code)

**(a1) Status-Text entkoppeln — kleinster, sicherster Fix:**
- `dictation_starting` **nicht** als `stickyError` führen, sondern als eigenen
  transienten Zustand (`startingUp`-Flag wird in `updateStatus` **vor** `stickyError`
  ausgewertet: `if (startingUp) append(starting)`).
- `stickyError` nur für **Fehlertexte** verwenden (E5-Klassen).
- Test: nach erfolgreichem Start zeigt die Zeile `Diktat… (intern)`; nach Fehler den
  Fehlertext; nach Stop `Bereit`.

**(a2) Watchdog für `startingUp`:**
- `finally { startingUp = false }` im Start-Job **plus** Frist (Vorschlag **8 s**):
  läuft sie ab, `startingUp=false`, `stopFgs()`, Status `Start dauert zu lange – erneut
  versuchen` (≤ 45 Zeichen), Log nur mit Klassennamen.
- Test: `start()` blockiert künstlich 10 s ⇒ nach 8 s Meldung, Mic wieder bedienbar.

**(b1) Stillen Controller-Fail sichtbar machen (Kern von (b)):**
- `fail()`/`abort()` müssen den Coordinator **informieren** (Listener/Callback):
  Aufnahme kontrolliert beenden (`stopFgs()`, `micRecording=false`), Statuszeile mit dem
  E5-Text, `finalResultExpected=false` **plus** Toast „Kein Text erkannt".
- Alternativ (mindestens): `stop()` darf bei `state == IDLE` **nicht** stillschweigend
  returnen, wenn `totalSamples > 0` — dann Final-Decode trotzdem versuchen.
- Test: `feedAudio` wirft `IllegalStateException` ⇒ Status zeigt Fehler, kein
  Dauer-„Diktat…".

**(b2) Rückstau sichtbar/gebremst:**
- `hyp_gap_ms` in die Statuszeile (Debug) und bei > 4 s „Verarbeite… (Rückstau)".
- Optional: Hypothesen-Intervall **adaptiv** (bei RTF > 0,5 auf 3 s), damit der Worker
  nicht dauerhaft hinterherläuft.

---

## 5. Streichliste (was **nicht** die Ursache ist)

| Verdacht | Warum ausgeschlossen |
|---|---|
| **Pegel / AudioSource** | 🐞: `peak_dbfs −22.0`, `route intern`, `src VOICE_RECOGNITION` — normale Sprechlage, kein Dämpfungsbefund (vgl. A17: −34 dBFS war der alte Fall) |
| **Berechtigungen** | `record=yes`; `notif=no` betrifft nur die **Sichtbarkeit** der FGS-Notification (`TECH_FACTS §4.1`), nicht Aufnahme/ASR |
| **Modell** | `model_status READY`, `model_bytes 670619706` (vollständig), `model_version` gesetzt |
| **`maxDurationMs` 300 s** | 15 s ≠ 300 s (`AudioRecordSource.kt:27/38`) |
| **Queue-Verlust in der Quelle** | Coordinator liest mit 50-ms-Timeout und schreibt in einen **unbegrenzten** Kanal (`DictationCoordinator.kt:62/180/210`) |
| **`first_hyp_ms 5339`, `rtf 0.44`** | Indizien (langsam), **keine** Befunde — sie erklären Latenz, nicht den Textverlust; erst `hyp_count`/`hyp_gap_ms` machen daraus eine Ursache |
| **E8-Commit-Regel** | Erklärt „Text erscheint spät" (3 Hypothesen, 2 Wörter zurück), aber **nicht** „gar kein Text": der Final-Decode schreibt immer — **außer** C1 verhindert ihn (§2b) |

## Offene Punkte (nicht belegbar mit den vorhandenen Daten)

1. **Welche Ausnahme** C1 konkret auslöst (Editor-Timeout vs. ORT-`IllegalStateException`)
   — entscheidbar nur mit `last_error` **direkt nach** dem Einfrieren (nicht später).
2. **Ob A1 in diesem konkreten Fall eintrat** — der Bericht („kurze Diktate danach ok")
   spricht dagegen; ohne `starting`-Feld im Export bleibt es offen.
3. **Ob der Editor (Ziel-App) den Timeout verursacht** — braucht den Test in zwei
   verschiedenen Apps (dumme vs. „schlauere" App).

## ANNAHME-Zeilen
- `ANNAHME: Der Export wurde nach einem späteren, erfolgreichen Kurzdiktat erzeugt —`
  `nur so ist last_error "-" mit einem vorangegangenen Fail vereinbar (lastErrorCode`
  `wird bei jedem Start auf null gesetzt, DictationController.kt:76).`
- `ANNAHME: Der Auslöser für C1 liegt im Editor-Timeout-Pfad (:158-162) oder in einer`
  `ORT-Exception bei ~15 s Fenstergröße; beides endet in derselben stillen IDLE-Schaltung.`
