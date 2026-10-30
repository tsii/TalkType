# Projektstand Voice-Keyboard (Arbeitsdatei für Fortsetzung)

**Stand: 2026-10-30 · v0.1.13 / versionCode 14** (nächster Build 15 / 0.1.14). Aktuelle Details siehe `HANDOFF.md`,
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

## Auslieferung (Stand v0.1.13)
| Datei | Bytes | SHA-256 (Kurz) |
|---|---|---|
| `dist/voice-keyboard-0.1.13.apk` | 40 327 468 | `20d36569…b344` |
| `dist/voice-keyboard-0.1.13-diag-debug.apk` | 53 123 218 | `89c91aa6…76df` |

Download: `http://<download-server>/api/file?path=Voice-Keyboard/dist/<name>&download=1`
(`curl -I` → 200). Ältere APKs (0.1.3 … 0.1.11) wurden aus `dist/` entfernt.

**GitHub:** `tsii/Voice-Keyboard`, `main` = `7f917c8` (219 Dateien); Signier-Passwort
geschwärzt; `keystore/`, `dist/`, `state/`, `testdata/`, `.models-fp*` per `.gitignore`
ignoriert; 11,8 GiB `.git`-Müll entfernt. **Push weiterer Änderungen nur nach
Nutzer-Freigabe (Regel 10).**

## Testbestand (gemessen)
`app/build/test-results/testDebugUnitTest`: **373 Tests / 0 Fehler** (49 Ergebnis-Dateien) +
`app/build/test-results/testReleaseUnitTest`: **353 Tests / 0 Fehler** (47 Ergebnis-Dateien)
= **726 / 0** gesamt (neu: `keyboard/KeySurfaceTest.kt` in `src/test`, zählt in beiden Varianten).

## Fertige Artefakte in docs/
- `KONZEPT.md` (Vorgabe des Nutzers, **nicht ändern**)
- `DESIGN.md`, `IMPLEMENTATION_PLAN.md` (M0–M8), `TECH_FACTS.md`
- `ASR_CONTRACT.md` (Rev 3: Tensorvertrag, TDT-Greedy, Token-ID-Goldens, §4.4 → `min_audio_s = 2,0 s`)
- `LAYOUT_REQUIREMENTS.md` (Rev 3 + Regel 4a Symbolebenen-Rückweg), `LAYOUT_SCHEMA_REF.md`,
  `LAYOUT_AUDIT.md`
- `COMMIT_RULE_DECISION.md`, `COMMIT_RULE_DECISION_2.md` (E8, Rev 2)
- `SETUP_UI_DECISIONS.md` (E1–E4), `ERROR_UX_DECISIONS.md` (E5–E7), `LANGUAGE_EMOJI_DECISIONS.md` (E9/E10)
- `DEBUG_INFO_FIELDS.md` (🐞-Export, 24 Felder), `MULTILANG_PLAN.md`, `PLAN_AUDIO_UND_LAYOUT.md`
- `GPU_ACCELERATION_ANALYSIS.md`, `RUNTIME_SWITCH_NPU_ASSESSMENT.md`, `NPU_LITERT_FEASIBILITY.md`,
  `MODEL_VARIANT_SWITCH_MEMO.md`, `VARIANT_B_QUALITY.md`, `ASR_MODEL_SURVEY_DE.md`
- `DICTATION_RELIABILITY_PLAN.md`, `ERROR_STOP_PATH.md`, `STREAMING_FREEZE_ANALYSE.md`
- `CONTINGENCY_PLAN.md` (§12/§13 Betriebsregeln), `MODEL_SHA256.txt`
- Reviews: `REVIEW_STAGE1…6` + `_FIXES`, `REVIEW_STAGE4B/4C`, `REVIEW_STAGE2B1`, `FINAL_REVIEW_M7`
- Tools: `tools/reference_decode.py`, `tools/make_cut_windows.py`, `tools/measure_min_audio.py`,
  `tools/arm_litert_reference_decode.py`, `tools/normalize_symbol_shift.py`,
  `tools/emulator_audio_down.sh`

## Verifizierter Code-Stand (app/src/main/java/de/voicekeyboard/)
- `ime/`: VoiceKeyboardService, ImeRootView, **StatusBarText**, **StatusRouting**,
  **MicToggle**, **DictationOutputCounter**, DictationCoordinator, DictationPermissions,
  SetupActivity (echte Setup-UI mit Download/Sprachauswahl/Berechtigungen)
- `keyboard/`: KeyboardView (Ebenen base↔symbols↔symbolsShift, Accessibility, Pointer-Capture),
  KeyboardModel, **KeySurface** (reine Naht für Tastendruck-/Caps-Optik, D11/F15),
  KeyboardTheme, KeyboardHeight, EmojiPanelView
- `layouts/`: LayoutAsset, LayoutParser, LayoutRepository (Asset-Scan), CaseMapper
- `asr/`: ParakeetAsrEngine, OnnxSessions, TdtGreedy, Hypothesis (`AsrEngine`-Interface),
  AsrThreadConfig, TokenBudget, Vocabulary
- `commit/`: LocalAgreement, CommitPolicy (E8-Hysterese)
- `editor/`: DeltaTyper (Rollback W3c), InputConnectionSink, OwnershipTracker
- `dictation/`: DictationController (enthält `StopOutcome`), DictationState
- `model/`: ModelStore, ModelManifest, ModelDownloader, DownloadController, ModelVariant,
  ModelDownloadService (dataSync-FGS), ModelError
- `capture/`: CaptureForegroundService (`microphone`)
- `debug/`: DebugInfoFormatter (24 KEYS), DebugInfoCollector, LevelPeak (RMS/dBFS)

## Offene Punkte (Stand 2026-10-30)
| # | Inhalt | Status |
|---|---|---|
| C2 | **Engine-Rückstau** (`rtf 0,44`, erste Hypothese 5 339 ms) als Erklärung für fehlenden Text nach 10–15 s Dauerdiktat | **offen, jetzt messbar** (`hyp_gap_ms`/`ctl_state`/`dropped`); Entscheidung aus dem nächsten 🐞-Export |
| — | Rest-Screenshots (Caps, gedrückte Taste, Emoji-Panel, zweite Öffnung, hell + dunkel) | offen |
| — | fp16-Variante | **pausiert** (`.models-fp16`, 1,2 GB) |
| — | Englisch/Tippen über Sprachgrenze | **Nutzerwunsch, zurückgestellt** → `docs/MULTILANG_PLAN.md` |
| — | NPU/GPU | abgeschlossen: ohne Google-Dienste nicht erreichbar (drei Analysen) |
| — | Variante B (Arm/LiteRT) | **abgelehnt** (50,2 % Wort-Identität, sprachinstabil) |

## Geklärt (nicht erneut untersuchen)
- Mikro-Pegel am Telefon **in Ordnung** (`peak_dbfs −22,0`, `peak_pct 7`, `route intern`,
  `src VOICE_RECOGNITION`) → AudioSource-Experiment hinfällig.
- Kein Sample-Verlust bei gesundem Consumer (`Channel.UNLIMITED`).
- Kein `maxDuration`-Abschaltverdacht.

## Historie (überholt)
- v0.1.0 … v0.1.11 waren frühere Auslieferungen; v0.1.3 (40 242 852 B, versionCode 4) war
  bis 2026-10-28 der dokumentierte Stand — **ersetzt durch v0.1.13**.
- Testbestände 157 / 204 / 213 / 217 / 279 gehörten zu früheren Stufen — **heute 368 Debug**.
- `SetupActivity` war bis Stufe 5 ein Platzhalter — **heute echte Setup-UI**.
- `LAYOUT_SCHEMA_REF.md` galt als „noch nicht reviewt" — **inzwischen auditiert** (`LAYOUT_AUDIT.md`).
