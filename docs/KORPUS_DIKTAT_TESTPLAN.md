# Korpus-Diktat-Testplan — echten (gemeinfreien) Audio-Input im Emulator/Phone

**Ziel:** entscheiden, ob der Diktat-Abbruch/Korpus-Fall **jetzt** sinnvoll im
Simulator/Emulator (<device>, headless) testbar ist — inkl. Einspeisen von echtem
Audio aus einem Korpus. Nur Doku/Plan, kein Code/Build/git. REGEL 9: keine
Audio-/Textinhalte in Logs; Korpus = vorhandene Projekt-Test-Assets (bereits in M4d
genutzt) oder synthetisch.

## 0) Ist-Lage (belegt, mit Pfaden)

- **AVD/Emulator:** `vk-api36` = **x86_64** / 4 Cores / 1536 MB / API 36
  `google_apis` (`<workdir>/.android/avd/vk-api36.avd/config.ini`); Container ohne
  Display ⇒ nur Headless-Betrieb; IME wird dort nicht angezeigt (`mInputShown=false`),
  `adb`/`connectedDebugAndroidTest` funktionieren. Emulator aktuell nicht gestartet.
- **Engine:** `onnxruntime-android:1.31.1` x86_64 `.so` → **SIGSEGV im dlopen**
  (arm64/Gerät ok); Kandidat **F32-B** (Core 1.28.2 aus sherpa-onnx-1.13.8-AAR +
  1.31.1-Bridge) wird als Probe geprüft (Ergebnis offen; Probe-Material
  `/tmp/sherpa_tree.json` vorhanden). Modell liegt vor: `.models/` (int8-ONNX, 671 MB).
- **Nahten:** `AudioSource`-Interface (`app/src/main/java/de/voicekeyboard/audio/
  AudioSource.kt:24`), Debug-`FakeWavAudioSource` (F30, zeitgetreue 10-ms-Blöcke,
  Stille/Aussetzer/`stopAfterMs`), androidTests `dictation/DictationPipelineTest.kt`
  (S1: WAV → `feedAudio` 320-Sample-Blöcke → echte Engine, M4d 6/6 grün **auf arm64**;
  M3: virtuelles Mikrofon-Recipe), `MicCaptureTest.kt` (echter AudioRecord + Pegel).
- **Messfelder (alle vorhanden):** `cap_blocks`, `cap_stall_ms`, `loud_s`, `mic_rel`,
  `status_shown`, `abort_reason`, `hyp_count`, `hyp_gap_ms`, `buffer_s`,
  `decode_ms_max/p95`, `audio_s`, `first_hyp_ms` (`debug/DebugInfoFormatter.kt`).

## 1) Vier Wege (Aufwand / Risiko / Aussagekraft)

| | A) androidTest + FakeWavAudioSource + echte Engine (Service-/Coordinator-Ebene) | B) androidTest liest WAV (`/sdcard`/assets) → `AudioSource`-Naht, Realtime-Takt 30–60 s, Assertions auf Hypothesen-Wachstum | C) Emulator-Virtuellmikro (Host-Audio, `-allow-host-audio`) | D) Engine-Stub (kein ONNX), nur Coordinator/Takt/Puffer |
|---|---|---|---|---|
| **Geprüft** | F30-Quelle + echter Coordinator + echte Engine, volle 🐞-Felder; IME-Service-Wiring | exakt der M4d-S1-Pfad skaliert: Decode-Qualität/-Rückstau (C2), Takt, finale Qualität; `hyp_count`/`buffer_s`/`decode_ms_*` über 30–60 s | **echter AudioRecord + FGS + Routing** (die Schicht, die Export #2 verdächtigt); Pegel via `LevelPeak` | Coordinator/Watchdog/Stille/Status deterministisch (F30/F31-Bestand), CI-fähig |
| **NICHT geprüft** | echtes Mikro, api-37-Politik | Mikro, FGS, IME-UI | Engine nur, wenn F32-B grün; IME-UI in Headless (s. u.) | **keine Transkription**, kein Rückstau, kein Pegel |
| **Blocker** | **F32-B** (x86_64-SIGSEGV); Headless-IME-UI | **F32-B** für Emulator — **auf dem Phone (arm64) läuft er SOFORT** (M4d 6/6 grün) | F32-B + Headless-Audio (s. u.) | **kein** — läuft heute |
| **Aufwand** | ~2–4 h (Test + Wiring, nach F32-B) | ~2 h (Fixture + 1 Testklasse; S1-Muster existiert) | ~2 h Setup (Recipe existiert: `tools/emulator_audio_up.sh`) + Orchestrierung | ~1–2 h (F30-Tests ausbauen, z. B. 30/60-s-Rückstau-Kurven) |
| **Risiko** | mittel (Emulator-Audio/Timing) | **niedrig** (kein Audio-Stack, deterministisch) | hoch (Qt/Audio-Stack im Container, nur 5560) | **null** (JVM-Test) |
| **CI-fähig** | ja (nach F32-B, offline) | ja (offline) | **nein** (Host-PulseAudio, exklusiver Emulator) | ja |

**C-Headless-Prüfung (die wichtige Frage):** Das vermessene Recipe in
`tools/emulator_audio_up.sh` (Kontrollen 2026-10-30) sagt explizit: **`-no-window`
geht nicht** (headless-Branch hat keinen `pa`-Treiber, „Could not init `pa' audio
driver" **und** nullt ohne `-allow-host-audio` das Mikro). **Aber** der vorgesehene
Ersatz — `QT_QPA_PLATFORM=offscreen` + `-qt-hide-window` — braucht **kein physisches
Display** (offscreen-QPA rendert in einen Speicherpuffer). ⇒ **C ist im
displaylosen Container prinzipiell möglich**, aber: (1) der Emulator muss **ohne**
`-no-window` starten (aktueller Headless-Aufruf anpassen), (2) PulseAudio-Hoststack
läuft müssen, (3) die **IME-UI bleibt zu** (`mInputShown=false`) — eine echte
Mic-Halte-Geste ist headless nicht antreibbar; testbar ist in C nur die
**Capture-Hälfte** (AudioRecord→FGS→Pegel→Coordinator) im Instrumentation-Test
(M3-Muster), nicht die volle IME-Session.

## 2) Korpus — OFFLINE verfügbar (alle Pfade geprüft, heute gemessen)

| Asset | Länge | Format | Anmerkung |
|---|---|---|---|
| `testdata/de_smoke.wav` | **6,4 s** | 16 kHz / s16 / mono | M4d-Golden-Quelle (berechtigt als Test-Asset) |
| `testdata/de_part1.wav` | **2,8 s** | dito | Teil 1 |
| `testdata/de_part2.wav` | **3,1 s** | dito | Teil 2 |
| `testdata/de_pause.wav` | **9,9 s** | dito | **laut** (MaxAbs 27281 ≈ **−1,6 dBFS**) — kein Stille-Fixtures trotz des Namens |
| `testdata/cut/*.wav` | **41 Dateien, Summe 88,9 s** (1–6 s-Segmente) | dito | Cut-Fenster aus `tools/make_cut_windows.py` |
| `/tmp/seg_de_multi.wav` | 9,6 s | **22 050 Hz** | resample- oder weglassen |

**Gesamt: ~111 s echte 16-kHz-Deutsch-Audio offline vorhanden** ⇒ 30/60-s-Fixtures
ohne Netz trivial erzeugen: **Konkatenation** `de_smoke + de_part1 + de_part2 + de_pause`
= 22,2 s, dann **Schleife mit 0,3 s synthetischer Stille** zwischen Wiederholungen
bis 30 s bzw. 60 s (einmaliger Python-Schritt, kein Asset-Commit nötig — Fixture im
Test-Scaffold bauen). **Stille-Abschnitte** (für F31-Tests) **synthetisch erzeugen**
(Null-Blöcke) — `de_pause` ist trotz des Namens durchgehend laut.
**Was Schleifen verfälscht:** (a) TDT-Greedy sieht auf wiederholtem Satz wiederholte
Hypothesen → Assertions dürfen **nicht** auf Neuheit des Texts prüfen, sondern auf
Zähler/Kurven (Regel 9 ohnehin); (b) Loop-Grenzen können Decodiergrenzen
künstlich stabilisieren (selbiger Satz, gleiche Kontexte) — **Rückstau/Takt-Messung
bleibt davon unberührt** (das ist ja die C2-Frage); (c) Pegel bleibt konstant (kein
natürliches Sprach-Dynamikband) — für Stille-/Watchdog-Tests egal, für
Qualitäts-Vergleich ein Nachteil. Für echte Qualitätstests gilt: **Phone + M4d-
Golden** bleibt Referenz, Schleifen-Fixtures beantworten **Timing- und
Abbruch-Fragen**.

## 3) Testprotokoll (Konkret, Ziel: Weg B auf Emulator bzw. Phone)

**Ablauf (Pfad pro Tick):** Fixture (`testdata/de_long30.wav` / `de_long60.wav`,
im Test-Scaffold aus §2 erzeugt, 16 kHz mono s16) → 10-ms-Blöcke (320 Samples),
**Realtime-getaktet** (10 ms-Zyklus, `SystemClock`-Referenz; Jitter < 5 ms
tolerieren, sonst Tick-Marge notieren) → `DictationCoordinator`-Consumer
(`AudioSource`-Naht, s. Ist-Lage) → `DictationController` → **echte Engine** →
Zähler. **Variante „ohne Service"** = S1-Muster aus
`DictationPipelineTest.kt` (bewährt, headless-fähig); Variante „mit Service"
= A (nach F32-B, mit `FakeWavAudioSource`-Takt).

**Dauer/Takt:** 2 Fixtures (30 s, 60 s) × 2 Läufe (Stabilität). Takt-Parameter
wie Produktion (`DEFAULT_CHUNK_MS` 2000, Pace-Klammern 2000/6000, Guard 2,0 s).
**Messpunkte:** nach jedem Ticker (2 s) ein 🐞-Snapshot der Felder aus §0 (im Test
direkt von den Coordinator-/Controller-Gettern — kein Clipboard-Pfad nötig);
Abschluss-Snapshot nach `stop()` (Endzustand).

**Erfolgs-Kriterien (alle gelten je Lauf, sonst = FEHLER mit Feld-Snapshot):**

| # | Kriterium | Wert |
|---|---|---|
| E1 | `hyp_count` wächst über 30 s monoton | ≥ 10 (≈ 14 Ticks × Guard 2 s) bzw. ≥ 20 bei 60 s; **kein** Fenster von > 4 s ohne neue Hypothese |
| E2 | `hyp_gap_ms` | < 4000 am Ende (gemessen wie 🐞-Export) |
| E3 | **Kein C2-Rückstau:** `buffer_s` vs. `audio_s` | `buffer_s ≤ audio_s + 1,0` **am Ende**; Zwischen-Snapshots: `buffer_s` darf nicht über 5 s steigen |
| E4 | `decode_ms_max` / `decode_ms_p95` | < 4000 / < 2000 (RTF ≪ 1 am Emulator 4-Core; Grenzwerte beim ersten Lauf kalibrieren und hier festhalten) |
| E5 | `abort_reason` | bleibt „-" während des Laufs, am Ende **`final`** (erst durch Test-`stop()`); `lastError` = „-" |
| E6 | Watchdog-Fehlalarme | **0**× `audio_error:stall`, **0**× `audio_error:silence` bei echtem Korpus-Audio (F31b-`everLoud`-Regression) |
| E7 | `loud_s` / `cap_blocks` | `loud_s ≈ audio_s ± 1,0`; `cap_blocks ≈ audio_s × 100 ± 5 %` |
| E8 | Text-Golden (nur Phone-Variante) | Endtext enthält §4.1-Golden (M4d-Muster, Wort-Identität ≥ 95 %) — im Emulator auf Schleifen-Fixture **nicht** ansetzen (s. §2) |

**Abbruch-Kriterien des Testlaufs (Kill-Schalter, nicht „Test fehlgeschlagen"):**
`decode_ms_max > 8000` zweimal in Folge → Lauf stoppen und als
„Engine zu langsam auf x86_64" protokollieren (dann Weg D für Timing-Fragen);
nativ-SIGSEGV → F32-B-Protokoll (Kandidat dokumentieren, kein Debugging im Test).

## 4) Was der Test NICHT kann (und was nur am Phone bleibt)

- **Echtes Mikro-Hardware-/Routing-DSP** (AGC/Noise-Suppression, `VOICE_RECOGNITION`-
  Quellen-Verhalten am Gerät): Emulator-Virtuellmikro = sauberes Host-Signal, kein
  DSP — der **−56,2-dBFS-Nutzerfall ist hier nicht reproduzierbar** (er ist
  Hardware-/OS-Politik, kein App-Pfad).
- **FGS-Mikro-Berechtigung api 37 + `POST_NOTIFICATIONS`:** Emulator = api 36 mit
  lockerer Politik ⇒ der Hauptverdacht aus `ANALYSE_DIKTAT_ABBRUCH_2.md` (system-
  seitiges Stummschalten nach ~1 s) bleibt **Phone-only**.
- **IME-UI/Stopp-Gesten** headless: `mInputShown=false` ⇒ Haltegeste, `mic_rel`-
  Pfade, Statusanzeige nicht antreibbar (F30-Tests decken die Logik ohne UI).
- **Thermik/Speicher am Phone** bei Dauerlauf — nur annähernd am Emulator.
⇒ **Phone-only-Verbleib:** 🐞-Export #3 (Entscheidungsbaum `ANALYSE_DIKTAT_ABBRUCH_2.md`
  §9) bleibt der einzig gültige Beweis für den realen Abbruch; der Testplan
  eliminiert nur die **App-Hälfte** (Decode-Rückstau, Watchdog-Fehlalarme,
  Stopp-Pfade) als Ursache-Kandidaten.

## 5) Reihenfolge / Empfehlung (mit F32-B-Zweig)

| Schritt | Inhalt | Aufwand | Voraussetzung |
|---|---|---|---|
| 1 | **Weg D ausbauen** (heute, ohne Engine): 30/60-s-„Rückstau-Kurven" aus `FakeAudioScenarioTest`-Muster (Stub-Engine mit konstanter 150-ms-Latenz) ⇒ CI-Netz für Takt/Puffer/Watchdog, unabhängig von F32-B | ~1–2 h | — |
| 2a | **F32-B grün:** Weg **B auf dem Emulator** (Fixture §2 + S1-Muster, Kriterien §3) — vollständige Decode-/C2-Antwort auf x86_64 | ~2 h | F32-B |
| 2b | **F32-B rot:** Weg **B auf dem Phone** (arm64, M4d 6/6 grün) per `connectedDebugAndroidTest` — gleiche Fixture, gleiche Kriterien, E8-Golden zusätzlich; Emulator-Engine-Weg wartet auf nächsten Runtime-Kandidaten | ~2 h + 1 adb-Lauf | — (Phone) |
| 3 | **Weg C** nur, wenn 2a/2b grün und die **FGS/AudioRecord-Schicht** am Emulator zusätzlich abgesichert werden soll (offscreen-Qt-Start ohne `-no-window`, Host-PA, M3-Muster, Capture-Hälfte ohne IME-UI) | ~2 h + Orchestrierung | 2a/2b grün |
| 4 | **Export #3 vom Phone** (parallel, unabhängig von allen Schritten) — Entscheidungsbaum §9 anwenden | Nutzerlauf | — |

**Entscheidungspunkt:** 2a vs. 2b wird **ausschließlich** durch das F32-B-Ergebnis
getriggert (Kandidat-Core-1.28.2-dlopen-Test → SIGSEGV weg = 2a, sonst 2b). Weg C wird
erst nach grünen Decode-Läufen gebaut — sonst wird ein zweites Risiko (Emulator-Audio-
Stack) auf eine noch nicht bewiesene Engine-Basis gestapelt.
