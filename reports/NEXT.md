# Nächste Aufgaben (Reihenfolge)

1. **Build wieder vollständig grün**: `./gradlew --offline test` → 157 Tests / 0 Fehler (Fix `model/ModelStore.kt` läuft).
2. **DeepSeek-Review M4b + M5a** (`docs/REVIEW_STAGE4B.md`), Findings abarbeiten bis keine P0/P1.
3. **M4c IME-Verdrahtung** (3 Mikro-Aufträge, ≤ 4 Dateien je Auftrag):
   a. `InputConnectionSink` an `currentInputConnection` binden, Editorwechsel → `onEditorChanged()`.
   b. `RECORD_AUDIO`-/`POST_NOTIFICATIONS`-Runtime-Grant + FGS-Start beim Mic-Taste-Druck (while-in-use-Regel: nur bei sichtbarer IME).
   c. `onUpdateSelection` → `DictationController`, Audio-Queue (Capture-Thread → Coroutine), Routen-/Fehleranzeige in `KeyboardView`.
4. **M4d Instrumentierter Diktat-Test**: WAV-Injektion (`de_pause.wav`) → Fake-ASR + `InputConnectionSink` in 3 Editorvarianten; eigener Auftrag, nur Instrumented.
5. **M5b Setup-UI + Download-Controller**: Reihenfolge `markDownloading → download → movePartToStaging → markVerifying → activate`, `DownloadProgress` → ProgressBar, INTERNET-Permission erst hier + Netz-Aus-Schalter, Datenschutzseite.
6. **M6 Release**: `assembleRelease` mit `keystore/release.jks`, Signatur + Manifest im finalen APK prüfen (0× Telemetry-Provider, 0× INTERNET), APK nach `dist/`, Link `http://<host>:8736/api/file?path=Voice-Keyboard/dist/<apk>&download=1` + `present_files`.
7. **M7 Finaler Emulator-Review durch GPT 6.1 Sol**: IME aktivieren, Tippen in 3 Sprachen, symbolsShift, Diktat per WAV, Screenshots; Befundtabelle.

## Betrieb (verbindlich, `docs/CONTINGENCY_PLAN.md`)
- ≤ 4 Dateien / ≤ 2 Builds / ≤ 10 min pro Coder-Auftrag; Instrumented-Test nie im Coder-Auftrag.
- Kein `sleep` im Turn — `schedule_task` (≥ 60 s) bzw. max. 2× `wait_all(60)`.
- Gradle = globales Mutex; Emulator exklusiv; `claim_files` vor Multi-Datei-Aufträgen.
- ≤ 6 lebende Subagenten; Slot-Recycling per `steer`; fertige Sessions dismissen.
- Liveness am Dateisystem messen (mtime/`git status`), nicht am Session-Output.
- Vor jeder Kompaktierung: `HANDOFF.md` + `state/progress.md` + `reports/NEXT.md` aktualisieren.
