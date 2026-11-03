# Implementation plan: offline speech keyboard (Android 16 / API 36)

**Role of this document:** executable work instruction for the coder. No
production code, only structure, contracts, order, acceptance criteria.

**Source ranking:** `TECH_FACTS.md` (environment/artifacts) > `docs/ASR_CONTRACT.md`
(measured ONNX contract, overrides every design assumption) > `KONZEPT.md`
(binding mechanics) > `DESIGN.md` (architecture; deviations there are already
decided and adopted here as steps, **not** re-discussed).

**Already present and to be used (do not recreate):**

| Path | Content | Status |
|---|---|---|
| `docs/ASR_CONTRACT.md` | tensor names, shapes, dtypes, logit split `8198 = 8193 + 5`, `<blk>=8192`, subsampling 8 (80 ms/frame), detokenization, verified greedy | binding |
| `docs/MODEL_SHA256.txt` | SHA-256 of all four model files | binding |
| `tools/reference_decode.py`, `.venv-asr/` | runnable Python reference decoder (onnxruntime 1.31.1) | reference |
| `testdata/de_smoke.wav`, `de_pause.wav`, `de_part1.wav`, `de_part2.wav` | 16 kHz mono PCM16, golden texts in `ASR_CONTRACT.md` §4 | golden data |
| `.models/` (671 MB) | `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, `nemo128.onnx`, `vocab.txt` | local, **not** into the APK/repo |
| `keystore/release.jks` | PKCS12, alias `voicekeyboard`, FP SHA-256 `76:0F:B5:A9:…:AD:EB` (checked) | release signature |

**Consequence for the "ONNX contract" gate:** the contract is **fulfilled**. Only
the Android-side smoke test against the already documented names/shapes remains
(M1). From here on: **no guessed tensor names** — exclusively those from
`ASR_CONTRACT.md`.

---

## A. Project scaffold

### A.1 Toolchain (versions verified on 2026-10-27 in this container)

| Component | Version | Evidence |
|---|---|---|
| JDK | 17.0.20.1 (Debian) | `java -version` |
| Gradle | **8.10.2** (`/usr/local/bin/gradle`) | `gradle -v` |
| AGP | **8.8.2** | test build `/tmp/agpcheck` green with Gradle 8.10.2 (`app-debug.apk`) |
| Kotlin | **2.0.21** (`org.jetbrains.kotlin.android`) | green in the same test build |
| compileSdk / targetSdk | **36** | `platforms/android-36` present; test build green, **no** `suppressUnsupportedCompileSdk` crutch needed |
| minSdk | **29** | TECH_FACTS recommendation "29+"; no legacy branches below 29 |
| Build-Tools | 35.0.0 (AGP default) | `build-tools/34.0.0, 35.0.0` present |
| NDK | 27.0.12077973 | present, **not needed** (no native code, ORT comes as an AAR) |
| Gradle compatibility | AGP 8.8.2 requires Gradle **≥ 8.10.2** → fits the installation exactly. **No upgrade to AGP 8.9+/9.x** without first raising the wrapper to ≥ 8.11.1 (then verify separately). | |

First acceptance rule: as soon as a build runs with a different AGP/Gradle
combination, update this table — do not treat the table as an assumption.

### A.2 Dependencies (checked on Maven Central / Google Maven)

| Coordinate | Version | Purpose |
|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime-android` | **1.31.1** | CPU EP; identical ORT version to the Python reference (`1.31.1`), hence minimal contract drift |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.10.2** | `Dispatchers.Default`, `StateFlow` (1.11.0 also exists; 1.10.2 chosen = conservative) |
| `androidx.core:core-ktx` | **1.19.1** | `NotificationCompat`, basics |
| `androidx.activity:activity-ktx` | **1.13.0** | setup/settings activity, `registerForActivityResult` |
| `androidx.lifecycle:lifecycle-runtime-ktx` | **2.11.0** | `repeatOnLifecycle` for setup screens |
| `junit:junit` | **4.13.2** | JVM unit tests |
| `org.robolectric:robolectric` | **4.17** | `org.json` + resources in unit tests (LayoutParser); ORT AAR **not** on the unit test classpath |
| `androidx.test.ext:junit` | **1.3.0** | instrumented runner (`AndroidJUnit4`) |
| `androidx.test:core` | **1.7.0** | `InstrumentationRegistry`, `ActivityScenario` |
| `androidx.test:runner` | **1.7.0** | test runner |
| `androidx.test.espresso:espresso-core` | **3.7.0** | UI assertions host app |
| `androidx.test.uiautomator:uiautomator` | **2.4.0** | IME visibility, global UI interaction |

Deliberately **not** used: NNAPI/GPU EP, ML Kit, TFLite, second ASR backends,
Gson/Moshi (layout JSON via `org.json`, test golden as a plaintext file), mocking
frameworks (fakes instead of mocks), OkHttp/MockWebServer (download test with the
JDK `com.sun.net.httpserver`, which is available in the `androidTest` source set —
**verify on the first test run**).

`onnxruntime-android` ships native `.so` for many ABIs; filter the APK
accordingly: `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` (arm64 = target
device, x86_64 = emulator).

### A.3 Directory tree (one module `app` — deliberately no multi-module)

```
Voice-Keyboard/
├── settings.gradle.kts            repositories (google, mavenCentral), rootProject, include(":app")
├── build.gradle.kts               only declare plugins (apply false): AGP 8.8.2, Kotlin 2.0.21
├── gradle.properties              JVM args, useAndroidX, nonTransitiveRClass, caching
├── local.properties               sdk.dir=/opt/android-sdk           (untracked)
├── keystore.properties            release passwords                    (untracked)
├── .gitignore                     .models/ testdata/*.generated build/ .gradle/ local.properties keystore.properties
├── gradle/wrapper/*, gradlew      wrapper pinned to 8.10.2 (one-time `gradle wrapper --gradle-version 8.10.2`)
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/schema/layout.schema.json
│       │   ├── assets/layouts/{de-DE,en-US,fr-FR,es-ES,it-IT,pt-PT,nl-NL,pl-PL,tr-TR,ru-RU,cs-CZ,sv-SE,da-DK,fi-FI,el-GR}.json
│       │   ├── assets/models-manifest.json         (SHA-256 from MODEL_SHA256.txt + revision + license ID)
│       │   ├── java/de/voicekeyboard/…             (packages see B)
│       │   └── res/{xml/method.xml, xml/backup_rules.xml, values/*.xml, drawable/*.xml, mipmap-*/…}
│       ├── debug/java/de/voicekeyboard/devtools/   WavAudioSource, FakeAsrEngine, DebugModelPath
│       ├── debug/assets/testdata/*.wav             copy/link of the golden WAVs (debug only)
│       ├── test/java/de/voicekeyboard/…            JVM unit tests (no ORT)
│       └── androidTest/java/de/voicekeyboard/…     instrumented + host app
├── docs/                          KONZEPT, DESIGN, TECH_FACTS, ASR_CONTRACT, MODEL_SHA256, this plan, PERF.md, DECISIONS.md
├── tools/reference_decode.py      (present) golden generator, leave unchanged
├── testdata/*.wav                 (present) golden audio
└── .models/                       (present, 671 MB) local artifacts, untracked
```

### A.4 File list with purpose and LOC estimate (order of magnitude, no promise)

**Build & manifest**

| Path | Purpose | ~LOC |
|---|---|---|
| `settings.gradle.kts` | repos, module | 12 |
| `build.gradle.kts` | plugin declaration | 10 |
| `gradle.properties` | `org.gradle.jvmargs=-Xmx3g`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`, `org.gradle.caching=true`, `org.gradle.parallel=true` | 12 |
| `app/build.gradle.kts` | android block (SDK versions, `abiFilters`, `compileOptions`/`kotlinOptions` = 17, `buildFeatures.buildConfig=true`, debug application ID `de.voicekeyboard.debug`? **no** — same ID, debug/release separate), signingConfig, dependencies, `testOptions.unitTests.isIncludeAndroidResources=true` | 110 |
| `app/proguard-rules.pro` | ORT keep rules; v1: `isMinifyEnabled=false`, rules prepared | 20 |
| `app/src/main/AndroidManifest.xml` | IME service (`BIND_INPUT_METHOD`, meta-data), mic FGS (`foregroundServiceType="microphone"`), setup/settings activity, permissions | 70 |
| `res/xml/method.xml` | `input-method` meta (subtypes per language) | 25 |

**Core packages** (details per class in section B)

| Path | Purpose | ~LOC |
|---|---|---|
| `ime/VoiceKeyboardService.kt` | `InputMethodService`, lifecycle→controller, binding of `InputConnectionSink` | 200 |
| `ime/ImeRootView.kt` | container: keyboard + status row (mic state, source, time) | 150 |
| `ime/SetupActivity.kt` | enable IME, mic permission, model status, audio test | 160 |
| `ime/SettingsActivity.kt` | language/layout, source choice, accessibility mode, network switch | 140 |
| `keyboard/KeyboardView.kt` | own view: rendering, touch/pointer capture, repeat, mic zone + abort swipe | 450 |
| `keyboard/KeyboardModel.kt` | layout→measured keys (widths, dp), hit detection | 200 |
| `keyboard/KeyGesture.kt` | DOWN/UP/CANCEL, long-press, repeat, space swipe, double-tap caps | 180 |
| `keyboard/EmojiPanel.kt` | categories, search, favorites, complete sequences | 160 |
| `keyboard/Haptics.kt` | optional haptics | 40 |
| `layouts/LayoutAsset.kt` | data model (layer, key, dead key, language) | 130 |
| `layouts/LayoutParser.kt` | JSON→model, schema/semantic validation, allowlist | 200 |
| `layouts/LayoutRepository.kt` | assets + imported JSONs, active list, fallback DE/EN | 130 |
| `layouts/CaseMapper.kt` | language-sensitive case mapping (tr i/İ, multi-character capable) | 90 |
| `layouts/DeadKeyEngine.kt` | spacing/combining/compose, backspace order | 100 |
| `dictation/DictationController.kt` | state machine + actor on main, session/editor generation | 280 |
| `dictation/DictationState.kt` | sealed states + error codes | 80 |
| `dictation/AsrScheduler.kt` | exactly one run, snapshots, consolidation, final has priority | 220 |
| `dictation/Session.kt` | session ID, PCM storage, timeline | 110 |
| `audio/AudioSource.kt` | interface + `PcmBlock` (see B) | 45 |
| `audio/AudioRecordSource.kt` | `AudioRecord` 16 kHz mono PCM16, `getMinBufferSize`, chunks 20–40 ms | 180 |
| `audio/PcmWindowStore.kt` | monotonic sample buffer, snapshot creation without a float duplicate | 120 |
| `audio/AudioRouter.kt` | default mic, `setPreferredDevice`/`setCommunicationDevice`, route confirmation + timeout | 140 |
| `audio/CaptureForegroundService.kt` | FGS start/stop, notification in the start window, no audio over binder | 230 |
| `audio/Resampler.kt` | only if 16 kHz is not delivered (stateful) — **optional, only after device measurement** | 100 |
| `asr/AsrContract.kt` | exactly the names/shapes/constants from `ASR_CONTRACT.md` | 60 |
| `asr/AsrEngine.kt` | interface + `Hypothesis` (see B) | 60 |
| `asr/ParakeetAsrEngine.kt` | Mel→encoder→TDT greedy, budget, error mapping | 300 |
| `asr/TdtGreedy.kt` | frame loop per `ASR_CONTRACT.md` §3 (pure, testable with a fake joint) | 200 |
| `asr/OnnxSessions.kt` | three sessions, IO validation on load, thread count, close | 160 |
| `asr/Vocabulary.kt` | parse `vocab.txt`, detokenize (`\u2581`), filter texts | 80 |
| `asr/TokenBudget.kt` | `max(256, min(4096, ceil(12*s)+64))` | 30 |
| `commit/LocalAgreement.kt` | 2 agreements, word boundary, grapheme boundary, prefix protection | 110 |
| `commit/TextAlign.kt` | detokenized hypothesis ↔ global audio position | 100 |
| `commit/CommitPlanner.kt` | commit horizon, "no commit on uncertainty" | 110 |
| `editor/EditorSink.kt` | interface + `EditPlan`, `EditResult`, `EditorSnapshot` | 60 |
| `editor/InputConnectionSink.kt` | `deleteSurroundingText`/`commitText`, callback evaluation | 300 |
| `editor/OwnershipTracker.kt` | editor generation, expected cursor position, own span | 160 |
| `editor/DeltaTyper.kt` | delta computation, 60-codepoint limit, no blind appending | 170 |
| `editor/Graphemes.kt` / `editor/Utf16.kt` | grapheme clusters, UTF-16 lengths | 110 |
| `model/ModelStore.kt` | interface + `ModelState` (ABSENT…BROKEN) | 60 |
| `model/FileModelStore.kt` | `.part`→hash→smoke→atomic swap, space check | 230 |
| `model/Manifest.kt` | read `assets/models-manifest.json`, revision/license | 90 |
| `model/Downloader.kt` | resume with `Range`/`ETag`, abort, progress, network off | 200 |
| `settings/Settings.kt` | DataStore-free via SharedPreferences, `networkDisabled`, layout list | 100 |
| `di/AppGraph.kt` | process singletons (sessions, ModelStore, engine handle), fakes debug only | 110 |
| `util/Clock.kt`, `util/Log.kt` | injectable time; logging without audio/text in release | 80 |

Total production code ≈ **4,700 LOC** + 15 layout JSONs ≈ 1,700 lines + schema ≈ 150.

### A.5 Core Gradle snippet (orientation, not a complete file)

```kotlin
// app/build.gradle.kts (excerpt)
android {
    namespace = "de.voicekeyboard"; compileSdk = 36
    defaultConfig { applicationId = "de.voicekeyboard"; minSdk = 29; targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    signingConfigs { create("release") {
        storeFile = rootProject.file("keystore/release.jks")   // path: <workdir>/keystore/release.jks
        storePassword = ksProps["storePassword"]; keyAlias = "voicekeyboard"
        keyPassword = ksProps["keyPassword"] } }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
```

`keystore.properties` (untracked, values provided by the user):
`storePassword=<in local.properties, untracked>` / `keyPassword=<in
local.properties, untracked>`.

---

## B. Package structure, classes, interfaces

Rule: **`asr`, `commit`, `editor` (DeltaTyper), `layouts` (parser), `dictation`
(state machine) contain no Android UI code** and are fully testable on the JVM.
Android types only in `ime`, `keyboard`, `audio` (impl.),
`editor/InputConnectionSink`, `model/Downloader`.

### B.1 Interfaces (pseudo-signatures, binding for tests)

```kotlin
// audio
data class PcmBlock(val seq: Long, val startSample: Long, val samples: ShortArray) // 16 kHz mono s16
interface AudioSource {
    fun start(sessionId: Long): Boolean          // idempotent: a second call does not restart
    fun read(dst: ShortArray, timeoutMs: Int): Int  // >0 samples, 0 timeout/no data, <0 error
    fun stopAndDrain(): List<PcmBlock>           // OLDER queue remnants; NOT the last read
    fun release()
}

// time
interface Clock { fun nowMs(): Long; suspend fun sleepUntil(ms: Long) }   // fake: virtual clock

// asr
data class Hypothesis(val tokenIds: IntArray, val text: String, val frameTimesMs: IntArray,
                      val sessionId: Long, val runId: Long, val audioSamples: Int,
                      val incomplete: Boolean)   // incomplete = safety limit/budget hit
interface AsrEngine {
    fun load(modelDir: File)                     // validates IO exactly against AsrContract
    suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis
    fun close()
}

// editor
data class EditorSnapshot(val generation: Long, val selectionStart: Int, val selectionEnd: Int,
                          val textBeforeCursor: String?, val ownSpanStart: Int, val ownSpanEnd: Int)
data class EditPlan(val deleteUtf16Before: Int, val insertText: String)
sealed interface EditResult { data object Applied; data class Rejected(val reason: String)
                              data class WriteFailed(val stage: String) }
interface EditorSink {
    fun generation(): Long
    fun snapshot(): EditorSnapshot?              // null = no reliable information
    fun applyPlan(plan: EditPlan, generation: Long): EditResult
}

// model
enum class ModelStatus { ABSENT, DOWNLOADING, VERIFYING, READY, BROKEN }
interface ModelStore {
    fun status(): ModelStatus
    fun modelDir(): File
    suspend fun install(progress: (done: Long, total: Long) -> Unit): ModelStatus   // resume + hash
    fun verifyInstalled(): Boolean
}
```

`LayoutRepository`, `Settings`, `Log` are injected analogously (interfaces only
where tests need them; no interface flood).

### B.2 Responsibilities

| Package | Classes | Responsibility / limit |
|---|---|---|
| `ime` | `VoiceKeyboardService`, `ImeRootView`, `SetupActivity`, `SettingsActivity` | IME lifecycle, own the `InputConnection` (never pass it to the service), status display |
| `keyboard` | `KeyboardView`, `KeyboardModel`, `KeyGesture`, `EmojiPanel`, `Haptics` | rendering/touch, pointer capture, mic zone/abort swipe, no ASR knowledge |
| `layouts` | `LayoutAsset`, `LayoutParser`, `LayoutRepository`, `CaseMapper`, `DeadKeyEngine` | JSON contract, layers, shift/caps, dead keys, import validation, isolate import errors |
| `dictation` | `DictationController`, `DictationState`, `AsrScheduler`, `Session` | state machine + generations; discards stale runs; 300-s limit |
| `audio` | `AudioSource`, `AudioRecordSource`, `PcmWindowStore`, `AudioRouter`, `CaptureForegroundService`, `Resampler?` | capture, order/sequence, route, FGS/notification |
| `asr` | `AsrContract`, `AsrEngine`, `ParakeetAsrEngine`, `TdtGreedy`, `OnnxSessions`, `Vocabulary`, `TokenBudget` | only contract names from `ASR_CONTRACT.md`, deterministic argmax |
| `commit` | `LocalAgreement`, `TextAlign`, `CommitPlanner` | stability + commit horizon, no text normalization |
| `editor` | `EditorSink`, `InputConnectionSink`, `OwnershipTracker`, `DeltaTyper`, `Graphemes`, `Utf16` | ownership check, revision, grapheme/UTF-16 boundaries, **never** delete foreign text |
| `model` | `ModelStore`, `FileModelStore`, `Manifest`, `Downloader` | 671 MB, resume, hash, atomic swap, license |
| `settings` | `Settings` | layout list, source, accessibility mode, network off |
| `di`, `util` | `AppGraph`, `Clock`, `Log` | process-wide wiring, injectable time, release logging without content |

**Fakes (only `src/test` + `src/debug`):** `FakeAudioSource` (sequences, pauses,
late reads, errors), `FakeAsrEngine` (hypotheses per audio state, contradictions,
budget overrun), `FakeEditorSink` (text/selection/UTF-16, external changes,
failing calls), `FakeClock`, `WavAudioSource` (debug, reads
`assets/testdata/*.wav`).

---

## C. Milestones M0–M8

Every milestone ends with **one verifiable** criterion. Nothing that is a gate
gets "verified later".

| # | Goal | Verifiable acceptance criterion |
|---|---|---|
| **M0** | scaffold + **empty, installable IME APK** | `./gradlew :app:assembleDebug` green; `adb install` ok; `adb shell ime enable` + `ime set` ok; host app `EditText` focused → screenshot shows the keyboard; 3 keys write `abc` **via `InputConnection`** into the host app |
| **M1** | **Gate: Android ONNX smoke against the already measured contract** (blocking before any decoder implementation) | `OnnxContractSmokeTest` green: all IN/OUT names, dtypes, and ranks **exactly** as `ASR_CONTRACT.md` §2; `encoder.outputs` = `[1,1024,frames]` with `frames == encoded_lengths[0]` and `frames ≈ ceil(T/8)`; exactly one decoder call with one frame yields `outputs=[1,1,1,8198]`, `output_states_1/2=[2,1,640]`; logit split `[0..8192]` token / `[8193..8197]` duration confirmed |
| **M2** | **Gate: mic foreground service from a visible IME (API 36) + capture spike** | `FgsMicSpikeTest` logs **success or a named exception** on an API 36 emulator in `docs/DECISIONS.md`; on success: notification within the start window, `RECORDING` at most 300 s; on prohibition: defined error path + setup hint, keyboard stays fully usable. No silent retry |
| **M3** | layout core + operable keyboard (15 languages) | `LayoutParser` tests over all 15 assets green (layers complete, IDs unique, row sums, shift counterparts, action allowlist, TR `i/İ`, EL final sigma); emulator: all layers typable, double-tap caps, dead key + space, space long-press switches layout |
| **M4** | editor core: DeltaTyper + ownership | `DeltaTyper` unit tests + `EditorSinkInstrumentedTest` in 6 host editor variants (single-/multi-line, password, number, selection, transforming/`null`-`InputConnection`): **no foreign text deleted**; ownership loss ⇒ `Rejected`, no append; 60-codepoint limit effective |
| **M5** | whole-buffer pipeline with the **real model**, WAV injection, LocalAgreement | `GoldenDecodeTest`: `de_smoke.wav` and `de_pause.wav` deliver **exactly** the texts from `ASR_CONTRACT.md` §4; pause regression: pipeline result with pause ≥ sum of the two partial windows (KONZEPT §7, "256 vs 137"); runtime/peak RSS logged in `docs/PERF.md` |
| **M6** | real capture + stop protocol + state machine | `ShutdownSequenceTest` green (samples exactly **once**, order monotonic, no read after EOS, double UP ineffective); `dictation` tests: UP during `STARTING` is pre-noted, mic start in `FINALIZING` rejected, 300 s auto-stop, editor switch cancels; emulator run with `-no-audio` documented |
| **M7** | ModelStore/download/setup | `DownloadResumeTest` green (Range, ETag change, abort, hash error, lack of space, active version stays intact); emulator: setup installs 671 MB from a local test server, then revoke network ⇒ dictation still possible; without the model the keyboard stays usable |
| **M8** | release: signature, lint, device gates | `assembleRelease` signed, `apksigner verify` ok, fingerprint = `76:0F:B5:A9:…:AD:EB`; `lintRelease` without new errors; release gate list from DESIGN §10.4 as a table with status `done/open` (device gates ARM64/BT/thermal stay open if no device is available — do **not** mark them green) |

**Order rationale:** M1 before any decoder line (the contract is the gate), M2
early (an FGS prohibition would shift the architecture), M4 before M6 (text
integrity is more expensive than audio), M5 measures the real resource situation
before M6 delivers microphone data, M7 only once the pipeline stands (otherwise
one debugs download and decode at the same time).

---

## D. Test plan

### D.1 JVM unit tests (`app/src/test`, no ORT, no microphone)

| Class | Test cases (excerpt) |
|---|---|
| `LocalAgreementTest` | first commit only at 2 **different** audio states; an identical snapshot does not count; word boundary (no half words); punctuation; Unicode/emoji; contradictory prefix ⇒ no commit; 300-s sequence; history across window switches |
| `DeltaTyperTest` | append-only; tail revision; 60-codepoint limit (deletion scope, not tail length); grapheme boundary before deletion; surrogate pairs/emoji-ZWJ untouched; `Rejected` on ownership loss; partial failure (deletion ok, commit fails) ⇒ no blind retry; no double appending |
| `TokenBudgetTest` | 1 s ⇒ 256; 20 s ⇒ 304; 300 s ⇒ 3664; saturation at 4096; **total length incl. context**; retry only as an explicit exception |
| `LayoutParserTest` | schema violations (missing layer, `width<=0`, unknown `kind`, action outside the allowlist); unknown main `schemaVersion` ⇒ rejection; all 15 assets; row sums; IDs unique per layer; character coverage per language; dead key spacing/compose fallback; TR case; EL diacritics |
| `TdtGreedyTest` | fake joint with an 8198 vector: token argmax; `<blk>=8192`; **duration index as the frame value** (`t += dur`); non-blank + `dur=0` allows several labels/frame; blank + `dur=0` ⇒ at least 1 frame progress; emission limit 10/frame; global step limit; `t >= frames` stops; NaN/dim error ⇒ `ModelError`; limit hit ⇒ `incomplete=true` (no silent truncation) |
| `ShutdownSequenceTest` | order: stop marker → read unblocked → capture thread join → queue drain (older blocks) → last read block → EOS → final inference; each sample exactly once; monotonic `startSample`; no read after EOS; double UP/CANCEL idempotent |
| `DictationControllerTest` | states `IDLE/STARTING/RECORDING/STOPPING/FINALIZING/CANCELLING`; UP in `STARTING` pre-noted; mic start in `FINALIZING` rejected; 300 s; editor switch/screen-off cancels; stale `sessionId/runId/editorGeneration` discarded |
| `CommitPlannerTest`, `TextAlignTest` | commit horizon; overlap duplicates; detokenization `\u2581`; no commit on uncertainty |
| `GraphemesTest`/`Utf16Test` | UTF-16 vs. codepoint lengths; cluster boundaries (combining accents, emoji families) |
| `WavInjectionPipelineTest` | **without model**: `FakeAsrEngine` delivers script hypotheses → commit + delta end-to-end, including pause, contradiction, ownership loss, budget error |
| `LayoutRoundTripTest` | import copy → private storage → load → identical model; a broken file is isolated |

### D.2 Instrumented / emulator (`app/src/androidTest`, API 36)

| Test | Content |
|---|---|
| `OnnxContractSmokeTest` | M1 gate: IO names/shapes/dtypes/1-frame decode against `AsrContract.kt` (regression protection on model/ORT change) |
| `GoldenDecodeTest` | real encoder/decoder on `assets/testdata/de_smoke.wav`, `de_pause.wav`; assertion on the **exact** strings from `ASR_CONTRACT.md` §4. On deviation: do **not** loosen the expectation, but document the deviation in `ASR_CONTRACT.md` and report CER per fixture |
| `ImeSmokeTest` (uiautomator) | enable/set IME, focus host app, check visibility, commit text, screenshot |
| `EditorSinkInstrumentedTest` | host app with 6 editor variants; invariant "foreign text remains untouched"; cursor move during fake inference ⇒ no revision |
| `FgsMicSpikeTest` | M2 gate: FGS start from a visible IME; result + exception name into the log/artifact |
| `DownloadResumeTest` | JDK `HttpServer` in the test delivers Range/ETag/errors; checks `.part`, hash abort, active version unchanged |
| `CaptureOrderInstrumentedTest` | `AudioRecord` path only as far as possible in the emulator; otherwise the identical controller path with `WavAudioSource` |

### D.3 WAV injection without a microphone

1. `WavAudioSource` (only `src/debug`) implements `AudioSource`: reads PCM16/16 kHz
   mono, delivers 20 ms blocks with `seq`/`startSample` – identical semantics to
   `AudioRecord`.
2. The production path (`DictationController` + `AsrScheduler` +
   `PcmWindowStore`) is **not** bypassed; only the source is swapped. That is
   what makes the test meaningful.
3. Instrumented route: debug build with an intent extra (`--ez wavFixture
   de_pause`) or a `BuildConfig` field; the **production APK contains no
   WAV/text injection** (DESIGN 10.2).
4. Fixtures: `testdata/de_*.wav` present; additionally generate via
   `tools/make_fixtures.py` (0.3 s / 1 s / 3 s excerpts from `de_smoke`, 300 s
   concatenation from `de_pause`, 30 s silence). Generated files are not
   versioned.

### D.4 Testing without the 671 MB

- Standard CI (`test`, `lint`, `assembleDebug`) is **model-free**: `FakeAsrEngine`
  + `WavInjectionPipelineTest` cover commit/delta/state.
- ORT integration without Parakeet: tiny **self-generated** ONNX test graphs
  (identity/add) check tensor lifetime, dtypes, and error paths — they prove
  **no** ASR quality and must not count as a golden replacement.
- The real model path (`GoldenDecodeTest`, `OnnxContractSmokeTest`) runs only
  opt-in: models lie locally under `.models/`, in the emulator via
  `adb push .models/* /sdcard/Android/data/de.voicekeyboard/files/models/parakeet-tdt-0.6b-v3-int8/`
  (debug `ModelStore` reads this directory first; **verify the path on the first
  emulator run**). No download in CI.

### D.5 Golden set (minimum scope)

| Fixture | Duration | Expectation |
|---|---|---|
| `de_smoke.wav` | 6.40 s | text from `ASR_CONTRACT.md` §4 |
| `de_pause.wav` | 9.90 s | text from §4 **plus** pause regression |
| `de_part1.wav` | 2.80 s | text from §4; **no** evidence for `min_audio_s` anymore (the old statement was an artifact of the decoder bug, ASR_CONTRACT §4.3) |
| `de_part2.wav` | 3.10 s | text from §4 |
| `de_smoke_1.0s.wav` / `de_pause_2.0s.wav` | 1.0 s / 2.0 s | `min_audio_s` boundary case: 1.0 s must be blocked, 2.0 s must transcribe (ASR_CONTRACT §4.4) |
| generated: 0.3 s / 1 s / 3 s / 30 s silence / 300 s | — | no dummy text, empty result allowed; 300 s without truncation |

**Evidence for `min_audio_s = 2.0 s` and `whole_buffer`:** in
`ASR_CONTRACT.md` §4.4 it is measured with the corrected decoder that 1.0-s
windows hallucinate (3 foreign-language hallucinations, 1 total failure) and
from 2.0 s no failure occurs in 23 samples; 3.0 s brings no measurable benefit.
The earlier rationale via `de_part1` ("2.8-s window decodes worse than in
context") is **refuted** — it was an artifact of the state update on blank.
`whole_buffer` stays the default, but justified via deadlock freedom and commit
logic (KONZEPT.md §5.1), not window quality. Reproduction:
`.venv-asr/bin/python tools/make_cut_windows.py && .venv-asr/bin/python tools/measure_min_audio.py --markdown`.

---

## E. Build and CI commands

```bash
# --- environment (every shell) ---
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH
printf 'sdk.dir=/opt/android-sdk\n' > <workdir>/local.properties   # untracked

# --- one-time: pin the wrapper to the existing Gradle version ---
cd <workdir> && gradle wrapper --gradle-version 8.10.2

# --- standard (model-free, without emulator) ---
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# --- release APK with the existing keystore ---
./gradlew :app:assembleRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk   # fingerprint must be 76:0F:B5:A9:12:9D:8A:05:…:AD:EB
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -5

# --- instrumented (emulator must be running) ---
./gradlew :app:connectedDebugAndroidTest
# only one gate:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.voicekeyboard.asr.OnnxContractSmokeTest
# real model path (opt-in, models pushed beforehand):
./gradlew :app:connectedDebugAndroidTest -PvkRealModel=true \
  -Pandroid.testInstrumentationRunnerArguments.class=de.voicekeyboard.asr.GoldenDecodeTest
```

`keystore/release.jks` exists (PKCS12, alias `voicekeyboard`, store/key password
`<in local.properties, untracked>`, checked with `keytool -list`).
`keystore.properties` stays untracked; the build aborts with a clear message if
it is missing and `assembleRelease` is requested.

---

## F. Emulator verification recipe (API 36, x86_64, KVM)

```bash
export ANDROID_HOME=/opt/android-sdk
AVDM=$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager
# fresh creation (a fresh device, do not reuse the existing AVDs)
$AVDM create avd -n vk-api36 -k "system-images;android-36;google_apis;x86_64" \
      -d pixel_7 --sdcard 2048M
# start (KVM present: /dev/kvm, mode 666; 16 cores/60 GB RAM on the host)
$ANDROID_HOME/emulator/emulator -avd vk-api36 -no-window -no-snapshot \
      -gpu swiftshader_indirect -accel on -memory 6144 -cores 4 -no-boot-anim \
      -no-audio &                     # -no-audio only for WAV tests; otherwise omit
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
```

`-no-audio` deliberately: AudioRecord tests run via `WavAudioSource`; the
emulator cannot evidence microphone/BT routing anyway (`-memory 6144` because
the int8 encoder alone already needs ~900 MB RSS — measured in the Python
reference; **verify** in the emulator).

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r -g $APK
adb shell ime enable de.voicekeyboard/.ime.VoiceKeyboardService
adb shell ime set    de.voicekeyboard/.ime.VoiceKeyboardService
adb shell ime list -s                      # must list the service
adb shell dumpsys input_method | grep -m2 -i 'mCurMethodId\|mInputShown'
adb exec-out screencap -p > /tmp/vk-$RANDOM.png     # evidence of visibility
adb shell input text "hallo"               # only a supplement, does NOT prove the IME
adb logcat -c && adb logcat -s VoiceKeyboard:V Asr:V Capture:V > /tmp/vk.log &
```

**Dictation simulation without a microphone:** debug build with
`WavAudioSource`, then `adb shell am broadcast -a de.voicekeyboard.DEBUG_WAV
--es fixture de_pause` (debug only; intent filter exclusively in the debug
manifest). Sequence in the screenshot log:
`STARTING → RECORDING → STOPPING → FINALIZING → text in the host editor`.
**Model push:** see D.4; alternatively `adb shell run-as de.voicekeyboard` for
internal storage. **Screenshot series** per milestone in `docs/screens/`
(evidence, not a product artifact).

---

## G. Risks, early warning indicators, processing order

| Rank | Risk | Early warning indicator (measurable) | Handled in |
|---|---|---|---|
| 1 | **whole-buffer live inference too slow/RAM-heavy on ARM** | RTF in the emulator > 0.5; p95 per 1-s update > 1 s; native RSS > 1.5 GB; first text later than 8 s; thermal throttling | M5 measurement, then `docs/PERF.md`; do **not** silently change the default to chunking |
| 2 | **text integrity in foreign apps (InputConnection)** | any test case in which foreign text disappears; `snapshot()==null` rate > 0 in host apps; `Rejected` rate unexpectedly high | M4 (zero-tolerance test), then every change to `editor` |
| 3 | **final decode over 300 s** (latency/OOM) | 60-s fixture final > 10 s; OOM/`incomplete` at 300 s; peak RSS near the emulator's RAM | M5 (300-s fixture), open until device measurement |
| 4 | **mic FGS start from a visible IME on API 36** | `ForegroundServiceStartNotAllowedException`/`SecurityException`; missing notification in the start window | M2 (spike, result in `docs/DECISIONS.md`) |
| 5 | ONNX contract drift Android↔Python | `OnnxContractSmokeTest` red; `outputs` ≠ `[1,1,1,8198]`; duration constant; step limit triggers | M1 (gate). Largely defused by `ASR_CONTRACT.md`, regression protection remains |
| 6 | model installation (671 MB, space, hash) | hash mismatch; free storage < 3 GB; resume after ETag change inconsistent | M7 |
| 7 | audio routing/Bluetooth | route not confirmed after 3 s; silent recording; source switches unexpectedly | M6 (implementation) + M8 (device) |
| 8 | short utterances hallucinate | 0.3-s/1-s fixture delivers foreign-language text; first commit without agreement | M5 (golden set) |
| 9 | 15 layouts linguistically incomplete | coverage test finds missing characters; TR/EL special cases red | M3 |
| 10 | emulator ≠ target device (ARM/BT/thermal/battery) | all tests green but no ARM64 run possible | M8: document as **open**, not green |
| 11 | secrets/keystore in the repo | `keystore.properties`/`.models/` visible in `git status` | M0 (.gitignore), every commit check |

**Order:** 5 (gate) → 4 (spike) → 2 (editor) → 1/3 (measurement M5) → 8 → 6 → 9
→ 7/10 (device) → 11 (permanently, on every commit).

---

## H. Work instruction for the coder

### H.1 Order (create files in this sequence)

1. `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`,
   `local.properties`, `gradle wrapper --gradle-version 8.10.2`.
2. `app/build.gradle.kts`, `app/proguard-rules.pro`, `AndroidManifest.xml`,
   `res/xml/method.xml`, `res/values/strings.xml`, minimal theme/drawables.
3. `util/Clock.kt`, `util/Log.kt` → `audio/AudioSource.kt` (+`PcmBlock`),
   `asr/AsrEngine.kt` (+`Hypothesis`), `editor/EditorSink.kt`
   (+`EditPlan`/`EditResult`), `model/ModelStore.kt`. **Interfaces first, then
   implementations** (testability).
4. `ime/VoiceKeyboardService.kt` minimal (only `onCreateInputView` with a
   placeholder view) → **build M0 and evidence it on the emulator**, only then
   continue.
5. `layouts/LayoutAsset.kt`, `layouts/LayoutParser.kt`,
   `assets/schema/layout.schema.json`, `assets/layouts/de-DE.json`, `en-US.json`
   → parser tests → then the remaining 13 assets.
6. `keyboard/KeyboardModel.kt`, `keyboard/KeyboardView.kt`,
   `keyboard/KeyGesture.kt`, `layouts/CaseMapper.kt`, `layouts/DeadKeyEngine.kt`,
   `layouts/LayoutRepository.kt` → wire `ime/ImeRootView.kt` → evidence the M3
   criterion.
7. `editor/Graphemes.kt`, `editor/Utf16.kt`, `editor/DeltaTyper.kt`,
   `editor/OwnershipTracker.kt`, `editor/InputConnectionSink.kt` +
   `FakeEditorSink` → M4.
8. `asr/AsrContract.kt` (values **verbatim** from `ASR_CONTRACT.md`),
   `asr/Vocabulary.kt`, `asr/OnnxSessions.kt`, `asr/TokenBudget.kt`,
   `asr/TdtGreedy.kt`, `asr/ParakeetAsrEngine.kt` → **`OnnxContractSmokeTest`
   (M1) before the first line of `TdtGreedy`**.
9. `audio/PcmWindowStore.kt`, `commit/TextAlign.kt`, `commit/LocalAgreement.kt`,
   `commit/CommitPlanner.kt`, `dictation/*` → `WavInjectionPipelineTest`, then
   `GoldenDecodeTest` (M5).
10. `audio/AudioRecordSource.kt`, `audio/AudioRouter.kt`,
    `audio/CaptureForegroundService.kt`, `ime/SetupActivity.kt` (mic permission)
    → M2/M6.
11. `model/Manifest.kt`, `model/FileModelStore.kt`, `model/Downloader.kt`,
    `ime/SettingsActivity.kt`, `settings/Settings.kt` → M7.
12. release: signing, `lint`, `assembleRelease`, `docs/PERF.md`,
    `docs/DECISIONS.md`, release gate table → M8.

### H.2 What to watch per step

- **M0:** `applicationId` = `de.voicekeyboard`, service name stable,
  `android:exported="true"` + `BIND_INPUT_METHOD`; test `ime enable/set`
  immediately — not only after the UI.
- **Layouts:** widths relative, IDs unique per layer, the same physical key
  keeps its ID across layers; actions only from the allowlist; no silent QWERTY
  augmentation.
- **DeltaTyper:** deletion scope in **UTF-16 units**, boundaries on **grapheme
  clusters**, limit 60 **codepoints** (not characters of tail length); on
  uncertainty `Rejected` instead of "appending".
- **ASR:** encoder **once per window** over all Mel frames; decoder
  **frame-wise** with **one** frame; advance states only on non-blank;
  `t += duration` (index = frame value, evidenced by
  `tools/reference_decode.py`); blank with `dur=0` ⇒ `max(dur,1)`; budget on
  the **whole** window length; make `incomplete` visible.
- **Stop protocol:** stop marker → unblock `read` → thread join → queue drain
  (older blocks) → last read block → EOS → final. Each sample exactly once.
- **State machine:** every change only on the main thread; check `sessionId`,
  `runId`, `editorGeneration` before anything is typed.
- **FGS:** notification **before** model/audio init; the FGS ends with the mic
  release; no keep-alive service.

### H.3 What the coder must NOT do

1. **No invented ONNX tensor names, axes, dtypes, or logit splits.** Sole
   source: `docs/ASR_CONTRACT.md`. Deviation ⇒ test red, do not adapt the code.
2. **No `AccessibilityService`, no `setText`, no clipboard injection** — only
   the `InputConnection` of the active IME.
3. **No `windowed`/chunk streaming as the default.** Whole-buffer is binding;
   the chunk strategy (W16/H8/O8/R2) stays a disabled experiment, activation only
   with a documented release.
4. **No cloud, analytics, or network calls except the one-time model download**
   (and it must be switchable off).
5. **No after-the-fact text normalization** (no regex capitalization, NFC, case,
   or punctuation correction of the transcript).
6. **No VAD gate** that could discard speech; no "silent = do not transcribe".
7. **No fixed token limit**, no silent capping: budget from the formula,
   saturation ⇒ a one-time retry with a larger budget, then a visible error.
8. **No NNAPI/GPU EP**, no second ASR backend, no model swap "because smaller".
9. **Do not assume an `AudioRecord.stop()` leftover buffer** (there is none);
   order per H.2.
10. **No audio/text logs in release**, no audio dumps in product operation, no
    capture in password fields (mic disabled there).
11. **No model into the APK**, no download in CI, no `.models/` or
    `keystore.properties` file committed.
12. **No checklist marked "green" without evidence** (screenshot/test
    run/log line). Non-measurable device gates stay `open`.

### H.4 Definition of done per file

- Compiles under `./gradlew :app:assembleDebug` (no warnings suggesting missing
  API levels).
- Public behavior covered by at least one test (unit preferred; instrumented
  only when Android types are involved).
- No `TODO` placeholders in paths that touch a milestone criterion.
- Reference to the `KONZEPT.md` section in the KDoc when a rule is implemented
  (e.g. "KONZEPT 2.8 shutdown order").

---

## I. Open points ("to verify", do not assume)

| Point | When | How |
|---|---|---|
| `org.json` available under Robolectric (LayoutParser tests) | M0/M3 | first test run |
| JDK `HttpServer` usable in `androidTest` | M7 | first `DownloadResumeTest` |
| emulator RAM for the 652-MB encoder (peak RSS) | M1 | `adb shell dumpsys meminfo de.voicekeyboard` + `Perf` log |
| push path `/sdcard/Android/data/de.voicekeyboard/files/models/` readable | M1 | first emulator run |
| AGP 8.8.2 + `compileSdk 36`: `lint` without blockers | M8 | `lintDebug`/`lintRelease` |
| ORT AAR ProGuard rules at `minifyEnabled=true` | M8 (v1: `false`) | release test run |
| `duration` classes {0,1,2,3,4} = encoder frames (index as value) | M5 | golden text identity against the Python reference |
| ARM64 latency/RSS/thermal, Bluetooth routing, FGS on GrapheneOS | M8 / device | real hardware; without a device leave **open** |

**Non-goals (v1):** cloud ASR, NNAPI/GPU, always-on/wake word, hardware PTT
keys, beam search, translation, learning autocorrection, swipe typing,
plugins/themes, autocorrect dictionaries (DESIGN §7.1, §11).
