# Projektstand Voice-Keyboard (Arbeitsdatei für Fortsetzung)

**Stand: 2026-11-03 07:50 · v0.1.38 / versionCode 39** (nächster Bau 40 / 0.1.39). Aktuelle Details siehe `HANDOFF.md`,
Verifikationsstand siehe `state/progress.md`, Reihenfolge siehe `reports/NEXT.md`.

Ziel: Android-IME für GrapheneOS (API 36), 15 Sprachen, Offline-Diktat (Parakeet TDT 0.6B ONNX).
Auftrag des Nutzers: Design = GPT 6.1 Sol, Plan = Kimi K3, Code = GLM 5.3 (cortecs/glm-5.3),
Review von GLM-Arbeit = **immer** DeepSeek V4 Pro 0813 (cortecs/deepseek-v4-pro-0813).
Orchestrator macht keine inhaltliche Arbeit selbst, nur Beauftragung/Verifikation.
GLM-Sessions brechen nach ca. 20–25 Min ab → Aufträge klein schneiden (≤ 4 Dateien).

## Modell-IDs (verifiziert vorhanden)
- openai-codex/gpt-6.1-sol (Design + finaler Emulator-Review)
- moonshot/kimi-k3 (Plan + Analysen)
- cortecs/glm-5.3 (Coder)
- cortecs/deepseek-v4-pro-0813 (Reviewer der Coder-Arbeit)

## Umgebung
JDK 17, Gradle 8.10.2, /opt/android-sdk (API 35/36, NDK 27), KVM vorhanden.
- `<device>` (eigenes AVD `vk-api36`, API 36, IME installiert). **Blocker:** bringt den
  IME-Prozess beim Laden von `libonnxruntime.so` nativ per **SIGSEGV** um
  (dlopen/call_constructors, `am_anr 0`, nur Worker-Thread) → **Diktat/Engine dort nicht
  validierbar**; Tasten-/Gestenzustellung funktioniert (`q` → `qq`). Audio-Brücke **AUS**
  (`tools/emulator_audio_down.sh`), Peak = 0.
- `<device>` (fremder AVD, API 35) — **nicht anfassen**.
- Modelle/Test-WAVs auf 5560 unter `/data/local/tmp/…` (projektrelativ dokumentiert in
  `docs/PLAN_AUDIO_UND_LAYOUT.md`).

## Auslieferung (Stand v0.1.31, ausgeliefert 2026-11-02 01:40)
| Datei | Bytes | SHA-256 (Kurz) |
|---|---|---|
| `dist/voice-keyboard-0.1.31.apk` | 40 399 584 | `367165ea…` |
| `dist/voice-keyboard-0.1.31-diag-debug.apk` | 102 459 938 | `2ee37322…` |

Beide APKs mit **beiden ABIs**; `dist/` enthält die 0.1.31-APKs + `SHA256SUMS-0.1.31.txt`
(0.1.30 rotiert; aapt: versionCode 32 / versionName 0.1.31).
**Versionstabelle:** 0.1.28 interim = F38–F44, 0.1.29 = +F45, 0.1.30 = F46+F49–F52
(beide rotiert), **0.1.31 = F53 + 900-s-Haltegrenze + F50-/Fehlerpfad-Fixes**
(Nutzer-Vertrag-Fortsetzung). Der Diag-Bau ist größer, weil debug
`abiFilters += listOf("x86_64","arm64-v8a")` (`app/build.gradle.kts:35`); die x86_64
`libonnxruntime.so` allein wiegt 40,8 MB — der Release-Bau trägt nur arm64-v8a (Zeile 33).

Download: `http://<download-server>/api/file?path=Voice-Keyboard/dist/<name>&download=1`
(`curl -I` → 200). Ältere APKs wurden aus `dist/` entfernt.

**GitHub:** `tsii/Voice-Keyboard`, `main` = `dc3b4f0` (231 Dateien, Inhalt F13a-e + D11/F15);
**v0.1.14 … v0.1.31 noch nicht gepusht (Regel 10, warten auf Nutzer-Freigabe).**
Signier-Passwort geschwärzt; `keystore/`, `dist/`, `state/`, `testdata/`, `.models-fp*` per
`.gitignore` ignoriert; 11,8 GiB `.git`-Müll entfernt. **Push weiterer Änderungen nur nach
Nutzer-Freigabe (Regel 10).**

## Testbestand (gemessen)
`app/build/test-results/testDebugUnitTest`: **513 Tests / 0 Fehler** +
`app/build/test-results/testReleaseUnitTest`: **482 Tests / 0 Fehler**
= **995 / 0** gesamt (Beleg: Summe der Ergebnis-XMLs, Stand 0.1.31).
Layout-Status: **15/15 Layouts** (base+shift) mit **einheitlicher Fußzeile** (Σ 13, keine
`cursor-*`-Tasten, Punkt-Menü mit `cellActions` — F24e, Zielbild
`docs/LAYOUT_REQUIREMENTS.md` §2.3); Globus-Sprachwechsel F25 verdrahtet
(`ime/VoiceKeyboardService.kt:979/:1022`); nächster Bau **33 / 0.1.32**.

## F27 Stock-Geometrie (fertig)
Alle **15 Layouts** auf EINEM Raster (Buchstabe = 1u = **90 px** bei 1080 px Breite):
- **r0** = 10 Buchstaben + ⌫ 2u
- **r1** = Buchstaben + MARGIN (9er-Reihen 1.5u / 10er 1.0u / 11er 0.5u)
- **r2** = ⇧ 2u + Buchstaben + ⏎ 2u (+ MARGIN)
- **r3** = symbols 1.5 / language 1 / emoji 1 / **space 7** / **period 1** / **mic 1.5**
  (Σ 13, **KEIN** enter in der Fußzeile)
- Akzente als **`alternates[0]`** statt Direkt-Tasten (**D26**); **ru-ru** Ziffernreihe
  ⇧1 + 10 Ziffern + ⏎1 (**D27**).

Gerätebelege (Emulator, VK-HIT): `z`/`a`/`b` = je **90 px** Zeilensprung; space **582 px**
(7u); **period 872–955 px** direkt vor **mic 955–1080 px**.

## F28 Diktat-Diagnose (in 0.1.18/0.1.19)
Kurze Faktenliste:
- **F26a/b Messfelder** `abort_reason` + `hyp_last_buffer_s` (Namen in
  `debug/DebugInfoFormatter.kt`, Drift-Check aktiv).
- **Kimi-Analyse** `docs/ANALYSE_DIKTAT_ABBRUCH_2.md` zu Export #2 (0.1.17, api 37):
  **kein 1-s-Timer im Code**; Stop lief über einen **echten UP-Pfad bei ~1,2 s** (aus
  `first_hyp_ms`/flush-Semantik rechnerisch belegt); **−56,2 dBFS echt** (gemessen am
  rohen Capture, kein Messfehler); `read()=0` loopet **endlos ohne Fehler** (Code-Lücke);
  `rtf 0.00` = **Messblindspot** (Final-Decode zählt nicht in `processingMs`).
- **F28a/a2 Popup-Geometrie:** r0-Palette **über der Taste** (Root-Overlay), Fußzeile via
  **space-Erkennung** statt Hard-Coded-Index, r3-Top ≈ **265 px** (0.1.16-Position).
- **F28b1 Stall-Watchdog** **3000 ms** ohne Capture-Block → sichtbarer Fehler statt
  „Diktat-Ewigkeit" (`ime/DictationCoordinator.kt`: `ERR_STALL`/`STALL_LIMIT_MS = 3000`);
  neue 🐞-Felder **`cap_blocks`** / **`cap_stall_ms`**.
- **F28c FGS-Ready-Handshake** vor `AudioRecord`-Start (`ime/VoiceKeyboardService.kt`:
  `fgsReady`-Condition), **notif-Gate ab api 33** + `notif=-`-Bugfix.
- **F30 (in 0.1.23+):** `app/src/debug/java/de/voicekeyboard/audio/FakeWavAudioSource.kt`
  (Debug-only, zeitgetreue 10-ms-Blöcke aus WAV; Szenarien durchgehend/Stille/Aussetzer/
  `stopAfterMs`) + `FakeAudioScenarioTest` (8 Fälle a–h, u. a. Fall d = Nutzerfall
  `stopAfterMs=1200` → `abort_reason final`, `audio_s ≈ 1,2`) — Nutzerfall deterministisch
  nachstellbar; Engine-Decode auf dem Emulator bleibt x86_64-SIGSEGV-blockiert.
- **F31 (in 0.1.23+):** Stille-Erkennung — `lastLoudAtMs`, **`ERR_SILENT "silence"** wenn
  Blöcke laufen aber > 3000 ms kein lautes Signal (`DictationCoordinator.kt:337-338`,
  `SILENCE_RMS = 0,01` ≈ −40 dBFS `:100`), Meldung `dictation_error_no_signal`,
  neues 🐞-Feld **`loud_s`**.
- **F31b (in 0.1.24, Review-P1-Fix):** `everLoud`-Latch (`DictationCoordinator.kt:218/
  300/330-332`, Gate `:361-362`) — `ERR_SILENT` feuert nur, solange **noch nie ein lauter
  Block** kam ⇒ keine Fehlabbrüche bei natürlichen Sprechpausen; Rest-Risiko als Note
  dokumentiert (ein lauter Störknack schaltet die Frühdiagnose dauerhaft ab). Daneben:
  **`FGS_READY_TIMEOUT_MS` 2 s → 4 s** (`VoiceKeyboardService.kt:149`) und **Layout-Gates
  auf Einzelwerte** (r1[0]=0,5u, genau ein „@" je Ebene, ru-ru ohne @-Taste +
  `period.alternates[0]="@"`, r2[0] shift=1, space 6,5/mic 2/period 1) — 3 neue Tests.
- **Review** `docs/REVIEW_F28_F31.md` (F28–F31): ursprünglich P0=0/P1=1/P2=3 → nach F31b
  **P0=0 · P1=0 · P2=1** (offen: Popup-Clip/`isFooterRow`-Randfall) — Urteil
  **push-fähig: JA**.

**Offene Punkte:** 🐞-**Export #3 fehlt** (entscheidend für die Mikro-/FGS-Ursache,
Entscheidungsbaum in `docs/ANALYSE_DIKTAT_ABBRUCH_2.md` §9);
**Geräteprüfung r3-Geometrie offen** (Emulator-AVD belegt); **Mikro-Frage an den Nutzer
offen** (Halt-Dauer bei Export #2, Mikro in anderen Apps — `ANALYSE_…` §10);
P2-Note: Popup-Clip/`isFooterRow`-Randfall (`REVIEW_F28_F31.md`).

## F35 „Weg D“ — Korpus-Takt-Tests (grün, in 0.1.27)
- **`KorpusFixture`** (30 s/60 s aus `testdata/de_smoke.wav` + `de_part1/2` + `de_pause`,
  0,3-s-Stille-Schleifen) + **Stub-Engine mit konstanter 150-ms-Latenz** (injizierbare Uhr)
  + **`KorpusTaktTest` mit 7 Assertions** (`app/src/test/java/de/voicekeyboard/dictation/`):
  `hyp_count` wächst, `hyp_gap_ms < 4000`, `buffer_s == audio_s`, `decode_ms_max < 1000`,
  `cap_blocks`/`loud_s > 0`, `abort_reason` erst am Ende, kein `stall`/`silence`.
- **Befunde:** (1) **Watchdog-Uhr-Race im 60-s-Fall** — gefixt: die Stub-Latenz pumpt jetzt
  ihre **eigene Uhr**; (2) die **Controller-Uhr ist im Coordinator NICHT injizierbar** →
  **F37-Follow-up** (nötig für Weg B, s. `docs/KORPUS_DIKTAT_TESTPLAN.md` §5).
- **Review F35:** P0/P1 = 0, P2 = 2 — Push-Empfehlung **JA**.

## F36 — V2-Tastatur (grün, in 0.1.27)
- **1u = 108 px** (vorher 90); **r0–r2 Σ 10u** (r0 = 10 Buchstaben randlos ohne ⌫,
  r1 = 0,5 + 9×0,9444 + @1,0, r2 = ⇧1,8889 + 7×0,8889 + **⌫0,8889** + ⏎1,0 — ⌫ zwischen
  M und ⏎); Footer bleibt Σ 13u. Flächen/Fugen/Radius: 2-px-Fuge (`KEY_FUGE_DP`),
  Radius 10 px, Theme-Farben wie `docs/DESIGN_TASTATUR_V2.md` §7; **⌫-Glyph = reiner
  Links-Pfeil mit Sockel** (Pfad-Icons ⌫/⇧/⏎).
- **Sonderfälle (eigens gerendert, `docs/preview/keyboard_v2_impl_sonderfaelle.png`):**
  fr-fr = de-Muster (0,5 + 9×0,9444 + @1,0, p in r0), cs-cz r1 = 0,5 + 10×0,85 + @1,0
  und r2 = 204 + 6×112 + ⌫96 + ⏎108 (Mitte 540), ru-ru 11er-Reihen 11×96 + 2×12 px und
  **r2 bleibt D27/Σ 12** (⇧1 + 10 + ⏎1, ⌫ nur symbols-Ebene).
- **Abgleich Umsetzung vs. Spec: 6/6 ✓** (`docs/DESIGN_TASTATUR_V2.md` §10, Belege
  `layouts/*.json` + `keyboard/KeyboardTheme.kt:82-86/131-155` + `KeyboardView.kt`);
  Previews `docs/preview/keyboard_v2_impl_{hell,dunkel}.png` + `.gif` (5 Frames).
- **Review F36:** P0/P1 = 0, P2 = 1 — Push-Empfehlung **JA**.

## F46 + F49–F52 (in 0.1.30; Plan `docs/PLAN_V0130.md` Schnitt A + §6)
- **F46:** ⇧-Aufwärts-Pfeil-Glyph drin (V9).
- **F49 (A1) = Auto-Stop-Hold-Gate:** `autoStopSuppressed` — während **Halten** wird der
  F43-Auto-Stop (2,5 s) **unterdrückt** (Halten = kontinuierlich aufnehmen, **Nutzervertrag**);
  der **Toggle-Pfad bleibt „Pause=Ende“** (2,5 s). Orakelbeweis: `DictationHoldGateTest`
  (`app/src/test/java/de/voicekeyboard/ime/`).
- **F50 (A2) = Start-Serialisierung:** startJob-Cancel, Supersede, `lastStartMs`-Schutz
  (kein Doppelstart); **F50a:** UP-Hinweis „Loslassen vorgemerkt“
  (`dictation_start_pending_stop`); F49-Wiring `holdGestureActive`.
- **F51 (K2):** 4 Strings in `strings.xml` (inkl. FGS-Titel).
- **F52 (A3) = `status_res`:** StatusKind-Enum + 🐞-Export-Feld + `status_res` im
  Formatter; hint-Abkürzung = **P2-Follow-up** für 0.1.31.
- **Review A1–A3:** **P0 = 0 / P1 = 0, P2 = 2** (Follow-ups für 0.1.31: defensiver
  `holdGestureActive`-Reset bei `onAborted`; `hint:<res-name>`); Push-Empfehlung **JA**.
- **Smoke-Transparenz (0.1.30):** Emulator-App-Mikrofonpfad nach ~2 Sessions instabil
  (tinycap/host OK, App-Stream −84 dBFS) → **182-s-Holdbeweis auf Emulator nicht erbracht**;
  Mini-Sessions mit **echter Transkription** (66 Zeichen, 251 Blöcke) grün; finale
  Bestätigung via Nutzer-🐞-Export (`status_res`, `fgs_stopped_by=up` bei Halten).

## F53 + Halte-Obergrenze + Fehlerpfad-Fixes (in 0.1.31; Nutzer-Vertrag-Fortsetzung)
- **F53 = Zwischensegment-Finalisierung:** `segmentFinalizeMs = 35 000` **scharf**
  (`dictation/DictationController.kt`); α = **38,5 ms/s** Kurve ⇒ Decode-Fenster ≤ 35 s ⇒
  Hyp-Latenz ≤ **~1,4 s**, **kein RSS-/Zeit-Exponential**; **§5.1-konform**: `whole_buffer`
  wird nur **segmentiert** (kein Verstoß gegen die gesperrte windowed-Streaming-Frage);
  Kette golden: **`SegmentGoldenTest` „alpha. bravo. charlie.“ exakt**;
  (b)-Zwangsschnitt-Trade-off = **genau ein Leerzeichen** (KDoc-dokumentiert).
- **Halte-Obergrenze 900 s** für **Hold + Toggle** mit **einmaligem Warn-Hinweis ab 780 s**
  (`holdWarnDue` im Coordinator; **`HoldWarnTest` 5/0**).
- **F50-Fix:** `ConsumerFaultTest`-Last-Flaky **an der Wurzel** behoben — der Poll auf den
  Null-Sentinel war No-Op → **Endzustands-Poll**; 2× Vollbild grün.
- **finalizeSegment-Fehlerpfad:** **transient ⇒ Antrag verwerfen, Session lebt** (nur OOM
  hart) — Review-P1 behoben. **B2b:** `onAborted`-Resets (das F49-P2-Follow-up von 0.1.30).
- **Reviews A/B1/B2: P0/P1 = 0** — Push-Empfehlung **JA**.
- **Smoke-Transparenz (0.1.31) — Emulator-Audio-GESETZ:** **nur die ERSTE App-Session nach
  Kaltstart liefert Ton** (jede weitere: App-Stream −84 dBFS; tinycap/host OK;
  audioserver-Neustart/`emu mic` wirkungslos) → **92-s-Haltebeweis auf Emulator nicht
  erbracht**; vc32-Erste-Session transkribierte **real** (93 Zeichen, peak −17 dBFS,
  `cap_blocks` 280). Google-Onboarding-Plage bereinigt: `messaging`/`gms`/`calendar`/
  `quicksearchbox` jetzt `disabled-user`. Finale Halte-Beweisführung via Nutzer-🐞-Export
  (`fgs_stopped_by`, `status_res`).

## F38–F45 (in 0.1.28 interim / 0.1.29)
- **F38–F44 (0.1.28, interim, dist-rotiert):** Diktat-Debugging-Pakete um die Auto-Stop-
  Diagnose (u. a. **F42 = 6 neue 🐞-Felder**: `had_output`, `output_chars`,
  `first_loud_ms`, `first3s_loud_blocks`, `first3s_peak_dbfs`, `fgs_stopped_by` —
  Katalog in `docs/DEBUG_INFO_FIELDS.md`; Sanitizer wandert `:`→`=`,
  `DebugInfoFormatter.kt:250`; Export = Kopf + **42 Felder = 43 Zeilen**, Drift-Check
  `check(lines.size == KEYS.size + 1)`); **F43 = Auto-Stop nach ~2,5 s Sprechpause**
  (`TRAILING_SILENCE_MS = 2500`, `ime/DictationCoordinator.kt:97/436-439`, c-Gate:
  **nur bei streamender Stille** `capStallMs < TRAILING_SILENCE_MS` — „Stille ≠ Stall",
  `ERR_STALL`-Vertrag F28b1 intakt; dokumentierte Präzisierung, s. `KONZEPT.md`);
  **F44 = `fgs_stopped_by`**-Ursachencodes.
- **F45 (0.1.29):** **FGS-Leak auf dem Auto-Stop-Pfad gefixt** — `stopFgs("autostop")`
  im onStopped-Finale (`VoiceKeyboardService.kt:1356-1360`; idempotent, überschreibt
  keinen bestehenden Grund).
- **Konzept-Treue (F43):** Auto-Stop nach ~2,5 s Sprechpause ist eine **vom Nutzer
  ausdrücklich gewünschte Präzisierung** (dokumentiert in `docs/KONZEPT.md` als
  „Dokumentierte Präzisierung F43“, die übrigen KONZEPT-Inhalte unverändert).

## Fertige Artefakte in docs/
- `KONZEPT.md` (Vorgabe des Nutzers, **nicht ändern**)
- `DESIGN.md`, `IMPLEMENTATION_PLAN.md` (M0–M8), `TECH_FACTS.md`
- `ASR_CONTRACT.md` (Rev 3: Tensorvertrag, TDT-Greedy, Token-ID-Goldens, §4.4 → `min_audio_s = 2,0 s`)
- `LAYOUT_REQUIREMENTS.md` (Rev 3 + Regel 4a Symbolebenen-Rückweg), `LAYOUT_SCHEMA_REF.md`,
  `LAYOUT_AUDIT.md`
- `COMMIT_RULE_DECISION.md`, `COMMIT_RULE_DECISION_2.md` (E8, Rev 2)
- `SETUP_UI_DECISIONS.md` (E1–E4), `ERROR_UX_DECISIONS.md` (E5–E7), `LANGUAGE_EMOJI_DECISIONS.md` (E9/E10)
- `DEBUG_INFO_FIELDS.md` (🐞-Export, 29 Felder), `MULTILANG_PLAN.md`, `PLAN_AUDIO_UND_LAYOUT.md`
- `DESIGN_KEY_MULTIPLEX.md` (A19 Rev 2, AOSP-Belege), `DESIGN_KEY_POPUP.md` (D12-Menü),
  `DESIGN_DEDE_ZIELBILD.md` (F23-Datenvorlage), `LAYOUT_REQUIREMENTS.md` §2.2 (R8-Ausnahme de-de)
- `GPU_ACCELERATION_ANALYSIS.md`, `RUNTIME_SWITCH_NPU_ASSESSMENT.md`, `NPU_LITERT_FEASIBILITY.md`,
  `MODEL_VARIANT_SWITCH_MEMO.md`, `VARIANT_B_QUALITY.md`, `ASR_MODEL_SURVEY_DE.md`
- `DICTATION_RELIABILITY_PLAN.md`, `ERROR_STOP_PATH.md`, `STREAMING_FREEZE_ANALYSE.md`
- `CONTINGENCY_PLAN.md` (§12/§13 Betriebsregeln), `MODEL_SHA256.txt`
- Reviews: `REVIEW_STAGE1…6` + `_FIXES`, `REVIEW_STAGE4B/4C`, `REVIEW_STAGE2B1`, `FINAL_REVIEW_M7`,
  `REVIEW_F17_MESSBARKEIT.md` (P0=0/P1=0/P2=2 behoben), `REVIEW_F21_DECODE_PACE.md` (0/0/1, P2 behoben),
  `REVIEW_F22_POPUP.md` (0/0/0), `REVIEW_F23_HINT_LAYOUT.md` (0/0/1; **einziger P2 = FALSE
  POSITIVE, geprüft verworfen** — base/shift-Gate vorhanden in `KeyboardView.kt:393`)
- Tools: `tools/reference_decode.py`, `tools/make_cut_windows.py`, `tools/measure_min_audio.py`,
  `tools/arm_litert_reference_decode.py`, `tools/normalize_symbol_shift.py`,
  `tools/emulator_audio_down.sh`

## Verifizierter Code-Stand (app/src/main/java/de/voicekeyboard/)
- `ime/`: VoiceKeyboardService, ImeRootView, **StatusBarText**, **StatusRouting**,
  **MicToggle**, **DictationOutputCounter**, DictationCoordinator, DictationPermissions,
  SetupActivity (echte Setup-UI mit Download/Sprachauswahl/Berechtigungen)
- `keyboard/`: KeyboardView (Ebenen base↔symbols↔symbolsShift, Accessibility, Pointer-Capture,
  **Langdruck-Menü F22**),
  KeyboardModel, **KeySurface** (reine Naht für Tastendruck-/Caps-Optik, D11/F15),
  **LongPressMenu** (F22, `MAX_CELLS = 8`),
  KeyboardTheme, KeyboardHeight, EmojiPanelView
- `layouts/`: LayoutAsset, LayoutParser, LayoutRepository (Asset-Scan), CaseMapper
- `asr/`: ParakeetAsrEngine, OnnxSessions, TdtGreedy, Hypothesis (`AsrEngine`-Interface),
  AsrThreadConfig, TokenBudget, Vocabulary
- `commit/`: LocalAgreement, CommitPolicy (E8-Hysterese)
- `editor/`: DeltaTyper (Rollback W3c), InputConnectionSink, OwnershipTracker
- `dictation/`: DictationController (enthält `StopOutcome`), **DecodePace** (F21-Takt),
  DictationState
- `model/`: ModelStore, ModelManifest, ModelDownloader, DownloadController, ModelVariant,
  ModelDownloadService (dataSync-FGS), ModelError
- `capture/`: CaptureForegroundService (`microphone`)
- `debug/`: DebugInfoFormatter (29 KEYS; F21-Felder `decode_pace_ms`/`decode_skips`,
  Export = Kopf + 29 = 30 Zeilen),
  DebugInfoCollector, LevelPeak (RMS/dBFS)

## Offene Punkte (Stand 2026-10-30)
| # | Inhalt | Status |
|---|---|---|
| C2 | **Engine-Rückstau** (`rtf 0,44`, erste Hypothese 5 339 ms) als Erklärung für fehlenden Text nach 10–15 s Dauerdiktat; Ursache: `window()` = `PcmWindow(pcm.copyOf(totalSamples), sampleRateHz)` dekodiert pro Ticker das komplette bisherige Audio → gesamt quadratisch (`dictation/DictationController.kt` `window()`) | **gemildert, Ursache offen** — F21-Takt ist DRIN in 0.1.15 (`dictation/DecodePace.kt`, Gate nur Partial `DictationController.kt:255-259`, Finale frei `:333`, `decodePacing=true` `DictationCoordinator.kt:130`); Wirkung am Telefon zu messen (`decode_skips`/`decode_pace_ms`); echte Gegenmassnahme (windowed-Streaming) wartet auf Nutzerfreigabe (`KONZEPT` §5.1) |
| — | M7-Screenshots (Caps, gedrückte Taste, Emoji-Panel, zweite Öffnung, hell + dunkel) | **erledigt** (8 Abnahme-Fälle, hell + dunkel; Bilder nur unter `/tmp/m7_*.png`, bewusst nicht im Repo) |
| — | fp16-Variante | **geprüft und verworfen** (`docs/FP16_ABWEGUNG.md`): 1,90× Größe (1 275 463 785 B), Qualität **identisch** zu int8 (gleiche Token-IDs), Compute-RTF **1,30×** und Startzeit **2,13×** langsamer, maxRSS beim Laden **924 → 3 838 MB**; zudem sind die vorhandenen `.models-fp16/`-Dateien **nicht ladbar**  (3 Cast-Knoten im `pre_encode`, Typkonflikt) |
| — | Englisch/Tippen über Sprachgrenze | **Nutzerwunsch, zurückgestellt** → `docs/MULTILANG_PLAN.md` |
| — | NPU/GPU | abgeschlossen: ohne Google-Dienste nicht erreichbar (drei Analysen) |
| — | Variante B (Arm/LiteRT) | **abgelehnt** (50,2 % Wort-Identität, sprachinstabil) |

## Geklärt (nicht erneut untersuchen)
- Mikro-Pegel am Telefon **in Ordnung** (`peak_dbfs −22,0`, `peak_pct 7`, `route intern`,
  `src VOICE_RECOGNITION`) → AudioSource-Experiment hinfällig.
- Kein Sample-Verlust bei gesundem Consumer (`Channel.UNLIMITED`).
- Kein `maxDuration`-Abschaltverdacht.

## Historie (überholt)
- v0.1.0 … v0.1.14 waren frühere Auslieferungen; v0.1.3 (40 242 852 B, versionCode 4) war
  bis 2026-10-28 der dokumentierte Stand — **ersetzt durch v0.1.15**.
- Testbestände 157 / 204 / 213 / 217 / 279 / 373 / 353 / 726 / 377 / 357 / 734 gehörten zu
  früheren Stufen — **heute 513 Debug / 482 Release = 995/0** (Stand 0.1.31).
- `SetupActivity` war bis Stufe 5 ein Platzhalter — **heute echte Setup-UI**.
- `LAYOUT_SCHEMA_REF.md` galt als „noch nicht reviewt" — **inzwischen auditiert** (`LAYOUT_AUDIT.md`).
