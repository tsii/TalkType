# PLAN V0130 — Sofort-Release v0.1.30 + Infra-Woche

**Planer-Stand:** 2026-11-01 · **Autor:** Planer (read-only; kein Code geändert, kein git)
**Grundlage:** `docs/DEBUGGER_FABLE_BERATUNG_2026-11-01.md` (Debugging-Bericht, 340 Zeilen) — **jede Kernaussage
vor Aufnahme in diesen Plan an den zitierten Code-Stellen verifiziert** (Belege in §0).
**Basis:** v0.1.29 / versionCode 30 · Tests Debug **483/0**, Release **452/0** (935/0).
**Ziel:** neue Version mit Downloadlink **so bald wie möglich** (Nutzer-Vorgabe), ohne die C2-Symptome
ungelöst auszuliefern.

---

## 0. Verifizierte Faktenbasis (Belege, alle im Worktree nachgelesen)

| # | Fakt | Beleg (Datei:Zeile) |
|---|---|---|
| V1 | **F43 existiert genau wie beschrieben:** `TRAILING_SILENCE_MS = 2500`; Auto-Stop **nur** bei streamender Stille (`capStallMs < 2500`), Feuer über reguläres `stop()` (Finale, kein ERR_*). | `ime/DictationCoordinator.kt:97/436–453` |
| V2 | **Es gibt KEINEN hypothesen-getriggerten Stop-Pfad:** `stop()` wird im Coordinator nur aus der F43-Watchdog-Bedingung gerufen; Hypotheseninhalt fließt nirgends in eine Stop-Entscheidung. | `ime/DictationCoordinator.kt:359–454` (captureLoop vollständig gelesen) |
| V3 | **Controller-Uhr:** `DictationController` **hat bereits** einen injizierbaren `clock`-Parameter (Default `System.nanoTime()`), der Coordinator reicht ihn **nicht durch** → `lastDecodeMs`/DecodePace laufen auf Echtzeit, Watchdogs auf `nowMs`. **Fable-Impuls korrigiert** (s. §5, Abweichung 1): kein neues API nötig, nur Durchreichen. | `dictation/DictationController.kt:80,137,179–193` · `ime/DictationCoordinator.kt:172` |
| V4 | **Start-Race Symptom 1 ist im Code belegt:** `onMicDown` guard `if (startingUp) return` nur bis zur 15-s-Frist; danach läuft die erste Coroutine weiter (kein Cancel von `engine.load()`), ein zweiter DOWN startet eine zweite Coroutine und **setzt `startAbandoned = false` zurück** → die erste (alte) Session kann als „gültig" weiterlaufen (Zombie), der zweite `start()` scheitert an `active.compareAndSet`, **und der zweite Lauf überschreibt `lastStartMs` im `finally`** (Export-`start_ms` kann Kollisionswert sein). | `ime/VoiceKeyboardService.kt:139,407–411,447–455,493–510` · `ime/DictationCoordinator.kt:208–215,296–297` |
| V5 | **UP im STARTING-Fenster ist stilles Flag** (`cancelStartRequested = true`, kein UI-Feedback; Nachreichung als KEEP_RUNNING ist gewolltes P0-Befund2(a)-Design). | `ime/VoiceKeyboardService.kt:572–583` |
| V6 | **Zwei Start-Gesten existieren:** Statusleiste = Halten (DOWN startet / UP stoppt, `VoiceKeyboardService.kt:321–327`), Fußleisten-Mikro = **Toggle** (Tap startet/stopt, `micToggleAction`, `:1155–1161`). F43 feuert heute **modusunabhängig** bei jeder Pause ≥ 2,5 s — auch während der Finger unten ist. | `ime/VoiceKeyboardService.kt` wie zitiert |
| V7 | **Symptom 2:** jeder Statuszeilen-Pfad läuft über `getString(...)`/`mapVisibleError` (Fallback „Audio-Start fehlgeschlagen" ist **übersetzt** — der Rohcode-Verdacht trifft auf die vier Kandidaten K1–K4 zu). Hartkodierte Nicht-Ressourcen-Strings (K2) belegt: `"Verarbeite…"`, `"Bereit"`, `"Diktat… ("` (`updateStatus`), `"Diagnose kopiert — Teilen-Dialog geöffnet"` (`:637`), FGS-Titel `"Diktat läuft"` (`capture/CaptureForegroundService.kt:102`). `mapErrorTextRes` existiert bereits als reine Funktion (`:156`). | `ime/VoiceKeyboardService.kt:969–1001,1040–1112,637` · `capture/CaptureForegroundService.kt:102` |
| V8 | **Export hat kein `status_res`** und keinen Session-Ringpuffer; `mic_rel`, `status_shown`, `fgs_stopped_by` existieren; Collector nimmt Service-Parameter (Muster für neue Felder vorhanden). | `debug/DebugInfoFormatter.kt:110–160,217–218` · `debug/DebugInfoCollector.kt:42–54,121–122` |
| V9 | **F46 (⇧ = Aufwärts-Pfeil) ist code-fertig** (Stroked-Pfad Schaft+Kopf+Sockelleiste, Gleiche-Höhe-Gate zu ⌫/⏎) — nur visueller Abgleich im Bau nötig. | `keyboard/KeyboardView.kt:693–740` |
| V10 | **Infra liegt bereit:** Robolectric 4.17 in `testImplementation`; `kotlinx-coroutines-test` 1.10.2 **im Gradle-Cache** (offline nutzbar); JVM-Auto-Stop-Testgerüst mit virtueller Uhr existiert (`DictationAutoStopTest` mit `VClock` + gescripteter Fake-Quelle). | `app/build.gradle.kts:134` · `~/.gradle/caches` · `ime/DictationAutoStopTest.kt` |
| V11 | **Sicherheitsnetz bei Wegfall des Auto-Stops im Hold-Modus:** `MAX_DURATION_MS = 300_000` (300 s) beendet jede Session kontrolliert; F31-Silent- und F28b1-Stall-Watchdogs bleiben unberührt. | `audio/AudioRecordSource.kt:38,108` |
| V12 | **windowed-Streaming ist GESPERRT** (KONZEPT §5.1: dokumentierte Defekte, Freigabe beim Nutzer); F43 ist als „vom Nutzer ausdrücklich gewünschte Präzisierung" dokumentiert — globales Anheben der Schwelle kippt den gewünschten „Pause = Ende"-Flow. | `docs/KONZEPT.md` §5.1, §506–514 |

**Nicht belegt / OPTION (nicht in Schnitt A):** H3b (Pegel knapp an Schwelle), H3c/H3d/H3e,
K1 als tatsächliche Ursache (Diagnosesuffix ist debug-only und beabsichtigt — ob der Nutzer *ihn* sah,
klärt `status_res`), K4 (System-Toast — nur per Rückfrage ausschließbar), Kaltstart > 15 s am Gerät
(plausibel, aber `start_ms` kalt liegt noch nicht vor). **Export #3 des Nutzers** (0.1.29-diag, F42-Felder)
liegt noch nicht vor — alle F43-/Symptom-1-Zuordnungen bleiben Hypothesen mit hohem Rang, bis er gelesen ist.

---

## 1. Schnitt A — Release v0.1.30 (heute baubar)

**Umfang:** 3 Coder-Aufträge (A1 ∥ A2, A3 nach A2-Merge), 1 Review je Auftrag (DeepSeek V4 Pro),
1 Bau + Emulator-Smoke + Auslieferung. **F46 braucht keinen Coder-Auftrag** (V9) — nur visueller
Abgleich im Bau.

### A1 — F43-Hold-Gate + F49 Minimal-Repro (JVM)

- **ID:** A1 / Feature-Nummern F49 (Gate) + F49a (Repro-Test) — im Folgenden „F49" genannt,
  da F47/F48 an A2/A3 vergeben sind (Nummern-Konflikt zu Fables Bezeichnern bewusst: s. Zeile „Alias").
  *Alias zu Fable: Symptom-3-Hardening + §2.1-Repro Schritt 3.*
- **Ziel:** Der F43-Auto-Stop wird **modusabhängig**: Er bleibt unverändert aktiv für Toggle-Sessions
  („Pause = Ende", 2,5 s — V12), wird aber **unterdrückt, solange die Aufnahme per Halte-Geste läuft
  und der Finger noch unten ist** (die Hold-Geste signalisiert „ich bin noch nicht fertig"; Fable §6.1).
  Damit kippt der Toggle-Flow nicht (V12), und Satzpausen beim Halten-Diktieren beenden die Session nicht mehr.
- **Mechanismus (verbindlich, um den §6-Hold-Vertrag erweitert):** Neuer Coordinator-Parameter
  `holdGestureActive: () -> Boolean = { false }`. **Zwei** Stille-basierte Enden werden gepflegt:
  1. **F43-Auto-Stop** (Bedingung in `captureLoop` um `!holdGestureActive()` erweitert) —
     Toggle-„Pause = Ende“ (2,5 s) bleibt (V12).
  2. **F31/ERR_SILENT** (3-s-Startfenster) ebenfalls gepflt — sonst verletzt der Hold-Vertrag
     (§6.1) den Fall „Finger unten, Nutzer überlegt > 3 s vor Sprechbeginn“. ERR_STALL (tote
     Quelle) bleibt als Systemereignis **unberührt** (FakeAudioScenarioTest Fall e testet den
     Default-Pfad — Tests laufen ohne Gate, Default `false`, bleiben grün; Fall c Stille≠Stall-Vertrag
     V1 unberührt).
  Default `false` ⇒ **alle existierenden Tests bleiben ohne Änderung grün**.
- **Dateien (exakt, 4):**
  1. `app/src/main/java/de/voicekeyboard/ime/DictationCoordinator.kt` (~15 Zeilen: Param + Gate + Kommentar)
  2. `app/src/test/java/de/voicekeyboard/ime/DictationAutoStopTest.kt` (Erweiterung)
  3. `app/src/test/java/de/voicekeyboard/ime/DictationHoldGateTest.kt` (neu, Minimal-Repro F49a)
  4. — (Reserve; nur falls `FakeAudioScenarioTest` angefasst werden muss — Ziel: nicht)
- **Minimal-Repro-Test (F49a, erlaubter Release-Anteil):** JVM, `VClock` + gescriptete Fake-Quelle nach
  Muster `DictationAutoStopTest`: Korpus „Sprache—2,0 s Pause—Sprache—3,0 s Pause—Sprache" ×
  {Gate=false, Gate=true}. Assertions: Gate=false → Auto-Stop an der 3-s-Pause (Orakel), Finale läuft
  (`StopOutcome`-Äquivalent: Session endet über regulären Stop, `lastError == null`); Gate=true →
  **kein** Auto-Stop, Session läuft bis Quellen-Ende; **Hold + durchgehende Stille > 3 s → kein
  ERR_SILENT, Session lebt** (Vertragsfall §6.1); in allen Läufen: Besitz nie verloren,
  `capStallMs`-Vertrag unberührt. **Keine Emulator-/Langlauf-Teile** — die bleiben INFRA (Schnitt B).
- **Acceptance:** Debug-Tests ≥ **490/0** (483 + mind. 7 neue); existierende Auto-Stop-/Stall-Tests
  unverändert grün; genau **1** Gradle-Lauf (`ANDROID_HOME=/opt/android-sdk ./gradlew --offline testDebug`).
- **Risiko:** gering — additiver Parameter, Default-Verhalten identisch. *Rollback:* Param entfernen
  (ein Commit-Revert, keine Datenmigration).
- **Review-Bedarf:** ja (DeepSeek, P0/P1 müssen 0 sein) — Fokus: Gate-Race (kann das Lambda während
  des Feuerns wechseln? Antwort: irrelevant, Einweg-Entscheidung pro Block), Kommentar-Vollständigkeit.

### A2 — Service: Start-Serialisierung (F48) + UP-Feedback + Gate-Verdrahtung + String-Audit (K2)

- **ID:** A2 / Feature-Nummern **F50** (Start-Serialisierung = Fable-Symptom-1-Fix),
  **F50a** (UP-Feedback im STARTING-Fenster), F49-Verdrahtung, **F51** (K2-String-Audit).
  *Alias zu Fable: §2.2 Fix („20-Zeilen-Patch") + §2.3 K2.*
- **Ziel (F50):** Invariante „zu jedem Zeitpunkt läuft höchstens eine Start-Coroutine; eine abgelaufene
  wird abgebrochen oder ihr Ergebnis verworfen" (Fable §3.4) — behebt die verifizierte Race V4:
  kein Zombie-Mikro nach Fristablauf, kein Doppelstart, `lastStartMs` wird vom **ersten** Start einer
  Session geschrieben (Fable §5.3-Kleinigkeit vorgezogen, 3 Zeilen).
- **Mechanismus (verbindlich):**
  1. `startJob: Job?` im Service merken; der Frist-Timer (15 s) **cancelt** den Start-Job statt nur Flags
     zu setzen; in der Start-Coroutine nach `engine.load()`/vor `coordinator.start()` ein
     Aktivitäts-Check (Cooperative-Cancel), damit ein abgelaufener Kaltstart kein Zombie-Diktat erzeugt.
  2. `onMicDown`: `startAbandoned = false` **entfällt** (nur der Frist-Owner setzt es); zweiter DOWN nach
     Fristablauf: altes `startJob.cancel()` + `coordinator?.abort("start_superseded")` + `stopFgs(...)`,
     dann frischer Start.
  3. `lastStartMs`: nur schreiben, wenn der vorherige Wert 0 war (Reset in `start()` bereits vorhanden —
     dort wird je Aufnahme genullt; Kollisionslauf darf nicht überschreiben).
  4. **F49-Verdrahtung:** `holdGestureDown`-Flag (Setzen im Statusleisten-DOWN-Handler `:321`,
     Clear in UP/CANCEL/`onDictationStopped`); `buildCoordinator()` übergibt
     `holdGestureActive = { holdGestureDown }` (nur Halte-Gesten setzen das Flag; Toggle-Taps
     durchlaufen DOWN/UP paarweise, Flag bleibt false).
  5. **F50a (UP-Feedback):** bei UP während `startingUp` → `postEvent(StatusEvent.Hint(getString(
     R.string.dictation_start_pending_stop)))` — Hint läuft laut StatusRouting in den transienten Kanal,
     überschreibt die „Starte…"-Zeile mit „Loslassen vorgemerkt…". Behebt den Verwirrungseindruck
     „UP ignoriert" (C2-Symptom 1) ohne das KEEP_RUNNING-Design (P0-Befund2(a)) zu ändern.
  6. **F51 (K2-String-Audit, belegt V7):** `"Verarbeite…"`, `"Bereit"`, `"Diagnose kopiert — Teilen-Dialog
     geöffnet"`, FGS-Titel `"Diktat läuft"` → `strings.xml`-Ressourcen (`getString`). Nur `values/`
     vorhanden (ein Locale) — kein Übersetzungsaufwand. K1 (Diagnosesuffix) **bleibt** (debug-only,
     beabsichtigt; Klarheit kommt via `status_res`); K3/K4 sind OPTION (§0) und gehen nicht in diesen Auftrag.
- **Dateien (exakt, 4):**
  1. `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt` (~90 Zeilen netto)
  2. `app/src/main/java/de/voicekeyboard/capture/CaptureForegroundService.kt` (~3 Zeilen)
  3. `app/src/main/res/values/strings.xml` (+4 Strings)
  4. `app/src/test/java/de/voicekeyboard/ime/StartGateTest.kt` (neu — reine Entscheidungslogik
     „Frist abgelaufen? / supersede? / Flag-Übergänge" als extrahierte Funktion `micStartGate(...)` im
     Muster `micToggleAction`/`micStartFinishedAction`, JVM-testbar ohne Robolectric; Robolectric-Race-
     Beweis folgt in Schnitt B)
- **Acceptance:** Debug-Tests ≥ **492/0** (mind. +3: StartGate-Szenarien Frist-abgelaufen/supersede/
  UP-während-Start); `StatusRoutingTest`, `MicToggleTest`, `StatusBarTextTest` unverändert grün;
  ≤2 Gradle-Läufe (`testDebug`, ggf. Nachlauf).
- **Risiko:** mittel — Serialisierung berührt den Kaltstart-Pfad (Symptom-1-Kern). Mitigation:
  Invariante als reine Funktion + Test; Review mit Fokus auf Cancel-/Abort-Reihenfolge und
  FGS-Doppelstop. *Rollback:* Auftrag isoliert revertierbar (kein Schema-/Format-Einfluss).
- **Review-Bedarf:** **hoch** (Pflicht-Review, DeepSeek, P0/P1=0 vor Bau) — das ist der heikelste
  Auftrag des Releases.

### A3 — Export: `status_res`-Feld (F47)

- **ID:** A3 / Feature-Nummer **F52**. *Alias zu Fable: §5.1 `status_res` + §2.3 „errorTextRes pure".*
- **Ziel:** Der nächste 🐞-Export sagt selbst, welcher Statuszeilen-Text stand (Symptom 2 endgültig
  klärbar): `status_res` = Code des zuletzt gezeigten Statustexts — `starting` / `recording` /
  `finalizing` / `idle` / `hint:<res-name>` / `error:<res-name>` / `diag` (Diktatzeile mit
  Debug-Suffix) / `raw` (darf nie auftreten — Assertion im Test). Regel 9 bleibt gewahrt: nur
  Ressourcen-**namen**, nie Inhalte (Fable §5 Prinzip).
- **Mechanismus (verbindlich):** `StatusBarText.SelectResult` um `kind: StatusKind` (Enum) erweitern
  (reine Funktion, JVM-testbar — `mapErrorTextRes`-Muster V7); der Service speichert nach jedem
  `updateStatus` den `kind` (2–3 Zeilen, Naht in `VoiceKeyboardService.kt`) und reicht ihn an
  `DebugInfoCollector.collect(statusRes = …)` durch; Formatter hängt `status_res` an
  (Feldliste + Zeile, Muster `status_shown`).
- **Dateien (exakt, 3 + Naht):**
  1. `app/src/main/java/de/voicekeyboard/ime/StatusBarText.kt` (~20 Zeilen)
  2. `app/src/main/java/de/voicekeyboard/debug/DebugInfoCollector.kt` (+1 Parameter, ~5 Zeilen)
  3. `app/src/main/java/de/voicekeyboard/debug/DebugInfoFormatter.kt` (Feldliste + Zeile, ~8 Zeilen)
  4. Naht (2–3 Zeilen): `app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt` — **deshalb
     startet A3 erst nach A2-Merge** (gleiche Datei, disjunkte Stellen).
  - Tests: `StatusBarTextTest.kt` (+Kind-Assertions), `DebugInfoFormatterTest.kt` (+Feld).
- **Acceptance:** Debug-Tests ≥ **495/0** (mind. +3); Export Kopf+43=44 Zeilen (Debug-Format-Test
  aktualisiert); ≤2 Gradle-Läufe.
- **Risiko:** gering — additiv, Format-getestet. *Rollback:* Feld streichen (Formatter-Test zurück).
- **Review-Bedarf:** ja (Standard, DeepSeek) — Fokus: kein Textinhalt im Feld (Regel 9).

### F46 — ⇧-Aufwärts-Pfeil: kein Auftrag, nur Bau-Abgleich

Code-fertig (V9). Im Emulator-Smoke des Baus **Screenshot hell/dunkel** ziehen und gegen
`docs/DESIGN_TASTATUR_V2.md` §8 abgleichen (Gleiche-Höhe-Gate zu ⌫/⏎, Stroked-Stil). Abweichung →
Mini-Nachauftrag nur an `KeyboardView.kt`.

### Review, Bau, Auslieferung (Orchestrator)

1. **Review je Auftrag** durch DeepSeek V4 Pro (Rollen laut HANDOFF); P0/P1=0 ist Bau-Voraussetzung.
2. **Merge-Reihenfolge:** A1 ∥ A2 → A3. Vor dem Bau: Voll-Lauf `testDebug` + `testRelease`.
3. **Bau:** `app/build.gradle.kts`: `versionCode = 31`, `versionName = "0.1.30"`;
   `ANDROID_HOME=/opt/android-sdk ./gradlew --offline assembleDebug assembleRelease`.
   Soll: Debug ≈ 495–501/0, Release ≈ 452/0 (+ ggf. gemeinsame Tests).
4. **Emulator-Smoke** (exklusiv, **<device>**, niemals 5558 = fremdes Projekt, nie parallel zum Bau;
   `tools/emulator_audio_up.sh`, F41-Vorbedingung: PulseAudio-Sink sichtbar):
   Start, Kurz-Diktat (Toggle: Auto-Stop bei Pause ✓), Halte-Diktat mit 3-s-Satzpause (**kein** Abbruch ✓),
   **Halte-Diktat 180–240 s mit 2× 3-s-Satzpausen → KEIN Abbruch, Text wächst über die Pausen hinaus
   (`output_chars`), Final nach UP, Export `fgs_stopped_by=up`, `mic_rel=holdup`** (§6.4; bewusst
   unter 300 s bleiben — v0.1.30-Obergrenze),
   UP während Kaltstart (Hinweis „vorgemerkt“ ✓), F46-Screenshot, 🐞-Export mit `status_res`.
5. **Auslieferung:** dist-Rotation, Downloadlink nach dem in HANDOFF dokumentierten Muster
   (`<download-server>/api/file?path=Voice-Keyboard/dist/<name>&download=1`); `docs/SESSION_LOG.md`,
   `HANDOFF.md`, `docs/PROJECT_STATE.md` aktualisieren. **Kein Push** ohne Nutzer-Freigabe (Regel 10).

**Schnitt-A-Testbilanz (Erwartung):** Debug 483 → **~496–502/0**, Release 452/0 (+0–2) — 3 Aufträge,
je ≤4 Dateien, je ≤2 Gradle-Läufe, A1/A2 parallelisierbar.

**Vertragshinweis (E1, bindend):** Schnitt A erfüllt den Hold-Produktvertrag (§6.1) bis auf die
Obergrenze (v0.1.30 bleibt bei 300 s — vertraglich erlaubt, UX-Schwäche; Anhebung §6.2 in v0.1.31).
Alle **Stille-basierten** Enden (F43 + F31/ERR_SILENT) sind für Hold-Sessions gepfpt.

---

## 2. Nutzer-Entscheidungen (vor Bau bzw. vor Schnitt B)

| # | Entscheidung | Option A | Option B | Empfehlung |
|---|---|---|---|---|
| **E1** | **F43-Semantik** — **BINDEND ENTSCHEIDEN (Nutzer, 2026-11-01):** Produktvertrag „kontinuierliches Diktat“ (§6.1) | Umsetzung als Hold-Gate (A1/A2): Toggle behält „Pause = Ende“ (2,5 s); Hold endet nur durch Loslassen/Abbruch/Systemereignis/Obergrenze (§6). | (abgelehnt) Schwelle global auf 4–6 s — kippte den Toggle-Flow. | **Entschieden wie A** — kein Veto-Fenster mehr; Feintuning der Toggle-Schwelle (2,5 → 4–6 s) bleibt Datennachfrage aus I3/I5, kein Release-Blocker. |
| **E2** | **windowed-Streaming-Freigabe** (KONZEPT §5.1) | Gesperrt lassen bis Kostenkurve (I3) + 6 Langläufe (I5) O(N²)-These quantifizieren. | Sofort freigeben und umbauen. | **A** — Fable §6.2 ist zuzustimmen: vor Messung ist jede Diskussion Spekulation; die dokumentierten Defekte (§5.1) sind gravierend. |
| **E3** | **Preload-/Warmup-Strategie** (Symptom „erst Anlauf 3 liefert Text", Symptom-1-Kaltstart) | Kein Preload in 0.1.30; Experiment als Debug-Flag in der Infra-Woche (I4) + `dumpsys meminfo`-Kosten (~860 MB nativ) quantifizieren; `engine_loads`/Ringpuffer machen Anlauf 1–3 sichtbar. | Produkt-Preload sofort in 0.1.30. | **A** — B sprengt Sofort-Release (Aufwand + Speicher-/OOM-Risiko ohne Messbasis). |

---

## 3. Schnitt B — Infra-Woche (nach Release v0.1.30, priorisiert)

Reihenfolge grob nach Fable §7, angepasst um die Korrekturen aus §5. Alles Parallelisierbare markiert;
Emulator-Läufe exklusiv und nie parallel zum Bau.

| Prio | ID | Arbeit | Dateien/Orte | Aufwand | Abhängigkeit |
|---|---|---|---|---|---|
| 1 | I1 | **F37-breit** (Fable §1.2, **korrigiert** s. Abweichung 1): a) `controllerClock` an `DictationController` durchreichen (Param existiert bereits, V3 — Einheit ns beachten: `nowMs()*1_000_000`); b) `captureLoop` über injizierbarem `threadFactory`/synchronisierbar; c) Service: `ioDispatcher`/`mainDispatcher`/`elapsedClock`/`START_DEADLINE_MS`/`FGS_READY_TIMEOUT_MS` als `internal var` mit heutigen Defaults; d) Engine-Fake mit latenzgesteuertem `load()`/`transcribe()` auf derselben virtuellen Uhr (Muster F35). | `DictationCoordinator.kt`, `DictationController.kt`(betroffen nur indirekt), `VoiceKeyboardService.kt`, `FakeWavAudioSource.kt`, Test-Fakes | 0,5–1 Tag | nach Release; blockiert I2/I4 |
| 2 | I2 | **Export v2** (Fable §5.1/§5.2): `start_outcome`, `start_phase_ms`, `start_attempts`, `up_during_start`, `stop_cause`, `autostop_gap_ms`, `max_pause_ms`, `pause_gt1s/2s`, `loud_ratio`, `rms_p50/p90_dbfs`, `owner_lost_at_s`, `final_ms`, `final_chars_delta`, `engine_loads`, `coord_gen` + **Session-Ringpuffer (letzte 5)** + Reihenfolge (Session-Daten an den Anfang). | `DebugInfoFormatter.kt`, `DebugInfoCollector.kt`, `DictationCoordinator.kt`, `VoiceKeyboardService.kt` + Tests | 0,5–1 Tag | ∥ I1 möglich (Kontrakt zuerst festlegen) |
| 3 | I3 | **S0-Werkzeuge:** piper-tts in `.venv-asr` + Stimme `de_DE-thorsten-medium`; `tools/make_korpus.py` (Satzliste × Pausenraster 1,5–4,0 s × Pegel −17/−30/−38 dBFS × 60/120/180 s, Sidecar-JSON-Orakel); `tools/watchdog_twin.py` (F31/F43-Orakel auf Block-RMS); `tools/engine_cost_curve.py` (decode_ms/RSS vs. Pufferlänge, nutzt `reference_decode.py`). | `tools/`, `testdata/korpus/` (gitignore prüfen) | 1 Tag | keine — **sofort parallel zum Release möglich** |
| 4 | I4 | **Robolectric S2** (Abhängigkeit da, V10): Start-Race-Tests (formaler Beweis/Nachweis des F50-Fixes: Fake-Coordinator-Fabrik, Start-Latenz 3/7/16/25 s virtuell, DOWN/UP/CANCEL-Sequenzen, DOWN/UP-Fuzzer 200 Sequenzen), **Fehlerpfad-Enumerator** (alle ~18 Codes → Ergebnis ∈ strings.xml), K1-Absicherung via `status_res`. | `app/src/test/…` neu + `VoiceKeyboardService.kt` (Dispatcher-Injektion aus I1) | 1–1,5 Tage | **nach I1** |
| 5 | I5 | **S3-Langläufe:** `tools/emu_korpus_run.sh` (Vorbedingungs-Asserts: `pactl`-Sink, `first3s_peak_dbfs > −30`), Debug-Export als Datei `files/debug_export.txt` + `adb exec-out run-as … cat`, `tools/export_to_csv.py`; **6 Langläufe** (3 Pausenraster × 2 Pegel × 120 s) + **fresh-install-Kalt/warm-Matrix** (`pm clear`, `drop_caches`, Fable §3.1/§6.7) + RSS pro Lauf. | `tools/`, `reports/korpus_runs.csv` | 0,5 Tag Skripte + 1 Abend Läufe | nach I3 (Korpus) + I2 (Felder); Emulator exklusiv |
| 6 | I6 | **Fallback-Texte trennen** (Fable §6.8, P2 aus §2.3): `mapVisibleError` — „Audio / Erkennung / System gestoppt" statt Sammelfallback „Audio-Start fehlgeschlagen" (Diagnosefresser belegt in V7). | `VoiceKeyboardService.kt`, `strings.xml` + Tests | 0,25 Tag | nach I4 (Enumerator deckt Regression) |
| 7 | I7 | **ONNX Runtime JVM** (`onnxruntime-1.31.1.jar` nach `tools/hostlibs/`, separates Test-SourceSet — Build-Mutex beachten) + `tools/audit_ui_strings.sh` (Hardcoded-Text-Regressionsschutz, ersetzt offline-unmöglichen Lint). | `tools/`, Gradle-Testsetup | 0,5 + 0,1 Tage | ∥ jederzeit; Netz für Paket einmalig nötig |

**Explizit NICHT in Schnitt B (Fable folgend):** Paparazzi, edge-tts, Perfetto/Systrace (§4 „Nicht
empfohlen"). windowed-Streaming weiterhin gesperrt bis E2 (Nutzer) auf Basis von I3+I5.

---

## 4. Reihenfolge & Abhängigkeiten (Schnitt A)

| Schritt | Arbeit | hängt ab von | parallel zu | Fertig-Kriterium |
|---|---|---|---|---|
| 0 | Nutzer-Veto-Fenster E1 (F43-Semantik) | — | — | kein Widerspruch bis A1-Beginn ⇒ Empfehlung gilt |
| 1 | **A1** F49 Hold-Gate + Repro-Tests | 0 | **A2** | `testDebug` ≥489/0, Review P0/P1=0 |
| 2 | **A2** F50 Serialisierung + F50a Feedback + F49-Wiring + F51 Strings | 0 | **A1** | `testDebug` ≥492/0, Review P0/P1=0 |
| 3 | **A3** F52 `status_res` | A2 (2-Zeilen-Naht im Service) | — | `testDebug` ≥495/0, Review ok |
| 4 | Merge + Vollprüfung | A1, A2, A3 | — | Debug+Release komplett grün |
| 5 | Bau v0.1.30 (vc 31) | 4 | — | APKs in `dist/`, F46-Screenshot ok |
| 6 | Emulator-Smoke (5560, exklusiv) | 5 | kein Bau | Smoke-Checkliste §1 „Bau" erfüllt |
| 7 | Auslieferung + Downloadlink + Doku | 6 | — | Link an Nutzer; SESSION_LOG/HANDOFF aktualisiert |

*Schnitt B startet nach Schritt 7; I3 (Korpus-Werkzeuge) darf ausdrücklich schon parallel zu
Schnitt A laufen (kein App-Code, kein Gradle, kein Emulator).*

---

## 5. Abweichungen und Korrekturen gegenüber Fable (Kenntnis des Lesers halber)

1. **F37 ist kleiner als behauptet:** Fable (§1.2) schreibt, der Controller „bekommt keine clock".
   Tatsächlich **besitzt** `DictationController` bereits einen injizierbaren `clock`-Parameter
   (Default `System.nanoTime()`) — es fehlt nur das Durchreichen im Coordinator (V3). Aufwand des
   Kernpunkts: ~1 Zeile + Tests statt „neues API". Die übrigen F37-Teile (threadFactory,
   Service-Dispatcher) bleiben unverändert.
2. **F43 wird nicht als offene Messfrage behandelt, sondern als Modus-Gate geliefert:** Fable
   (§2.1/§6.1) verlagert die „Produktentscheidung Schwelle" hinter die S0/S3-Messungen. Wegen der
   Release-Vorgabe entscheide ich begründet **vor** den Messungen: Hold-Gate (A1/A2), Toggle bleibt
   bei 2,5 s — das ist die einzige Lösung, die Symptom 3 adressiert, **ohne** den dokumentierten
   Nutzer-Wunsch „Pause = Ende" (V12) zu kippen. Messungen (I3/I5) bleiben als Validierung, und
   E1 hält die Schwellen-Frage als Nutzer-Entscheidung offen.
3. **Symptom-1-Fix (F50) ohne vorherigen Robolectric-Beweis:** Fable (§2.2) will die Race erst in S2
   „beweisen, dann patchen". Die Race ist durch Code-Lektüre vollständig belegt (V4), und der Nutzer
   will den Release heute. Wir patchen in 0.1.30 (mit pure-Funktion-Test) und liefern den formalen
   Race-Beweis (Robolectric-Fuzzer) in I4 nach — Restrisiko dokumentiert, Review-Anforderung an A2
   entsprechend erhöht.
4. **Zusatz über Fable hinaus:** UP-Feedback „Loslassen vorgemerkt" (F50a). Fable hält das stille
   UP-Flag für „kein Bug" (§2.2 Punkt 2) — zutreffend für die Mechanik, aber der C2-Befund
   „UP ignoriert“ zeigt, dass die Stille Nutzer verwirrt; ein transienter Hint behebt das ohne
   Design-Änderung an P0-Befund2(a).
5. **E1 geht über Fables Option „A“ hinaus (§6):** Fable sah nur die Schwellen-/Semantik-Frage
   und wollte sie hinter Messungen verschieben; der Nutzer hat sie als **Produktvertrag** entschieden
   (§6.1). Der Plan hebt sie in §6 in ein Technik-Paket mit eigener Obergrenzen- und Langzeit-
   Strategie — inklusive der Konsequenz, dass auch **F31/ERR_SILENT** für Hold gepfpt werden muss
   (A1-Erweiterung), was weder Fable noch der ursprüngliche A1-Entwurf vorsah.

**Als OPTION gekennzeichnet (nicht belegt, nicht in Schnitt A):** H3b/H3c/H3d/H3e (§0), K1 als
Ursache, K4, Kaltstart > 15 s am Gerät, „3-Anläufe"-Kausalitäten (Page-Cache/Arena sind plausible
Modelle, aber die Matrix I5 fehlt noch). Nichts davon wird als Fakt in einen Auftrag geschrieben.

---

## 6. Kontinuierliches Diktat (Hold) — Technik-Paket (E1 bindend)

E1 ist nicht länger eine Option, sondern ein **Produktvertrag des Nutzers** (2026-11-01):
„Solange der Mikrofon-Knopf gehalten wird, spricht der Nutzer durchgehend und bekommt durchgehend
Transkription — **kein Abbruch**.“ Dieser Abschnitt definiert den Vertrag technisch, die Restlücke
in v0.1.30 (Obergrenze) und die Technik-Stufe v0.1.31. **Schnitt A bleibt wie geplant** — einzig
A1 wurde um eine Bedingung erweitert (§6.1, F31-Gate), A2/A3 unverändert.

### 6.1 Produktvertrag (Acceptance-Kriterium)

> **Eine Hold-Session (Halte-Geste auf der Statusleiste, Finger unten) endet ausschließlich über:
> 1. **Loslassen (UP)** → reguläres Finale, Export `stop_cause=up` / `fgs_stopped_by=up`;
> 2. **expliziten Abbruch** (CANCEL-Geste `michold_cancel`, Nutzeraktion im Menü);
> 3. **Systemereignis** (Editor-/App-Wechsel `onStartInput`/`onWindowHidden`, FGS-System-Stopp,
> Rechteverlust, **tote Quelle ERR_STALL** — Stille ≠ Stall-Vertrag V1 bleibt);
> 4. **eine definierte Obergrenze** (§6.2).
>
> Sie endet **NICHT** durch Stille (F43 **und** F31/ERR_SILENT für Hold gepfpt — A1-Erweiterung),
> **nicht** durch Hypotheseninhalt (dafür existiert kein Codepfad, V2) und **nicht** durch
> Decode-Latenz/Speicher allein (kein Zeit-Abbruchpfad; OOM-Ausschluss ist Ziel der Technik-Stufe).

**Vertragserfüllung v0.1.30:** Punkte „Stille“ und „Hypothesen“ erfüllt A1/A2. Die Obergrenze
bleibt in v0.1.30 bei 300 s (V11) — vertraglich gedeckt (Punkt 4), aber die UX-Schwäche des
„5-Minuten-Abschusses mitten im Diktat“ bleibt; Behebung §6.2 in v0.1.31. **Vertrag damit
halbbis voll erfüllt ab v0.1.31.**

**Bekanntes Restrisiko (dokumentiert):** Hold + gebrochenes Mikro (nur Rauschen) + gepfltes ERR_SILENT
= stille 15-min-Session ohne Text. Abfangbar: „Kein Text erkannt“-Toast bei UP mit 0 Zeichen existiert
(`VoiceKeyboardService.kt:732`-Umgebung), `first3s_peak_dbfs`-Export zeigt den Defekt rückwirkend.
Akzeptiert, da E1 den Nutzerwillen priorisiert.

### 6.2 Obergrenze: MAX_DURATION_MS für Hold-Sessions

Heute tötet `MAX_DURATION_MS = 300_000` (V11, `AudioRecordSource.kt:38`) **jede** Session nach 5 min —
mitten im Diktat, über denselben Abbruchkanal wie ein Fehler. Für den E1-Vertrag ist das die
einzige verbleibende harte Grenze.

| | Option A | Option B |
|---|---|---|
| Inhalt | Halt auf **900 000 ms (15 min)** + **transienter Warn-Hinweis ab 13 min** („Obergrenze in 2 min — rechtzeitig loslassen“) | Für Hold **gar keine** Obergrenze, dafür **strengere Watchdogs** (RSS-Schwelle mit sanftem kontrolliertem Ende statt OOM; ERR_STALL bleibt) |
| Pro | Nutzt die existierende `maxDurationMs`-Infrastruktur (bereits Konstruktor-Parameter, `AudioRecordSource.kt:27`); Nutzer behält Kontrolle; deckt realen Diktierbedarf; nach Technik-Stufe revisable | Vertrag „unbegrenzt“ wörtlich |
| Contra | Grenze bleibt eine Grenze | RSS-Schwelle ist **unmessen** (I3/I5 fehlen noch), falsche Schwelle = Abbruch genau dann, wenn der Vertrag ihn verbietet; OOM auf GrapheneOS nimmt die App gleich mit | 

**Empfehlung: Option A** — B ohne Messbasis ist der einzige Weg, den Vertrag *schlechter* zu
verletzen (Abbruch ohne Nutzereinwirkung). A ist ein ~30-Zeilen-Micro-Change und geht als **B2 in
die Technik-Stufe v0.1.31** (nicht mehr in 0.1.30 — Schnitt A bleibt eingefroren; die 300-s-Grenze
ist vertraglich Punkt 4 und im Smoke §6.4 bewusst unterlaufen wir sie mit 180–240 s).

**Welche Tests sich ändern (Option A):**
- **Keine bestehenden brechen:** keine Test-Datei behauptet `MAX_DURATION_MS = 300_000`
  (verifiziert — Konstante taucht in keinem Test auf; `FakeAudioScenarioTest` Fall e testet
  ERR_SILENT auf dem Default-Pfad, unberührt).
- Neu: `holdWarnDue(sessionMs): Boolean` als reine Funktion + Test (13-min-Schwelle, Hysterese-frei);
  Service-Timer analog `startDeadlineJob` (Coroutine) → `postEvent(StatusEvent.Hint(...))` —
  Routing transient (V7-Naht, `StatusRoutingTest` deckt Regression).
- Verdrahtung: Service reicht beim Start der Halte-Geste `maxDurationMs = 900_000` an die Quelle
  (Parameter existiert bereits); Toggle-Sessions können dieselbe Grenze bekommen (praktisch
  irrelevant: F43 stoppt Toggle ohnehin an der ersten 2,5-s-Pause).

### 6.3 Langzeit-Haltbarkeit — zwei Stufen (das eigentliche Technik-Problem)

**Problem:** Der Partial-Pfad dekodiert den **kompletten** Puffer (`DictationController.window()`,
`:462`) — Kosten je Decode wächst linear mit T, Gesamtarbeit quadratisch (C2-These; Fable §6.2
bestätigt sie für Latenz, nicht Abbrüche). Zwei Messpunkte liegen vor, mit großer Spanne:
- Gerät (C2-Export #1): decode_ms_max **583 ms @ 11,9 s** Puffer ⇒ ≈ **49 ms pro Audio-Sekunde**.
- **Nutzer-Messwert: decode_ms_max 4102 ms @ ~8 s** — **im Repo nicht belegbar** (keine Datei nennt
  diese Zahl); vermutlich andere Variante (fp32) / anderes Gerät / Host-Lauf. ⇒ genau deshalb:
  **Messung zuerst.**

Hochrechnung mit 49 ms/s: decode(60 s) ≈ 2,9 s · decode(300 s) ≈ 15 s · decode(900 s) ≈ 44 s.
Bei 4102-ms-Steigung (~512 ms/s) wäre schon decode(30 s) ≈ 15 s. In beiden Fällen gilt:
**ohne Eingriff verletzt die Hypothesen-Latenz ab wenigen Minuten den Vertrag sichtbar**
(Hyps laufen dem Sprecher hinterher), und der PCM-Puffer wächst linear (~1,92 MB/min → 15 min ≈
28,8 MB — unkritisch; kritisch ist die **Rechenzeit**, nicht der Speicher).

**Stufe 1 — v0.1.30 (Schnitt A unverändert):** Hold-Gate + Start-Fix + `status_res`. Der Vertrag
ist damit zur Hälfte erfüllt („Stille bricht nicht mehr ab“). Die Obergrenze hält Sessions auf
≤ 5 min, wo die Kostenkurve nach heutigem Stand *gerade noch* beherrschbar sein dürfte — aber das
ist eine Annahme, kein Beweis. Deshalb läuft **ab sofort, parallel zum Release**:
> **Messung zuerst (I3, kein App-Code):** `tools/engine_cost_curve.py` liefert decode_ms und RSS
> für Puffer 5…**≥ 300 s (Pflicht), Ziel 900 s** und **klärt die 4102-vs-583-Diskrepanz**
> (Variante fp32/fp16, Gerät vs. Host). Entscheidungsregel aus der Kurve: α = decode(T)/T in ms
> pro Audio-Sekunde ⇒ Segmentlänge der Technik-Stufe W = 1 500 ms / α (so dass decode(W) ≤ 1,5 s).

**Stufe 2 — v0.1.31 (Technik-Stufe), datenbasiert aus der Kurve. Drei Kandidaten:**

| | (a) DecodePace skaliert mit Pufferlänge | (b) rollierendes Decode-Fenster mit überlappungsfreier Text-Übernahme | (c) Zwischensegment-Finalisierung ohne Tonlücke |
|---|---|---|---|
| Mechanismus | `DecodePace.intervalMs` weiter öffnen (Klemmen 2000/6000 ms — `AsrThreadConfig.kt:72/73` — anheben/abschaffen; Takt folgt decode_ms) | Decode nur über `[T−W, T]`; Übernahme nur des **neuen** Textes; fester Überlappungsstreifen nur zur Wortgrenzen-Verknüpfung; **Finale bei UP über den GESAMT-Puffer** (28,8 MB @ 15 min — beherrschbar) | Periodisches „Mini-Finale“: Segment [T−W, T] wird wie ein `stop()` finalisiert und **permanent committet**; Decoder startet bei 0; Segmentgrenze **nur bei Stille** (Satz-/Wortgrenze aus der bereits vorhandenen Block-RMS-Klasse) oder Satzzeichen in der Hypothese | 
| §5.1-Konformität | konform (Takt, kein Fenster) | **bricht die Sperre** — braucht die drei dokumentierten Defekt-Fixes (s. u.) + formale Nutzer-Freigabe E2 | **konform**: jedes Segment ist ein `whole_buffer`-Lauf — exakt der in §5.1 empfohlene Ansatz, nur zeitlich segmentiert; **keine neue Fenster-Zustandsmaschine, kein Agreement-Cache** |
| Vertrags-Erfüllung „Hyp-Latenz ≤ 2 s @ 10 min“ | **NEIN**: decode(600 s) ≈ 29–300 s je nach α — Hyps laufen Minuten hinterher; (a) kauft nur Zeit, bis der Vertrag sichtbar bricht | ja, wenn W klein genug | ja: decode(W) ≤ 1,5 s per Konstruktion (W aus Kurve), unabhängig von Gesamtdauer |
| Text-Korrektheit | voll (unveränderte Pipeline) | Risiko an Segmentgrenzen (Worttrennung); Finale heilt alles, aber **erst bei UP** — während der Session sichtbare Nahtfehler möglich | Segmentgrenze in Stille ⇒ geringes Naht-Risiko; jede Segment-Verkettung gegen Golden testbar (I3-Sidecar) |
| Aufwand | ~0,25 Tag (nur Konstanten/Formel + Tests) | 2–3 Tage + E2-Freigabe + Defekt-Fixes; höchstes Regressionrisiko (Doktrin „keine Deadlock-Zustandsmaschine“) | ~1 Tag Kern (Segmenter + Puffer-Reset) + 0,25 Tag Cap/Warnung (B2) |

**§5.1-Defekte und wie (b) ihnen begegnen müsste** (EXAKT aus `KONZEPT.md` §5.1, dort verifiziert):
1. **Pause-Stall:** Fenster war auf `committed_s + 3 s` gedeckelt; bei Pause ≥ 3 s lieferte der VAD
   dauerhaft „keine Sprache“, `committed_s` rückte nie vor → **alles nach der Pause ging verloren**.
   → (b)-Gegenmaßnahme: Fenster position **nur nach Zeit/Bytes** (nie nach Commit-Fortschritt),
   kein VAD als Vorwärtsbedingung. *Anmerkung: Für Hold-Sessions ist der Fall heute harmloser,
   weil der Vertrag Stille zulässt — die Grundkonstruktion bleibt aber fehleranfällig.*
2. **Kopplung Fenster ↔ Commit:** Fenster konnte nicht über `committed_s + n` hinausreichen →
   stehender Commit = stehendes Fenster. → (b): Entkopplung — Decode-Fenster folgt der Aufnahmezeit,
   Commit-Punkt = Fensterende minus Überlappungsstreifen (deterministisch, nicht verhandelbar).
3. **Agreement-Deadlock:** Mit jedem Commit rückte der Commit-Punkt vor ⇒ der Agreement-Cache
   sammelte nie 2 Treffer ⇒ dauerhafter Stillstand. → (b): Agreement-Verfahren **ersatzlos
   streichen**; Textübernahme über deterministische Wortgrenze im Überlappungsstreifen.

**Formulierung für KONZEPT §5.1 („präzisiert, nicht gebrochen“):** Die Empfehlung „whole_buffer
ist robuster“ bleibt unangetastet — (c) **wendet sie an**, statt sie zu brechen. Sollte später (b)
gewählt werden, gilt: **Der Nutzer-Vertrag „kontinuierlich“ (E1) steht über der Sperre — die Freigabe
nach KONZEPT §5.1 ist seine Entscheidung** (formal über E2 mit den I3/I5-Daten einzuholen); die
§5.1-Tabelle ist dann als „präzisiert“ zu dokumentieren: gleiche Defekte, benannte Gegenmaßnahmen
(1–3), Messvergleich gegen Golden neu zu führen.

**Empfehlung: (c) Zwischensegment-Finalisierung** — Begründung: einzige Option, die die
Hypothesen-Latenz-**Akzeptanz** bei 10 min garantiert erfüllt, §5.1-konform ist (keine neue
Deadlock-fähige Zustandsmaschine — exakt die Lehre der Doktrin), und mit existierenden Bausteinen
gebaut wird (Final-Pfad, Block-RMS-Stilleklassifikation aus F43 sind bereits da). (a) als
**Begleitmaßnahme innerhalb der Segmente** (Takt bleibt cost-aware, unverändert). (b) nur falls
(c)-Nahtfehler in den Golden-Tests wider Erwarten zu grob ausfallen — dann mit den Defekt-Fixes
1–3 und nach E2.

**Aufträge v0.1.31 (je ≤4 Dateien, ≤~150 Zeilen):**
- **B1 — Segmenter-Kern:** `DictationCoordinator.kt`, `DictationController.kt`,
  `app/src/test/java/de/voicekeyboard/dictation/SegmentFinalizeTest.kt` (neu),
  `KorpusTaktTest.kt` (Erweiterung: 600-s-Korpus, 2× 3-s-Pausen). Stille-/Satzzeichen-Grenzfunktion
  `segmentBoundary(...)` als reine Funktion (JVM-testbar, Muster `DecodePace`). Segment-Finale über
  den bestehenden Final-Pfad, danach Puffer-/Decode-Stats je Segment zurücksetzen (Achtung:
  `Export`-Zähler `output_chars`/`had_output` sind **Session**-Zähler — nicht mittrealen).
- **B2 — Obergrenze/Warnung (Option A, §6.2):** `VoiceKeyboardService.kt`, `strings.xml`,
  `AudioRecordSource`-Verdrahtung über `audioSourceFactory` (kein Ändern der Quelle selbst),
  `StartGateTest`-Erweiterung bzw. neuer `HoldWarnTest`.
- **B3 — Nachweis:** Golden-Vergleich je Segment + Gesamt gegen I3-Sidecar (JVM mit Stub-Engine,
  Muster KorpusTaktTest); **S3-Emulatorlauf 600 s erst NACH Technik-Stufe** (§6.4).

**Akzeptanz Technik-Stufe (v0.1.31, Hold 10 min = Korpus 600 s mit 2× 3-s-Pausen):**
- `hyp_gap_ms` (Median/p95) **≤ 2 000 ms**, `decode_ms_max ≤ 2 000 ms` je Segment;
- **kein Abbruch**: `stop_cause=up` bzw. `fgs_stopped_by=up`, `last_error` leer, `ctl_failed=0`;
- **ΔRSS < 150 MB** über 10 min (`dumpsys meminfo` TOTAL, Messbasis I5; PCM-Anteil ≈ 19,2 MB —
  alles darüber ist Arena-/Codec-Wachstum und dann adressierbar);
- **Text fortlaufend korrekt**: je Segment 100 % Golden, Verkettung = Golden-Gesamttext;
- Obergrenzen-Lauf 15 min endet kontrolliert mit `maxdur`-Code **nach** vorheriger Warnung (13 min).

### 6.4 Emulator-Smoke-Checkliste (erweitert, v0.1.30 und v0.1.31)

| Version | Prüfling | Erwartung |
|---|---|---|
| **v0.1.30** | **Halte-Diktat 180–240 s** (Korpus/Playback via `paplay`), **2× 3-s-Satzpausen** eingebaut | **KEIN Abbruch**; `output_chars` wächst über beide Pausen hinaus; Text nach Pausen fortlaufend; **Final erst nach UP**; Export: `fgs_stopped_by=up`, `mic_rel=holdup`, `status_res` wechselt recording→idle; `last_error` leer; `ctl_failed=0`. Bewusst **unter 300 s** (Obergrenze v0.1.30, §6.2) | 
| **v0.1.30** | Kurz-Hold mit > 3 s **Stille vor Sprechbeginn** (Finger unten, nicht sprechen) | **kein ERR_SILENT** — Session lebt bis UP; danach „Kein Text erkannt“-Toast (A1-Gate + Vertragsfall §6.1) |
| **v0.1.31** | **10-min-Korpuslauf (600 s)** via `tools/emu_korpus_run.sh` + Akzeptanz §6.3 | erst **NACH** Technik-Stufe — in v0.1.30 liefe er in den 300-s-Deckel und wäre „erwartet rot“ (kein Bug-Befund!) |

Rahmenbedingungen wie §1 Bau Schritt 4: **<device> exklusiv**, nie parallel zum Bau,
Vorbedingungs-Asserts (`pactl`-Sink sichtbar, `first3s_peak_dbfs > −30` nach 3 s), Debug-APK,
Export-Pull als Datei (I5-Muster, sobald vorhanden, sonst 🐞-Zwischenablage).

---

*Ende PLAN V0130. Kein git, keine Code-Änderungen — nur diese Datei.*
