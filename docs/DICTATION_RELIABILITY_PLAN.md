# Plan: Diktat-Zuverlässigkeit auf dem echten Gerät (v0.1.6)

**Stand:** 2026-10-28 · **Rolle:** Analyse/Plan (nur lesend, keine Code-Änderung)
**Basis:** 🐞-Export des Nutzers (API 37, arm64) + Bericht + unser Bestand.
**Kernzahl, die alles erklärt:** `peak_pct: 2` — das ist **kein** „leises Sprechen",
sondern ein um **~30× schwächeres Eingangssignal** als in unserem Emulatorpfad.

## 0. Zwei Auflösungen zuerst (belegen, nicht raten)

**(a) `peak_pct` ist ein RMS-Wert, kein Sample-Peak.** `AudioRecordSource.rms()` rechnet
`sqrt(mean((s/32768)²))` (`audio/AudioRecordSource.kt:143-150`) und meldet das an
`levelListener` (`:119`); `LevelPeak.note()` speichert das Maximum und
`percent()` gibt `(max*100).toInt()` (`debug/LevelPeak.kt:27/32`). Der Feldname im
Export ist also irreführend, die Zahl aber eindeutig:

| Ort | `peak_pct` | RMS (0..1) | int16-RMS | dBFS |
|---|---|---|---|---|
| Telefon (Nutzer) | **2** | 0,020 | **≈ 655** | **−34 dB** |
| Emulator, M3-Test | ~6–65 (je Signal) | 0,06–0,65 | 1 930 … 19 800 | −24 … −4 dB |

**−34 dBFS RMS** liegt unter allem, was ein Handy-Mikrofon bei normalem Sprechabstand
liefert (typisch −18…−26 dBFS). Verständlich ist das nur bei sehr niedrigem
Rauschboden — und genau dann kippt ein ASR mit „Silence-Prior" in Fantasietext.

**(b) Die Engine kennt keine Sprachvorgabe.** Weder `asr/ParakeetAsrEngine.kt` noch
`asr/OnnxSessions.kt` enthalten einen `language`-Parameter (Volltextsuche: kein Treffer).
Parakeet TDT 0.6B v3 ist mehrsprachig und entscheidet selbst; die Tastatursprache wirkt
**nur** auf das Layout (`CaseMapper`, `LAYOUT_REQUIREMENTS`), **nicht** auf die ASR.
Der Globus kann beim Diktat also **nichts** verbessern — und wird deshalb gesperrt.

---

## 1. Ursachen-Trennung je Symptom

### A — Erstes Diktat liefert nur „Yeah."
| # | Hypothese | Dafür | Dagegen | Entscheidender Versuch | Datei bei Bestätigung |
|---|---|---|---|---|---|
| A1 | **Halluzination auf (nahezu) Stille** — dokumentiert: 0,3 s Stille ⇒ `Yeah.` (`docs/ASR_CONTRACT.md` §4.3/§4.4, Messung `silence_0.3s`) | `peak_pct=2` = −34 dBFS; `min_audio_s=2.0` lässt die ersten 2 s **Audio** dekodieren, egal ob Sprache drin ist | „Yeah." kam laut Nutzer nach *einem* ersten Diktat mit Worten — dann müsste der Pegel kurzzeitig höher gewesen sein | **Gerät:** gleicher Satz, `peak_pct` **und** Text gemeinsam notieren (2×) | `asr/` + Pegel-Tor (neu) |
| A2 | **Falscher Aufnahmeweg** (Source/Route) dämpft um ~30 dB | `MediaRecorder.AudioSource.VOICE_RECOGNITION` (`audio/AudioRecordSource.kt:93`) — auf manchen Geräten stark prozessiert (NR/AGC/Limiter) | Emulator nutzt denselben Code und liefert laut | **A/B am Gerät:** `VOICE_RECOGNITION` vs. `MIC` vs. `UNPROCESSED` (nur Test-Build), `peak_pct` vergleichen | `audio/AudioRecordSource.kt:93` |
| A3 | Routing auf falsches Gerät (BT-SCO/Headset) | `AudioRouter` kennt `WIRED > BLUETOOTH_SCO > BUILTIN` | Nutzer erwähnt kein Headset | Statuszeile/`route` im Export mitlesen | `audio/AudioRouter.kt` |

### B — Englische Sätze: überhaupt keine Ausgabe
| # | Hypothese | Dafür | Dagegen | Versuch | Datei |
|---|---|---|---|---|---|
| B1 | **Pegel zu niedrig** → Hypothese besteht nur aus `<blk>` (leerer Text) | gleiche Ursache wie A1/A2; Englisch hat mehr Zischlaute/hohe Frequenzen, die bei Dämpfung zuerst verschwinden | Deutsch funktionierte „relativ gut" — bei gleichem Pegel? | **Gerät:** identischer Pegel-Check mit englischem Satz; `peak_pct` + Text | `audio/AudioRecordSource.kt` |
| B2 | **Kein Commit, weil E8 zu streng bei kurzen Läufen** | E8: Commit erst ab **3** Hypothesen, letzte **2** Wörter ungeschützt (`COMMIT_RULE_DECISION_2.md` §3 C1/C2) | Der **Finaldecode** schreibt immer (D3-Regel) — „gar keine Ausgabe" wäre dann unmöglich | **Emulator:** 6-s-Englisch-Fixture ⇒ erwartet Text; falls Text kommt, ist es gerätespezifisch | `commit/`, `dictation/DictationController.kt:144-172` |
| B3 | **Ownership verloren** (Editor hat Korrektur/Composing gemacht) → `revise()` schreibt nichts | Ziel-App-Englisch (z. B. mit Autokorrektur) ändert Text aktiv; `DeltaTyper.revise()` gibt `null` bei `regionMatches`-Fehler (`editor/DeltaTyper.kt:64-72`) | Deutscher Fall hätte dasselbe Problem | **Gerät:** in einer „dummen" App (AOSP-Messenger/Notes) gegen eine „schlauen" (Signal/Gmail) vergleichen | `editor/DeltaTyper.kt` |
| B4 | Modell-Qualität Englisch bei *diesem* Pegel schlechter | mehrsprachiges Modell, aber SNR-arm | unbekannt | siehe B1 | — |

### C — „Häufig wird die Eingabe ignoriert"
| # | Hypothese | Dafür | Dagegen | Versuch | Datei |
|---|---|---|---|---|---|
| C1 | **RTF 0,80**: 10 s Diktat braucht 8 s Rechenzeit → Hypothesen kommen zu spät/werden übersprungen | `rtf: 0.80`, `threads: intra=4 inter=1`; `DEFAULT_HYPOTHESIS_MIN_INTERVAL_MS = 2000` (`AsrThreadConfig.kt:62`) | Finaldecode läuft trotzdem | **Gerät+Emulator:** `intra=6` vs. `4` messen (RTF, Ersthypothese) | `asr/AsrThreadConfig.kt` (`MAX_THREADS = 6`, `:64`) |
| C2 | **Pegel-Tor fehlt**: leere/blinde Fenster werden dekodiert (Kosten) oder als „Yeah." angezeigt | kein Pegel-Gate im Code; `min_audio_s` prüft nur **Länge** | — | **Unit+Emulator:** Fenster mit RMS < 1 % ⇒ kein Decode (Test) | `dictation/DictationController.kt` |
| C3 | Nutzer spricht zu früh los (FGS/STARTING) → erste Wörter fehlen | Aufnahme startet erst nach `startFgs()` + Coordinator-Start (`ime/VoiceKeyboardService.kt:306-320`) | — | **Gerät:** 6-s-Fixture mit 2 s Vorlauf-Stille vs. sofort sprechen | `ime/VoiceKeyboardService.kt` |
| C4 | `notif=no` → FGS-Notification unsichtbar, Nutzer hält für „läuft" was nicht läuft | `permissions: record=yes notif=no` | beeinflusst ASR nicht direkt | Statuszeile prüfen | `ime/` |

### D — Globus während des Diktats abgelehnt
**By design, kein Fehler:** `VoiceKeyboardService.switchToNextLanguage()` bricht bei
`state != IDLE` ab und zeigt `R.string.language_switch_blocked_recording`
(`ime/VoiceKeyboardService.kt:680-682`, Text `res/values/strings.xml:57`).
**Grund:** Ein Wechsel mitten im Lauf würde Layout **und** Case-Mapping wechseln, während
der committete Text in der alten Orthographie steht und die Hypothese aus dem alten
Audio dekodiert ist → gemischt-schriftige Revisionen und Rollbacks über Sprachgrenzen
brechen die Commit-Invarianten (E8/E9.2). **Da die ASR ohnehin keine Sprachvorgabe hat
(§0b), bringt ein Wechsel während des Diktats auch nichts.** → **keine Änderung**,
höchstens Klarstellung im Text („erst nach dem Loslassen").

### Reihenfolge nach Aufwand/Nutzen
1. **A2/B1 (Pegel/Aufnahmeweg)** — 10 min Test, größter Effekt (30-dB-Defizit).
2. **A1/C2 (Pegel-Tor + „Yeah."-Unterdrückung)** — 1–2 h, verhindert Fantasietext.
3. **C1 (Threads/Intervall)** — 30 min Messung, RTF 0,80 ist grenzwertig.
4. **B3 (Editor-Ownership)** — 1 h Vergleichstest in zwei Apps.
5. D: keine Änderung (Doku-Text).

---

## 2. `peak_pct: 2` — Rechnung und Konsequenz

- RMS 0,02 ⇒ **−34 dBFS**. Ein 16-bit-Sample mit RMS 655 ist bei 30 cm Sprechabstand
  **nicht** plausibel (typisch 2 000–8 000).
- Unser Emulator-Pfad (M3-Test, `DictationPipelineTest.m3_realEngineFromMicrophone_commitsGoldenText`,
  `OK (1 test)`) sieht RMS ≈ 1 930 (≈ 6 %) bzw. bei lauter Einspielung bis ~60 % — also
  **30× mehr Pegel** bei identischem Code.
- **Fazit:** Nicht die App-Logik, sondern der **Aufnahmeweg** ist verdächtig. Konkrete
  Kandidaten in dieser Reihenfolge:
  1. **`MediaRecorder.AudioSource.VOICE_RECOGNITION`** (`AudioRecordSource.kt:93`) —
     gerätespezifisch stark prozessiert; auf Pixel-Geräten kann das mit
     Noise-Suppression/AEC deutlich dämpfen. **Test:** `MIC` und `UNPROCESSED`
     (API ≥ 24) gegeneinander, gleicher Satz, `peak_pct` notieren.
  2. **Routing** (`AudioRouter`: `WIRED > BLUETOOTH_SCO > BUILTIN`) — falls SCO aktiv ist,
     liefert das Kommunikations-Mikrofon oft leiser; Route im Export mitführen.
  3. **Geräte-/OS-Ebene** (Mikrofon-Abdeckung, Hülle, `voice_communication`-Pfad) —
     durch (1)/(2) eingrenzbar.
- **Wichtig:** `VOICE_RECOGNITION` ist laut Kommentar „AGC ja, NR je nach Gerät" — genau
  dieses „je nach Gerät" ist das Problem. Ein A/B-Test entscheidet in 10 Minuten.

---

## 3. Messplan (Emulator **und** Gerät, 3 Minuten für den Nutzer)

| # | Messgröße | Messstelle | Schwellwert (Go) |
|---|---|---|---|
| M1 | **`peak_pct`** (max RMS je Sitzung) | `debug/LevelPeak` → Debug-Export | **≥ 8 %** (−22 dBFS) für Sprache |
| M2 | **RTF** = processing/audio | `DictationCoordinator:96` | **≤ 0,5** (heute 0,80) |
| M3 | **Ersthypothesen-Latenz** | `firstHypothesisLatencyMs` (`:70`) | ≤ **1,5 s** (heute 1,12 s — ok) |
| M4 | **Hypothesen-Lücken** (≥ 2 s ohne neue Hypothese bei laufender Aufnahme) | Zähler in `DictationCoordinator` | 0 Lücken > 2 s |
| M5 | **Verworfene Fenster** (leerer Text bei RMS > Schwelle) | Zähler | 0 bei Sprache |
| M6 | **Verworfene Revisionen** (Ownership/60-Zeichen) | `DeltaTyper`-Zähler | 0 |
| M7 | **Leerlauf am Ende** (letzter Text → Statuszeile „Bereit") | Zeitstempel | ≤ 3 s |
| M8 | **Sprach-ID-Wechsel** (deutsch ↔ englisch im selben Diktat) | Vergleich Text vs. erwartete Sprache | 0 unerwartete Wechsel |
| M9 | **Threads/Intervall** (`intra`, `hypMs`) | Export | dokumentiert je Lauf |

**3-Minuten-Protokoll für den Nutzer:** (1) 6 s deutsch, 2 s Pause davor; (2) 6 s englisch;
(3) 12 s durchgehend; (4) 🐞-Export nach jedem Lauf kopieren. Aus M1/M2/M4 fällt die
Entscheidung, ob Pegel oder Rechenzeit die Ursache ist.

---

## 4. Testfälle (Emulator mit Toneinspeisung **und** Gerät)

| # | Fall | Erwartung | Erlaubte Abweichung | Emulator-Nachbau |
|---|---|---|---|---|
| T-a | Deutsch 6 s, 2 s Stille davor | §4.1-nah: Wort-Identität ≥ 95 %, **kein** „Yeah." | CED ≤ 2 % | `testdata/de_smoke.wav` mit 2 s Stille-Prefix (Skript) |
| T-b | Englisch 6 s | englischer Text, ≥ 90 % Wort-Identität | CED ≤ 5 % | **fehlt** → neues Fixture `testdata/en_smoke.wav` (espeak `-v en`) |
| T-c | Deutsch mit englischem Wort („Das ist ein Test-Build") | deutsches Gerüst + englisches Wort erhalten | 1 Wort darf abweichen | `testdata/de_smoke.wav` (enthält „Dikti-Funktion") |
| T-d | 12 s durchgehend | Text vollständig, Commit-Rückstand ≤ 2 Wörter | 1 Wort | `de_pause.wav` (9,9 s) + `de_part2` konkateniert |
| T-e | Loslassen + sofort wieder drücken | zweites Diktat schreibt **nach** dem ersten, kein Verlust/Doppel | 1 Zeichen | zwei Sequenzen in einem Instrumented-Test |
| T-f | Sprachwechsel vor / während / nach dem Diktat | vor/nach: Wechsel ok; während: Hinweis, **kein** Abbruch | — | Emulator + UI-Test |

---

## 5. Was am echten Gerät anders ist — und die 3 nächsten Kandidaten

**Der Unterschied ist nicht die App-Logik** (M3 beweist denselben Code auf dem Emulator mit
Erfolg), sondern **Pegel + Rechenzeit**:

1. **Mikrofon-Pfad (Pegel):** `VOICE_RECOGNITION` + Geräte-DSP + evtl. Routing ⇒ −34 dBFS.
   → **Zuerst:** A/B `VOICE_RECOGNITION` / `MIC` / `UNPROCESSED`, `peak_pct` messen.
   **Das ist der billigste Test mit dem größten Effekt.**
2. **Pegel-Tor vor dem Decode** (verhindert „Yeah." und spart Rechenzeit): Fenster mit
   RMS < 1 % werden **nicht** dekodiert; Statuszeile optional „kein Ton erkannt".
   Verhindert die dokumentierte Stille-Halluzination an der Wurzel.
3. **Rechenzeit:** `intra=4` → `6` (`AsrThreadConfig.MAX_THREADS = 6`), Hypothesen-Intervall
   auf dem Gerät messen (2000 ms Default). RTF 0,80 bei 10 s ⇒ 8 s Nacheilung; mit 6
   Threads ist ≤ 0,5 realistisch.

**Nicht** als erste Maßnahme: Sprachvorabe an die ASR (Engine kennt keine Sprache, §0b),
Commit-Lockerung (E8 ist gerade erst kalibriert), Globus-Freigabe (sinnlos ohne
Sprachparameter).

## ANNAHME-Zeilen
- `ANNAHME: peak_pct ist RMS-basiert (Code-Beleg oben) — die Emulator-Vergleichszahl`
  `„65 %" entspricht dann RMS 0,65, also einem sehr lauten, teils geclippten Signal.`
- `ANNAHME: Englisch-Testfixture existiert noch nicht; für T-b muss eines erzeugt werden`
  `(espeak-ng -v en).`
