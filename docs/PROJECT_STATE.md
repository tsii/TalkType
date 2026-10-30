# Projektstand Voice-Keyboard (Arbeitsdatei für Fortsetzung)

Ziel: Android-IME für GrapheneOS (API 36), 15 Sprachen, Offline-Diktat (Parakeet TDT 0.6B ONNX).
Auftrag des Nutzers: Design = GPT 6.1 Sol, Plan = Kimi K3, Code = GLM 5.3 (cortecs/glm-5.3),
Review von GLM-Arbeit = **immer** DeepSeek V4 Pro 0813 (cortecs/deepseek-v4-pro-0813).
Analysen = Kimi K3, außer explizit GPT 6.1 Sol verlangt. Orchestrator macht keine inhaltliche
Arbeit selbst, nur Beauftragung/Verifikation. GLM-Sessions brechen nach ca. 20–25 Min ab →
Aufträge klein schneiden (max. ~1 Datei-Bündel).

## Modell-IDs (verifiziert vorhanden)
- openai-codex/gpt-6.1-sol (Design + finaler Emulator-Review)
- moonshot/kimi-k3 (Plan + Analysen)
- cortecs/glm-5.3 (Coder)
- cortecs/deepseek-v4-pro-0813 (Reviewer der Coder-Arbeit)

## Umgebung
JDK 17, Gradle 8.10.2, /opt/android-sdk (API 35/36, NDK 27), KVM vorhanden.
Emulatoren: `<device>` (eigenes AVD vk-api36, API 36, IME installiert, Mikrofon deaktiviert
mit `-no-audio`), `<device>` (fremder AVD, API 35). Modelle + Test-WAVs liegen auf 5560
unter `/data/local/tmp/models` bzw. `/data/local/tmp/testdata`.

## Fertige Artefakte in docs/
- `KONZEPT.md` (Vorgabe des Nutzers, nicht ändern)
- `DESIGN.md` (GPT 6.1 Sol, 688 Zeilen, inkl. Konzept-Treue-Tabelle)
- `IMPLEMENTATION_PLAN.md` (Kimi K3, M0–M8)
- `TECH_FACTS.md` (verifiziert; von Kimi korrigiert: ORT-AAR-Größen, Permissions, API-36-FGS-Regeln)
- `ASR_CONTRACT.md` (Rev 3: Tensorvertrag, TDT-Greedy mit 3 Gegenproben, Token-IDs-Goldens,
  §4.4 empirische min_audio_s-Messung → **min_audio_s = 2,0 s**, Guard nur für Partials)
- `LAYOUT_REQUIREMENTS.md` (Rev 3: Sprachmatrix, Pflichtzeichen-Gruppen, Regel 5a–5f als Whitelist)
- `LAYOUT_SCHEMA_REF.md` (kompakte Schema-Referenz für Coders; **noch nicht von Kimi reviewt**)
- `REVIEW_STAGE1.md`, `REVIEW_STAGE1_FIXES.md` (DeepSeek-Findings)
- `MODEL_SHA256.txt`
- `tools/reference_decode.py` (lauffähiger Python-Referenzdecoder, Rev 2 nach Kimi-Fix)
- `tools/make_cut_windows.py`, `tools/measure_min_audio.py` (Kimi-Messreihe)

## Verifizierter Code-Stand (app/src/main/java/de/voicekeyboard/)
- `ime/` VoiceKeyboardService, ImeRootView, SetupActivity (Platzhalter)
- `keyboard/` KeyboardView (Ebenenwechsel base↔symbols, AccessibilityNodeProvider, Pointer-Capture),
  KeyboardModel
- `layouts/` LayoutAsset, LayoutParser, LayoutRepository, CaseMapper
- `asr/` AsrContract, TdtGreedy, TokenBudget, Vocabulary, audio/PcmWindow — 47 JVM-Tests grün
- `app/src/debug/.../DebugInputActivity` (nur Debug, für Emulator-Prüfung)
- Assets: **alle 15 Layouts vorhanden** (de, en, fr, es, it, pt, nl, pl, tr, ru, cs, sv, da, fi, el)
  – noch nicht gegen die Kotlin-Tests geprüft (Gradle-Lauf offen)

## Offene P0/P1 aus Reviews
- **P1 (DeepSeek): `symbolsShift` unerreichbar** — `KeyboardView` mappt `layer-symbols` auf
  `if (symbolShift) …` mit Zirkelschluss. Muss gefixt werden (Shift in Symbolebene → symbolsShift).
- `LayoutCompletenessTest` prüft noch Blacklist (`&`, `\` verboten) → auf Whitelist (Regel 5a–5f)
  umstellen, sonst schlagen die neuen Layouts fehl.
- Alte Mic-Taste doppelt (Statuszeile + Tastenreihe) — Statuszeilen-Mic entfernen/umfunktionieren.
- `de-de.json`: `symbolsShift`-Shift-Taste trägt laut einem Coder noch `layer-symbols` statt
  `layer-base`; ebenso en-us — prüfen.

## Nächste Schritte (in dieser Reihenfolge)
1. **Stufe 2b**: `asr/OnnxSessions.kt`, `asr/Hypothesis.kt`, `asr/ParakeetAsrEngine.kt` + ORT-Dependency
   + Manifest-Permission-Removal. Danach instrumented Golden-Test (exakte Token-IDs aus
   ASR_CONTRACT §4.1) gegen `/data/local/tmp/models`.
2. **Stufe 3b**: `KeyboardView`/`KeyboardController`: symbolsShift-Fix, Langdruck-`alternates`,
   Deadkeys, Emoji-Panel, Sprachwechsel (`language`-Action) inkl. Persistenz, Caps-/Shift-Politur.
3. **Layout-Testlauf**: `./gradlew test` gegen alle 15 Assets; Fehlschläge an GLM zurückgeben.
4. **Stufe 4**: Diktat-Pipeline — `audio/AudioSource`, `AudioRecordSource` (16 kHz mono),
   `PcmWindowStore`, `AudioRouter` (Headset/BT), `CaptureForegroundService`
   (`foregroundServiceType="microphone"`, POST_NOTIFICATIONS), `dictation/DictationController`
   (Zustandsmaschine, idempotent, Session-/Editor-Generation), `commit/LocalAgreement`
   (2 Bestätigungen, Wortgrenze), `editor/DeltaTyper` + `InputConnectionSink` + `OwnershipTracker`
   (max_revision_chars=60, committed-Präfix unantastbar, finale Vollkorrektur).
5. **Stufe 5**: `model/ModelStore` + Downloader (Resume, SHA-256 aus MODEL_SHA256.txt, atomarer Swap),
   SetupActivity/SettingsActivity (Sprache, Mikrofonquelle, Netzwerk-Schalter, Datenschutztext).
6. **Reviews**: jede Stufe → DeepSeek; Analysefragen → Kimi.
7. **Release**: `assembleRelease` mit `keystore/release.jks` (Alias voicekeyboard, Store-/Keypass
   `<in local.properties, untracked>`), Signatur prüfen, APK nach `dist/` kopieren.
8. **Finaler Emulator-Review**: GPT 6.1 Sol (Screenshots, Tippen, Diktat über injiziertes WAV).

## Download-Link-Mechanik (verifiziert)
pi-web läuft auf Port 8736 mit `--cwd <workdir>`. Direkter Download funktioniert über
`GET /api/file?path=<pfad>&download=1` (Content-Disposition attachment, absolute Pfade erlaubt).
Also: APK nach `<workdir>/dist/voice-keyboard-<version>.apk` legen und
`/api/file?path=Voice-Keyboard/dist/voice-keyboard-<version>.apk&download=1` verlinken.

## Subagent-Limit
Maximal 16 Einträge gleichzeitig; abgeschlossene Einträge blockieren weitere Starts.
Wenn das Limit greift: Kontext kompaktieren (setzt die Liste zurück) und danach weiterarbeiten.

## Release 0.1.0

- **APK:** `dist/voice-keyboard-0.1.0.apk`, 81 148 054 B,
  SHA-256 `54c74a45280aaa7a60549add2f5c5b25d08d88b60b1eb762aac4481c124abd8f`
  (Stand 01:26, enthält F3/F3-R; Vorgänger-Build 01:18 hatte `93afcb24…25b6`)
  (vom Orchestrator nachgemessen: `app-release.apk` und `dist/`-Kopie sind hash-identisch)
- **Signatur:** CN=Voice Keyboard, OU=Dev, O=Local, L=Local, ST=Local, C=DE
  (Cert-SHA-256 `760fb5a9129d8a056ac0fa30d9adfc7be6342648f608527143269a086039adeb`);
  seit M6-R trägt auch der **Debug-Build** diese Signatur, damit End-to-End-Tests ohne
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` auf demselben Gerät installierbar bleiben
  (Keystore `keystore/release.jks`, PKCS12, Alias `voicekeyboard`)
- **SDK:** minSdk 29, targetSdk 36, versionCode 1, versionName 0.1.0
- **Kein R8/Minify in v1:** ONNX-Runtime bindet per JNI und Modell-/Layout-Assets
  sind reine Ressourcen — Keep-Regeln nicht reviewed; Aktivierung erst nach
  separatem Review (IMPLEMENTATION_PLAN M8).
- **Signing-Mechanik:** Credentials in `local.properties` (`vk.storePassword`,
  `vk.keyAlias=voicekeyboard`, `vk.keyPassword`) mit Env-Fallback
  (`VK_STORE_PASSWORD`/`VK_KEY_PASSWORD`); keine Klartext-Passwörter in
  `build.gradle.kts`. Debug-Builds signieren mit derselben Release-Config
  (End-to-End-Gerätetests ohne Signaturkonflikt, Distribution unverändert).
- **Download:** `http://<host>:8736/api/file?path=Voice-Keyboard/dist/voice-keyboard-0.1.0.apk&download=1`


## v0.1.1 (2026-10-28, Stand 11:58)
- `dist/voice-keyboard-0.1.1.apk` · 81 148 470 Bytes · SHA-256 `9ba8f438398ce94bad0c8984304cb88124d4aff6035641bc162cc1d4862db7fb`
- versionCode 2 / versionName 0.1.1 / minSdk 29 / targetSdk 36, Signatur identisch zu v0.1.0 (`CN=Voice Keyboard`, Cert `760fb5a9…9adeb`) → Overwrite-Install ohne Datenverlust
- Gegenstand: R1 (localisierte Leer-Taste, 15/15 auf dem Geraet) + R2 (`availableIds()` per Asset-Scan) + Kanonik-Dopplung aufgeloest
- Review: `docs/SPACE_LABEL_DECISIONS.md` (E11, Werte) und `docs/REVIEW_STAGE9_I18N.md` (freigabefaehig, 0 P0 / 0 P1 / 1 P2)
- Offene P2 unveraendert: Accessibility-Namen deutsch (E11 c3, Restliste R8); `availableIds()` degradiert bei Scan-Ausfall auf `de-de`

## Stand 10:2x — v0.1.3 ausgeliefert, Rechenweg abgeschlossen
- **v0.1.3**: 40 242 852 B, versionCode 4, SHA-256 `680057718c785428ea60988e73b0a8394689d4fec7553c80cb599974c9ae86f5`, arm64-only, Download Range 206.
- **Variante B (Arm/LiteRT) abgelehnt**: 50,2 % Wort-Identitaet, CED 33,2 %, 1/4 Dateien englisch, Sprachwechsel durch 400 ms fuehrende Stille (`docs/VARIANT_B_QUALITY.md`). Referenz-Dekoder funktioniert technisch (RTF 0,082 auf x86_64) und liegt erhalten in `app/src/debug/java/...` + `tools/arm_litert_reference_decode.py`.
- **Modell-Entscheidung** (`docs/ASR_MODEL_SURVEY_DE.md`): **nichts aendern**. Grosse Modelle koennen auf unserem Golden nur gleich gut werden und kosten 2-4x; jeder Whisper-Pfad ist ein 30-s-Fenster-Modell (Architektur-K.o. fuer 0,3-1 s-Hypothesen, Halluzinationsneigung); CTranslate2 hat kein Android-AAR. fp16-Parakeet (~1,2 GB, 6,4 min bei 25 Mbit/s) nur in Betracht ziehen, wenn der **Pixel-RTF > 0,6** liegt.
- **Reviews**: Stufe 14 freigabefaehig (0 P0 / 0 P1 / 1 P2 — Haertung `preEncodeCache`-Vertrag, laeuft als V14).
- **Bekannte Stelle**: `buildCoordinator` erzeugt weiterhin direkt `ParakeetAsrEngine()`; `EngineFactory`/`LiteRtBridge` sind vorbereitet, aber im Produktionspfad nicht verdrahtet (bewusst, solange es keine zweite taugliche Engine gibt).
- Testbestand: Debug 279 / 0, Release 263 / 0.
