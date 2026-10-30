# Verifizierte technische Fakten (Umgebung + Abhängigkeiten)

Stand: 2026-10-27 (Review-Fassung). Jede Zeile trägt ihren Beleg-Grad:

- **[gemessen]** — in dieser Container-Umgebung direkt ausgeführt/gemessen.
- **[SDK]** — direkt aus dem installierten Android SDK gelesen
  (`/opt/android-sdk/platforms/android-36`: `android.jar`, `data/res/values/attrs_manifest.xml`).
- **[AAR]** — direkt aus dem heruntergeladenen ONNX-Runtime-AAR gelesen
  (`AndroidManifest.xml`, `classes.jar`, `jni/**`).
- **[Doku]** — aus offizieller Doku zitiert (URL genannt), im Container **nicht**
  ausführbar; muss auf Gerät/Emulator bestätigt werden.

Nichts hier ist geraten. Werte ohne Beleg-Grad sind bewusst entfernt.

## 1. Build-Umgebung (Container)

| Komponente | Wert | Beleg |
|---|---|---|
| JDK | OpenJDK 17.0.20.1 (Debian) | [gemessen] |
| Gradle | 8.10.2 (`/usr/local/bin/gradle`, Kotlin 1.9.24) | [gemessen] |
| Android SDK | `/opt/android-sdk` (`sdk.dir` in `local.properties` gesetzt; `ANDROID_HOME` ist **nicht** gesetzt) | [gemessen] |
| Plattformen | `android-35` (Android 15), `android-36` (Android 16), beide `IsBaseSdk=true`, ExtensionLevel 17 | [SDK] |
| Build-Tools | 34.0.0, 35.0.0 | [gemessen] |
| NDK | 27.0.12077973 (enthält `llvm-readelf`, `check_elf_alignment`-Workflow) | [gemessen] |
| CMake | 3.31.0 | [gemessen] |
| platform-tools | adb 1.0.41 (37.0.1) | [gemessen] |
| Emulator | 37.2.12.0 | [gemessen] |
| System-Images | `android-30`, `android-35`, `android-36` — **nur `google_apis/x86_64`** (Google-APIs-Image, enthält GMS-Bibliotheken) | [gemessen] |
| AOSP-Image verfügbar | `system-images;android-36;default;x86_64` per `sdkmanager` installierbar (GrapheneOS-näher als `google_apis`) | [gemessen] |
| KVM | `/dev/kvm` vorhanden, Modus 666, Gruppe `_ssh` → HW-Beschleunigung nutzbar | [gemessen] |
| CPU / Disk | 16 Kerne, 44 TB, 37 TB frei | [gemessen] |
| RAM | 60 GB total; **frei/available schwankt stark** (parallel laufende Agenten), bei Messung 1,1 GB available | [gemessen] |
| Netz | `huggingface.co`, `repo1.maven.org`, `developer.android.com` → HTTP 200 | [gemessen] |

**Konsequenzen:** `sdk.dir=/opt/android-sdk` in `local.properties` ist Pflicht
(Gradle findet das SDK sonst nicht). `ANDROID_HOME` muss für Tools gesetzt
werden, die nicht über `local.properties` gehen (z. B. `sdkmanager`, `avdmanager`).
Emulator-Tests sind real möglich. Ziel-SDK 36, minSdk ≥ 24 (siehe §3: ORT-AAR
verlangt selbst `minSdkVersion=24`; Empfehlung 29+ bleibt).
**Speicher ist der Engpass, nicht die CPU:** ein paralleler Emulator + ONNX-Inferenz
(~0,9 GB RSS, siehe `docs/ASR_CONTRACT.md` §5) muss in den freien RAM passen —
vor Emulator-Starts `free -m` prüfen, nicht blind parallelisieren.

## 2. ASR-Modell: Parakeet TDT 0.6B v3 als ONNX

Repo: `istupakov/parakeet-tdt-0.6b-v3-onnx` (HF-Revision
`8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce`, Dateiliste + `config.json` per HF-API
geprüft; SHA-256 aller vier Dateien siehe `docs/MODEL_SHA256.txt`).

| Datei | Größe | Rolle |
|---|---|---|
| `encoder-model.int8.onnx` | 652.183.999 B | Encoder (int8, selbstenthaltend) |
| `decoder_joint-model.int8.onnx` | 18.202.004 B | Decoder+Joint (int8) |
| `nemo128.onnx` | 139.764 B | Mel-Feature-Extraktor (NeMo 128-bin log-mel) |
| `vocab.txt` | 93.939 B | 8193 Token-Strings `<token> <id>`, `<blk>` = 8192 |

Summe int8 ≈ **671 MB**. fp32-Variante: `encoder-model.onnx` (41,8 MB) +
`encoder-model.onnx.data` (2435 MB) + `decoder_joint-model.onnx` (72,5 MB) — für
Mobile ungeeignet. Nebenbefund [gemessen]: `config.json` enthält **kein**
`max_tokens_per_step` → Default 10 ist zu kodieren (Details `ASR_CONTRACT.md` §1).

Nur zur Vollständigkeit: `nvidia/parakeet-tdt-0.6b-v3` bietet zusätzlich
`parakeet-tdt-0.6b-v3.q8_0.gguf` (714 MB) — relevant nur, falls ein GGUF-Runtime-Weg
gewählt wird; **ONNX ist der geplante Weg**.

**Konsequenzen:** 671 MB passen nicht ins APK → Download beim ersten Start
(HTTPS, resumable, SHA-256-Prüfung, Fortschritt, Abbruch), Ablage in
`filesDir/models/parakeet-tdt-0.6b-v3-int8/`, **aus Backup/D2D-Transfer
ausgeschlossen** (sonst wandern 671 MB bei jeder Sicherung mit, siehe §4.5).
Gemessener Laufzeit-Footprint des int8-Modells: **~0,9 GB RSS** (x86_64, 8 Threads);
das ist die harte Geräte-Randbedingung und muss auf ARM64 nachgemessen werden.

## 3. ONNX Runtime für Android

Gemessen am aktuellen AAR `com.microsoft.onnxruntime:onnxruntime-android:1.31.1`
(2026-10-26, `SHA-256 d4d50660300ed2c4f38ffdfd5ab5bfa3a22f995f282dda24f42ea3ea55467b49`,
55.138.794 B) — gleiche Version wie das Python-`onnxruntime` 1.31.1, mit dem
`ASR_CONTRACT.md` erzeugt wurde (reproduzierbar).

| Fakt | Wert | Beleg |
|---|---|---|
| AAR-Größe (alle 4 ABIs) | 55,1 MB | [gemessen] |
| `jni/arm64-v8a` entpackt | 34,3 MB (`libonnxruntime.so` 34.208.680 B + JNI 122.576 B) | [gemessen] |
| `jni/x86_64` entpackt | 40,9 MB | [gemessen] |
| `jni/armeabi-v7a` / `x86` | 24,3 MB / 40,8 MB | [gemessen] |
| `minSdkVersion` des AAR | **24** | [AAR] |
| 16-KB-Page-Alignment der `.so` (alle ABIs) | `LOAD align = 0x4000` = 16 KB ✓ | [gemessen] (`llvm-readelf -l`) |
| Vom AAR eingeschleuste Permissions | `INTERNET`, `ACCESS_NETWORK_STATE` | [AAR] |
| Vom AAR eingeschleuster Provider | `ai.onnxruntime.TelemetryInitializer` (+ `telemetry.HttpClient`, `ConnectivityCallback`, `PowerInfoReceiver`) | [AAR] |

**Konsequenzen (verbindlich):**

1. **APK-Wachstum:** int8-Modell + ORT-AAR = 671 MB (Modelle, Download) + ~34 MB
   native Libs (arm64). APK/AAB mit `abiFilters` auf `arm64-v8a` (+ `x86_64` nur
   für Emulator-Builds) begrenzen; eine Universal-APK mit 4 ABIs wäre ~140 MB.
2. **16-KB-Pages:** ab Android 15 gibt es Geräte mit 16-KB-Speicherseiten; Apps
   mit nativen Libs müssen dafür gebaut sein (Google-Play-Auflage für Apps mit
   `targetSdk ≥ 35` auf 64-Bit-Geräten; Updates ohne 16-KB-Support können ab
   **1. Februar 2027** nicht mehr ausgeliefert werden). Die ORT-1.31.1-Libs sind
   bereits `0x4000`-aligniert [gemessen], also kein Blocker — bei jedem ORT-Update
   erneut prüfen (`llvm-readelf -l` bzw. `check_elf_alignment.sh` / `zipalign -c -P 16`).
   [Doku: developer.android.com/guide/practices/page-sizes]
3. **Telemetrie/Netz:** Die AAR bringt einen Telemetry-`ContentProvider` und
   Netzwerk-Permissions mit. Für ein lokales ASR auf GrapheneOS muss der Provider
   im App-Manifest per Manifest-Merger entfernt werden
   (`<provider android:name="ai.onnxruntime.TelemetryInitializer" tools:node="remove" …/>`),
   sonst initialisiert ORT seinen 1DS-HttpClient und darf HTTP senden (die native
   Lib enthält den 1DS-Stack; sie meldet ohne Java-Client lediglich
   „Android telemetry is unavailable …" [gemessen via `strings`]). `INTERNET`
   bleibt nur für den Modell-Download drin — die Doku-/Store-Aussage „keine
   Netzverbindung außer Modell-Download" ist also **nur nach** diesem Eingriff wahr.
4. **CPU-EP ist der sichere Default** (`intra_op_num_threads` = physische Kerne);
   NNAPI deckt nicht alle Parakeet-Ops ab. `nemo128.onnx` kapselt die
   Mel-Extraktion → kein eigener DSP-Code (Eingang 16 kHz float-PCM, Ausgang Mel).
   Achtung Speicher: `SessionOptions` mit deaktiviertem Memory-Pattern/Prepacking
   und ggf. `use_ort_model_bytes_directly` prüfen — 0,9 GB RSS sind auf 4-GB-Geräten
   grenzwertig (low-memory-killer).

## 4. Android-Plattform-Randbedingungen (API 34/35/36, GrapheneOS)

### 4.1 Mikrofon + Foreground-Service
- `foregroundServiceType="microphone"` = Flag `0x80`; ab `targetSdk 34` verlangt
  der Start dieses Typs die Permission `FOREGROUND_SERVICE_MICROPHONE` **und**
  eine von `RECORD_AUDIO` / `CAPTURE_AUDIO_OUTPUT` — sonst
  `InvalidForegroundServiceTypeException`/`SecurityException`. Ohne den Typ kein
  Mikrofonzugriff. [SDK: `attrs_manifest.xml`, `Manifest.permission`]
- Zusätzlich `FOREGROUND_SERVICE` (Basis-Permission) und `POST_NOTIFICATIONS`
  (Laufzeit-Permission ab API 33; ohne sie startet der Service, aber die
  Notification ist unsichtbar → Nutzer sieht keine Aufnahme-Indikation).
  [SDK: `Manifest.permission.POST_NOTIFICATIONS`]
- **While-in-use-Regel:** Ein FGS, der while-in-use-Permissions (Kamera, Mikrofon,
  Location) braucht, darf **nicht aus dem Hintergrund** gestartet werden — auch
  nicht über eine der Background-Start-Ausnahmen. [Doku:
  developer.android.com/develop/background-work/services/fgs/restrictions-bg-start]
  Für ein IME heißt das: Aufnahme erst starten, wenn die IME-Oberfläche sichtbar
  ist (Mic-Taste in `onStartInputView`), nicht aus einem Timer/Broadcast. Der
  umgekehrte Fall — Aufnahme läuft weiter, während die IME-Tastatur verdeckt ist
  (anderes Fenster im Vordergrund) — ist der riskante Pfad und muss auf dem
  Emulator explizit getestet werden.
- **Architektur-Konsequenz:** Ein IME-Prozess mit sichtbarer Tastatur ist selbst
  im Vordergrund; für kurze Diktate ist **kein** FGS nötig. Ein FGS ist nur für
  „Aufnahme läuft weiter, während die Tastatur nicht sichtbar ist" erforderlich —
  diese Produktentscheidung gehört bewusst getroffen (und dann mit §4.1-Regeln
  gebaut), nicht implizit.
- **FGS-Typen mit Zeitlimit:** `dataSync` und `mediaProcessing` dürfen ab
  `targetSdk 35` nur **6 h pro 24 h** laufen; danach ruft das System
  `Service.onTimeout(int, int)` und der Service muss sofort `stopSelf()` rufen
  (sonst interne Exception). `microphone` hat dieses Limit **nicht** — ein
  Diktat-FGS mit `microphone` ist also nicht zeitlich gekappt, `dataSync` als
  „bequemer“ Typ wäre ein Fehler. [Doku:
  developer.android.com/about/versions/15/behavior-changes-15]

### 4.2 Sideload / Restricted Settings
- `adb install` und Installation aus unbekannter Quelle funktionieren auf
  GrapheneOS; die App braucht keinen Store. Eigener Signaturschlüssel liegt in
  `keystore/` (siehe `IMPLEMENTATION_PLAN.md`). [Doku: grapheneos.org/usage,
  developer.android.com]
- „Restricted Settings" (Android 13+) betrifft **Accessibility- und
  Notification-Listener-Services**, nicht `InputMethodService`; ein IME muss
  also nicht über „Restricted Settings" freigeschaltet werden. [Doku; im
  Container nicht prüfbar] **Trotzdem Testaufgabe**, weil GrapheneOS eigene
  Defaults für Autofill/Accessibility hat und die Emulator-Images (google_apis)
  das Verhalten nicht abbilden.
- Kein Google Play Services / kein Google-Speech-Dienst → **jede** ASR lokal.
  Kein `QUERY_ALL_PACKAGES` nötig.

### 4.3 16-KB-Speicherseiten
Siehe §3: gilt für alle Apps mit nativen Libraries (`targetSdk ≥ 35`), also auch
für ONNX Runtime. Ein echter 16-KB-Emulatortest ist möglich — die Images sind
separat installierbar [gemessen via `sdkmanager --list`]:

```bash
sdkmanager "system-images;android-36;google_apis_ps16k;x86_64"    # 16-KB-Page-Image, API 36
# Varianten: android-35;google_apis_ps16k;*, android-36;google_apis_playstore_ps16k,*
# (alle per `sdkmanager --list | grep ps16k` bestätigt)
```

Die vorhandenen Images (`google_apis/x86_64`) sind **keine** 16-KB-Images.
Ersatzprüfung ohne Gerät: ELF-`LOAD`-Alignment ≥ 2**14 (`llvm-readelf -l`, §3).
[Doku: developer.android.com/guide/practices/page-sizes]

### 4.4 targetSdk 36 (Android 16) — was die App trifft
- **Edge-to-Edge ist nicht mehr abwählbar** (`windowOptOutEdgeToEdgeEnforcement`
  wird für `targetSdk ≥ 36` ignoriert) → Tastatur-View muss mit
  `WindowInsets`/`imeInsets` umgehen statt mit Systemleisten-Annahmen.
  [Doku: developer.android.com/about/versions/16/behavior-changes-16]
- **Predictive Back ist Default** → Back-Geste darf die Tastatur nicht
  unkontrolliert schließen; Migrationsschritte nötig. [ebd.]
- **Orientierungs-/Resizability-/Seitenverhältnis-Restriktionen werden auf großen
  Screens ignoriert** → Layout muss in jeder Fenstergröße funktionieren (Split
  Screen, Tablet, Desktop-Windowing). [ebd.]
- `android:exported` muss für alle Komponenten mit Intent-Filter explizit gesetzt
  sein (IME-Service: `exported="true"`, sonst installiert die App ab API 31 nicht).
  [SDK]
- 16-KB-Pages-Anforderung (§4.3) hängt ebenfalls an `targetSdk ≥ 35`. [Doku]

### 4.5 Backup / Daten-Extraktion
Ab API 31 ersetzt `android:dataExtractionRules` das alte `fullBackupContent`.
Für dieses Projekt Pflicht, nicht Kür: die 671 MB Modelldateien dürfen **nicht**
in Cloud-Backup oder D2D-Transfer landen.

```xml
<application android:allowBackup="false"
             android:dataExtractionRules="@xml/data_extraction_rules"
             android:fullBackupContent="false">
```
[SDK: `android:dataExtractionRules`, `android:fullBackupContent` in
`attrs_manifest.xml`; Verhalten Doku developer.android.com/guide/topics/data/autobackup]

### 4.6 Batterie / Sonstiges
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` nur optional und erklärt anbieten
  (Play-/Store-Richtlinien verbieten es sonst; hier Sideload, aber Vertrauen).
- Kein `SCHEDULE_EXACT_ALARM`, kein `BOOT_COMPLETED`-Start (ab API 35 sind
  bestimmte FGS-Typen aus `BOOT_COMPLETED` ohnehin gesperrt) — für ein IME
  irrelevant.

## 5. IME-Technik (AOSP, ohne Google)

### 5.1 Manifest-Pflichtteile (aus dem SDK abgeleitet, [SDK])
```xml
<service android:name=".VoiceImeService"
         android:exported="true"
         android:permission="android.permission.BIND_INPUT_METHOD">
  <intent-filter><action android:name="android.view.InputMethod"/></intent-filter>
  <meta-data android:name="android.view.im" android:resource="@xml/method"/>
</service>
```
`android.view.inputmethod.InputMethod.SERVICE_INTERFACE` /
`SERVICE_META_DATA` sind die Konstanten dafür; `BIND_INPUT_METHOD` ist die
System-Permission, die nur der System-Server hält — ohne sie kann keine fremde
App die Tastatur binden.

### 5.2 API-Realität (geprüft gegen `android.jar` API 36)
- **`InputMethodService.isProcessDefault()` existiert nicht** (in API 36 nicht
  vorhanden). „Ist meine IME die aktive?" wird über
  `Settings.Secure.DEFAULT_INPUT_METHOD` bzw.
  `InputMethodManager.getEnabledInputMethodList()` ermittelt; zum Umschalten
  `InputMethodManager.showInputMethodPicker()` oder
  `InputMethodService.switchInputMethod(id)`. [SDK]
- Für „soll die eigene View überhaupt angezeigt werden?" sind
  `onEvaluateInputViewShown()`, `isInputViewShown()` und
  `onShowInputRequested(int, boolean)` vorhanden — diese drei sind der richtige
  Hebel (Hardware-Tastatur, `TYPE_NULL`, Extraktionsmodus). [SDK]
- Weitere vorhandene Hooks: `isFullscreenMode()`, `onEvaluateFullscreenMode()`,
  `getCurrentInputBinding()`, `onAppPrivateCommand()`,
  `setCandidatesViewShown(boolean)`, `isExtractViewShown()`. [SDK]
- `android.inputmethodservice.KeyboardView` ist deprecated → eigene View
  (bestätigt die Design-Entscheidung).
- Text-Injektion: `InputConnection.commitText()`, `deleteSurroundingText()`,
  `setComposingText()`, `finishComposingText()` — kein AccessibilityService nötig.
- Sicherheit: bei `EditorInfo.inputType` mit `TYPE_TEXT_VARIATION_PASSWORD`,
  `TYPE_NULL` o. Ä. darf weder diktiert noch gelernt werden; die Mic-Taste muss
  dort deaktiviert werden (Datenschutz-Zusage).

### 5.3 Emulator-Testablauf [gemessen: Werkzeuge vorhanden]
```bash
export ANDROID_HOME=/opt/android-sdk
avdmanager create avd -n vk36 -k "system-images;android-36;google_apis;x86_64" -d pixel_6
$ANDROID_HOME/emulator/emulator -avd vk36 -no-window -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable  com.example.voicekeyboard/.VoiceImeService
adb shell ime set     com.example.voicekeyboard/.VoiceImeService
adb shell am start -a android.intent.action.SENDTO -d sms:10086 --es sms_body "test"
adb shell input text "hallo"      # prüft commitText-Pfad
adb exec-out screencap -p > screen.png
```
Für GrapheneOS-nah: AOSP-Image statt `google_apis`
(`system-images;android-36;default;x86_64`, per `sdkmanager` nachinstallierbar
[gemessen]) — nur dort fehlen die GMS-Bibliotheken wirklich.

### 5.4 Was im Container NICHT prüfbar ist
Verhalten von GrapheneOS im Detail (Restricted Settings, Sandboxed Play,
Permission-Defaults), echtes Mikrofon, ARM64-Latenz, 16-KB-Page-Gerät,
Akku-/Doze-Verhalten. Alles davon gehört in die Geräte-Testliste
(`DESIGN.md` §10.4), nicht in dieses Dokument als Behauptung.

## Emulator mit echtem Mikrofon-Einlass (gemessen 2026-10-30, 03:2x-03:4x)
Drei Bedingungen — jede einzelne war ein harter Blocker, keine ist optional:
1. **`-allow-host-audio`** ist zwingend. Wörtlich aus `emulator -help`: „Allows sending of audio from audio input devices. **Otherwise, zeroes out audio.**" (Ohne diesen Schalter ist das Gast-Mikrofon softwareseitig auf Null gesetzt — exakt der Zustand, der wie „App hört nichts" aussieht.)
2. **Kein `-no-window`.** Der Headless-Zweig (`qemu-system-x86_64-headless`) hat keinen Audio-Treiber; im Log steht dann `Could not init \`pa' audio driver` und die QEMU-Kommandozeile enthält gar kein Audiogerät. Unsichtbar geht es trotzdem: `QT_QPA_PLATFORM=offscreen` + `-qt-hide-window`.
3. **`-audio pa` + PulseAudio am Standardpfad** (`$XDG_RUNTIME_DIR/pulse/native`, also `/run/user/<uid>/`). `alsa` ist als `-audio`-Wert **unbekannt** („Unknown audio driver `alsa'"). Null-Sink `vkmic` als Standard-Senke **und** deren Remap-Quelle als Standard-Quelle.

Betriebssenkенlage: **65 %** ⇒ Peak 19 834, RMS 1931 (kein Clipping). 100 % ⇒ Peak 32 768 = Übersteuerung; 30 % ist zu leise (RMS ≈ 180).
Werkzeuge: `tools/emulator_audio_up.sh` (aufsetzen + End-to-End-Beweis), `tools/emulator_audio_down.sh` (Rückbau inkl. App/IME), `tools/check_mic_capture.py` (Peak/RMS pro Sekunde, keine Inhalte).
Beweiskette bisher: Host-Monitor der Null-Sink Peak 20545 / RMS 3501 (`parec`) → Geräteaufnahme über `tinycap` bei 65 % Senkenlage **Peak 19834 / RMS 1931** → Ton kommt am virtuellen Mikrofon an. Vorher (ohne `-no-window`-Weg) war die Geräteaufnahme **still** (Peak 8 = Rauschen).
