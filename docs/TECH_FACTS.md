# Verified technical facts (environment + dependencies)

Status: 2026-10-27 (review version). Every line carries its evidence grade:

- **[measured]** — executed/measured directly in this container environment.
- **[SDK]** — read directly from the installed Android SDK
  (`/opt/android-sdk/platforms/android-36`: `android.jar`,
  `data/res/values/attrs_manifest.xml`).
- **[AAR]** — read directly from the downloaded ONNX Runtime AAR
  (`AndroidManifest.xml`, `classes.jar`, `jni/**`).
- **[docs]** — quoted from official documentation (URL given), **not**
  executable in the container; must be confirmed on a device/emulator.

Nothing here is guessed. Values without an evidence grade are deliberately
removed.

## 1. Build environment (container)

| Component | Value | Evidence |
|---|---|---|
| JDK | OpenJDK 17.0.20.1 (Debian) | [measured] |
| Gradle | 8.10.2 (`/usr/local/bin/gradle`, Kotlin 1.9.24) | [measured] |
| Android SDK | `/opt/android-sdk` (`sdk.dir` set in `local.properties`; `ANDROID_HOME` is **not** set) | [measured] |
| Platforms | `android-35` (Android 15), `android-36` (Android 16), both `IsBaseSdk=true`, extension level 17 | [SDK] |
| Build tools | 34.0.0, 35.0.0 | [measured] |
| NDK | 27.0.12077973 (contains `llvm-readelf`, the `check_elf_alignment` workflow) | [measured] |
| CMake | 3.31.0 | [measured] |
| platform-tools | adb 1.0.41 (37.0.1) | [measured] |
| Emulator | 37.2.12.0 | [measured] |
| System images | `android-30`, `android-35`, `android-36` — **only `google_apis/x86_64`** (the Google APIs image, contains GMS libraries) | [measured] |
| AOSP image available | `system-images;android-36;default;x86_64` installable via `sdkmanager` (closer to GrapheneOS than `google_apis`) | [measured] |
| KVM | `/dev/kvm` present, mode 666, group `_ssh` → hardware acceleration usable | [measured] |
| CPU / disk | 16 cores, 44 TB, 37 TB free | [measured] |
| RAM | 60 GB total; **free/available fluctuates strongly** (agents running in parallel), 1.1 GB available at measurement time | [measured] |
| Network | `huggingface.co`, `repo1.maven.org`, `developer.android.com` → HTTP 200 | [measured] |

**Consequences:** `sdk.dir=/opt/android-sdk` in `local.properties` is
mandatory (otherwise Gradle does not find the SDK). `ANDROID_HOME` must be
set for tools that do not go through `local.properties` (e.g. `sdkmanager`,
`avdmanager`). Emulator tests are realistically possible. Target SDK 36,
minSdk ≥ 24 (see §3: the ORT AAR itself requires `minSdkVersion=24`; the
recommendation of 29+ stands).
**Memory is the bottleneck, not the CPU:** a parallel emulator + ONNX
inference (~0.9 GB RSS, see `docs/ASR_CONTRACT.md` §5) must fit into the
free RAM — check `free -m` before emulator starts, do not parallelize
blindly.

## 2. ASR model: Parakeet TDT 0.6B v3 as ONNX

Repo: `istupakov/parakeet-tdt-0.6b-v3-onnx` (HF revision
`8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce`, file list + `config.json`
checked via the HF API; SHA-256 of all four files see
`docs/MODEL_SHA256.txt`).

| File | Size | Role |
|---|---|---|
| `encoder-model.int8.onnx` | 652,183,999 B | encoder (int8, self-contained) |
| `decoder_joint-model.int8.onnx` | 18,202,004 B | decoder+joint (int8) |
| `nemo128.onnx` | 139,764 B | mel feature extractor (NeMo 128-bin log-mel) |
| `vocab.txt` | 93,939 B | 8193 token strings `<token> <id>`, `<blk>` = 8192 |

The int8 sum ≈ **671 MB**. fp32 variant: `encoder-model.onnx` (41.8 MB) +
`encoder-model.onnx.data` (2435 MB) + `decoder_joint-model.onnx` (72.5 MB) —
unsuitable for mobile. Side finding [measured]: `config.json` contains
**no** `max_tokens_per_step` → the default 10 must be coded (details
`ASR_CONTRACT.md` §1).

For completeness only: `nvidia/parakeet-tdt-0.6b-v3` additionally offers
`parakeet-tdt-0.6b-v3.q8_0.gguf` (714 MB) — relevant only if a GGUF runtime
path is chosen; **ONNX is the planned path**.

**Consequences:** 671 MB does not fit into the APK → download at the first
start (HTTPS, resumable, SHA-256 check, progress, abort), stored in
`filesDir/models/parakeet-tdt-0.6b-v3-int8/`, **excluded from
backup/D2D transfer** (otherwise 671 MB migrate with every backup, see
§4.5). Measured runtime footprint of the int8 model: **~0.9 GB RSS**
(x86_64, 8 threads); that is the hard device constraint and must be
re-measured on ARM64.

## 3. ONNX Runtime for Android

Measured on the current AAR
`com.microsoft.onnxruntime:onnxruntime-android:1.31.1` (2026-10-26,
`SHA-256 d4d50660300ed2c4f38ffdfd5ab5bfa3a22f995f282dda24f42ea3ea55467b49`,
55,138,794 B) — the same version as the Python `onnxruntime` 1.31.1 that
`ASR_CONTRACT.md` was produced with (reproducible).

| Fact | Value | Evidence |
|---|---|---|
| AAR size (all 4 ABIs) | 55.1 MB | [measured] |
| `jni/arm64-v8a` unpacked | 34.3 MB (`libonnxruntime.so` 34,208,680 B + JNI 122,576 B) | [measured] |
| `jni/x86_64` unpacked | 40.9 MB | [measured] |
| `jni/armeabi-v7a` / `x86` | 24.3 MB / 40.8 MB | [measured] |
| `minSdkVersion` of the AAR | **24** | [AAR] |
| 16-KB page alignment of the `.so` (all ABIs) | `LOAD align = 0x4000` = 16 KB ✓ | [measured] (`llvm-readelf -l`) |
| Permissions injected by the AAR | `INTERNET`, `ACCESS_NETWORK_STATE` | [AAR] |
| Provider injected by the AAR | `ai.onnxruntime.TelemetryInitializer` (+ `telemetry.HttpClient`, `ConnectivityCallback`, `PowerInfoReceiver`) | [AAR] |

**Consequences (binding):**

1. **APK growth:** int8 model + ORT AAR = 671 MB (models, download) + ~34 MB
   native libs (arm64). Limit APK/AAB with `abiFilters` to `arm64-v8a` (+
   `x86_64` for emulator builds only); a universal APK with 4 ABIs would be
   ~140 MB.
2. **16-KB pages:** from Android 15 there are devices with 16-KB memory
   pages; apps with native libs must be built for that (a Google Play
   requirement for apps with `targetSdk ≥ 35` on 64-bit devices; updates
   without 16-KB support can no longer be delivered from **February 1,
   2027**). The ORT 1.31.1 libs are already `0x4000`-aligned [measured], so
   no blocker — re-check on every ORT update (`llvm-readelf -l` resp.
   `check_elf_alignment.sh` / `zipalign -c -P 16`).
   [Docs: developer.android.com/guide/practices/page-sizes]
3. **Telemetry/network:** the AAR brings a telemetry `ContentProvider` and
   network permissions. For a local ASR on GrapheneOS the provider must be
   removed from the app manifest via the manifest merger
   (`<provider android:name="ai.onnxruntime.TelemetryInitializer"
   tools:node="remove" …/>`), otherwise ORT initializes its 1DS HTTP client
   and may send HTTP (the native lib contains the 1DS stack; without the
   Java client it merely reports "Android telemetry is unavailable …"
   [measured via `strings`]). `INTERNET` stays in only for the model
   download — the documentation/store statement "no network connection
   except model download" is thus true **only after** this intervention.
4. **The CPU EP is the safe default** (`intra_op_num_threads` = physical
   cores); NNAPI does not cover all Parakeet ops. `nemo128.onnx`
   encapsulates the mel extraction → no own DSP code (input 16 kHz float
   PCM, output mel). Watch the memory: check `SessionOptions` with the
   memory pattern/prepacking disabled and possibly
   `use_ort_model_bytes_directly` — 0.9 GB RSS is borderline on 4-GB
   devices (low-memory killer).

## 4. Android platform constraints (API 34/35/36, GrapheneOS)

### 4.1 Microphone + foreground service
- `foregroundServiceType="microphone"` = flag `0x80`; from `targetSdk 34`,
  starting this type requires the permission
  `FOREGROUND_SERVICE_MICROPHONE` **and** one of `RECORD_AUDIO` /
  `CAPTURE_AUDIO_OUTPUT` — otherwise
  `InvalidForegroundServiceTypeException`/`SecurityException`. Without the
  type, no microphone access. [SDK: `attrs_manifest.xml`,
  `Manifest.permission`]
- Additionally `FOREGROUND_SERVICE` (the base permission) and
  `POST_NOTIFICATIONS` (a runtime permission from API 33; without it the
  service starts, but the notification is invisible → the user sees no
  recording indication).
  [SDK: `Manifest.permission.POST_NOTIFICATIONS`]
- **While-in-use rule:** an FGS that needs while-in-use permissions
  (camera, microphone, location) may **not be started from the background**
  — not even via one of the background start exceptions. [Docs:
  developer.android.com/develop/background-work/services/fgs/restrictions-bg-start]
  For an IME this means: start recording only when the IME surface is
  visible (mic key in `onStartInputView`), not from a timer/broadcast. The
  reverse case — the recording keeps running while the IME keyboard is
  covered (another window in front) — is the risky path and must be tested
  explicitly on the emulator.
- **Architecture consequence:** an IME process with a visible keyboard is
  itself in the foreground; for short dictations **no** FGS is needed. An
  FGS is only required for "the recording keeps running while the keyboard
  is not visible" — this product decision must be made deliberately (and
  then built with the §4.1 rules), not implicitly.
- **FGS types with a time limit:** `dataSync` and `mediaProcessing` may run
  only **6 h per 24 h** from `targetSdk 35`; after that the system calls
  `Service.onTimeout(int, int)` and the service must call `stopSelf()`
  immediately (otherwise an internal exception). `microphone` does **not**
  have this limit — a dictation FGS with `microphone` is thus not
  time-capped; `dataSync` as a "convenient" type would be a mistake. [Docs:
  developer.android.com/about/versions/15/behavior-changes-15]

### 4.2 Sideload / restricted settings
- `adb install` and installation from an unknown source work on GrapheneOS;
  the app needs no store. The own signing key is in `keystore/` (see
  `IMPLEMENTATION_PLAN.md`). [Docs: grapheneos.org/usage,
  developer.android.com]
- "Restricted settings" (Android 13+) concerns **accessibility and
  notification listener services**, not `InputMethodService`; an IME thus
  does not have to be unlocked via "restricted settings". [Docs; not
  checkable in the container] **Nevertheless a test task**, because
  GrapheneOS has its own defaults for autofill/accessibility and the
  emulator images (google_apis) do not reflect the behavior.
- No Google Play services / no Google speech service → **every** ASR local.
  No `QUERY_ALL_PACKAGES` needed.

### 4.3 16-KB memory pages
See §3: applies to all apps with native libraries (`targetSdk ≥ 35`),
including ONNX Runtime. A real 16-KB emulator test is possible — the
images are separately installable [measured via `sdkmanager --list`]:

```bash
sdkmanager "system-images;android-36;google_apis_ps16k;x86_64"    # 16-KB page image, API 36
# Variants: android-35;google_apis_ps16k;*, android-36;google_apis_playstore_ps16k,*
# (all confirmed via `sdkmanager --list | grep ps16k`)
```

The existing images (`google_apis/x86_64`) are **not** 16-KB images.
Substitute check without a device: ELF `LOAD` alignment ≥ 2**14
(`llvm-readelf -l`, §3).
[Docs: developer.android.com/guide/practices/page-sizes]

### 4.4 targetSdk 36 (Android 16) — what hits the app
- **Edge-to-edge is no longer optional** (`windowOptOutEdgeToEdgeEnforcement`
  is ignored for `targetSdk ≥ 36`) → the keyboard view must work with
  `WindowInsets`/`imeInsets` instead of assumptions about the system bars.
  [Docs: developer.android.com/about/versions/16/behavior-changes-16]
- **Predictive back is the default** → the back gesture must not close the
  keyboard uncontrolled; migration steps needed. [ibid.]
- **Orientation/resizability/aspect-ratio restrictions are ignored on
  large screens** → the layout must work at every window size (split
  screen, tablet, desktop windowing). [ibid.]
- `android:exported` must be set explicitly for all components with an
  intent filter (IME service: `exported="true"`, otherwise the app does not
  install from API 31). [SDK]
- The 16-KB pages requirement (§4.3) also attaches to `targetSdk ≥ 35`.
  [Docs]

### 4.5 Backup / data extraction
From API 31, `android:dataExtractionRules` replaces the old
`fullBackupContent`. For this project mandatory, not trimming: the 671 MB
model files must **not** land in a cloud backup or a D2D transfer.

```xml
<application android:allowBackup="false"
             android:dataExtractionRules="@xml/data_extraction_rules"
             android:fullBackupContent="false">
```
[SDK: `android:dataExtractionRules`, `android:fullBackupContent` in
`attrs_manifest.xml`; behavior docs developer.android.com/guide/topics/data/autobackup]

### 4.6 Battery / misc
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only optionally and with an
  explanation offered (Play/store policies forbid it otherwise; here a
  sideload, but trust).
- No `SCHEDULE_EXACT_ALARM`, no `BOOT_COMPLETED` start (from API 35 certain
  FGS types are barred from `BOOT_COMPLETED` anyway) — irrelevant for an
  IME.

## 5. IME technology (AOSP, without Google)

### 5.1 Mandatory manifest parts (derived from the SDK, [SDK])
```xml
<service android:name=".VoiceImeService"
         android:exported="true"
         android:permission="android.permission.BIND_INPUT_METHOD">
  <intent-filter><action android:name="android.view.InputMethod"/></intent-filter>
  <meta-data android:name="android.view.im" android:resource="@xml/method"/>
</service>
```
`android.view.inputmethod.InputMethod.SERVICE_INTERFACE` /
`SERVICE_META_DATA` are the constants for that; `BIND_INPUT_METHOD` is the
system permission held only by the system server — without it no foreign
app can bind the keyboard.

### 5.2 API reality (checked against `android.jar` API 36)
- **`InputMethodService.isProcessDefault()` does not exist** (absent in API
  36). "Is my IME the active one?" is determined via
  `Settings.Secure.DEFAULT_INPUT_METHOD` resp.
  `InputMethodManager.getEnabledInputMethodList()`; for switching
  `InputMethodManager.showInputMethodPicker()` or
  `InputMethodService.switchInputMethod(id)`. [SDK]
- For "should my own view be shown at all?",
  `onEvaluateInputViewShown()`, `isInputViewShown()`, and
  `onShowInputRequested(int, boolean)` exist — these three are the right
  lever (hardware keyboard, `TYPE_NULL`, extraction mode). [SDK]
- Further hooks present: `isFullscreenMode()`,
  `onEvaluateFullscreenMode()`, `getCurrentInputBinding()`,
  `onAppPrivateCommand()`, `setCandidatesViewShown(boolean)`,
  `isExtractViewShown()`. [SDK]
- `android.inputmethodservice.KeyboardView` is deprecated → an own view
  (confirms the design decision).
- Text injection: `InputConnection.commitText()`,
  `deleteSurroundingText()`, `setComposingText()`,
  `finishComposingText()` — no accessibility service needed.
- Security: with `EditorInfo.inputType` such as
  `TYPE_TEXT_VARIATION_PASSWORD` or `TYPE_NULL`, neither dictation nor
  learning is allowed; the mic key must be disabled there (privacy
  promise).

### 5.3 Emulator test procedure [measured: tools present]
```bash
export ANDROID_HOME=/opt/android-sdk
avdmanager create avd -n vk36 -k "system-images;android-36;google_apis;x86_64" -d pixel_6
$ANDROID_HOME/emulator/emulator -avd vk36 -no-window -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable  com.example.voicekeyboard/.VoiceImeService
adb shell ime set     com.example.voicekeyboard/.VoiceImeService
adb shell am start -a android.intent.action.SENDTO -d sms:10086 --es sms_body "test"
adb shell input text "hallo"      # checks the commitText path
adb exec-out screencap -p > screen.png
```
For something close to GrapheneOS: the AOSP image instead of `google_apis`
(`system-images;android-36;default;x86_64`, installable afterwards via
`sdkmanager` [measured]) — only there are the GMS libraries really absent.

### 5.4 What is NOT checkable in the container
The behavior of GrapheneOS in detail (restricted settings, sandboxed Play,
permission defaults), a real microphone, ARM64 latency, a 16-KB page
device, battery/Doze behavior. All of that belongs on the device test list
(`DESIGN.md` §10.4), not in this document as an assertion.

## Emulator with a real microphone input (measured 2026-10-30, 03:2x-03:4x)
Three conditions — each one was a hard blocker, none is optional:
1. **`-allow-host-audio`** is mandatory. Verbatim from `emulator -help`:
   "Allows sending of audio from audio input devices. **Otherwise, zeroes
   out audio.**" (Without this flag the guest microphone is software-zeroed
   — exactly the state that looks like "the app hears nothing".)
2. **No `-no-window`.** The headless branch
   (`qemu-system-x86_64-headless`) has no audio driver; the log then says
   `Could not init \`pa' audio driver` and the QEMU command line contains
   no audio device at all. Invisible still works:
   `QT_QPA_PLATFORM=offscreen` + `-qt-hide-window`.
3. **`-audio pa` + PulseAudio on the standard path** (`$XDG_RUNTIME_DIR/
   pulse/native`, i.e. `/run/user/<uid>/`). `alsa` is **unknown** as an
   `-audio` value ("Unknown audio driver `alsa'"). Null sink `vkmic` as the
   default sink **and** its remap source as the default source.

Operating sink level: **65 %** ⇒ peak 19,834, RMS 1931 (no clipping).
100 % ⇒ peak 32,768 = overdriven; 30 % is too quiet (RMS ≈ 180).
Tools: `tools/emulator_audio_up.sh` (setup + end-to-end proof),
`tools/emulator_audio_down.sh` (teardown including the app/IME),
`tools/check_mic_capture.py` (peak/RMS per second, no content).
Evidence chain so far: the host monitor of the null sink peak 20,545 /
RMS 3,501 (`parec`) → device recording via `tinycap` at a 65 % sink level
**peak 19,834 / RMS 1,931** → the sound arrives at the virtual microphone.
Beforehand (without the non-`-no-window` path) the device recording was
**silent** (peak 8 = noise).
