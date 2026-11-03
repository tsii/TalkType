# Debugging-Beratung — Neustrukturierung (Stand v0.1.29-diag, 2026-11-01)

**Rolle:** Debugging-Berater (read-only, nur diese Datei geschrieben). Kein Build, kein git, kein Emulator.
**Grundlage:** Code-Lektüre `VoiceKeyboardService.kt` (Startpfad 407–572, `stopDictationIfRunning` 640–668,
`onDictationStopped` 740–770, `mapVisibleError` 969–1001, `updateStatus` 1040–1112), `DictationCoordinator.kt`
(start 208–297, captureLoop/Watchdogs 359–454, stop 565–612, abort 714–735), `DictationController.kt`
(feedAudio 248–296, stop 320–366, fail 417–422), `StatusBarText.kt`, `OwnershipTracker.kt`,
`AudioRecordSource.kt`, `DebugInfoFormatter.kt` (Feldliste), `FakeWavAudioSource.kt`, `tools/`, `.venv-asr`.

---

## 0. Kernaussage in fünf Sätzen

1. Die drei Geräte-Symptome sind **Zustandsmaschinen-/Zeitsteuerungs-Probleme**, keine ASR-Qualitätsprobleme.
   Sie lassen sich **ohne Gerät** reproduzieren, wenn Zeit, Engine-Latenz und Dispatcher **injizierbar** sind —
   das ist F37, aber F37 muss **breiter** gefasst werden als „Controller-Uhr im Coordinator" (siehe §1.2).
2. Für Symptom 3 (Satzabbruch) ist **F43 (2,5 s Trailing-Silence) die mit Abstand wahrscheinlichste Ursache**;
   die „hypothesen-getriggerte" Variante hat **keinen Codepfad** (kein einziger `stop()`-Aufruf hängt an
   Hypotheseninhalt). „Jedes Teil endete mit Satzzeichen" ist eine *Folge* des Final-Decodes (Parakeet
   punktiert Äußerungsenden), kein Auslöser. Export #3 kann das mit `fgs_stopped_by=autostop` **sofort** belegen.
3. Für Symptom 1 (Start-Hang) ist der **Kaltstart der Engine auf frischer Installation** (Page-Cache leer, 670 MB
   mmap + ORT-Session-Init) in Kombination mit zwei **nicht serialisierten Start-Coroutinen** (`onMicDown`
   zweimal während `startingUp`-Fenster/Deadline) die Hauptthese — reproduzierbar im JVM mit Fake-Engine
   `load()=7…20 s` virtuell.
4. Für Symptom 2 (unübersetzte Meldung) führt **jeder** gelesene UI-Pfad durch `getString(...)` — die Ursache
   liegt daher wahrscheinlich in einem der vier Kandidaten in §3.3 (Debug-Diagnosesuffix, hartkodierte
   Nicht-Ressourcen-Strings, Fremd-Toast, Sondertoken im Editor). Ein **statischer Audit + Robolectric-Fehlerpfad-
   Enumerator + ein neues Export-Feld `status_res`** klärt das endgültig, kein Gerät nötig.
5. Der Emulator ist **nur** für die Schicht „echter AudioRecord + FGS + Pegel + lange Korpora mit echter Engine"
   nötig. Alles andere (Zustandsmaschine, Watchdog-Timing, Fehler-Mapping, Start-Kollisionen) gehört in
   **deterministische JVM-Tests** mit virtueller Zeit — dort sind die Läufe sekundenschnell und CI-fähig.

---

## 1. Debugging-Architektur (Schichten, Reihenfolge, F37)

### 1.1 Zielbild: vier Schichten, jede mit eigener Determinismus-Garantie

| # | Schicht | Was sie beweist | Determinismus-Quelle | Stand heute |
|---|---|---|---|---|
| **S0** | **Host-Analytik (Python, `.venv-asr`)** — Engine-Kostenkurve, Korpus-Erzeugung, Pegel-/Pausen-Statistik einer WAV, „Watchdog-Zwilling" (reine Nachrechnung von F31/F43 auf Block-RMS) | Wie skaliert decode_ms mit Pufferlänge? Wo *würde* F43 bei gegebener WAV feuern? Welche Pausenverteilung hat echtes Diktat? | reine Funktionen auf Dateien | `reference_decode.py`, ORT 1.31.1 im venv vorhanden; Rest fehlt |
| **S1** | **JVM-Zustandsmaschine mit virtueller Zeit** (JUnit, kein Android) — Coordinator + Controller + Fake-Engine mit **einstellbarer Latenz** + Fake-AudioSource aus Korpus-WAV | Watchdog-Reihenfolge, Auto-Stop-Zeitpunkt, Hyp-Takt, Stop/Abort-Races, StopOutcome | **virtuelle Uhr** (F37) + Stub-Latenz auf derselben Uhr | Weg D (KorpusTaktTest) existiert, aber mit Echtzeit-Schlaf → langsam, nicht ganz deterministisch |
| **S2** | **Robolectric Service-Schicht** — `VoiceKeyboardService` mit Fake-Coordinator-Fabrik, `TestDispatcher`, DOWN/UP/CANCEL-Sequenzen, Statuszeilen-Text-Assertions | Start-Hang, Deadline-Race, Kollision zweier Starts, „welcher Text steht in `statusText`", Sticky/Transient | `StandardTestDispatcher` + Robolectric-Looper | Robolectric 4.17 ist **schon** in `testImplementation`, aber ungenutzt für den Service |
| **S3** | **Emulator-Korpuslauf mit echter Engine** (x86_64-ONNX läuft seit F41b) — virtuelles Mikro (`emulator_audio_up.sh`) + Debug-APK + automatisierter 🐞-Pull | echter AudioRecord/FGS/Routing, Pegel in dBFS, **lange Diktate 60–180 s**, RSS/OOM-Verhalten, Final-Decode-Dauer | Skript mit **Vorbedingungs-Asserts** (PulseAudio-Sink sichtbar — der F41-Pitfall darf nie wieder stumm passieren) | Kurzläufe ≤ 8 s gemacht; lange nie |
| **S4** | **Gerät (Nutzer, 1 Export/Session)** | Bestätigung, nicht Exploration | Export muss **selbsterklärend** sein (§5) | 42 Zeilen, Einzel-Session |

**Reihenfolge der Härtung:** S1 → S2 → S0 (parallel möglich, kein Code-Eingriff) → S3 → S4-Protokoll.
Begründung: S1/S2 brauchen Code-Refactoring (F37 breit), bringen aber die *Reproduzierbarkeit* der Symptome 1+2
und der Kausalkette von Symptom 3; S0 ist sofort machbar und liefert Zahlen für die C2-Entscheidung; S3 bestätigt
dann mit echter Engine; S4 bekommt die neuen Felder.

### 1.2 F37 muss größer gefasst werden („Clock-Injektion" reicht nicht)

Befund aus dem Code:
- `DictationCoordinator` **hat** bereits `nowMs: () -> Long` injizierbar (Zeile 59) — Watchdogs laufen darauf.
- Aber `controller = DictationController(engine, sink, tracker, decodePacing = true)` (Zeile 171) bekommt
  **keine** `clock` → Controller nutzt `System.nanoTime()`: `hyp_gap_ms`, `decode_ms_*` und — wichtiger — der
  **DecodePace-Takt** (`paceAllowsDecode()` auf `lastDecodeMs`) laufen auf Echtzeit, während Watchdogs auf
  virtueller Zeit laufen. Genau das ist das F35-„Uhr-Race" im 60-s-Fall.
- Der **Service** hat drei weitere harte Uhren/Dispatcher: `serviceScope = Dispatchers.Default`,
  `withContext(Dispatchers.Main)`, `SystemClock.elapsedRealtime()` (Transient-Klärheit), `delay(START_DEADLINE_MS)`.
- Der Coordinator startet `captureThread` als **echten Thread** mit `src.read(buf, 50 ms)` → in S1 muss die
  Fake-Quelle *virtuell* takten (FakeWavAudioSource kann `nowMs` schon, gut), aber der Thread selbst läuft real.

**F37 (empfohlener Umfang, ~0,5–1 Tag Code, kein Verhalten in Produktion geändert):**
1. `DictationCoordinator(…, controllerClock: () -> Long = { nowMs() * 1_000_000 })` durchreichen.
2. `DictationCoordinator`: `captureLoop` als `Runnable` mit injizierbarem `threadFactory` (Default: `Thread`),
   damit S1 die Loop **synchron** auf dem Testthread treiben kann (ein Tick = ein `read`).
3. `VoiceKeyboardService`: `ioDispatcher`/`mainDispatcher`/`elapsedClock` als `internal var` (Default wie heute),
   `START_DEADLINE_MS`/`FGS_READY_TIMEOUT_MS` als `internal var`.
4. `AsrEngine`-Fake mit **latenzgesteuertem `load()` und `transcribe()`** auf der *gleichen* virtuellen Uhr
   (Stub erhöht die Uhr selbst — Muster aus F35 übernehmen).

Was das freischaltet: alle Tests in §2 laufen in Millisekunden statt Minuten, Flakes verschwinden, und die
beiden Start-Races (§2.2) werden erstmals überhaupt formulierbar.

---

## 2. Reproduktionsplan für die drei Symptome

### 2.1 Symptom 3 — „längere Diktate brechen am Satzende ab"

**Hypothesen-Rangfolge (mit Falsifikationskriterium aus heutigen Export-Feldern):**

| Rang | Hypothese | Mechanismus | Export #3 widerlegt, wenn … |
|---|---|---|---|
| **H3a (hoch)** | F43 feuert bei natürlicher Satzpause ≥ 2,5 s | `everLoud && capStallMs<2500 && now-lastLoudAt>2500 → stop()`; Final-Decode punktiert → „endet mit Satzzeichen" | `fgs_stopped_by ≠ autostop` **und** `mic_rel` zeigt `holdup:<ms>` mit ms ≈ audio_s |
| **H3b (mittel)** | Pegel am Gerät knapp an −40 dBFS: leise Silben zählen als „still" → Auto-Stop schon bei kürzeren Pausen oder mitten im Satz | `SILENCE_RMS = 0.01` ist Block-RMS über 128 ms (2048 Samples) | `peak_dbfs ≥ −25` **und** `loud_s/audio_s ≥ 0,6` → Pegel ist nicht das Problem |
| H3c (niedrig) | Besitzverlust (`OwnershipTracker.lost`) → Hypothesen werden *stumm* nicht mehr geschrieben; Session läuft weiter, Nutzer sieht „Abbruch" des Textes | `isOwned` false → `writeHypothesis` return false, kein Fehler, kein Stop | `output_chars` wächst bis Ende **oder** `had_output` + Status „Diktat" lief weiter → kein Abbruch, sondern Schreibstopp (dann **eigenes** Problem!) |
| H3d (niedrig) | Editor-Sync-Timeout bei langem Decode (Main-Thread blockiert) | `fail(EDITOR_SYNC_TIMEOUT)` → „App reagiert gerade nicht" | `ctl_failed=0`, `last_error` leer |
| H3e (sehr niedrig) | OOM im Encoder bei großem T | ERR_OOM → „Zu wenig Speicher" | `last_error ≠ oom` |

**Wichtig:** H3a und H3b unterscheiden sich im Export heute **nicht sicher** — beide enden `autostop`. Dafür §5
(`autostop_gap_ms`, `max_pause_ms`, `pause_gt2s`, `loud_ratio`, `rms_p50_dbfs`).

**Repro-Plan (S0 → S1 → S3):**

1. **S0 — Korpus bauen** (`tools/make_korpus.py`, ~2–3 h):
   - TTS: **piper-tts** (pip, Stimme `de_DE-thorsten-medium`, offline, natürlich genug für Parakeet) als
     Hauptquelle; `espeak-ng` (bereits installiert) als Fallback/robotische Variante.
   - Baukasten: Satzliste (20–40 deutsche Sätze, 5–15 Wörter) × Pausenraster **1,5 / 2,0 / 2,4 / 2,6 / 3,0 / 4,0 s**
     × Pegel **−17 / −30 / −38 dBFS** (ffmpeg `volume=`) × Dauer **60 / 120 / 180 s**.
   - Leises Rauschen (−60 dBFS) in die Pausen mischen (Gerätemikro ist nie digital still; sonst testet man
     `capStallMs`-Pfade statt F43).
   - Jede WAV bekommt ein **Sidecar-JSON**: Satzgrenzen (ms), Pausenlängen, erwarteter Golden-Text (per
     `reference_decode.py` auf den Einzel-Sätzen). → Ground Truth für „wo *müsste* F43 feuern".
   - 16 kHz mono s16le, Ausgabe nach `testdata/korpus/` (gitignore prüfen — Größe).
2. **S0 — Watchdog-Zwilling** (`tools/watchdog_twin.py`, ~1 h): rechnet pro WAV Block-RMS (2048 Samples) wie
   `captureLoop`, simuliert F31/F43 und gibt aus: `autostop_at_ms`, `max_pause_ms`, `loud_ratio`. Kein
   Kotlin-Ersatz, sondern **Orakel** für die Erwartungswerte der S1/S3-Läufe (und sofortige Antwort: „bricht
   Korpus X bei 2,5 s ab?" — ja, per Konstruktion bei jeder Pause ≥ 2,6 s).
3. **S1 — KorpusTaktTest erweitern** (nach F37): Korpus-WAV über FakeWavAudioSource mit virtueller Zeit, Stub-Engine
   mit Latenzmodell `decode_ms = a + b·buffer_s` (a, b aus S0-Messung), Assertions: Auto-Stop-Zeitpunkt =
   Orakel ± 1 Block; `hyp_count` monoton; `StopOutcome.FinalRan`; Besitz nie verloren.
4. **S3 — Emulator-Langläufe mit echter Engine** (`tools/emu_korpus_run.sh`, ~3–4 h inkl. Pull):
   - **Vorbedingungs-Assert** (Lehre aus F41): `pactl info | grep -q "Default Sink: vkmic"` und
     `XDG_RUNTIME_DIR` gesetzt, sonst Abbruch mit klarer Meldung. Zusätzlich Pegelkontrolle: nach 3 s Wiedergabe
     `first3s_peak_dbfs` im Export > −30, sonst Lauf ungültig.
   - Ablauf: IME fokussieren (DebugInputActivity), Mic-Tap (adb tap), `paplay korpus.wav`, warten bis
     `fgs_stopped_by` gesetzt, Export ziehen.
   - **Export-Pull ohne Clipboard:** Debug-Build schreibt den 🐞-Text zusätzlich nach `files/debug_export.txt`
     (identischer Inhalt → Regel 9 unverändert); Pull mit `adb exec-out run-as de.voicekeyboard cat …`.
     Clipboard-Lesen ist ab API 29 für Hintergrund-Shell unzuverlässig.
   - Matrix: 3 Pausenraster × 2 Pegel × 120 s = 6 Läufe à ~3 min → 1 Abend. Ergebnis als CSV
     (`reports/korpus_runs.csv`) mit Orakel-Spalte daneben.
   - Zusatzmessung pro Lauf: `adb shell dumpsys meminfo de.voicekeyboard | grep TOTAL` am Ende → RSS vs.
     audio_s (H3e, C2).

**Erwartung:** Alle Läufe mit Pause ≥ 2,6 s stoppen bei der ersten solchen Pause — damit ist H3a bewiesen und
die Diskussion verschiebt sich von „Bug suchen" zu **„Produktentscheidung F43-Schwelle"** (Daten: Pausen-
verteilung echter Diktate; 2,5 s ist für Nachdenk-Pausen beim Diktieren sehr knapp; typische Werte in
Diktier-Apps 4–6 s oder adaptiv „Stille-Länge wächst mit Sessiondauer"). Das ist nicht meine Entscheidung,
aber die Messung macht sie entscheidbar.

### 2.2 Symptom 1 — Start-Hang („starte Aufnahme", Loslassen wirkungslos, 2. Druck → „bereit")

Codepfad: `onMicDown` → `postEvent(Starting)` → `startingUp=true` → Coroutine: `awaitFgsReady(4 s)` →
`coordinator.start()` (enthält `engine.load()` **im Startpfad**, Kaltstart ≈ 7 s am Gerät laut `start_ms`;
auf **frischer Installation** ohne Page-Cache plausibel deutlich länger) → Main: `startingUp=false`, UP-Nachreichung
via `cancelStartRequested` → `KEEP_RUNNING`.

**Beobachtete Lücken (aus dem Code, nicht aus dem Gerät):**

1. **Zwei Start-Coroutinen sind nicht serialisiert.** Nach `START_DEADLINE_MS=15 s` wird `startingUp=false`
   gesetzt, der *erste* Start läuft aber weiter (kein Cancel von `engine.load`). Ein zweiter DOWN startet eine
   zweite Coroutine; die setzt `startAbandoned=false`. Wenn jetzt der *erste* Start fertig wird, sieht er
   `abandoned=false`, `started=true` → führt die *alte* Session als gültig fort; der *zweite*
   `coordinator.start()` scheitert an `active.compareAndSet` → `started=false` → Fehlertext. Je nach Reihenfolge
   erhält man (a) „bereit" mit laufendem Zombie-Mikro, (b) Fehler, (c) UI/Status inkonsistent. Außerdem
   überschreibt der zweite Lauf `lastStartMs` (`finally`) → **`start_ms` im Export kann der Kollisionswert sein,
   nicht der Kaltstart** — Vorsicht beim Deuten von Export #3.
2. **UP während STARTING ist ein stilles Flag** — die Statuszeile bleibt „Starte Aufnahme…" (transient), es
   gibt keine Rückmeldung „Loslassen vorgemerkt". Das erklärt den Nutzer-Eindruck „Loslassen ändert nichts",
   ist aber per Design (P0-Befund2(a)) und muss kein Bug sein. Ohne Export-Feld ist nicht trennbar, ob die
   Session dann **lief** (grün) oder nicht.
3. **FGS-Ready 4 s + engine.load ≥ 7 s + Deadline 15 s** — auf GrapheneOS mit frischer App (erste FGS-Nutzung
   des Typs `microphone`, Benachrichtigungskanal neu, Modell nicht im Page-Cache) ist 15 s realistisch
   überschreitbar → „Start dauert zu lange" (sticky). Das wäre *übersetzt*; aber die Folge-Kollision aus Punkt 1
   ist dann das eigentliche Problem.

**Repro (S2, Robolectric, nach F37-Dispatcher-Injektion, ~1 Tag):**
- Fake-Coordinator-Fabrik mit `start()`-Latenz L ∈ {3 s, 7 s, 16 s, 25 s} virtuell.
- Sequenzen: `DOWN; +2s UP` / `DOWN; +2s UP; +4s DOWN; +5s UP` / `DOWN; +16s DOWN; +17s UP` /
  `DOWN; +1s CANCEL`.
- Assertions auf **`statusText.text` (Ressourcen-ID-Abgleich)**, `micRecording`, `fgsRunning`, Anzahl aktiver
  Sessions (`coordinator.isActive`), `fgsStoppedBy`.
- Erwartete rote Fälle: Kollision aus Punkt 1 (Zombie oder Doppelstart). Danach ist der Fix (Start-Mutex /
  Cancel der verwaisten Coroutine / `abort` der veralteten Session **vor** dem zweiten `start`) ein 20-Zeilen-Patch
  — aber erst beweisen, dann patchen.

**Kaltstart als Zeitsteuerung nachbauen (S3, 1 h):** Emulator `adb root && adb shell "sync; echo 3 >
/proc/sys/vm/drop_caches"` vor dem ersten Mic-Tap → `start_ms` kalt vs. warm (zweiter Tap nach IME-Neustart).
Erwartung: kalt ≫ warm; wenn kalt > 15 s am Emulator (langsamer als Gerät nicht ausgeschlossen), ist die
Deadline-Kollision **direkt** am Emulator reproduzierbar.

### 2.3 Symptom 2 — unübersetzte Fehlermeldung

**Befund des Code-Audits:** Alle Pfade, die `statusText` setzen, laufen durch `StatusBarText.select` mit
Eingaben aus `getString(...)` bzw. `mapVisibleError(...)` (immer `getString`). Es gibt **keinen** Pfad, der
`lastError`/`lastErrorCode`/Enum roh in die Statuszeile schreibt. Das Mapping ist aber *semantisch* grob:
`asr_final_error`, `asr_partial_error`, `audio_error:stall`, `audio_error:systemStop`, `audio_error:init`,
`audio_error:maxDuration`, `consumer:*`, `startFailed` → alle fallen auf **„Audio-Start fehlgeschlagen"**
(Fallback-Zweig). Falsch, aber übersetzt.

**Vier Kandidaten für das, was der Nutzer sah (in Reihenfolge der Plausibilität):**

| # | Kandidat | Wo | Prüfung ohne Gerät |
|---|---|---|---|
| K1 | **Debug-Diagnosesuffix** in der Diktat-Zeile: `Diktat… (intern) · Diagnose: erst=-s RTF=- T=4 Peak=…` (nur im **-diag-debug**-APK, das der Nutzer installiert hat; `updateStatus` 1070–1081) — abgeschnitten in einer schmalen Statuszeile sieht das wie „roher Code" aus | `DictationCoordinator.diagnosticsLine` | Nutzer fragen: „stand da `RTF=`/`erst=`?" — oder `status_res`-Feld (§5) |
| K2 | **Hartkodierte Nicht-Ressourcen-Strings** („Verarbeite…", „Bereit", „Diktat… (…)", „Diagnose kopiert — Teilen-Dialog geöffnet", Toasts 1212/1216, FGS-Notification „Diktat läuft") — kein Rohcode, aber ein Lint-Audit `HardcodedText`/`SetTextI18n` findet sie in 10 Minuten | Service, CaptureForegroundService | `./gradlew lint` ist offline evtl. nicht möglich → `rg '"[A-ZÄÖÜ][^"]*"' --type kotlin app/src/main` als Ersatz |
| K3 | **Sondertoken im Editor-Text** (z. B. `<unk>`, `▁`) — der Nutzer könnte eine Fehlausgabe *im Textfeld* als „Fehlercode" gelesen haben | `Vocabulary.isSpecial`, `TdtGreedy` | Golden-Test auf Korpus mit Rauschen/Pausen in S3; `output_chars` vs. Golden-Länge |
| K4 | **System-/GrapheneOS-Toast** (Mikrofon-Zugriffsanzeige, Sensors-Permission, FGS-Hinweis) — nicht unsere UI | — | ausschließbar nur per Rückfrage |

**Methodik (S2, 0,5 Tag):**
1. **Fehlercode-Enumerator-Test:** alle Codes aus dem Code sammeln (`rg 'const val ERR_|fail\(|lastError = "'`)
   → Liste von ~18 Strings → `mapVisibleError(code)` für jeden → Assertion: Ergebnis ∈ Werte aus
   `strings.xml` (Robolectric liest Ressourcen). Deckt K1-ähnliche Regressionen künftig automatisch ab und
   dokumentiert gleichzeitig die groben Fallback-Zuordnungen (Review-Futter).
2. **`mapVisibleError` in eine pure Funktion `errorTextRes(raw): Int` heben** (Muster `mapErrorTextRes` existiert
   bereits für `editor_sync_timeout`) — dann JVM-testbar ohne Robolectric und der Service kann die **Ressourcen-ID
   des zuletzt gezeigten Textes** merken → Export-Feld `status_res` (§5). Damit sagt der nächste Export selbst,
   welcher Text stand (`dictation_error_audio_start`) oder dass es **kein** Ressourcen-Text war (`raw`).

---

## 3. Methodik gegen das „3-Anläufe-Muster"

Das Muster ist typisch für **Zustand, der die Session überlebt**: Page-Cache, `engineLoaded`, verwaiste
Start-Coroutine, FGS-Zustand, `OwnershipTracker`-Baseline, ORT-Arena-Wachstum. Vorgehen:

1. **Kalt/warm sauber definieren und messen** (S3, 1–2 h): Matrix {Prozess frisch (`am force-stop`), Page-Cache
   leer (`drop_caches`), IME-Fenster neu (`onCreate` der InputView), Coordinator vorhanden} × {1., 2., 3. Session}.
   Messgrößen: `start_ms`, `first_hyp_ms`, `decode_ms_max`, Ergebnis. Erwartung: Anlauf 1 = Page-Cache +
   ORT-Session-Init, Anlauf 2 = Arena-Wachstum/erste Inferenz (ORT allokiert beim ersten `run` groß), Anlauf 3 =
   stationär. Wenn das Muster **auch im Emulator** erscheint, ist es nicht gerätespezifisch — ein großer Gewinn.
2. **Engine-Preload-Experiment** (Debug-Flag, ~2 h Code): `engine.load()` + **eine Warmlauf-Inferenz auf 2 s
   Stille** beim `onStartInputView` im Hintergrund (nicht im Mic-Pfad). Dann Matrix wiederholen. Wenn Anlauf 1
   verschwindet → Produktvorschlag „Preload + Warmup", und Symptom 1 ist entkoppelt von der Touch-Geste.
   Speicherkosten (~860 MB nativ) sind das Gegenargument — mit `dumpsys meminfo` quantifizieren.
3. **Session-Historie im Export** (§5): ohne Ringpuffer sieht man am Gerät immer nur Anlauf 3 (der funktioniert
   hat) — die beiden Fehlversuche sind überschrieben. Das ist der Hauptgrund, warum das Muster bisher nicht
   greifbar war.
4. **Start-Serialisierung als Invariante testen** (S2): „Zu jedem Zeitpunkt läuft höchstens eine Start-Coroutine;
   eine abgelaufene wird abgebrochen oder ihr Ergebnis verworfen." Property-artig über zufällige DOWN/UP-Folgen
   mit virtueller Zeit (einfacher Fuzzer, 200 Sequenzen, Sekunden Laufzeit).

---

## 4. Neue Werkzeuge / Infrastruktur (priorisiert, mit Aufwand)

| Prio | Werkzeug | Wofür | Aufwand | Installation |
|---|---|---|---|---|
| **1** | **piper-tts** (pip in `.venv-asr`) + Stimme `de_DE-thorsten-medium` + `tools/make_korpus.py` | Synthetische 60–180-s-Korpora mit kontrollierten Pausen/Pegeln (S0/S1/S3); Sidecar-JSON als Orakel | 2–3 h | `pip install piper-tts`; Modell-Download einmalig (~60 MB). `espeak-ng` ist schon da (Fallback) |
| **1** | **`tools/watchdog_twin.py`** (numpy/soundfile vorhanden) | Vorhersage „wo feuert F31/F43 bei dieser WAV" + Pausenstatistik; Orakel für Tests | 1 h | nichts |
| **1** | **`tools/engine_cost_curve.py`** (nutzt `reference_decode.py`-Sessions) | decode_ms und RSS vs. Pufferlänge 5…180 s auf dem Host → C2-These quantifizieren (Form der Kurve ist übertragbar, absolute Werte nicht) | 1–2 h | nichts (ORT 1.31.1 im venv) |
| **2** | **Robolectric für `VoiceKeyboardService`** (Abhängigkeit ist bereits da) + `kotlinx-coroutines-test` (prüfen, ob offline im Gradle-Cache; sonst einmalig mit Netz holen) | S2: Start-Races, Statuszeilen-Text, Fehlerpfad-Enumerator | 1 Tag inkl. F37-Dispatcher-Injektion | ggf. `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<Version wie coroutines-core>")` |
| **2** | **`tools/emu_korpus_run.sh`** + Debug-Export-Datei (`files/debug_export.txt`) + `tools/export_to_csv.py` | S3-Langläufe reproduzierbar, F41-Pitfall per Assert ausgeschlossen, Ergebnisse tabellarisch | 3–4 h | nichts neues; `adb`, `paplay`, `pactl` vorhanden |
| **3** | **ONNX Runtime JVM (linux-x64) `onnxruntime-1.31.1.jar`** manuell nach `tools/hostlibs/` + `flatDir` in einem *separaten* Gradle-Test-SourceSet | Kotlin-Pipeline (Controller/Coordinator) **mit echter Engine im JVM** ohne Emulator — schnellster „Weg B" überhaupt | 0,5 Tag (Gradle-Änderung, Build-Mutex beachten) | `curl` von Maven Central (Netz für Pakete erlaubt) |
| 3 | `rg`-basierter **Hardcoded-String-Audit** als Skript (`tools/audit_ui_strings.sh`) | K2 aus §2.3, verhindert Rückfälle | 0,5 h | nichts |
| — | **Nicht empfohlen:** Paparazzi (Screenshot-Tests) — löst keines der drei Symptome; edge-tts — Online-Dienst, widerspricht Offline-Doktrin; Perfetto/Systrace am Emulator — erst sinnvoll, wenn S1/S2 eine konkrete Thread-Frage offenlassen | | | |

Alle Skripte: Pfad-/Audio-/Textinhalte nur in `testdata/`/`reports/` (nicht in Logs) — Regel 9 betrifft Logs
und Exporte, nicht Testartefakte, aber Golden-Texte gehören nicht in Commit-Messages/Docs.

---

## 5. Änderungen am 🐞-Export (ein Export → eindeutige Schicht)

Prinzip: **jedes Feld ist Zahl oder geschlossener Code**, nie Inhalt — Regel 9 bleibt vollständig gewahrt. Ziel ist,
dass ein einzelner Export die Frage „Audio-Schicht / Watchdog / Engine / Editor / UI-Zustandsmaschine?" ohne
Rückfrage beantwortet.

### 5.1 Neue Felder der aktuellen Session (≈ +12 Zeilen)

| Feld | Inhalt | Beantwortet |
|---|---|---|
| `start_outcome` | `ok` / `fgs_timeout` / `abandoned` / `collision` / `failed=<code>` | Symptom 1: welcher Startpfad |
| `start_phase_ms` | `fgs=<ms> load=<ms> audio=<ms>` (drei Zahlen) | Kaltstart vs. FGS vs. AudioRecord |
| `start_attempts` | Anzahl `onMicDown` seit IME-Fenster-Erzeugung | Mehrfach-DOWN im Startfenster |
| `up_during_start` | 0/1 (+ms) | „Loslassen vorgemerkt?" |
| `stop_cause` | `up` / `holdup` / `autostop` / `hidden` / `editor` / `abort=<grund>` / `maxdur` | ersetzt Deutung aus `mic_rel`+`fgs_stopped_by` |
| `autostop_gap_ms` | gemessene Stillelücke beim Auto-Stop (0 = kein Auto-Stop) | H3a direkt |
| `max_pause_ms`, `pause_gt1s`, `pause_gt2s` | längste Lücke zwischen lauten Blöcken; Anzahl Pausen > 1 s / > 2 s | Pausenprofil des Diktats |
| `loud_ratio` | `loud_s/audio_s` | H3b (Pegel knapp an Schwelle) |
| `rms_p50_dbfs`, `rms_p90_dbfs` | Perzentile des Block-RMS **lauter** Blöcke | H3b, ohne Kurve/Inhalt |
| `owner_lost_at_s` | Sekunde, ab der `isOwned` false lieferte (−1 = nie) | H3c stummer Schreibstopp |
| `final_ms`, `final_chars_delta` | Dauer Final-Decode; Zeichenänderung durch Finale (Zahl) | Finale-Qualität ohne Text |
| `status_res` | **Ressourcenname** des zuletzt gezeigten Statustextes (`dictation_error_unknown`) oder `raw`/`diag` | Symptom 2 endgültig |
| `engine_loads`, `coord_gen` | Anzahl `engine.load()` und Coordinator-Instanzen seit Prozessstart | Kalt/warm-Zuordnung |

### 5.2 Session-Ringpuffer (letzte 5 Sessions, je 1 Zeile)

Format pro Zeile (Codes/Zahlen, ~90 Zeichen):
`s-2: start=ok/7120ms stop=autostop audio=38.2 loud=31.0 maxpause=2710 hyp=14 dmax=2100 err=- res=idle out=1/412`

Warum: Das „3-Anläufe-Muster" ist am Gerät heute **unsichtbar**, weil jeder Start die Zähler nullt. Fünf Zeilen
reichen für Anlauf 1–3 plus Kontext; Inhalt bleibt Regel-9-konform (keine Texte, keine Pfade, keine Zeiten als
Wallclock — nur relative ms). Speicher: ein `ArrayDeque<String>(5)` im Service.

### 5.3 Kleinigkeiten, die Deutungsfehler verhindern

- `start_ms` nur vom **ersten** `start()` einer Session schreiben (Kollisionslauf darf nicht überschreiben).
- `threads`/`api_level`/`model_*` ans Ende (unverändert, aber Session-Daten zuerst — der Nutzer kopiert manchmal nur
  den Anfang).
- Debug-Build: Export zusätzlich als Datei schreiben (S3-Pull), Kopf mit `build=debug` steht schon drin.

---

## 6. Warnungen, Grenzen, Sackgassen

1. **F43-Satzpausen-These ist richtig, aber nur zur Hälfte formuliert.** Richtig: Auto-Stop bei 2,5 s feuert bei
   natürlichen Diktierpausen. Falsch/unnötig: eine „hypothesen-getriggerte" Komponente — es existiert kein
   Codepfad. **Sackgasse:** Zeit in Finalize-/Hypothesen-Interaktion investieren, bevor Export #3
   `fgs_stopped_by=autostop` ausgeschlossen hat. Die eigentliche Arbeit ist eine **Produktentscheidung**
   (Schwelle, adaptiv, oder Auto-Stop nur in Toggle-Modus, nicht bei gehaltener Taste — Hold-Geste heißt
   bereits „ich bin noch nicht fertig").
2. **Quadratische-Decode-These (C2) erklärt Latenz, nicht Abbrüche.** Gesamtaufwand pro Session ist Σ decode(T)
   ≈ O(N²) — korrekt — aber DecodePace drosselt, und kein Pfad bricht wegen langer Decodes ab (außer indirekt
   Editor-Sync-Timeout → wäre `ctl_failed=1`, `editor_sync_timeout`). Erst **messen** (S0-Kostenkurve, S3-RSS),
   dann über windowed-Streaming (KONZEPT §5.1) entscheiden. Vorher ist jede Diskussion darüber Spekulation —
   und sie blockiert die Freigabe-Frage unnötig.
3. **Emulator-Zeiten sind nicht Gerätezeiten.** x86_64-int8-Kernels (AVX2/VNNI auf dem Host-CPU) vs. arm64-NEON
   skalieren anders; `decode_ms` absolute Werte nicht übertragen, nur **Verhältnisse** (kalt/warm, 30 s/120 s).
   Deadlines (15 s, 4 s, 2,5 s) am Emulator ggf. **künstlich verkürzen** (injizierbar, F37) statt echte Sekunden
   zu warten.
4. **Der Emulator kann Symptom 1/2 nicht „einfach zeigen"** — ohne Gerätezustand (frische Installation,
   GrapheneOS-FGS-Politik) fehlen die Auslöser. Deshalb S1/S2 zuerst: Race-Bedingungen werden dort per
   Konstruktion erzwungen, nicht erhofft.
5. **Regel 9 wird zu eng gelesen.** Perzentile, Histogramm-Zähler, Pausenlängen, Ressourcennamen,
   Zustandsübergänge sind Metadaten ohne Inhaltsrückschluss. Was tabu bleibt: Hypothesentext, Samples/Kurven,
   `e.message`, Pfade, Ziel-App. Ein 5-Zeilen-Ringpuffer verletzt nichts davon.
6. **Weg B über Emulator ist der teure Weg zum Ziel „echte Engine im Test".** Der billige Weg ist die
   JVM-ONNX-Runtime (Prio 3 in §4): gleiche Kotlin-Pipeline, gleiche Modelle, Sekunden statt Minuten pro
   Lauf, keine PulseAudio-Kette. Emulator dann nur für AudioRecord/FGS/Pegel.
7. **„Fresh install" ist ein Zustand, den der Emulator nachstellen kann** (`pm clear` + `drop_caches` +
   Modell-Kopie + erster Start). Dieser eine Lauf fehlt bisher und ist billig (30 min).
8. **Statusmapping-Fallback „Audio-Start fehlgeschlagen" für ASR-/Consumer-/Stall-Fehler** ist ein
   Diagnosefresser: der Nutzer meldet „Audio-Start", wir suchen im Audio-Stack, Ursache war `asr_final_error`.
   Mindestens drei Texte trennen (Audio / Erkennung / System gestoppt) — nicht Teil des Debuggings, aber direkte
   Folge daraus.
9. **Nicht wiederholen:** Watchdog-Logik an der Statuszeile ablesen. `status_shown` ist der Nebeneffekt; die
   Wahrheit sind `stop_cause`/`start_outcome` (§5). Und: nie wieder einen Emulator-Pegelbefund ohne
   Vorbedingungs-Assert der Audio-Kette akzeptieren.

---

## 7. Vorgeschlagene Reihenfolge (eine Woche, parallelisierbar)

| Tag | Arbeit | Schicht | Ergebnis |
|---|---|---|---|
| 1 | piper + `make_korpus.py` + `watchdog_twin.py` + `engine_cost_curve.py` | S0 | 3 Korpus-Sätze (60/120/180 s), Orakel-JSONs, Kostenkurve decode_ms/RSS vs. T |
| 1–2 | F37 breit (Controller-Clock, Thread-Factory, Service-Dispatcher/Deadlines injizierbar) | Code | 483 Tests weiter grün, keine Verhaltensänderung |
| 2–3 | KorpusTaktTest auf virtuelle Zeit + Korpus; Fehlercode-Enumerator; `errorTextRes` pure | S1/S2 | Auto-Stop-Zeitpunkte = Orakel; Mapping-Lücken dokumentiert |
| 3–4 | Robolectric Service-Tests: Start-Races, DOWN/UP-Fuzzer | S2 | Symptom-1-Race rot → Fix → grün |
| 4 | Export v2 (§5) + Debug-Datei-Dump | Code | nächster Nutzer-Export ist selbsterklärend |
| 5 | `emu_korpus_run.sh`, 6 Langläufe, fresh-install-Lauf, kalt/warm-Matrix | S3 | CSV mit Auto-Stop-Zeiten, RSS, start_ms kalt/warm |
| danach | Produktentscheidungen: F43-Schwelle/Hold-Semantik, Preload, windowed Streaming | — | datenbasiert |

**Sofort und ohne Code (heute):** Export #3 des Nutzers auf `fgs_stopped_by` und `mic_rel` lesen. Steht dort
`autostop`, ist Symptom 3 erklärt, und die Woche beginnt bei S0 mit der Pausenstatistik statt mit einer Bugsuche.
