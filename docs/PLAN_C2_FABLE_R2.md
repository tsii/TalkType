# PLAN C2 Runde 2 — „Lange Aufnahmen unmöglich" trotz v0.1.31

**Autor-Rolle:** Debugging-Berater (read-only Analyse; dieser Plan ist das Arbeitsdokument für Coder/Orchestrator).
**Stand:** 2026-11-01, v0.1.31/vc32. Vorgänger: `docs/DEBUGGER_FABLE_BERATUNG_2026-11-01.md`, `docs/PLAN_V0130.md`.
**Code-Grundlage:** `VoiceKeyboardService.kt` (onStartInput 374–388, Hold-Gate 349–363/930–945, buildCoordinator 795–840,
Cap 1550–1566), `DictationCoordinator.kt` (Watchdogs 480–545, Consumer/Segmenter 585–640), `DictationController.kt`
(finalizeSegment 548–590), `OwnershipTracker.kt`, `InputConnectionSink.kt`, `KeyboardView.kt` (Mic-Hold 855–962),
`FakeWavAudioSource.kt`, `DebugInputActivity.kt`, `DebugInfoFormatter.kt`.

---

## 0. Antwort auf die Kernfrage zuerst

**Ja, es gibt eine tragfähige Emulator-Simulation — und sie hängt NICHT am Erste-Session-Gesetz, nicht an F37 und
nicht an F32-B.** Begründung:

1. **Das Erste-Session-Gesetz betrifft nur die AudioRecord-Schicht.** Alles darüber (Coordinator, Watchdogs, Segmenter,
   Controller, Engine, Editor-Sink, IME-Lifecycle) lässt sich mit der **vorhandenen Debug-Naht**
   `VoiceKeyboardService.debugAudioSourceFactory` + `FakeWavAudioSource` (zeitgetreue 10-ms-Blöcke aus WAV) beliebig oft
   pro Boot fahren. Es fehlt nur ein **Schalter von außen** (`setprop`/Intent) — heute wird das Feld nirgends gesetzt.
2. **Die echte int8-Engine läuft im x86_64-Emulator** (Erste-Session-Transkripte: 93 Zeichen, −17 dBFS). F32-B ist
   damit gegenstandslos; F37 ist nur für *deterministische CI-Assertions* nötig, nicht für eine Echtzeit-Reproduktion
   (600 s Lauf = 10 min, akzeptabel).
3. **Die beiden wahrscheinlichsten Killer liegen in der Editor-Schicht** — und genau die wurde im Emulator nie
   gereizt, weil `DebugInputActivity` ein gutmütiges Standard-`EditText` ist (§3, H-E1/H-E2). Ein „feindlicher
   Editor" als Debug-Activity ist der fehlende Baustein; er braucht **kein** Mikrofon.
4. Das Erste-Session-Gesetz bleibt als Werkzeug für **eine** Schicht: echter AudioRecord + FGS über 600 s
   (H-A1/H-A2). Dafür reicht 1 Kaltstart = 1 Versuch (§1.4).

**Neuer Code-Befund (hohe Relevanz):** `onStartInput(info, restarting)` ruft **unabhängig von `restarting`**
`coordinator.onEditorChanged()` auf → `abort("editor_change")` während RECORDING/FINALIZING. Viele Ziel-Apps rufen
`InputMethodManager.restartInput()` **auf Textänderungen** (WebView/Chrome, Compose-TextField < 1.6, Telegram,
Notiz-Apps mit Rich-Text) — also **ausgelöst durch unseren eigenen `commitText`/`deleteSurroundingText`**. Folge: die
Session stirbt still (kein Fehlertext — `abort` setzt keinen `visibleError`, Statuszeile → „Bereit"), der bereits
geschriebene Text bleibt stehen, „Aufnahme einfach zu Ende". Im Export wäre das **`abort_reason=editor_change`,
`mic_rel=editor:<ms>`, `fgs_stopped_by=abort:editor_change`** — drei Felder, die es heute schon gibt. Je länger das
Diktat, desto mehr Commits, desto wahrscheinlicher der Treffer → passt exakt zu „nur lange Aufnahmen betroffen".

**Zweiter Befund:** `OwnershipTracker.onUpdateSelection` setzt `lost=true` bei jedem *nicht erwarteten* Selektions-
Callback. Apps mit Autokorrektur, Spell-Check-Spans, Compose-Selektionsnormalisierung oder Batch-Edit-Doppelcallbacks
liefern genau solche. Danach liefert `isOwned` false → `writeHypothesis` kehrt still zurück → **Session läuft weiter
(Mikro grün), aber kein Text mehr**. Im Export: `had_output=1`, `output_chars` stagniert weit unter dem, was `audio_s`
erwarten lässt, `abort_reason=final`, `hyp_count` wächst weiter. Diese Signatur kann der Export heute schon
unterscheiden, aber nur grob — `owner_lost_at_s` (§4, AP3) macht sie eindeutig.

---

## 0.1 Nachtrag (2026-11-02): Nutzer-Symptom „bis zum ersten Satzzeichen → ‚Bereit'" — Neugewichtung

**Symptom (wörtlich):** Status „Diktat (…)…" → erste 1–2 s transkribiert, typischerweise bis zum ersten Satzzeichen →
nach einer **kurzen, nicht lesbaren Zwischenanzeige** → **„Bereit"**. Alles weitere Gesprochene wird nicht transkribiert.

### 0.1.1 Code-Verifikation: „Bereit" ist ein **Stop-mit-Finale**, kein Abort

| Endpfad | Statuszeile danach | Zwischenanzeige | Passt? |
|---|---|---|---|
| `coordinator.stop()` (UP, `hidden`, **F43-Auto-Stop**) → FINALIZING → `onDictationStopped` → `successJustFinished`/Idle | **„Bereit"** (`status_res=status_ready`) | **„Verarbeite…"** (FINALIZING, Final-Decode ~0,3–1 s) | **✔ exakt** |
| `abort("editor_change")` via `onStartInput` → `controller.abort()` → `lastErrorCode="aborted"` → Consumer-Zweig `DictationCoordinator.kt:692–698` überschreibt `lastAbortReason` mit `audio_error:aborted`, `notifyAborted` → `updateStatus` mit `visibleError="aborted"` → `mapVisibleError` → **else-Fallback** | **„Audio-Start fehlgeschlagen"** (sticky; `ERROR_STOP_PATH.md` §2.3 bestätigt dasselbe) | keine (kein FINALIZING) | ✘ |
| `abort("michold_cancel")` (ACTION_CANCEL) | dito Fehlertext | keine | ✘ |
| `fail(ASR_*/EDITOR_SYNC_TIMEOUT)`, `audio_error:*`, `maxDuration` | Fehlertext | keine | ✘ |
| Besitzverlust (H-E2) | Session läuft weiter: **„Diktat…"** bleibt, Mikro grün | — | ✘ |

**Folgerung:** Die Lesart „H-E1 = Reinschrift" hält der Code-Prüfung **nicht** stand — jeder Abort-Pfad endet mit
sichtbarem (sticky) Fehlertext, nicht mit „Bereit". „Bereit" nach kurzem Zwischentext = `stop()` mit Finale
(„Verarbeite…" ist der nicht lesbare Zwischenstatus). Von den drei `stop()`-Auslösern (UP, IME-hidden, F43) ist nur
**einer** ohne Nutzer-/App-Aktion: **F43-Auto-Stop nach 2,5 s Sprechpause**. „Bis zum ersten Satzzeichen" ist genau
die erste Sprechpause: Parakeet punktiert Äußerungsenden, 2,5 s später `stop()`, Final-Decode über die erste Phrase,
„Bereit", Mikro aus — der Nutzer spricht weiter ins Leere („egal wie viel ich reinspreche").
**Kein LocalAgreement-/DeltaTyper-Mechanismus hängt am Satzzeichen** (LocalAgreement committet an Wortgrenzen bei
2 Bestätigungen); Satzzeichen sind nur Eingang der Segmentregel (a) (`segmentBoundary`, `lastHypothesisEndsWithPunct`),
die ein *Mini-Finale* auslöst, nie ein Session-Ende.

**Warum feuert F43 trotz F49-Hold-Gate?** Zwei Kandidaten; Export + Rückfrage trennen sie:
- **W1a — Nutzer tippt (Toggle) statt zu halten.** Toggle läuft mit offenem Gate („Pause = Ende", V12) — F43 ist dort
  *beabsichtigt*. Dann ist das Symptom **Produktverhalten**: 2,5 s sind für Diktierpausen zu kurz, Toggle ist damit
  faktisch auf einen Satz begrenzt. Beleg: `mic_rel` ohne `holdup`, `fgs_stopped_by=autostop`.
- **W1b — Gate-Defekt beim Halten.** `holdGestureActive` wird an **zwei** Quellen gesetzt (Statusleisten-Button
  `VoiceKeyboardService.kt:377/:382`, Fußreihen-Hold `:979/:984`) und an drei Stellen zurückgesetzt (`:632` onAborted,
  `:924` onDictationStopped, `:964` abortDictation) — keine davon ist an die **Session** gebunden. Ein verspäteter
  `onAborted`/`onStopped`-Callback einer *vorigen* Session (F50-Kollisionsrest, Deadline-Überhang) öffnet das Gate der
  **laufenden** Session. Beleg: `mic_rel=holdup:<ms>` mit **ms ≫ audio_s·1000** (Finger war noch unten, als die
  Session endete) **und** `fgs_stopped_by=autostop`.

### 0.1.2 Neue Gewichtung

| # | Hypothese | vorher | **jetzt** | Begründung |
|---|---|---|---|---|
| **H-W1** | F43-Auto-Stop an erster Sprechpause (W1a Toggle-Nutzung / W1b Gate-Defekt) | 5 % | **50 % → nach AP0: W1b 70 %** (§0.1.5) | einziger Pfad „Verarbeite…"→„Bereit" ohne Nutzeraktion; Zeitpunkt = erste Pause = erstes Satzzeichen |
| H-A2 | App blendet IME aus → Stop mit Finale | 7 % | 12 % | ebenfalls „Bereit"; aber Tastatur wäre weg — hätte der Nutzer genannt |
| H-E1 | `restartInput` → `editor_change`-Abort | 30 % | **12 %** | Statuszeile wäre „Audio-Start fehlgeschlagen" (sticky); bleibt im Rennen, falls der Nutzer die Meldung als „Bereit" erinnert — `status_res` entscheidet |
| H-E2 | Besitzverlust, Session läuft weiter | 20 % | 5 % | Status bliebe „Diktat…" |
| H-E3 | Editor-Sync-Timeout | 10 % | 5 % | Fehlertext |
| H-A1/H-T1/H-G1 | System-Stop / Fremdzugriff / CANCEL | 21 % | 10 % | alle mit Fehlertext |
| H-D1/H-S1 | Decode/Segmenter | 7 % | 6 % | kein Session-Ende |

### 0.1.3 Diskriminierung im Export (3 Felder, alle in 0.1.31)

| Kandidat | `fgs_stopped_by` | `mic_rel` | `status_res` / `abort_reason` |
|---|---|---|---|
| **W1a Toggle** | `autostop` | **kein** `holdup`/`holdcancel` | `status_ready` / `final` |
| **W1b Gate-Defekt** | `autostop` | `holdup:<ms>` mit ms ≫ `audio_s`·1000 | `status_ready` / `final` |
| hidden (H-A2) | `stop` | `hidden:<ms>` ≈ `audio_s`·1000 | `status_ready` / `final` |
| H-E1 | `abort:audio_error:aborted` | `editor:<ms>` | `dictation_error_audio_start` / `audio_error:aborted` |

Zusatz bei W1: `audio_s` ≈ 3–6, `hyp_count` 1–3, `loud_s` < `audio_s` − 2,5.
⚠ **Export-Defekt gefunden:** `DictationCoordinator.kt:696` überschreibt `lastAbortReason="editor_change"` mit
`audio_error:aborted` (Consumer-Zweig läuft nach `controller.abort()`). H-E1 wäre im heutigen Export **nur** an
`mic_rel=editor` erkennbar → Fix in AP3.

### 0.1.4 Konsequenzen für die Reihenfolge

1. **AP0 (sofort, 0 Code): zwei Rückfragen an den Nutzer** — (a) *Tippen oder Halten?* Welche Taste (Fußreihe 🎤 /
   Statusleiste)? (b) Stand vor „Bereit" kurz „Verarbeite…"? Dazu 🐞-Export direkt nach dem Ende. Antwort (a)=„Tippen"
   beendet die Bugsuche: C2 wird **Produktentscheidung** (Toggle-Pausenschwelle 2,5 s → 6–8 s, oder Toggle ohne
   Auto-Stop und mit Segmenter; Hold-Vertrag unverändert).
2. **AP3 vorziehen + erweitern (0.1.32-Diag):** `start_src` (`footer-hold`/`bar-hold`/`toggle`), `gate_at_stop` (0/1),
   `stop_cause` vereinheitlicht, **Fix `lastAbortReason`-Überschreiben (:696: nur setzen, wenn noch `-`)**,
   `hint:<res-name>`. VK-SESS: `gate=<0|1> src=<…>` bei jedem Zustandswechsel.
3. **AP7-W1 vorziehen (0.1.32):** `holdGestureActive` sessiongebunden — gesetzt nur vom Start-Pfad derselben Session,
   zurückgesetzt nur durch Ende *dieser* Session (Session-ID vergleichen); eine Quelle statt zwei. Klein, risikoarm,
   Test auf Service-Ebene (`DictationHoldGateTest` erweitern: verspätetes `onAborted` alter Session darf Gate nicht öffnen).
4. **AP7-E1 (`restarting=true` ⇒ kein Abort)** bleibt als Robustheitsfix, aber **nicht** als 0.1.32-Beweiskandidat —
   die Evidenz spricht gegen H-E1 als Symptomursache.
5. **AP6-Matrix, Zusatzzeilen (exaktes Symptom):**
   - `plain` + **Toggle-Tap** + Korpus „Satz. ⟨3 s Pause⟩ Satz…": erwartet `fgs_stopped_by=autostop`,
     `status_res=status_ready`, `audio_s≈4–5`, `hyp_count≤3`, `mic_rel` ohne `holdup` → **W1a-Signatur** (sofort
     reproduzierbar, S-B, kein Mikro nötig).
   - `plain` + **Hold (Fußreihe, `motionevent DOWN` ohne UP)** + derselbe Korpus: erwartet **kein** Stop bis Korpus-Ende;
     endet die Session trotzdem mit `autostop` + `holdup:<ms≫audio_s>` → **W1b bewiesen**, Bisect über `start_src` und
     vorgeschaltete Kollisionssession (DOWN/UP/DOWN schnell hintereinander, dann Hold).
   - `restart:40` + Hold: erwartet `status_res=dictation_error_audio_start`, `mic_rel=editor` → H-E1-Signatur
     (Abgrenzung, nicht Symptom).

### 0.1.5 AP0-Antworten (2026-11-02) und Einordnung: **W1b**

Nutzer: (1) Taste war **durchgehend gehalten**; (2) **„Verarbeite…" gesehen**, dann „Bereit".
⇒ `stop()` mit Finale **während** gehaltener Geste. UP/`hidden` scheiden aus (Finger unten, Tastatur blieb) ⇒ der
Stop kam von **F43** ⇒ `autoStopSuppressed()` lieferte `false` bei gehaltenem Finger ⇒ **Gate-Defekt (W1b)**.
W1a (Toggle by design) ist **raus**. Neue Gewichtung: **H-W1b 70 %**, H-A2 5 %, H-E1 8 %, Rest 17 %.

**Konkreter Race-Pfad (Code, `VoiceKeyboardService.kt`), der zum Nutzerbericht „2. Versuch" passt:**
1. Versuch 1: DOWN → Kaltstart langsam → Frist/Supersede → Session 1 wird per `coordinator.abort("start_superseded")`
   bzw. `abort("start_abandoned")` beendet. `DictationCoordinator.abort()` ruft `notifyAborted()` →
   **`scope.launch(mainDispatcher) { onAborted(reason) }`** — der Callback läuft **asynchron, später** auf Main.
2. Versuch 2: Touch-DOWN setzt **synchron** `holdGestureActive = true` (`:381` bzw. `:994`), dann `onMicDown()` →
   F50 `SUPERSEDE` → `coordinator?.abort("start_superseded")` → Callback **gepostet**, neuer Coordinator gebaut.
3. Nächster Main-Tick: der gepostete `onAborted` der **alten** Session läuft (`:637`) → **`holdGestureActive = false`**
   — Finger ist unten, Session 2 läuft nun mit **offenem Gate**.
4. Erste Sprechpause ≥ 2,5 s (= erstes Satzzeichen) → F43 → `stop()` → „Verarbeite…" → „Bereit". Alles danach
   ins Leere. Identisch möglich über `onDictationStopped` (`:939`) einer spät finalisierten Vorgängersession und
   über `abortDictation` (`:979`).

Ursache in einem Satz: **das Gate ist ein globales Boolean, das jedes Session-Ende nullt — ohne zu wissen, ob es die
Session der aktuellen Geste ist.** Fix = AP7-W1 (§0.1.6).

### 0.1.6 Fix-Spezifikation AP7-W1 (für den Coder; 2 Dateien + 1 Test)

**Datei 1 — `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt`**

1. Ersetzen: `@Volatile private var holdGestureActive = false` durch
   ```kotlin
   private val holdGenSeq = java.util.concurrent.atomic.AtomicLong(0)   // monoton, nie zurück
   @Volatile private var holdActiveGen = 0L                              // 0 = keine Geste aktiv
   @Volatile private var holdSrc = "-"                                   // "footer-hold" | "bar-hold"
   @Volatile private var sessionHoldGen = 0L                             // Geste, unter der die AKTIVE Session startete
   @Volatile private var gateForeignResets = 0                           // 🐞: abgewiesene Fremd-Resets
   ```
2. **Einzige Setz-Quelle:**
   ```kotlin
   private fun beginHold(src: String): Long {
       val g = holdGenSeq.incrementAndGet(); holdActiveGen = g; holdSrc = src
       sess("gate set gen=$g src=$src"); return g
   }
   ```
   Aufrufer: `micButton` ACTION_DOWN (`:381`, `src="bar-hold"`) und `onMicHoldStart()` (`:994`, `src="footer-hold"`).
   Beide merken sich die Rückgabe (`barTouchGen` / `footerTouchGen`) für ihr UP.
3. **Generationsgebundener Reset:**
   ```kotlin
   private fun endHold(gen: Long, cause: String) {
       if (gen != 0L && holdActiveGen == gen) { holdActiveGen = 0L; sess("gate reset gen=$gen cause=$cause ok=1") }
       else { gateForeignResets++; sess("gate reset gen=$gen cause=$cause ok=0 active=$holdActiveGen") }
   }
   ```
   Aufrufer und ihre `gen`:
   - `micButton` UP/CANCEL (`:386`) → `endHold(barTouchGen, "up")`; `onMicHoldEnd` (`:999`) → `endHold(footerTouchGen, "holdup"|"holdcancel")`.
   - `sess("start_ok")`-Stelle (Start erfolgreich): `sessionHoldGen = holdActiveGen` (Session an Geste binden).
   - `onAborted`-Closure (`:637`): **vor** dem `launch` die Generation einfangen — in der Start-Coroutine
     `val genAtStart = holdActiveGen` und in der Closure `endHold(genAtStart, "aborted")`.
   - `onDictationStopped` (`:939`) → `endHold(sessionHoldGen, "stopped")`.
   - `abortDictation` (`:979`) → `endHold(sessionHoldGen, "abort")`.
   - Keine weiteren Zuweisungen an `holdActiveGen` — `grep holdActiveGen =` muss genau 2 Treffer liefern (beginHold/endHold).
4. Gate: `autoStopSuppressed = { holdActiveGen != 0L }` (`:859`).
5. Toggle-Pfad (`micToggleAction` START, `:1363 ff.`) ruft **kein** `beginHold` → `holdSrc` bleibt `-` bzw.
   `start_src="toggle"`, wenn beim `start_ok` `holdActiveGen == 0`.
6. Export-Zulieferung (Collector-Aufruf, bestehendes Muster `noteMicRel`): `start_src` (`footer-hold`/`bar-hold`/`toggle`),
   `gate_open_at_stop` (0/1 = `holdActiveGen == 0L` **im Moment des Stops**, d. h. in `stopDictationIfRunning` vor
   `c.stop()` bzw. in `onDictationStopped` vor dem `endHold`), `gate_foreign_resets` (Zähler, Session-Reset bei `start_ok`).

**Datei 2 — `app/src/main/java/de/voicekeyboard/debug/DebugInfoFormatter.kt` (+ `DebugInfoCollector.kt`, falls Feldträger):**
drei Felder `start_src`, `gate_open_at_stop`, `gate_foreign_resets` (Codes/Zahlen, Regel 9). Falls AP3 die
Feldträger schon ändert: dort anhängen, nicht duplizieren.

**Test — `app/src/test/java/de/voicekeyboard/ime/HoldGateGenerationTest.kt`** (JVM, reine Logik — die
`beginHold/endHold`-Entscheidung als `internal`/`companion`-Funktion `holdGateAfterReset(activeGen, resetGen): Long`
heben, Muster `micHoldReleaseAction`):
- `reset(gen=1)` bei `active=2` → bleibt 2 (Fremd-Reset abgewiesen).
- `reset(gen=2)` bei `active=2` → 0.
- Sequenz DOWN₁(1) → abort₁ verspätet → DOWN₂(2) → `endHold(1)` → Gate noch 2 → `autoStopSuppressed()==true`.
- `DictationHoldGateTest` unverändert grün (Coordinator-Seite).

**Beweis auf dem Gerät (0.1.32-diag, Nutzer wiederholt sein Szenario):**
- **VK-SESS:** vor dem Fix erscheint `gate reset gen=1 cause=aborted ok=1` **nach** `gate set gen=2`; nach dem Fix
  `… ok=0 active=2` (Reset abgewiesen).
- **Export:** vor Fix `fgs_stopped_by=autostop`, `gate_open_at_stop=1`, `mic_rel=holdup:<ms ≫ audio_s·1000>`;
  nach Fix `fgs_stopped_by=stop`, `gate_open_at_stop=0`, `gate_foreign_resets ≥ 1` (der Race trat auf und wurde
  abgefangen — das ist der positive Beweis), `audio_s` ≈ Haltedauer.

### 0.1.7 AP6-Matrix — W1b-Zelle (S-B, kein Mikro nötig)

| Lauf | Editor | Geste | Vorlauf | Korpus | Erwartung **ohne** Fix | Erwartung **mit** Fix |
|---|---|---|---|---|---|---|
| W1b-1 | `plain` | Fußreihe-Hold (`motionevent DOWN`, kein UP) | DOWN₁ → 200 ms → UP₁ → 300 ms → DOWN₂ (F50-Supersede-/Abort-Rest von Session 1 steht aus) | 120 s, „Satz. ⟨3 s⟩ Satz…" | `fgs_stopped_by=autostop`, `gate_open_at_stop=1`, `audio_s≈4–6`, `status_res=status_ready`, VK-SESS `gate reset gen=1 … ok=1` nach `gate set gen=2` | kein Stop bis Korpus-Ende, `gate_open_at_stop=0`, `gate_foreign_resets=1`, `fgs_stopped_by=stop` nach UP |
| W1b-2 | `plain` | Fußreihe-Hold | DOWN₁ mit `debug.vk.engine=stub:38.5:16000` (Kaltstart > 15 s ⇒ Frist/`start_abandoned`), UP₁, DOWN₂ mit `stub:38.5:300` | dito | wie W1b-1 (Pfad `start_abandoned`) | wie W1b-1 |
| W1b-3 | `plain` | Bar-Hold (Statusleisten-Button) | wie W1b-1 | dito | wie W1b-1, `start_src=bar-hold` | dito |
| W1b-0 (Kontrolle) | `plain` | Fußreihe-Hold | kein Vorlauf | dito | kein Stop (Gate intakt) | kein Stop |

Ungültig, wenn W1b-0 ohne Fix stoppt (dann liegt ein weiterer Gate-Pfad vor — VK-SESS `gate reset`-Zeile nennt die `cause`).

## 0.2 Nachtrag (2026-11-02, 11:4x): „Abbruch ≤ 2 s, immer am Satzende, Fragment grammatisch geschlossen"

**Nutzer (wörtlich):** „…bricht immer schon nach zwei Sekunden ab, spätestens, manchmal auch etwas schneller. Es war
immer das Satzende. Der Satz … war meistens ein Fragment …, wurde aber so umgewandelt, dass er grammatikalisch korrekt
an dieser Stelle hätte enden können."
**Looker-Befund vc32-Screenshot:** Statuszeile „Audio-Start fehlgeschlagen – erneut versuchen" ⇒ der Nutzer nennt den
sticky Else-Fallback eines **Abort**-Pfads offenbar „Bereit". Damit fällt die §0.1-Prämisse „Bereit ≠ Abort" als
Diskriminator **weg**; „Verarbeite…" (AP0) bleibt aber ein echtes Signal für ein Finale — beide Berichte sind nur
vereinbar, wenn **mehrere** Endpfade vorkommen (§0.2.4).

### 0.2.1 Zeitachse der ersten 2,5 s (Code, vc31/32)

| t (ab AudioRecord-Start) | Ereignis | Quelle |
|---|---|---|
| 0 | `start()`: Uhren null, `lastLoudAtMs = now`, `everLoud=false`, `tracker.reset()` (Besitz-Baseline offen) | `DictationCoordinator.start` |
| 0–3,0 s | ERR_SILENT-Fenster: ohne lauten Block (≥ −40 dBFS) bis 3,0 s → `abort("audio_error:silence")` | `SILENT_START_LIMIT_MS=3000` |
| erster lauter Block (~0,1–0,3 s) | `everLoud=true`; F43-Uhr `lastLoudAtMs` läuft ab hier | captureLoop |
| 2,0 s Audio | erster Consumer-Tick (`chunkMs=2000`) → `feedAudio` → `audioSeconds ≥ 2,0` (`MIN_AUDIO_SECONDS_PARTIALS`) → **1. Decode** (Gerät ~0,1–0,4 s) → **1. Hypothese** → `writeHypothesis` → `isOwned` (expectedText leer ⇒ true) → `policy.onHypothesis` (Agreement 1/2 ⇒ **kein Commit**) → **`typer.revise()` schreibt den vollen Hypothesentext als revidierbaren Partial in den Editor** (`replaceBeforeCursor` = `deleteSurroundingText`+`commitText` im Batch) → `tracker.onOwnEdit` | `DictationController.feedAudio/writeHypothesis` |
| +0–50 ms | App antwortet auf den **ersten Fremd-Commit**: `onUpdateSelection` (erwartet, `ownEditPending`), ggf. zusätzliche Callbacks (Autokorrektur/Spans → `lost=true`), ggf. **`restartInput()`** → `onStartInput(restarting=true)` → `onEditorChanged()` → `abort("editor_change")` | `OwnershipTracker`, `VoiceKeyboardService.onStartInput` |
| 4,0 s Audio | 2. Tick → 2. Hypothese → Agreement 2/2 → **erster LocalAgreement-Commit** (Wortgrenze, Holdback 2 Wörter, min 3) | `CommitPolicy` |
| ≥ 2,5 s nach letztem lauten Block | F43 (nur bei offenem Gate) → `stop()` → „Verarbeite…" → „Bereit" | captureLoop |

**Lesart „~2 s, manchmal schneller":** Das deckt sich mit dem **ersten Editor-Schreibvorgang bei t ≈ 2,0 s + Decode**
— nicht mit dem ersten *Commit* (t ≈ 4 s) und nicht mit F43 (frühestens 2,5 s nach der ersten Pause, also für ein
gesprochenes Fragment ≥ 3–4 s). „Schneller als 2 s" ist mit F43 unmöglich, mit dem Partial-Schreibpfad nur dann,
wenn das Gerät den ersten Tick früher sieht (Block-Aggregation ≥ `tickSamples`, Overshoot) — oder die Zeit ab
Tastendruck statt ab Audio-Start gefühlt ist (Kaltstart `start_ms` kommt hinzu, spricht für „gefühlt").

### 0.2.2 „Fragment grammatisch geschlossen" — reines Engine-Verhalten

Ja: Parakeet-TDT punktiert jede Äußerung am **Decode-Fensterende** (das Modell sieht den Puffer als abgeschlossen
und setzt Satzzeichen/Großschreibung so, dass das Gesehene ein Satz ist). Jede Partial-Hypothese endet deshalb
„grammatisch korrekt", ganz gleich, wo das Audio abgeschnitten ist. **Im Code gibt es keinen Pfad, der ein
Satzzeichen zum Commit-/Stop-Signal macht:** LocalAgreement schneidet an Wortgrenzen, `lastHypPunct` speist nur die
Segmentregel (a) (Mini-Finale, Session läuft weiter), F43 misst Stille, nicht Zeichen. Der Nutzer beobachtet also den
**ersten in den Editor geschriebenen Partial** (Fragment der ersten 2 s mit Modell-Punkt), danach endet die Session
— die Frage ist allein, *welcher* Endpfad bei t ≈ 2,1–2,4 s feuert.

### 0.2.3 Welche Pfade können ≤ 0,5 s nach dem ersten Partial enden?

| # | Pfad | Auslöser | Status danach | Export (vc33) |
|---|---|---|---|---|
| **P1 (H-E1)** | App → `restartInput()` auf ersten Fremdtext → `abort("editor_change")` | 1. `replaceBeforeCursor` | **„Audio-Start fehlgeschlagen"** (sticky; ⚠ `lastAbortReason` wird auf `audio_error:aborted` überschrieben, :696 → AP3-Fix) | `mic_rel=editor:<~2100>`, `restart_inputs ≥ 1`, `stop_cause=abort:editor_change` (nach Fix), `status_res=dictation_error_audio_start`, `hyp_count=1`, `had_output=1`, `output_chars` ≈ Fragmentlänge |
| **P2 (H-E2′)** | App → unerwartete Selektions-Callbacks → `lost=true`; **kein** Session-Ende, aber ab 2. Hypothese `isOwned=false` → nichts mehr geschrieben | 1. Schreibvorgang | **„Diktat…" bleibt, Mikro grün** bis UP → dann „Verarbeite…" → „Bereit" (Final-Decode wird ebenfalls verweigert) | `sel_unexpected ≥ 1`, `owner_lost_at_s ≈ 2–4`, `hyp_count` wächst, `output_chars` eingefroren, `stop_cause=stop:holdup`, `status_res=status_ready` |
| P3 (H-E3′) | `commitText`/`deleteSurroundingText` liefert `false` → `EditorWriteException` (ISE) → `fail(ASR_PARTIAL_ERROR)` | 1. Schreibvorgang in App ohne aktiven InputConnection-Fokus / Read-only-Phase | Fehlertext (Fallback „Audio-Start") | `ctl_failed=1`, `last_error=asr_partial_error`, `mic_rel` ohne `editor` |
| P4 (H-E3) | Main-Thread-Stall > Timeout beim ersten Edit → `editor_sync_timeout` | schwere App; F56-Emulator-Nebenbefund zeigt dieselbe Signatur (dort Hardware-Drift bei 16 s) | „App reagiert gerade nicht" (`dictation_error_editor_busy`) | `last_error=editor_sync_timeout`, `status_res=dictation_error_editor_busy` |
| P5 (W1b) | Gate offen + ≥ 2,5 s Pause | nur **nach** Supersede-/Abort-Rest und nur mit echter Sprechpause | „Verarbeite…" → „Bereit" | `fgs_stopped_by=autostop`, `gate_at_stop=1`, `audio_s ≥ 4` |
| P6 | ERR_SILENT (3 s ohne lauten Block) | Pegel < −40 dBFS (Gerät −56 dBFS gemessen, ANALYSE_2 §2!) | „Kein Signal" | `abort_reason=audio_error:silence`, `loud_s=0`, `had_output=0` — **widerspricht** „Fragment wurde geschrieben" → raus |

### 0.2.4 Gewichtung final

Zwei Nutzerberichte, zwei Signaturen: **(A)** „bis Satzzeichen, dann ≤ 2 s Ende, Fehlertext (als ‚Bereit' erinnert)" =
**P1**; **(B)** „gehalten, ‚Verarbeite…', ‚Bereit', manchmal hält es" = **P5/P2**. Kombination ist die konsistenteste
Erklärung: **P1 ist der Regelfall** (jedes Diktat stirbt beim ersten Schreibvorgang, App-abhängig), **W1b erklärt die
Ausreißer** („mal hält es doch" = App hat gerade nicht restartet, dafür Gate-Race nach Supersede) — W1b ist durch
AP7-W1 ohnehin billig geschlossen und bleibt im Plan.

| Hypothese | §0.1.5 | **jetzt** | Begründung |
|---|---|---|---|
| **H-E1 / P1** restartInput-Abort | 8 % | **45 %** | t ≈ 2 s = erster Schreibvorgang; Looker sieht Abort-Text; „jedes Diktat"; App-spezifisch |
| **H-E2′ / P2** Besitzverlust beim 1. Edit | 5 % | **20 %** | gleiche Uhr; erklärt „Verarbeite…→Bereit" + „Fragment" bei gehaltenem Finger (Session lebt stumm bis UP) |
| H-W1b / P5 Gate-Race | 70 % | 15 % | nur nach Vorsession, braucht ≥ 4 s Audio — passt nicht zu „≤ 2 s, jedes Mal" |
| H-E3 / P3+P4 | 5 % | 12 % | F56 zeigt, dass die Signatur real auftritt; schwere App ⇒ Main-Drift |
| Rest (A1/A2/T1/D1/S1) | 12 % | 8 % | — |

**Entscheidend bleibt EIN Nutzer-Export** direkt nach dem Ende: `mic_rel` (`editor:` ⇒ P1), `sel_unexpected`/
`owner_lost_at_s` (⇒ P2), `last_error` (⇒ P3/P4), `fgs_stopped_by=autostop` (⇒ P5). Plus die Angabe der Ziel-App —
P1/P2 sind App-Verhalten; der Emulator kann sie nur *imitieren*.

### 0.2.5 Emulator-Entscheidungstabelle (vc33, `emu_c2_run.sh`, FakeWav k120 „Satz. ⟨3 s⟩ Satz…", Engine real, Hold)

Jeder Lauf: Hold per `motionevent DOWN`, Ende durch UP nach 120 s oder früher durch Session-Ende; 🐞 → Export-Datei.
Sollwert „Stop-Zeitpunkt" = VK-SESS-Zeile `state=IDLE`/`aborted:` relativ zu `start_ok`.

| Zelle | Modus | Erwartung (Zeitpunkt, Statuszeile) | Export-Signatur, die das Ergebnis EINDEUTIG macht | ⇒ Hypothese |
|---|---|---|---|---|
| T0 | `plain` | kein Ende bis UP; `output_chars` ≈ Golden | `stop_cause=stop:holdup`, `restart_inputs=0`, `sel_unexpected=0`, `gate_at_stop=0` | Kontrolle — muss grün sein, sonst Runner/Audio-Kette ungültig |
| **T1** | `restart:20` | **Ende bei ~2,1–2,4 s**, Fehlertext `dictation_error_audio_start` | `mic_rel=editor:<2000–2600>`, `restart_inputs=1`, `stop_cause=abort:editor_change`, `hyp_count=1`, `had_output=1`, `output_chars` ≈ 15–40 | **P1 reproduziert = Nutzer-Signatur (A)** |
| T1b | `restart:200` | Ende erst nach ~4–8 s (erst beim 200. Zeichen) | wie T1, `mic_rel=editor:<4000–9000>`, `hyp_count ≥ 2` | P1, zeigt App-Schwellenabhängigkeit („manchmal schneller") |
| **T2** | `cursor:25` | **kein** Ende; Mikro grün; Text friert nach ~2 s ein; UP → „Verarbeite…" → „Bereit" | `sel_unexpected ≥ 1`, `owner_lost_at_s ≈ 2–4`, `output_chars` ≪ Golden, `hyp_count` ≈ 55, `stop_cause=stop:holdup`, `status_res=status_ready` | **P2 = Nutzer-Signatur (B) bei gehaltenem Finger** |
| T3 | `spans` | wie T2 (Span-Setzen löst i. d. R. Selektions-Callback aus) oder kein Effekt | wie T2 bzw. T0 | P2 (Spell-Checker-Variante) / neutral |
| T4 | `slowmain:1500` | Ende bei erstem Edit (~2 s + 1,5 s), Text „App reagiert gerade nicht" | `last_error=editor_sync_timeout`, `status_res=dictation_error_editor_busy`, `ctl_failed=1` | P4 (H-E3); **gleiche Signatur wie F56-Nebenbefund** — auf dem Gerät nur glaubhaft, wenn der Nutzer diesen Text nennt |
| T4b | `slowmain:300` | kein Ende (unter Timeout) | wie T0, `hyp_gap_ms` erhöht | H-E3 nicht bei moderatem Stall |
| T5 | `hideime:3` | Ende bei 3 s mit Finale → „Bereit" | `mic_rel=hidden:<3000>`, `stop_cause=stop:hidden` | H-A2 (Abgrenzung: Tastatur verschwindet sichtbar) |
| T6 | `webview` | **offen** — echtes Chrome-Verhalten: erwartet Ende wie T1 oder Besitzverlust wie T2 | wie T1 oder T2 | P1/P2 mit realem App-Stack (wichtigste Zelle nach T1/T2) |
| W1b-1 | `plain` + Vorsession (DOWN₁/UP₁/DOWN₂) | §0.1.7 | `gate_at_stop=1`, `fgs_stopped_by=autostop` | P5 |

**Abnahmekriterium für „Simulation reproduziert Nutzer-Symptom":** T1 liefert `mic_rel=editor` bei ≤ 2,6 s und
`output_chars` = Länge des ersten Partials; T2 liefert eingefrorenes `output_chars` bei laufender Session. Beide
Signaturen sind dann mit dem nächsten Nutzer-Export (ein Blick auf `mic_rel`/`sel_unexpected`) direkt vergleichbar.

**Fix-Implikationen (AP7, nach Befund):**
- P1 → **AP7-E1**: `onStartInput(restarting=true)` während RECORDING ⇒ **kein** Abort; stattdessen `tracker`-Rebaseline
  (expectedText beibehalten, `baselineTaken=false`), VK-SESS `input restarting=1 kept=1`. Echter Editorwechsel
  (`restarting=false` oder anderer `EditorInfo.fieldId`/`packageName`) bleibt Abort.
- P2 → **AP7-E2**: `lost` nicht als Einweg-Latch beim 1. Edit; bei unerwartetem Callback Suffix-Resync
  (`isOwned`-Prüfung ohne `lost`-Kurzschluss, erst bei tatsächlicher Divergenz verlieren); Selektionsbereiche
  (`newSelStart != newSelEnd`) weiterhin hart.
- P3/P4 → nur Diagnose (Textwahl), kein Pipeline-Fix.

---

## 1. Emulator-Simulation (Kern-Pflicht) — Schritt für Schritt

### 1.1 Prinzip: drei Spuren, eine Matrix

| Spur | Audio-Quelle | Engine | Editor | Wiederholbar pro Boot | Deckt ab |
|---|---|---|---|---|---|
| **S-A „Pipeline"** | FakeWav (600-s-Korpus) | echte int8 | DebugInputActivity (gutmütig) | ∞ | Segmenter, Controller, Decode-Rückstau, RSS, Watchdogs mit echten Decode-Zeiten |
| **S-B „Editor"** | FakeWav | echte int8 **oder** Latenz-Stub | **HostileEditorActivity** (Varianten) | ∞ | H-E1 restartInput, H-E2 Besitzverlust, H-E3 Sync-Timeout, Hold-Geste über 600 s |
| **S-C „Mikro"** | echtes virtuelles Mikro (`paplay`) | echte int8 | DebugInputActivity | **1 (Erste-Session-Gesetz)** | AudioRecord 600 s, FGS-Dauerlauf, Pegel, `maxDuration`, System-Stopps |

Reihenfolge: **S-B zuerst** (höchste Vorab-Wahrscheinlichkeit, billigste Läufe), dann S-A (Nebenbefunde RSS/Segmenter),
S-C zuletzt (teuer, 1 Versuch/Boot).

### 1.2 Voraussetzungen (AP1, AP2 — je ≤ 4 Dateien, Debug-Sourceset)

**AP1 — Debug-Hooks von außen (kein Produktionsverhalten):**
- `app/src/debug/.../debug/DebugHooks.kt` (neu): liest beim Aufbau des Coordinators
  - `debug.vk.fakewav` (System-Property, `adb shell setprop`) → Pfad einer WAV unter `/data/local/tmp` oder
    `files/` → setzt `debugAudioSourceFactory = { FakeWavAudioSource(file, scenario="throughput") }`;
  - `debug.vk.engine` = `real` | `stub:<alpha_ms_per_s>:<base_ms>` → `engineFactory` wird im Debug durch
    `LatencyStubEngine` ersetzt (siehe unten);
  - `debug.vk.export_file=1` → jeder 🐞-Export wird zusätzlich nach `files/debug_export.txt` geschrieben
    (identischer Inhalt → Regel 9 unverändert; Pull per `adb exec-out run-as de.voicekeyboard cat files/debug_export.txt`).
- `app/src/debug/.../asr/LatencyStubEngine.kt` (neu): `transcribe()` schläft `base + α·buffer_s` ms auf der
  **Echtzeituhr**, liefert deterministischen Pseudo-Text mit Satzzeichen alle ~8 Wörter (damit Segmentregel (a)
  feuert) und Länge ∝ `buffer_s` (damit Commit/Delta realistisch groß wird). α-Default **38,5** (Gerätekurve I3),
  Stress-Varianten 80 und 150. **Antwort auf „simuliert α sicher genug?":** Für Watchdog-/Segmenter-/Editor-Fragen ja —
  die Pipeline sieht nur Decode-Dauer und Textlänge. Für RSS/OOM und Transkriptqualität **nein** → dafür echte Engine
  (S-A). Beides im Plan.
- `VoiceKeyboardService.kt`: 3 Zeilen — `DebugHooks.apply(this)` vor `buildCoordinator()` (nur Debug-Klasse, im
  Release über leeren Stub im `release/`-Sourceset wie `LiteRtBridgeSetup`).
- Ein zusätzlicher **debug-only logcat-Tag `VK-SESS`** (Codes/Zahlen): `state=<IDLE|STARTING|RECORDING|FINALIZING>
  cause=<code> audio_s=<n> out=<chars> seg=<n>` bei jedem Zustandsübergang, bei jedem Mini-Finale, bei
  `onStartInput(restarting=…)`, `onUpdateSelection(lost=…)`. Regel 9: keine Texte.

**AP2 — Feindlicher Editor (`app/src/debug/.../debug/HostileEditorActivity.kt` + Debug-Manifest-Eintrag):**
Ein `EditText`, dessen Verhalten per Intent-Extra `mode` gewählt wird:

| mode | Verhalten (imitiert reale Apps) | Zielhypothese |
|---|---|---|
| `plain` | wie DebugInputActivity | Kontrolle |
| `restart:N` | nach jeweils N Zeichen Zuwachs `imm.restartInput(view)` | **H-E1** |
| `cursor:N` | nach jeweils N Zeichen `setSelection(len-1)` dann `setSelection(len)` (Compose-/Autokorrektur-Muster) | **H-E2** |
| `spans` | setzt bei jedem Text-Change einen `SuggestionSpan`/`UnderlineSpan` (Spell-Checker-Muster) | H-E2 |
| `slowmain:MS` | `TextWatcher.afterTextChanged` blockiert Main MS ms (z. B. 300/1500) | **H-E3** |
| `hideime:S` | nach S Sekunden `imm.hideSoftInputFromWindow` | H-A2 |
| `webview` | `WebView` mit `<textarea>` (Chrome-Restart-Verhalten real statt imitiert) | H-E1 |

### 1.3 Ablauf S-B/S-A (Skript `tools/emu_c2_run.sh`, AP5)

```
1  Vorbedingungen: <device> boot_completed=1; APK -diag-debug installiert; Modell unter files/models/versions/<v>.
2  adb push testdata/korpus/k600_p3s_-17.wav /data/local/tmp/k.wav
3  adb shell setprop debug.vk.fakewav /data/local/tmp/k.wav ; setprop debug.vk.engine real ; setprop debug.vk.export_file 1
4  adb shell am force-stop de.voicekeyboard            # Prozess frisch (Hooks werden beim Coordinator-Bau gelesen)
5  adb shell am start -n de.voicekeyboard/.debug.HostileEditorActivity --es mode restart:40
6  IME einblenden (ime set + Tap ins Feld), Screenshot als Belege-Anker
7  HOLD starten:  adb shell input motionevent DOWN <micX> <micY>          # bleibt unten (kein UP)
8  Sampler parallel (alle 30 s, in CSV): date; dumpsys meminfo de.voicekeyboard | grep -E "TOTAL PSS|Native Heap";
   logcat -d -s VK-SESS | tail -1 ; dumpsys audio | grep -A3 "Recording"   (nur S-C relevant)
9  Warten bis: logcat VK-SESS zeigt state=IDLE  ODER  t > korpus_len + 60 s
10 Falls noch RECORDING: adb shell input motionevent UP <micX> <micY>  → reguläres Finale
11 🐞 antippen (Tap) → Export-Datei pullen → tools/export_to_csv.py hängt Zeile an reports/c2_runs.csv
12 Editor-Inhalt zählen (uiautomator dump → Textlänge, NICHT Inhalt loggen) → output_len_editor
13 Vergleich mit Golden-Länge des Korpus (Sidecar-JSON) → Vollständigkeitsquote
```

**Messpunkte je Lauf (alle Regel-9-konform):**
- Export: `abort_reason`, `fgs_stopped_by`, `mic_rel`, `status_res`, `last_error`, `ctl_failed`, `audio_s`,
  `loud_s`, `had_output`, `output_chars`, `hyp_count`, `hyp_gap_ms`, `decode_ms_max/p95`, `buffer_s`,
  `dropped`, `cap_stall_ms`; **neu (AP3):** `stop_cause`, `owner_lost_at_s`, `segment_finalizations`,
  `max_pause_ms`, `restart_inputs`, `sel_callbacks_unexpected`.
- logcat `VK-SESS`: Zeitpunkt des letzten Zustandswechsels, Anzahl `onStartInput(restarting=true)`.
- RSS-Kurve: PSS/Native Heap über Zeit (S-A); Erwartung mit Segmenter: Sägezahn ≤ 35-s-Fenster, kein Trend.
- Editor-Textlänge vs. Golden-Länge.

**Abbruch-/Erfolgskriterien (Reproduktion gilt als gelungen, wenn EINES zutrifft):**
- R1: Session endet vor Korpus-Ende und `stop_cause ∉ {holdup, up}` → Abbruch-Pfad identifiziert (Code im Feld).
- R2: Session läuft bis Ende, aber `output_chars`/Golden < 0,6 **und** `owner_lost_at_s ≥ 0` → stiller Schreibstopp.
- R3: `ctl_failed=1` mit `last_error ∈ {editor_sync_timeout, asr_*}` → Fehlerpfad.
- Lauf **ungültig**, wenn `cap_blocks` < 0,9·erwartet (Fake-Quelle lief nicht) oder `audio_s` < Korpus-Länge ohne R1.
- Matrix S-B: 7 Modi × {real, stub:38.5} × 1 Korpus (600 s, Pausen 3 s) = 14 Läufe à ≤ 11 min ≈ 2,5 h.
  Erst nach einem Treffer: Verfeinerung (N variieren, Korpus 120 s zum schnellen Bisect).

### 1.4 Spur S-C (Erste-Session-Gesetz als Werkzeug)

Pro Versuch: `emulator -no-snapshot-load` (Kaltboot) → `emulator_audio_up.sh` → **Vorbedingungs-Assert**
(`pactl info | grep "Default Sink: vkmic"`, `XDG_RUNTIME_DIR` gesetzt) → Install → `DebugInputActivity` → Hold per
`motionevent DOWN` → `paplay k600.wav` → Sampler → nach 3 s Export-Zwischencheck ist **nicht** möglich (Export erst
am Ende), daher **Pegel-Gate über logcat `VK-SESS first_loud_ms`** innerhalb 5 s, sonst Lauf abbrechen (Gesetz
verletzt / Kette falsch). Ziel: zwei gültige 600-s-Läufe. Erwartung: wenn S-B/S-A keinen Killer zeigen und S-C
sauber durchläuft, ist die Ursache gerätespezifisch (H-A1/H-T1) → Nutzer-Beweiskette (§2) entscheidet.

### 1.5 Korpus (AP4, Python, kein Kotlin)

`tools/make_korpus.py`: piper-tts (`de_DE-thorsten-medium`, offline) bzw. `espeak-ng` Fallback; 600 s = ~70 Sätze,
Pausen 1,5–4 s gemischt (Sidecar-JSON: Satzgrenzen, Pausen, Golden-Länge in Zeichen), Pegel −17 dBFS, Pausen mit
−60-dBFS-Rauschen (nie digital still). Varianten: 120 s (Bisect), 600 s (Hauptlauf), 900 s (Cap-Test).
`tools/watchdog_twin.py`: rechnet Block-RMS wie `captureLoop` → wo feuern F43 (nur Toggle), Segmentregel (a),
`max_pause_ms` — Orakel für die Export-Werte.

### 1.6 Was F37 hier noch bedeutet

Nicht nötig für die Reproduktion. Nötig für **AP7**: dieselben Läufe als JVM-Tests in Sekunden (Weg B
deterministisch). Dafür F37 „breit" wie in der Vorberatung §1.2 (Controller-Clock, Thread-Factory, Service-Dispatcher).

---

## 2. Parallele Beweiskette Nutzer (minimal, zumutbar)

**Anleitung (eine Nachricht an den Nutzer):**
1. Lange Aufnahme wie gewohnt starten (Halten), in **derselben App wie bisher**.
2. Sobald es „aufhört" (Text wächst nicht mehr **oder** Mikro nicht mehr grün): **nichts anderes antippen**, Finger ggf.
   lösen, dann **sofort 🐞** drücken und den Export schicken. (Jeder weitere Mic-Tap überschreibt die Session-Felder.)
3. Zwei Ja/Nein-Antworten dazu: (a) War das Mikro noch grün, als der Text aufhörte? (b) Welche App (nur Name — er steht
   bewusst nicht im Export)?

**Die drei entscheidenden Felder** (alle in 0.1.31 vorhanden):

| Feld | Wert | Bedeutung |
|---|---|---|
| `abort_reason` | `editor_change` | **H-E1** bestätigt (App hat restartInput ausgelöst) |
| | `final` + `output_chars` ≪ erwartet | **H-E2** (Besitz verloren, stiller Schreibstopp) |
| | `audio_error:<code>` | Audio-/System-Schicht (`systemStop`, `invalidOperation`, `stall`, `maxDuration`) |
| | `michold_cancel` | Geste vom System gecancelt (H-G1) |
| `mic_rel` | `editor:<ms>` / `hidden:<ms>` / `holdup:<ms>` / `holdcancel:<ms>` | **wer** hat beendet, **wann** (ms seit Start) |
| `fgs_stopped_by` | `abort:…` / `stop` / `autostop` / `fgs-timeout` | Gegenprobe zu `abort_reason` |

Sekundär: `audio_s` vs. `loud_s` (wurde noch gesprochen?), `ctl_failed`/`last_error` (H-E3), `status_res`.

---

## 3. Hypothesen-Baum — was auf dem echten Gerät lange Aufnahmen killt

Wahrscheinlichkeiten = meine Einschätzung **ohne** Export #3; Summe ≈ 100 %.

| # | Hypothese | p | Mechanismus im Code | Tötet sie | Überlebt sie |
|---|---|---|---|---|---|
| **H-E1** | Ziel-App ruft `restartInput()` auf Textänderung → `onStartInput(restarting=true)` → `onEditorChanged()` → `abort("editor_change")`, still (kein Fehlertext) | **30 %** | `VoiceKeyboardService.kt:374–388` ignoriert `restarting` | `abort_reason≠editor_change` **und** `mic_rel` nicht `editor:` | `abort_reason=editor_change`; S-B `restart:N` reproduziert; Fix: bei `restarting=true` **nicht** abbrechen, nur Besitz neu verankern (`tracker` re-baseline) |
| **H-E2** | Besitzverlust durch App-Selektions-Callbacks (Autokorrektur, Spans, Compose-Normalisierung) → `isOwned=false` → Hypothesen werden still nicht geschrieben; Session läuft weiter | **20 %** | `OwnershipTracker.onUpdateSelection` → `lost=true`; `writeHypothesis` return false ohne Zähler | `output_chars` wächst bis Ende ≈ Golden | `had_output=1`, `output_chars` stagniert, `hyp_count` steigt, Mikro grün; S-B `cursor:N`/`spans`; Fix: Resync statt Verlust (Text-vor-Cursor erneut lesen; Besitz gilt, wenn eigener Text noch Suffix ist — die Prüfung existiert, nur `lost` ist ein Einweg-Latch) |
| H-E3 | Editor-Sync-Timeout: Main-Thread der IME ausgelastet (große Commits in schwerer App, lange `deleteSurroundingText`) → `fail(editor_sync_timeout)` | 10 % | `InputConnectionSink.onEditExecutor`-Timeout; `DictationController` fail-Pfad | `ctl_failed=0` | `last_error=editor_sync_timeout`, `status_res=dictation_error_editor_busy`; S-B `slowmain` |
| H-A1 | System stoppt die Aufnahme (GrapheneOS Mikrofon-Indikator/Scoped-Mic, OEM-FGS-Kill, `AudioRecord.read<0`, Client silenced wenn IME teils verdeckt) | 10 % | `AudioRecordSource.captureLoop` → `ERR_SYSTEM_STOP`/`ERR_INVALID_OP` → `audio_error:*`; ERR_STALL nach 3 s ohne Blöcke | `abort_reason` nicht `audio_error:*`, `cap_stall_ms` klein | `audio_error:systemStop|invalidOperation|stall`; S-C 600 s; `dumpsys audio` Recording-Status |
| H-G1 | Hold-Geste vom System gecancelt (`ACTION_CANCEL`: Gestennavigation am Rand, zweiter Finger/Handballen, Fenster-Resize durch App) → `abort("michold_cancel")` ohne Finale | 8 % | `KeyboardView` 939–962, `onMicHoldEnd(cancelled=true)` | `mic_rel≠holdcancel` | `mic_rel=holdcancel:<ms>`, `abort_reason=michold_cancel`; Hinweis: hier geht **das ganze unkommittete Segment verloren** — selbst wenn p klein, ist CANCEL→Finale statt Abort eine billige Robustheitsmaßnahme |
| H-A2 | App blendet IME aus (Fullscreen, Scroll-Hide, Dialog) → `onFinishInputView`/`onWindowHidden` → Stop mit Finale | 7 % | `VoiceKeyboardService.kt:395–413` | `mic_rel≠hidden` | `mic_rel=hidden:<ms>`, `fgs_stopped_by=stop`; S-B `hideime:S` |
| H-W1 | Eigene Watchdogs: F43 trotz Hold (Gate-Flag kippt bei Pointer-Wechsel), ERR_STALL bei kurzem BT-Aussetzer, ERR_SILENT falsch | 5 % | `holdGestureActive` an zwei Stellen gesetzt (Button-Touch **und** KeyboardView-Hold) — ein UP des *anderen* Pfads setzt es false | `fgs_stopped_by≠autostop`, `abort_reason` nicht stall/silence | `fgs_stopped_by=autostop` bei Hold; S-B Lauf mit beiden Mic-Eingängen |
| H-D1 | Decode-Rückstau/OOM trotz Segmenter (Mini-Finale-Fehler → `return false` → Segment wächst weiter → ab ~60 s Fenster superlinear) | 5 % | `finalizeSegment` wirft transient → Antrag verworfen; nächster Versuch erst bei nächster Pause/Längenregel | `segment_finalizations ≈ audio_s/35`, `decode_ms_max < 3000`, RSS flach | `decode_ms_max ≫`, RSS-Trend, `last_error=oom`; S-A 600/900 s |
| H-T1 | Fremder Mikro-Zugriff (Anruf, Assistent, Sprachnotiz-Widget) / AudioFocus | 3 % | wie H-A1 (`systemStop`) | — | nur Nutzerkontext |
| H-S1 | Segmenter-Naht verliert/dupliziert Text (Prefix-Rekonstruktion, `trimEnd`-Naht), wirkt wie „unvollständig" | 2 % | `withSegmentPrefix`, `finalizeSegment` setzt `segmentPrefix=typer.currentText` | S-A Text-Länge ≈ Golden über 600 s | Differenz an Segmentgrenzen (nur Längen/Positionen loggen) |

**Lesehilfe:** H-E1+H-E2+H-E3 = 60 % liegen in einer Schicht, die der Emulator **ohne Mikrofon** vollständig
abdeckt. Das rechtfertigt S-B als ersten Schritt.

---

## 4. Arbeitspakete (Reihenfolge, je ≤ 4 Dateien, reviewbar)

| AP | Inhalt | Dateien | Aufwand | Abhängig von |
|---|---|---|---|---|
| **AP1** Debug-Hooks | `DebugHooks.kt` (setprop → FakeWav/Engine/Export-Datei), `LatencyStubEngine.kt`, 3 Zeilen Service, `VK-SESS`-Log | debug/DebugHooks.kt, debug/asr/LatencyStubEngine.kt, VoiceKeyboardService.kt, release/…/DebugHooks.kt (leer) | 0,5 Tag | — |
| **AP2** Feindlicher Editor | `HostileEditorActivity` mit 7 Modi | debug/HostileEditorActivity.kt, debug/AndroidManifest.xml | 0,5 Tag | — |
| **AP3** Export v2 (Teil 1) | `stop_cause` (vereinheitlicht aus mic_rel/abort_reason/fgs), `owner_lost_at_s`, `segment_finalizations`, `max_pause_ms`, `restart_inputs`, `sel_unexpected`; `hint:<res-name>` in `status_res` auch für Hint-Events | DictationCoordinator.kt, DictationController.kt (owner_lost-Zähler), DebugInfoCollector.kt, DebugInfoFormatter.kt | 0,5 Tag | — |
| **AP4** Korpus-Tools | `make_korpus.py` (piper/espeak, Sidecar), `watchdog_twin.py`, `export_to_csv.py` | tools/ (3 Dateien) | 0,5 Tag | pip piper-tts |
| **AP5** Emulator-Runner | `emu_c2_run.sh` (Ablauf §1.3 inkl. Sampler, Asserts, CSV), README-Abschnitt | tools/emu_c2_run.sh, docs/EMULATOR_DIKTAT_HARNESS.md | 0,5 Tag | AP1, AP2, AP4 |
| **AP6** Läufe S-B → S-A → S-C | Matrix §1.3/1.4, Ergebnis `reports/c2_runs.csv` + `docs/C2_R2_BEFUNDE.md` | — | 1 Tag | AP5 |
| **AP7** Fixes nach Befund (jeweils eigener Review) | **E1:** `onStartInput`: bei `restarting=true` kein Abort, Besitz re-verankern. **E2:** `OwnershipTracker`: `lost` nicht als Einweg-Latch — bei unerwartetem Callback Suffix-Resync versuchen, erst bei Divergenz verlieren; Zähler. **G1:** `ACTION_CANCEL` während Hold → Finale statt Abort (kein Textverlust). **W1:** `holdGestureActive` nur aus EINER Quelle setzen. | je 1–2 Dateien + Tests | je 0,5 Tag | AP6 |
| **AP8** Export v2 (Teil 2) | Session-Ringpuffer (5 Zeilen), `start_phase_ms` | VoiceKeyboardService.kt, DebugInfoCollector.kt, DebugInfoFormatter.kt | 0,5 Tag | AP3 |
| **AP9** F37 breit + JVM-Weg-B | Controller-Clock/Thread-Factory/Dispatcher injizierbar; KorpusTaktTest mit 600-s-Korpus und Latenz-Stub | Coordinator, Controller, Service, Test | 1 Tag | nach AP7 |

**Bau 0.1.32 (Mindestinhalt):** AP1 + AP2 (nur Debug-Sourceset, Release unverändert) + AP3 (`hint:<res-name>`,
`segment_finalizations`, `stop_cause`, `max_pause_ms`, `owner_lost_at_s`). Falls AP6 bis dahin H-E1/H-E2 bestätigt:
zusätzlich den jeweiligen AP7-Fix. **Nicht** in 0.1.32: Ringpuffer (AP8), F37 (AP9).

**Entscheidungspunkt nach AP6:** Zeigt keine der drei Spuren R1/R2/R3 **und** liegt weiterhin kein Nutzer-Export vor →
dann (und erst dann) 3er-Expertenrunde. Vorher wäre sie Spekulation ohne Daten.

---

## 5. Risiken und Grenzen dieses Plans

- `input motionevent DOWN` ohne UP über 10 min: funktioniert ab API 30; falls der Emulator-Input-Stack den Pointer
  nach einer Weile verwirft (ACTION_CANCEL), ist das **selbst** ein Befund (H-G1-Signatur im Export) — nicht als
  Werkzeugfehler abtun, sondern mit `uiautomator`-Alternative (`--duration`-Swipe) gegenprüfen.
- FakeWav speist **vor** AudioRecord ein: H-A1/H-T1 bleiben S-C vorbehalten (1 Versuch/Boot).
- Stub-Engine imitiert Textlänge und Decode-Dauer, nicht Hypothesen-Instabilität (LocalAgreement-Verhalten) — für
  CommitPolicy-Fragen echte Engine nehmen.
- Pfade/Modi/Zähler sind Regel-9-konform; der Editor-Inhalt wird im Runner **nur gezählt**, nie protokolliert.
- Alle Debug-Hooks laufen über System-Properties — im Release nicht kompiliert (Sourceset), kein Angriffsvektor.
