# Emulator-Diktat-Harness — Plan zur reproduzierbaren Nachstellung des Abbruchs

**Zweck:** im Emulator (<device>, api 36) den Diktat-Abbruch deterministisch
nachstellen („eine echte Sprachnachricht in den Emulator stecken"), um zu sehen,
wann/warum Aufnahme oder Transkription abbricht. **Nur Analyse/Plan** — kein Code,
kein Build, kein git. REGEL 9: keine Audioinhalte; Test-WAVs dürfen synthetisch sein
(Sinus/Stille), Golden-Audio bleibt `testdata/de_smoke.wav`.

## 1) Ist-Stand der Testbarkeit (belegt)

| Baustein | Stand | Beleg |
|---|---|---|
| Pipeline-Test mit echtem WAV + echter Engine | **existiert** (M4d, S1: `de_smoke.wav` → `controller.feedAudio` in 320-Sample-Blöcken → Endtext = §4.1-Golden; S2–S4 Editor-Sinks; M3: Audio **vom virtuellen Mikrofon**) | `app/src/androidTest/java/de/voicekeyboard/dictation/DictationPipelineTest.kt` (Klammern S1/M3, `feedAudio` ~Zeile 99); 6/6 GRÜN auf **Gerät** (`state/progress.md` M4d) |
| Echter AudioRecord-Pfad + Pegelkanal | **existiert** (M1/M2/M4: Samples + `LevelPeak`-Nachweis) | `app/src/androidTest/java/de/voicekeyboard/MicCaptureTest.kt` |
| Emulator-Virtuelles-Mikrofon-Recipe | **existiert und vermessen** (`-allow-host-audio` — ohne: **Mikro wird nullt**; kein `-no-window` (headless hat keinen `pa`-Treiber), statt Offscreen+`-qt-hide-window`; PulseAudio + Null-Sink; Betriebssensor **65 %** ⇒ Peak ~19800, RMS ~1900) | `tools/emulator_audio_up.sh` (Kommentarblock 1–25), `tools/emulator_audio_down.sh` |
| Engine-Lauf auf 5560 | **Blocker:** nativer **SIGSEGV in `libonnxruntime.so`** (dlopen/call_constructors, nur Worker-Thread) — Diktat/Engine in IME-Prozess dort **nicht validierbar** | `docs/PROJECT_STATE.md` „Umgebung" (Hauptbeleg: `HANDOFF.md`) |
| Injektionsnaht für Quelle | **existiert**: `interface AudioSource` (`start/read/stopAndDrain/release`, `app/src/main/java/de/voicekeyboard/audio/AudioSource.kt:24`); in Produktion `AudioRecordSource` in `buildCoordinator` (`ime/VoiceKeyboardService.kt:651`); Engine hinter `AsrEngine`-Interface (`asr/`) | — |
| Messfelder für die Szenarien | **alle da** (F26a/b + F28b): `cap_blocks`, `cap_stall_ms`, `mic_rel`, `status_shown`, `abort_reason`, `hyp_count`, `buffer_s` (🐞-Export, `debug/DebugInfoFormatter.kt`, Drift-Check) | `docs/ANALYSE_DIKTAT_ABBRUCH_2.md` §5 |
| Stall-Watchdog | **DRIN**: `ERR_STALL`/`STALL_LIMIT_MS = 3000` (`ime/DictationCoordinator.kt:71/73`) → `audio_error:stall`-Pfad | `docs/PROJECT_STATE.md` „F28" |

**Wichtigste Randbedingung:** Der Engine-SIGSEGV betrifft **jeden** Prozess, der die
native ONNX-Lib auf x86_64 lädt. **Hypothese (zu prüfen, bevor A/B ernsthaft laufen):**
der 5560-AVD ist x86_64 und nutzt die x86_64 `libonnxruntime.so` — das
`dlopen/call_constructors`-Crash-Muster ist typisch für eine inkonsistente x86_64-Bibliothek,
nicht für den ONNX-Modellinhalt (derselbe läuft auf dem Gerät/arm64 6/6 grün). Prüfstein:
bekannte-gute x86_64 ONNX-Runtime-Build austauschen (ein Build-Lauf, kein Feature).

## 2) Drei Optionen (Aufwand / Risiko / Aussagekraft)

### A) Instrumentierter WAV-Feed durch die Pipeline (AudioRecord umgangen)
**Was nachgestellt wird:** 30–60-s-WAV (synthetisch: Sprache-Äquivalent = Sinus-Töne,
Stille-Abschnitte, Level ~-20 dBFS — keine echten Stimmen, Regel 9) im **Realtime-Takt**
(10-ms-Blöcke, `SystemClock`-getaktet) → `DictationCoordinator`-Consumer → echter
`DictationController` + **echte Engine** → finale Text-/Zähler-Asserts.
**Was NICHT:** Mic-Treiber, Route, FGS, `LevelPeak` vom echten Capture, Stop/Abort über
UI-Events (stop/abort triggert der Test direkt), Pointer-Pfade.
**Dateien/Klassen:** 1 neue androidTest-Klasse (`dictation/LongDictationHarnessTest.kt`),
WAV-Fixtures 30 s/60 s (`testdata/`), ggf. Takt-Feed-Helfer; ~1 Datei.
**Dauer:** 1× 30 s + 1× 60 s Realtime ⇒ **~2 min/Lauf**. **CI-fähig:** ja (offline) —
**aber nur auf Gerät oder erst nach Fix des x86_64-Runtime-Crashes** (Engine!).
**Szenarien:** Dauerdiktat (30/60 s), Stille-Zwischenabschnitte, Decode-Qualität/-Rückstau
(`decode_ms_max`, `buffer_s` über 60 s — C2-Verlauf!). Loslassen/Fokusverlust **nicht**
(kein UI-Event).

### B) Emulator-Virtuelles-Mikrofon (Host-Audio → echte IME)
**Was nachgestellt wird:** die **ganze Kette**: Host-PulseAudio → `paplay`-Loop (WAV) →
virtuelles Mikro → **echtes AudioRecord** → FGS-Handshake (F28c) → Coordinator →
**echte IME-Session** mit echten Haltegesten (adb-Input-Events: DOWN/UP nach Zeitplan,
`wm`-Befehle für Fokusverlust) → komplette Statusmaschine + 🐞-Felder.
**Was NICHT:** das echte Telefon-Mikro, die **api-37-FGS-Mikro-Politik** (Emulator =
api 36 — das Hauptverdachtsfeld des Nutzerfalls ist genau DORT anders), reale
Routing-/Mute-Zustände.
**Dateien/Klassen:** keine neuen (Recipe existiert: `emulator_audio_up.sh`, M3-Treiber in
`DictationPipelineTest`); neu nur ein Orchestrator-Skript mit Timeplan (paplay +
`input swipe`/`wm` + 🐞-Export via ADB-Clipboard).
**Dauer:** ~5–10 min pro Szenario (manuelle Orchestrierung). **CI-fähig:** **nein**
(Host-PulseAudio + exklusiver adb-Einsatz). **Risiko:** mittel/hoch — Emulator-Audio auf
5560 notorisch (s. Script-Kommentar), **Engine-Crash bleibt** ⇒ Transkriptions-Hälfte
(Decodes, `hyp_count`) bleibt im Emulator blind, solange §1-Hypothese nicht gefixt ist.
Dann deckt B nur: Capture-Feld, FGS-Handshake, Stop/Abort-Pfade, Status.

### C) Debug-only Fake-AudioSource (deterministisch, ohne Emulator-Audio)
**Was nachgestellt wird:** `FakeAudioSource : AudioSource` (Script: Zeitblöcke aus
synthetischer WAV, konfigurierbar **Stille / Aussetzer (0-Blöcke) / Abbruch nach N ms**),
per Debug-Flag in `buildCoordinator` injiziert (Naeht `VoiceKeyboardService.kt:651`);
optional `FakeAsrEngine : AsrEngine` (zählt Decodes, konstante Latenz) ⇒ **ohne ONNX-
Laden** → läuft **auch auf dem SIGSEGV-5560**. Gesteuert wird die UI: adb-
DOWN/UP/Hide-Events nach Timeplan; Assertion über 🐞-Felder (alle vorhanden).
**Was NICHT:** Mic-Treiber/Route/FGS-Realverhalten, echte Decode-Qualität (nur mit
echter Engine, dann wieder x86_64-Problem), api-37-Politik.
**Dateien/Klassen:** `audio/FakeAudioSource.kt` (~120 Zeilen, **debug-Sourceset**),
Debug-Eingang (Query-Param/Intent-Extra) in VKS, 1 Testklasse pro Szenario; 2–3 Dateien.
**Dauer:** Sekunden bis ~1 min je Szenario (zeitverfügbar!). **CI-fähig:** **ja**
(instrumented, offline, deterministisch) — der einzige Weg zu einer **regressions-
fähigen** Abbruch-Testflotte.
**Szenarien:** **alle fünf** (s. §4), inklusive Aussetzer > 3 s (Watchdog) und
Loslassen während Start — deterministisch wiederholbar.

## 3) Empfehlung (Reihenfolge + Begründung)

1. **C sofort** — deterministisches Regressionsnetz über Coordinator/Watchdog/Status-
   maschine; der einzige Weg, alle Abbruchpfade **wiederholbar und CI-fähig** zu
   testen, und der einzige, der trotz Engine-SIGSEGV auf 5560 läuft.
2. **Vor A: x86_64-Runtime-Hypothese prüfen** (1 Build, Lib austauschen) — wenn grün:
   **A für Decode-Qualität/C2-Verlauf** (30/60-s-Dauerdiktat mit realer Engine,
   `decode_ms_max`/`buffer_s`-Kurven) — das beantwortet auch die offene C2-Frage
   „wächst der Rückstau über 60 s?" ohne Telefon.
3. **B nur, wenn 2. grün ist** — dann als letzter Beweis der AudioRecord/FGS-Stopp-
   Kette im Emulator (F28c-Handshake, `mic_rel`-Pfade). Ansonsten warten: B hat ohne
   Engine nur halbe Aussagekraft und das schlechteste Risiko pro Stunde.
4. **Telefon bleibt Ground-Truth** für den Nutzerfall selbst (api 37, echtes Mikro) —
   Harness ersetzt Export #3 nicht, macht ihn aber auswertbar (Felder + Vergleichs-
   erwartung aus §4).

## 4) Szenarien-Tabelle (Zielwerte = Soll, gemessen über 🐞-Felder; 10-ms-Capture-Blöcke)

| Szenario | Eingabe (Timeplan) | Erwartete Messwerte | Abbruchkriterium (Fehler, wenn…) |
|---|---|---|---|
| **S1 Dauerdiktat 30 s** | Fake/Mic-WAV (Töne, ~-20 dBFS) 30 s; UP bei 30 s | `cap_blocks` ≈ 3000; `cap_stall_ms` < 1000; `mic_rel` = `up:≈30000`; `status_shown` = dictat bis finalizing; `abort_reason` = **final**; `hyp_count` ≥ 10 (2-s-Takt) | `hyp_count` < 10 ODER `decode_ms_max` wächst je Tick > 2× (C2-Rückstau) ODER `abort_reason` ≠ final |
| **S2 Stille 60 s** | 60 s Nullpegel-WAV; UP bei 60 s | `cap_blocks` ≈ 6000, `cap_stall_ms` < 1000 (Stille ≠ Stall!); `hyp_count` ≥ 8; **kein** commit (leere Hypothesen); `abort_reason` = final | `cap_stall_ms` > 3000 (falscher Stall) ODER Watchdog-Fehlalarm `audio_error:stall` |
| **S3 Aussetzer > 3 s** | 5 s Ton, **4 s keine Blöcke** (Fake: 0-Lieferung), 5 s Ton | `cap_stall_ms` läuft > 3000; Watchdog (3000 ms) feuert; `abort_reason` = **audio_error:stall**; `status_shown` = error; `mic_rel` = `-` (kein UI-Event) | Watchdog schlägt nicht within 3,5 s ODER Session läuft endlos weiter („Diktat-Ewigkeit" = der Nutzer-Bug) |
| **S4 Loslassen während Start** | DOWN t=0, UP t=0,5 s (Engine-Start > 0,5 s) | `mic_rel` = `up:≈500`; `abort_reason` = **final** (nachgereichter Stop, `VKS:450-469`); `audio_s` < 1,0; `hyp_count` ≤ 1 | `abort_reason` = `michold_cancel` (falscher CANCEL) ODER Session hängt in STARTING |
| **S5 Fokusverlust bei 10 s** | 10 s Ton, dann IME-`onWindowHidden`/Editor-Wechsel | (Hide) `mic_rel` = `hidden:≈10000`, `abort_reason` = final; (Editor) `abort_reason` = **editor_change** | Grund ≠ zugehörigem Pfad ODER `status_shown` bleibt dictat nach dem Event |

## 5) Was das über den Nutzerfall (1,2-s-Stop + −56,2 dBFS) lehrt — und was nicht

**Im Emulator belegbar (mit C/A/B):**
- **S3:** ob der Stall-Watchdog (F28b1) den „1-s-Stillmikro"-Zustand des Nutzers
  korrekt in `audio_error:stall` übersetzt — die Lücke, die bei Export #2 noch offen war.
- **S4/S5:** ob die Stop-/Abort-Pfade (`mic_rel`-Tags, `abort_reason`-Semantik,
  `status_shown`-Kohärenz) exakt so verhalten wie die Analyse
  (`docs/ANALYSE_DIKTAT_ABBRUCH_2.md` §1/§4) sagt — d. h. ob das 1,2-s-`final` im
  Export #2 **nur** durch einen echten UP erklärbar ist oder auch ein Hidden-/
  Deferred-Pfad es erzeugen kann.
- **S1/S2:** ob Capture-Zähler/Stall-Messung (F28b) selbst korrekt sind (Sanity-
  Kontrollen), bevor sie am Telefon gedeutet werden.
- **F28c-Handshake:** FGS-Ready vor AudioRecord im Emulator-API-36-Verhalten.

**Prinzipiell nur am Telefon messbar (Harness ersetzbar nicht):**
- **Das −56,2-dBFS-Stillmikro selbst:** virtuelles Mikro = synthetisches Signal, keine
  Hardware-/Mute-/Routing-Zustände; ob das System api 37 das Mikro nach ~1 s stummlegt
  (FGS-microphone-Berechtigung ohne `POST_NOTIFICATIONS`) ist eine **OS-Politikfrage**,
  die auf dem api-36-Emulator nicht replizierbar ist.
- **Der tatsächliche UP-Zeitpunkt des Nutzers** (H-A/H-B aus der Analyse): braucht
  Export #3 mit `mic_rel` + `cap_stall_ms` vom echten Gerät.
- **Echte Decode-Qualität** am Nutzer-Modell/der Nutzer-Umgebung (Speicher, Thermik,
  api 37) — außer wenn A nach x86_64-Fix im Emulator läuft (dann: gute Näherung).

**Konsequenz:** Harness (C→A→B) macht die **App-Hälfte** des Falls reproduzierbar;
die **System-Hälfte** (Mikro/FGS api 37) bleibt eine Frage an Export #3 — der Harness
sieht aber dann genau, was man im Export suchen muss (Sollwerte §4).

## 6) Umsetzung F30 (Stand)

- **Option C teils umgesetzt (2026-10-31, BAU 23/24-Zeitraum):** `audio/FakeWavAudioSource.kt`
  im **debug-Quellordner** — WAV-Feed (`testdata/de_smoke.wav`, 16 kHz mono s16, geschleift)
  als 10-ms-Blöcke, zeitgetreu über injizierbare Uhr; Szenarien `throughput/silence/gap/short`
  (`silenceFromMs`, `gapFromMs/gapToMs`, `stopAfterMs`).
- Injektion: Feld `VoiceKeyboardService.debugAudioSourceFactory` (Release: immer null →
  `AudioRecordSource`, Produktion unverändert); Device-Seite (adb-UI-Timeplan) noch offen.
- 4 Deterministik-Tests in `testDebug/.../FakeAudioScenarioTest.kt` (durchgehend/Stille/
  Aussetzer/1,2-s-UP) — alle grün, inkl. Nutzerfall-Beleg (`final`, audio_s ≈ 1,2).
