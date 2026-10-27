# Verifizierte technische Fakten (Umgebung + Abhängigkeiten)

Stand: 2026-10-27. Alle Angaben in diesem Dokument wurden in der Build-Umgebung
**nachgeprüft** (HTTP-Status, Dateilisten, Tool-Versionen). Nichts hier ist geraten.

## 1. Build-Umgebung (Container)

| Komponente | Wert |
|---|---|
| JDK | OpenJDK 17.0.20.1 (Debian) |
| Gradle | 8.10.2 (`/usr/local/bin/gradle`) |
| Android SDK | `/opt/android-sdk` (ANDROID_HOME muss gesetzt werden) |
| Plattformen | android-35, android-36 (API 36 = Android 16) |
| Build-Tools | 34.0.0, 35.0.0 |
| NDK | 27.0.12077973 |
| Emulator | `/opt/android-sdk/emulator/emulator` |
| KVM | `/dev/kvm` vorhanden, Gruppe `_ssh`, Modus 666 → Hardware-Beschleunigung verfügbar |
| System-Images | android-30/google_apis/x86_64, android-35, android-36 (Verzeichnisse vorhanden) |
| CPU / RAM / Disk | 16 Kerne, 60 GB RAM (aktuell ~8 GB frei), 37 TB frei |
| Netz | huggingface.co und repo1.maven.org erreichbar (HTTP 200) |

**Konsequenz:** `sdk.dir=/opt/android-sdk` in `local.properties`. Emulator-Tests sind
real möglich (KVM). Ziel-SDK: 36 (Android 16), minSdk frei wählbar (Empfehlung 29+).

## 2. ASR-Modell: Parakeet TDT 0.6B v3 als ONNX (verifiziert auf Hugging Face)

Repo: `istupakov/parakeet-tdt-0.6b-v3-onnx` (Dateiliste per HF-API geprüft)

| Datei | Größe | Rolle |
|---|---|---|
| `encoder-model.int8.onnx` | 652,2 MB | Encoder (int8, selbstenthaltend) |
| `decoder_joint-model.int8.onnx` | 18,2 MB | Decoder+Joint (int8) |
| `nemo128.onnx` | ~0,1 MB | Mel-Feature-Extraktor (NeMo 128-bin log-mel) |
| `vocab.txt` | ~0,1 MB | Token-Vokabular (SentencePiece-Stücke) |

Summe int8 ≈ **671 MB**. fp32-Variante: `encoder-model.onnx` (41,8 MB) +
`encoder-model.onnx.data` (2435 MB) + `decoder_joint-model.onnx` (72,5 MB) – für
Mobile ungeeignet.

Alternativ vorhanden: `nvidia/parakeet-tdt-0.6b-v3` mit
`parakeet-tdt-0.6b-v3.q8_0.gguf` (714 MB) – nur relevant, falls ein
GGUF-Runtime-Weg gewählt wird; **ONNX ist der geplante Weg**.

**Konsequenz:** 671 MB können nicht ins APK. Modell-Download beim ersten Start
(HTTPS, Resumable, SHA-256-Prüfung, Fortschrittsanzeige, Abbruch möglich),
Speicherort `filesDir/models/parakeet-tdt-0.6b-v3-int8/`.

## 3. ONNX Runtime für Android

- Maven: `com.microsoft.onnxruntime:onnxruntime-android` (AAR, ca. 15–20 MB pro ABI).
- NNAPI-Execution-Provider optional; CPU-EP mit `intra_op_num_threads` = physische
  Kerne ist der sichere Default (NNAPI unterstützt nicht alle Parakeet-Ops).
- `nemo128.onnx` kapselt die Mel-Extraktion → kein eigener DSP-Code nötig
  (Eingang: 16 kHz float-PCM, Ausgang: Mel-Frames).

## 4. GrapheneOS-Randbedingungen

- Kein Google Play Services, kein Google-Speech-Dienst → **jede** ASR muss lokal sein.
- GrapheneOS = AOSP ohne Google-Apps; IME muss als `InputMethodService` normal
  installierbar sein (Sideload-APK, eigener Signaturschlüssel).
- Ab Android 14: `FOREGROUND_SERVICE_MICROPHONE` + `foregroundServiceType="microphone"`,
  Mikrofonzugriff aus einem Foreground-Service nur mit passender Deklaration.
- `POST_NOTIFICATIONS` ab API 33 für die Service-Notification.
- Kein `QUERY_ALL_PACKAGES` nötig; keine Internet-Berechtigung außer für den
  einmaligen Modell-Download (optional per Nutzerentscheidung abschaltbar).
- Batterie-Optimierung: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` nur optional/erklärt.

## 5. IME-Technik (AOSP, ohne Google)

- `InputMethodService` + `KeyboardView`-Ersatz (eigene View, kein deprecated
  `android.inputmethodservice.KeyboardView`).
- Text-Injektion: `InputConnection.commitText()` / `deleteSurroundingText()` /
  `setComposingText()`. Kein AccessibilityService nötig (kein Tasten-Emulations-Problem
  wie auf dem Desktop, siehe KONZEPT.md 3.5).
- Sprachen/Layouts: als JSON-Assets, damit Nutzer weitere Layouts nachlegen können.
- Emulator-Test: AVD aus `/opt/android-sdk/system-images/android-36/...`,
  `-no-window -gpu swiftshader_indirect -no-snapshot`, `adb install`, IME per
  `adb shell ime enable/set`, Texteingabe per `adb shell input text` prüfen,
  Screenshots per `adb exec-out screencap -p`.