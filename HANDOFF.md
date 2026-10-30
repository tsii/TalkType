# HANDOFF — Voice-Keyboard (Projekt-Root, aktiviert Kompaktierungs-Checkpoint)

**Stand:** 2026-10-28 01:15 UTC · Details: `state/progress.md`, `docs/PROJECT_STATE.md`
**Protokoll:** `docs/CONTINGENCY_PLAN.md` · Regeln: `reminders/ALWAYS.md`

## Rollen (fix)
Design = GPT 6.1 Sol · Analysen/Plan/Doku = Kimi K3 · Code = GLM 5.3 · **Review jeder GLM-Arbeit = DeepSeek v4 Pro 0813**.
Orchestrator: beauftragen + mechanisch verifizieren; keine eigene Facharbeit, keine eigenen Reviews.

## Status (23:40): D1 fertig + Review gruuen, GPU/NPU entschieden, Nutzer-Diagnose offen
## Status (10:1x): v0.1.3 ausgeliefert (40,2 MB), Rechenweg abgeschlossen, B abgelehnt
- **v0.1.3**: `dist/voice-keyboard-0.1.3.apk`, 40 242 852 B, versionCode 4, SHA-256 `680057718c785428ea60988e73b0a8394689d4fec7553c80cb599974c9ae86f5`, arm64-only. Enthaelt: mmap-Speicherfix, Retry-Anzeige (Versuch N von M), Diagnosezeile, Varianten-Grundlage. Download: http://localhost:8736/api/file?path=Voice-Keyboard/dist/voice-keyboard-0.1.3.apk&download=1
- **Variante B (Arm/LiteRT) abgelehnt**: 50,2 % Wort-Identitaet, CED 33,2 %, Sprachwechsel durch 400 ms Stille (`docs/VARIANT_B_QUALITY.md`). A bleibt bei 100 % auf dem deutschen Referenzmaterial. LiteRT-Code liegt in `app/src/debug/java/...`, Release faellt dokumentiert auf A (`LiteRtBridge`).
- **Rechenweg abgeschlossen**: NPU ohne Google-Dienste nicht erreichbar (`docs/GPU_ACCELERATION_ANALYSIS.md`, `docs/RUNTIME_SWITCH_NPU_ASSESSMENT.md`, `docs/NPU_LITERT_FEASIBILITY.md`); Threads/Chunk konfigurierbar (`asr/AsrThreadConfig.kt`).
- **Testbestand**: Debug 279 / 0, Release 263 / 0.
- **Laeuft**: DeepSeek-Review Stufe 14; Modell-Vergleich Deutsch (`docs/ASR_MODEL_SURVEY_DE.md`).


## Größter Erfolg (01:05, eigener Gerätebeweis)
`connectedDebugAndroidTest`: **tests=6, failures=0, skipped=0** — `s1_realEngine_realWav` **PASSED**. Das Diktat schreibt aus einer echten WAV über die echte Engine den vollständigen §4.1-Goldentext. Vorher (00:36) war derselbe Test rot: `… ob die Viktifunktion auf dem T.` — ein früh committetes Fehlwort hatte den ganzen Rest blockiert. Behoben durch E8 (Commit erst ab 3 Hypothesen, letzte 2 Wörter ungeschützt, `commitUpTo` darf schrumpfen, W3c-Rollback im eigenen Bereich).
Unit-Bestand: **204 / 0 Fehler** (7 neue E8-Tests, inkl. T1 = exakt der Gerätefall).

## Verifizierter Stand
- Modell-Download end-to-end echt bewiesen (671 MB, `.part`+`.etag`, Geräte-Hashes = `docs/MODEL_SHA256.txt`, Netz danach automatisch aus).
- Thread-Invarianten: Editorzugriff zentral in `InputConnectionSink`, `onStopped` auf Main, `onDestroy` = `abort → stopFgs → stopObserving → shutdown(1×) → cancel`.
- E5-Fehlertexte statt Rohcodes in der Statuszeile; R-3 (`setup_network_disabled`) erledigt.
- Reviews `REVIEW_STAGE1`…`REVIEW_STAGE6`: 0 offene P0; **1 offener P1 = A1** (Fix läuft).

## Offene Punkte
| ID | Schwere | Inhalt |
|---|---|---|
| A1 | **P1** | Geisterschreiben nach `latch`-Timeout — Fix läuft |
| A2 | P2 | E5-Lücken + `OutOfMemoryError` (Error, nicht Exception) — läuft |
| C6 | P2 | `DictationController` ruft nach W3c-Rollback kein `policy.reset()` → Agreement zählt über den Rollback weiter |
| E7 | Note | FGS-Start-Fallback-Kaskade in `model/ModelDownloadService.kt` |
| A3 | Note | `updateStatus(extra)` ist untypisierte Rohstring-Leitung |
| M6/M7 | offen | Release-Build lief noch nie; finaler Review durch GPT 6.1 Sol steht aus |

## Nächste konkrete Schritte
1. A1/A2 verifizieren, dann C6 und E7 als zwei Mini-Aufträge.
2. M6: `assembleRelease` mit `keystore/release.jks` (Alias `voicekeyboard`, Pass `<in local.properties, untracked>`) → `apksigner verify` → `dist/` → Download-Link `http://<host>:8736/api/file?path=Voice-Keyboard/dist/<apk>&download=1` (mit `curl -I` prüfen).
3. M7: GPT-6.1-Sol-Emulator-Review. Gerät ist vorbereitet: IME gesetzt, Modell aktiv („Modell aktiv – Sprachfunktion freigeschaltet.").

## Fallstricke
- **Kein `sleep` im Turn** — `schedule_task` + Statuszeile; max. 2× `wait_all(60)`.
- Gradle = globales Mutex; Write-only-Aufträge nur mit terminierter Verifikation.
- `connectedDebugAndroidTest` **deinstalliert die App am Ende** → danach immer: `install -r`, `ime enable`, `ime set de.voicekeyboard/.ime.VoiceKeyboardService`.
- **Modell ohne Download vorinstallieren** (spart 671 MB und 2 min): `run-as de.voicekeyboard mkdir -p files/models/versions/v-test`, die 4 Dateien aus `/data/local/tmp/models` per `run-as … cp` hineinkopieren, `echo -n v-test > files/models/active.txt`; App hasht beim nächsten Setup-Aufruf und meldet „Modell aktiv" (~40 s).
- `clearPackageData` muss auf `false` bleiben, sonst löscht jeder Instrumented-Lauf den Modellbestand.
- `adb` nicht im PATH: `export PATH=$PATH:/opt/android-sdk/platform-tools`. Emulator `<device>` läuft mit `-no-audio` → Diktat nur per WAV beweisbar.
- `am force-stop` setzt das System-IME auf Google-Voice zurück.
- UI-Koordinaten: Feld `(400,240)` (nach `am start -S` nur einmal tippen), Tastenreihe `y≈1783`, Push-to-Talk `(1006,1742)`, Setup-Schalter `(540,517)`, Setup-Start `(540,377)`.
- Edit-Tool: mehrfache `edits[]`-Arrys mit eingebetteten `"`-Zeichen schlagen in diesem Client fehl → Einzel-Edits oder `write`.
- Slots werden nur durch Dismiss im UI frei; Liveness am Dateisystem prüfen.
