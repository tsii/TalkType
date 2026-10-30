# Implementierungsplan: Offline-Sprachtastatur (Android 16 / API 36)

**Rolle dieses Dokuments:** ausführbare Arbeitsanweisung für den Coder. Kein Produktivcode,
nur Struktur, Verträge, Reihenfolge, Abnahmekriterien.

**Quellenrang:** `TECH_FACTS.md` (Umgebung/Artefakte) > `docs/ASR_CONTRACT.md`
(gemessener ONNX-Vertrag, überschreibt jede Design-Annahme) > `KONZEPT.md`
(verbindliche Mechanik) > `DESIGN.md` (Architektur; Abweichungen dort bereits
entschieden und hier als Schritte übernommen, **nicht** neu diskutiert).

**Bereits vorhanden und zu nutzen (nicht neu erzeugen):**

| Pfad | Inhalt | Status |
|---|---|---|
| `docs/ASR_CONTRACT.md` | Tensornamen, Shapes, Dtypes, Logit-Split `8198 = 8193 + 5`, `<blk>=8192`, Subsampling 8 (80 ms/Frame), Detokenisierung, verifizierter Greedy | verbindlich |
| `docs/MODEL_SHA256.txt` | SHA-256 aller vier Modelldateien | verbindlich |
| `tools/reference_decode.py`, `.venv-asr/` | lauffähiger Python-Referenzdecoder (onnxruntime 1.31.1) | Referenz |
| `testdata/de_smoke.wav`, `de_pause.wav`, `de_part1.wav`, `de_part2.wav` | 16 kHz mono PCM16, Golden-Texte in `ASR_CONTRACT.md` §4 | Golden-Data |
| `.models/` (671 MB) | `encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, `nemo128.onnx`, `vocab.txt` | lokal, **nicht** ins APK/Repo |
| `keystore/release.jks` | PKCS12, Alias `voicekeyboard`, FP SHA-256 `76:0F:B5:A9:…:AD:EB` (geprüft) | Release-Signatur |

**Konsequenz für das Gate „ONNX-Vertrag":** Der Vertrag ist **erfüllt**. Es bleibt nur
der Android-seitige Smoke-Test gegen die bereits dokumentierten Namen/Shapes (M1).
Ab hier gilt: **keine geratenen Tensornamen** — ausschließlich die aus `ASR_CONTRACT.md`.

---

## A. Projektgerüst

### A.1 Toolchain (Versionen verifiziert am 2026-10-27 in diesem Container)

| Komponente | Version | Evidenz |
|---|---|---|
| JDK | 17.0.20.1 (Debian) | `java -version` |
| Gradle | **8.10.2** (`/usr/local/bin/gradle`) | `gradle -v` |
| AGP | **8.8.2** | Testbuild `/tmp/agpcheck` mit Gradle 8.10.2 grün (`app-debug.apk`) |
| Kotlin | **2.0.21** (`org.jetbrains.kotlin.android`) | im selben Testbuild grün |
| compileSdk / targetSdk | **36** | `platforms/android-36` vorhanden; Testbuild grün, **keine** `suppressUnsupportedCompileSdk`-Krücke nötig |
| minSdk | **29** | TECH_FACTS-Empfehlung „29+"; keine Legacy-Verzweigungen unter 29 |
| Build-Tools | 35.0.0 (AGP-Default) | `build-tools/34.0.0, 35.0.0` vorhanden |
| NDK | 27.0.12077973 | vorhanden, **wird nicht gebraucht** (kein Native-Code, ORT kommt als AAR) |
| Gradle-Kompatibilität | AGP 8.8.2 verlangt Gradle **≥ 8.10.2** → passt exakt zur Installation. **Kein Upgrade auf AGP 8.9+/9.x**, ohne vorher den Wrapper auf ≥ 8.11.1 zu heben (dann separat verifizieren). |  |

Erstabnahme-Regel: Sobald ein Build mit einer anderen AGP-/Gradle-Kombination läuft,
diese Tabelle aktualisieren — nicht die Tabelle als Annahme behandeln.

### A.2 Abhängigkeiten (auf Maven Central / Google Maven geprüft)

| Koordinate | Version | Zweck |
|---|---|---|
| `com.microsoft.onnxruntime:onnxruntime-android` | **1.31.1** | CPU-EP; identische ORT-Version wie die Python-Referenz (`1.31.1`), dadurch kleinster Vertragsdrift |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.10.2** | `Dispatchers.Default`, `StateFlow` (1.11.0 existiert ebenfalls; 1.10.2 gewählt = konservativ) |
| `androidx.core:core-ktx` | **1.19.1** | `NotificationCompat`, Grundlagen |
| `androidx.activity:activity-ktx` | **1.13.0** | Setup-/Settings-Activity, `registerForActivityResult` |
| `androidx.lifecycle:lifecycle-runtime-ktx` | **2.11.0** | `repeatOnLifecycle` für Setup-Screens |
| `junit:junit` | **4.13.2** | JVM-Unit-Tests |
| `org.robolectric:robolectric` | **4.17** | `org.json` + Ressourcen in Unit-Tests (LayoutParser); ORT-AAR **nicht** im Unit-Test-Classpath |
| `androidx.test.ext:junit` | **1.3.0** | Instrumented-Runner (`AndroidJUnit4`) |
| `androidx.test:core` | **1.7.0** | `InstrumentationRegistry`, `ActivityScenario` |
| `androidx.test:runner` | **1.7.0** | Test-Runner |
| `androidx.test.espresso:espresso-core` | **3.7.0** | UI-Assertions Host-App |
| `androidx.test.uiautomator:uiautomator` | **2.4.0** | IME-Sichtbarkeit, globale UI-Interaktion |

Bewusst **nicht** verwendet: NNAPI/GPU-EP, ML-Kit, TFLite, zweite ASR-Backends, Gson/Moshi
(Layout-JSON via `org.json`, Test-Golden als Klartextdatei), Mocking-Frameworks
(Fakes statt Mocks), OkHttp/MockWebServer (Download-Test mit JDK-`com.sun.net.httpserver`,
das im `androidTest`-Quellset verfügbar ist — **beim ersten Testlauf verifizieren**).

`onnxruntime-android` bringt native `.so` für viele ABIs mit; APK deshalb filtern:
`ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` (arm64 = Zielgerät, x86_64 = Emulator).

### A.3 Verzeichnisbaum (ein Modul `app` — bewusst kein Multi-Modul)

```
Voice-Keyboard/
├── settings.gradle.kts            Repositories (google, mavenCentral), rootProject, include(":app")
├── build.gradle.kts               Plugins nur deklarieren (apply false): AGP 8.8.2, Kotlin 2.0.21
├── gradle.properties              JVM-Args, useAndroidX, nonTransitiveRClass, Caching
├── local.properties               sdk.dir=/opt/android-sdk           (untracked)
├── keystore.properties            Release-Passwörter                    (untracked)
├── .gitignore                     .models/ testdata/*.generated build/ .gradle/ local.properties keystore.properties
├── gradle/wrapper/*, gradlew      Wrapper gepinnt auf 8.10.2 (einmalig `gradle wrapper --gradle-version 8.10.2`)
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/schema/layout.schema.json
│       │   ├── assets/layouts/{de-DE,en-US,fr-FR,es-ES,it-IT,pt-PT,nl-NL,pl-PL,tr-TR,ru-RU,cs-CZ,sv-SE,da-DK,fi-FI,el-GR}.json
│       │   ├── assets/models-manifest.json         (SHA-256 aus MODEL_SHA256.txt + Revision + Lizenz-ID)
│       │   ├── java/de/voicekeyboard/…             (Pakete siehe B)
│       │   └── res/{xml/method.xml, xml/backup_rules.xml, values/*.xml, drawable/*.xml, mipmap-*/…}
│       ├── debug/java/de/voicekeyboard/devtools/   WavAudioSource, FakeAsrEngine, DebugModelPath
│       ├── debug/assets/testdata/*.wav             Kopie/Link der Golden-WAVs (nur Debug)
│       ├── test/java/de/voicekeyboard/…            JVM-Unit-Tests (kein ORT)
│       └── androidTest/java/de/voicekeyboard/…     Instrumented + Host-App
├── docs/                          KONZEPT, DESIGN, TECH_FACTS, ASR_CONTRACT, MODEL_SHA256, dieser Plan, PERF.md, DECISIONS.md
├── tools/reference_decode.py      (vorhanden) Golden-Erzeuger, unverändert lassen
├── testdata/*.wav                 (vorhanden) Golden-Audio
└── .models/                       (vorhanden, 671 MB) lokale Artefakte, untracked
```

### A.4 Dateiliste mit Zweck und LOC-Schätzung (Größenklasse, keine Zusage)

**Build & Manifest**

| Pfad | Zweck | ~LOC |
|---|---|---|
| `settings.gradle.kts` | Repos, Modul | 12 |
| `build.gradle.kts` | Plugin-Deklaration | 10 |
| `gradle.properties` | `org.gradle.jvmargs=-Xmx3g`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`, `org.gradle.caching=true`, `org.gradle.parallel=true` | 12 |
| `app/build.gradle.kts` | android-Block (sdk-Versionen, `abiFilters`, `compileOptions`/`kotlinOptions` = 17, `buildFeatures.buildConfig=true`, Debug-Anwendungs-ID `de.voicekeyboard.debug`? **nein** — gleiche ID, Debug/Release getrennt), signingConfig, dependencies, `testOptions.unitTests.isIncludeAndroidResources=true` | 110 |
| `app/proguard-rules.pro` | ORT-Keep-Regeln; v1: `isMinifyEnabled=false`, Regeln vorbereitet | 20 |
| `app/src/main/AndroidManifest.xml` | IME-Service (`BIND_INPUT_METHOD`, meta-data), Mic-FGS (`foregroundServiceType="microphone"`), Setup-/Settings-Activity, Permissions | 70 |
| `res/xml/method.xml` | `input-method`-Meta (Subtypes je Sprache) | 25 |

**Kern-Pakete** (Details je Klasse in Abschnitt B)

| Pfad | Zweck | ~LOC |
|---|---|---|
| `ime/VoiceKeyboardService.kt` | `InputMethodService`, Lifecycle→Controller, Bindung von `InputConnectionSink` | 200 |
| `ime/ImeRootView.kt` | Container: Keyboard + Statuszeile (Mic-Zustand, Quelle, Zeit) | 150 |
| `ime/SetupActivity.kt` | IME aktivieren, Mic-Recht, Modellstatus, Audio-Test | 160 |
| `ime/SettingsActivity.kt` | Sprache/Layout, Quellenwahl, Barrierefrei-Modus, Netzwerk-Schalter | 140 |
| `keyboard/KeyboardView.kt` | eigene View: Rendern, Touch/Pointer-Capture, Repeat, Mic-Zone + Abbruch-Wisch | 450 |
| `keyboard/KeyboardModel.kt` | Layout→gemessene Tasten (Breiten, dp), Treffererkennung | 200 |
| `keyboard/KeyGesture.kt` | DOWN/UP/CANCEL, Long-Press, Repeat, Space-Wisch, Doppeltipp-Caps | 180 |
| `keyboard/EmojiPanel.kt` | Kategorien, Suche, Favoriten, vollständige Sequenzen | 160 |
| `keyboard/Haptics.kt` | optionale Haptik | 40 |
| `layouts/LayoutAsset.kt` | Datenmodell (Layer, Key, DeadKey, Language) | 130 |
| `layouts/LayoutParser.kt` | JSON→Modell, Schema-/Semantikvalidierung, Allowlist | 200 |
| `layouts/LayoutRepository.kt` | Assets + importierte JSONs, Aktivliste, Fallback DE/EN | 130 |
| `layouts/CaseMapper.kt` | sprachsensitives Case-Mapping (tr i/İ, mehrzeichenfähig) | 90 |
| `layouts/DeadKeyEngine.kt` | spacing/combining/compose, Backspace-Reihenfolge | 100 |
| `dictation/DictationController.kt` | Zustandsmaschine + Actor auf Main, Session-/Editor-Generation | 280 |
| `dictation/DictationState.kt` | sealed States + Fehlercodes | 80 |
| `dictation/AsrScheduler.kt` | genau ein Lauf, Snapshots, Zusammenfassen, Final hat Vorrang | 220 |
| `dictation/Session.kt` | Session-ID, PCM-Ablage, Zeitachse | 110 |
| `audio/AudioSource.kt` | Interface + `PcmBlock` (siehe B) | 45 |
| `audio/AudioRecordSource.kt` | `AudioRecord` 16 kHz mono PCM16, `getMinBufferSize`, Chunks 20–40 ms | 180 |
| `audio/PcmWindowStore.kt` | monotoner Samplepuffer, Snapshot-Erzeugung ohne Float-Duplikat | 120 |
| `audio/AudioRouter.kt` | Default-Mic, `setPreferredDevice`/`setCommunicationDevice`, Routenbestätigung + Timeout | 140 |
| `audio/CaptureForegroundService.kt` | FGS-Start/-Stop, Notification im Startfenster, kein Audio über Binder | 230 |
| `audio/Resampler.kt` | nur falls 16 kHz nicht geliefert (zustandsbehaftet) — **optional, erst nach Gerätemessung** | 100 |
| `asr/AsrContract.kt` | exakt die Namen/Shapes/Konstanten aus `ASR_CONTRACT.md` | 60 |
| `asr/AsrEngine.kt` | Interface + `Hypothesis` (siehe B) | 60 |
| `asr/ParakeetAsrEngine.kt` | Mel→Encoder→TDT-Greedy, Budget, Fehlerabbildung | 300 |
| `asr/TdtGreedy.kt` | Frame-Schleife nach `ASR_CONTRACT.md` §3 (pur, testbar mit Fake-Joint) | 200 |
| `asr/OnnxSessions.kt` | drei Sessions, IO-Validierung beim Laden, Threadzahl, Close | 160 |
| `asr/Vocabulary.kt` | `vocab.txt` parsen, Detokenisieren (`\u2581`), Filtertexte | 80 |
| `asr/TokenBudget.kt` | `max(256, min(4096, ceil(12*s)+64))` | 30 |
| `commit/LocalAgreement.kt` | 2 Agreements, Wortgrenze, Graphemgrenze, Präfixschutz | 110 |
| `commit/TextAlign.kt` | Detokenisierte Hypothese ↔ globaler Audioposition | 100 |
| `commit/CommitPlanner.kt` | Commit-Horizont, „kein Commit bei Unsicherheit" | 110 |
| `editor/EditorSink.kt` | Interface + `EditPlan`, `EditResult`, `EditorSnapshot` | 60 |
| `editor/InputConnectionSink.kt` | `deleteSurroundingText`/`commitText`, Callback-Auswertung | 300 |
| `editor/OwnershipTracker.kt` | Editorgeneration, erwartete Cursorposition, eigene Spanne | 160 |
| `editor/DeltaTyper.kt` | Delta-Berechnung, 60-Codepoint-Limit, kein blindes Appending | 170 |
| `editor/Graphemes.kt` / `editor/Utf16.kt` | Graphemcluster, UTF-16-Längen | 110 |
| `model/ModelStore.kt` | Interface + `ModelState` (ABSENT…BROKEN) | 60 |
| `model/FileModelStore.kt` | `.part`→Hash→Smoke→atomarer Swap, Platzprüfung | 230 |
| `model/Manifest.kt` | `assets/models-manifest.json` lesen, Revision/Lizenz | 90 |
| `model/Downloader.kt` | Resume mit `Range`/`ETag`, Abbruch, Fortschritt, Netz-Aus | 200 |
| `settings/Settings.kt` | DataStore-frei via SharedPreferences, `networkDisabled`, Layoutliste | 100 |
| `di/AppGraph.kt` | Prozess-Singletons (Sessions, ModelStore, Engine-Handle), Fakes nur Debug | 110 |
| `util/Clock.kt`, `util/Log.kt` | Injektive Zeit; Logging ohne Audio/Text im Release | 80 |

Summe Produktivcode ≈ **4.700 LOC** + 15 Layout-JSONs ≈ 1.700 Zeilen + Schema ≈ 150.

### A.5 Gradle-Kernschnipsel (Orientierung, keine vollständige Datei)

```kotlin
// app/build.gradle.kts (Auszug)
android {
    namespace = "de.voicekeyboard"; compileSdk = 36
    defaultConfig { applicationId = "de.voicekeyboard"; minSdk = 29; targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    signingConfigs { create("release") {
        storeFile = rootProject.file("keystore/release.jks")   // Pfad: <workdir>/keystore/release.jks
        storePassword = ksProps["storePassword"]; keyAlias = "voicekeyboard"
        keyPassword = ksProps["keyPassword"] } }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
```

`keystore.properties` (untracked, Werte vom Nutzer vorgegeben):
`storePassword=<in local.properties, untracked>` / `keyPassword=<in local.properties, untracked>`.

---

## B. Paketstruktur, Klassen, Schnittstellen

Regel: **`asr`, `commit`, `editor` (DeltaTyper), `layouts` (Parser), `dictation` (Zustandsmaschine)
enthalten keinen Android-UI-Code** und sind auf der JVM vollständig testbar.
Android-Typen nur in `ime`, `keyboard`, `audio` (Impl.), `editor/InputConnectionSink`,
`model/Downloader`.

### B.1 Schnittstellen (Pseudo-Signaturen, verbindlich für Tests)

```kotlin
// audio
data class PcmBlock(val seq: Long, val startSample: Long, val samples: ShortArray) // 16 kHz mono s16
interface AudioSource {
    fun start(sessionId: Long): Boolean          // idempotent: zweiter Aufruf startet nicht neu
    fun read(dst: ShortArray, timeoutMs: Int): Int  // >0 Samples, 0 Timeout/keine Daten, <0 Fehler
    fun stopAndDrain(): List<PcmBlock>           // ÄLTERE Queue-Reste; NICHT der letzte Read
    fun release()
}

// Zeit
interface Clock { fun nowMs(): Long; suspend fun sleepUntil(ms: Long) }   // Fake: virtuelle Uhr

// asr
data class Hypothesis(val tokenIds: IntArray, val text: String, val frameTimesMs: IntArray,
                      val sessionId: Long, val runId: Long, val audioSamples: Int,
                      val incomplete: Boolean)   // incomplete = Sicherheitslimit/Budget getroffen
interface AsrEngine {
    fun load(modelDir: File)                     // validiert IO exakt gegen AsrContract
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
    fun snapshot(): EditorSnapshot?              // null = keine verlässliche Auskunft
    fun applyPlan(plan: EditPlan, generation: Long): EditResult
}

// model
enum class ModelStatus { ABSENT, DOWNLOADING, VERIFYING, READY, BROKEN }
interface ModelStore {
    fun status(): ModelStatus
    fun modelDir(): File
    suspend fun install(progress: (done: Long, total: Long) -> Unit): ModelStatus   // resume + Hash
    fun verifyInstalled(): Boolean
}
```

`LayoutRepository`, `Settings`, `Log` werden analog injiziert (Interfaces nur dort, wo Tests
sie brauchen; keine Interface-Flut).

### B.2 Zuständigkeiten

| Paket | Klassen | Verantwortung / Grenze |
|---|---|---|
| `ime` | `VoiceKeyboardService`, `ImeRootView`, `SetupActivity`, `SettingsActivity` | IME-Lifecycle, `InputConnection` besitzen (nie an den Service weitergeben), Statusanzeige |
| `keyboard` | `KeyboardView`, `KeyboardModel`, `KeyGesture`, `EmojiPanel`, `Haptics` | Rendering/Touch, Pointer-Capture, Mic-Zone/Abbruch-Wisch, kein ASR-Wissen |
| `layouts` | `LayoutAsset`, `LayoutParser`, `LayoutRepository`, `CaseMapper`, `DeadKeyEngine` | JSON-Vertrag, Ebenen, Shift/Caps, Deadkeys, Importvalidierung, Import-Fehler isolieren |
| `dictation` | `DictationController`, `DictationState`, `AsrScheduler`, `Session` | Zustandsautomat + Generationen; verwirft veraltete Läufe; 300-s-Limit |
| `audio` | `AudioSource`, `AudioRecordSource`, `PcmWindowStore`, `AudioRouter`, `CaptureForegroundService`, `Resampler?` | Aufnahme, Reihenfolge/Sequenz, Route, FGS/Notification |
| `asr` | `AsrContract`, `AsrEngine`, `ParakeetAsrEngine`, `TdtGreedy`, `OnnxSessions`, `Vocabulary`, `TokenBudget` | nur Vertragsnamen aus `ASR_CONTRACT.md`, deterministischer Argmax |
| `commit` | `LocalAgreement`, `TextAlign`, `CommitPlanner` | Stabilität + Commit-Horizont, keine Textnormalisierung |
| `editor` | `EditorSink`, `InputConnectionSink`, `OwnershipTracker`, `DeltaTyper`, `Graphemes`, `Utf16` | Besitzprüfung, Revision, Graphem-/UTF-16-Grenzen, **niemals** fremden Text löschen |
| `model` | `ModelStore`, `FileModelStore`, `Manifest`, `Downloader` | 671 MB, Resume, Hash, atomarer Swap, Lizenz |
| `settings` | `Settings` | Layoutliste, Quelle, Barrierefrei-Modus, Netz-Aus |
| `di`, `util` | `AppGraph`, `Clock`, `Log` | prozessweite Verdrahtung, injizierbare Zeit, Release-Logging ohne Inhalte |

**Fakes (nur `src/test` + `src/debug`):** `FakeAudioSource` (Sequenzen, Pausen, verspätete
Reads, Fehler), `FakeAsrEngine` (Hypothesen je Audiostand, Widersprüche, Budgetüberschreitung),
`FakeEditorSink` (Text/Selektion/UTF-16, externe Änderungen, fehlschlagende Calls),
`FakeClock`, `WavAudioSource` (Debug, liest `assets/testdata/*.wav`).

---

## C. Meilensteine M0–M8

Jeder Meilenstein endet mit **einem prüfbaren** Kriterium. Nichts wird „später verifiziert",
was ein Gate ist.

| # | Ziel | Prüfbares Abnahmekriterium |
|---|---|---|
| **M0** | Gerüst + **leeres, installierbares IME-APK** | `./gradlew :app:assembleDebug` grün; `adb install` ok; `adb shell ime enable` + `ime set` ok; Host-App-`EditText` fokussiert → Screenshot zeigt Tastatur; 3 Tasten schreiben `abc` **via `InputConnection`** in die Host-App |
| **M1** | **Gate: Android-ONNX-Smoke gegen den bereits gemessenen Vertrag** (blockierend vor jeder Decoder-Implementierung) | `OnnxContractSmokeTest` grün: alle IN/OUT-Namen, Dtypes und Ranks **exakt** wie `ASR_CONTRACT.md` §2; `encoder.outputs` = `[1,1024,frames]` mit `frames == encoded_lengths[0]` und `frames ≈ ceil(T/8)`; genau ein Decoder-Aufruf mit einem Frame liefert `outputs=[1,1,1,8198]`, `output_states_1/2=[2,1,640]`; Logit-Split `[0..8192]` Token / `[8193..8197]` Duration bestätigt |
| **M2** | **Gate: Mic-ForegroundService aus sichtbarer IME (API 36) + Capture-Spike** | `FgsMicSpikeTest` protokolliert auf API-36-Emulator **Erfolg oder benannte Exception** in `docs/DECISIONS.md`; bei Erfolg: Notification innerhalb des Startfensters, `RECORDING` höchstens 300 s; bei Verbot: definierter Fehlerpfad + Setup-Hinweis, Tastatur bleibt voll benutzbar. Kein stiller Retry |
| **M3** | Layout-Core + bedienbare Tastatur (15 Sprachen) | `LayoutParser`-Tests über alle 15 Assets grün (Ebenen vollständig, IDs eindeutig, Zeilensummen, Shift-Gegenstücke, Action-Allowlist, TR `i/İ`, EL finales Sigma); Emulator: alle Ebenen tippbar, Doppeltipp-Caps, Deadkey+Space, Space-Langdruck wechselt Layout |
| **M4** | Editor-Kern: DeltaTyper + Besitz | `DeltaTyper`-Unit-Tests + `EditorSinkInstrumentedTest` in 6 Host-Editor-Varianten (ein-/mehrzeilig, Passwort, Nummer, Selektion, transformierende/`null`-`InputConnection`): **kein fremder Text gelöscht**; Besitzverlust ⇒ `Rejected`, kein Append; 60-Codepoint-Limit wirkt |
| **M5** | Whole-buffer-Pipeline mit **echtem Modell**, WAV-Injektion, LocalAgreement | `GoldenDecodeTest`: `de_smoke.wav` und `de_pause.wav` liefern **exakt** die Texte aus `ASR_CONTRACT.md` §4; Pausen-Regression: Pipeline-Ergebnis mit Pause ≥ Summe der beiden Teilfenster (KONZEPT §7, „256 vs 137"); Laufzeit/Peak-RSS in `docs/PERF.md` protokolliert |
| **M6** | Echte Aufnahme + Stop-Protokoll + Zustandsmaschine | `ShutdownSequenceTest` grün (Samples exakt **einmal**, Reihenfolge monoton, kein Read nach EOS, doppeltes UP wirkungslos); `dictation`-Tests: UP während `STARTING` wird vorgemerkt, Mic-Start in `FINALIZING` abgelehnt, 300 s Auto-Stop, Editorwechsel cancelt; Emulator-Lauf mit `-no-audio` dokumentiert |
| **M7** | ModelStore/Download/Setup | `DownloadResumeTest` grün (Range, ETag-Wechsel, Abbruch, Hash-Fehler, Platzmangel, aktive Version bleibt intakt); Emulator: Setup installiert 671 MB aus lokalem Testserver, danach Netz entziehen ⇒ Diktat unverändert möglich; ohne Modell bleibt Tastatur nutzbar |
| **M8** | Release: Signatur, Lint, Gerätegates | `assembleRelease` signiert, `apksigner verify` ok, Fingerprint = `76:0F:B5:A9:…:AD:EB`; `lintRelease` ohne neue Fehler; Release-Gate-Liste aus DESIGN §10.4 als Tabelle mit Status `erledigt/offen` (Gerätegates ARM64/BT/Thermal bleiben offen, wenn kein Gerät verfügbar — **nicht** als grün markieren) |

**Reihenfolgebegründung:** M1 vor jeder Decoder-Zeile (Vertrag ist Gate), M2 früh
(FGS-Verbot würde Architektur verschieben), M4 vor M6 (Textintegrität ist teurer als Audio),
M5 misst die reale Ressourcenlage bevor M6 Mikrofon-Daten liefert, M7 erst wenn die Pipeline
steht (sonst debuggt man Download und Decode gleichzeitig).

---

## D. Testplan

### D.1 JVM-Unit-Tests (`app/src/test`, kein ORT, kein Mikrofon)

| Klasse | Testfälle (Auszug) |
|---|---|
| `LocalAgreementTest` | erster Commit erst bei 2 **unterschiedlichen** Audioständen; identischer Snapshot zählt nicht; Wortgrenze (kein halbes Wort); Satzzeichen; Unicode/Emoji; widersprüchlicher Präfix ⇒ kein Commit; 300-s-Sequenz; Historie über Fensterwechsel |
| `DeltaTyperTest` | Append-only; Tail-Revision; 60-Codepoint-Grenze (Löschumfang, nicht Taillänge); Graphemgrenze vor Löschung; Surrogatpaare/Emoji-ZWJ unangetastet; `Rejected` bei Besitzverlust; Teilfehler (Löschung ok, Commit scheitert) ⇒ kein blindes Retry; kein doppeltes Anhängen |
| `TokenBudgetTest` | 1 s ⇒ 256; 20 s ⇒ 304; 300 s ⇒ 3664; Sättigung bei 4096; **Gesamtlänge inkl. Kontext**; Retry nur als explizite Ausnahme |
| `LayoutParserTest` | Schema-Verstöße (fehlende Ebene, `width<=0`, unbekannte `kind`, Action außerhalb Allowlist); unbekannte Haupt-`schemaVersion` ⇒ Ablehnung; alle 15 Assets; Zeilensummen; IDs pro Ebene eindeutig; Zeichenabdeckung je Sprache; Deadkey spacing/compose-Fallback; TR-Case; EL-Diakritika |
| `TdtGreedyTest` | Fake-Joint mit 8198-Vektor: Token-Argmax; `<blk>=8192`; **Duration-Index als Framewert** (`t += dur`); Non-Blank + `dur=0` erlaubt mehrere Labels/Frame; Blank + `dur=0` ⇒ mindestens 1 Frame Fortschritt; Emissionslimit 10/Frame; globales Schrittlimit; `t >= frames` stoppt; NaN/Dim-Fehler ⇒ `ModelError`; Limit-Treffer ⇒ `incomplete=true` (keine stille Kürzung) |
| `ShutdownSequenceTest` | Reihenfolge: Stopmarker → Read entblockt → Capture-Thread-Join → Queue-Drain (ältere Blöcke) → letzter Read-Block → EOS → Finalinferenz; jedes Sample genau einmal; monotone `startSample`; kein Read nach EOS; doppeltes UP/CANCEL idempotent |
| `DictationControllerTest` | Zustände `IDLE/STARTING/RECORDING/STOPPING/FINALIZING/CANCELLING`; UP in `STARTING` vorgemerkt; Mic-Start in `FINALIZING` abgelehnt; 300 s; Editorwechsel/Screen-off cancelt; veraltete `sessionId/runId/editorGeneration` verworfen |
| `CommitPlannerTest`, `TextAlignTest` | Commit-Horizont; Overlap-Duplikate; Detokenisierung `\u2581`; kein Commit bei Unsicherheit |
| `GraphemesTest`/`Utf16Test` | UTF-16- vs. Codepoint-Längen; Cluster-Grenzen (kombinierende Akzente, Emoji-Familien) |
| `WavInjectionPipelineTest` | **ohne Modell**: `FakeAsrEngine` liefert Skript-Hypothesen → Commit + Delta end-to-end, inkl. Pause, Widerspruch, Besitzverlust, Budgetfehler |
| `LayoutRoundTripTest` | Import-Kopie → private Ablage → laden → identisches Modell; defekte Datei isoliert |

### D.2 Instrumented / Emulator (`app/src/androidTest`, API 36)

| Test | Inhalt |
|---|---|
| `OnnxContractSmokeTest` | M1-Gate: IO-Namen/Shapes/Dtypes/1-Frame-Decode gegen `AsrContract.kt` (Regressionsschutz bei Modell-/ORT-Wechsel) |
| `GoldenDecodeTest` | echter Encoder/Decoder auf `assets/testdata/de_smoke.wav`, `de_pause.wav`; Assertion auf die **exakten** Strings aus `ASR_CONTRACT.md` §4. Bei Abweichung: **nicht** die Erwartung aufweichen, sondern Abweichung in `ASR_CONTRACT.md` dokumentieren und CER je Fixture ausweisen |
| `ImeSmokeTest` (uiautomator) | IME aktivieren/setzen, Host-App fokussieren, Sichtbarkeit prüfen, Text committen, Screenshot |
| `EditorSinkInstrumentedTest` | Host-App mit 6 Editorvarianten; Invariante „fremder Text bleibt unangetastet"; Cursorverschiebung während Fake-Inferenz ⇒ keine Revision |
| `FgsMicSpikeTest` | M2-Gate: FGS-Start aus sichtbarer IME; Ergebnis + Exception-Name ins Log/Artefakt |
| `DownloadResumeTest` | JDK-`HttpServer` im Test liefert Range/ETag/Fehler; prüft `.part`, Hash-Abbruch, aktive Version unverändert |
| `CaptureOrderInstrumentedTest` | `AudioRecord`-Pfad nur so weit wie im Emulator möglich; sonst identischer Controller-Pfad mit `WavAudioSource` |

### D.3 WAV-Injektion ohne Mikrofon

1. `WavAudioSource` (nur `src/debug`) implementiert `AudioSource`: liest PCM16/16 kHz mono,
   liefert Blöcke à 20 ms mit `seq`/`startSample` – identische Semantik wie `AudioRecord`.
2. Der Produktionspfad (`DictationController` + `AsrScheduler` + `PcmWindowStore`) wird **nicht**
   umgangen; nur die Quelle wird getauscht. Damit ist der Test aussagekräftig.
3. Instrumented-Weg: Debug-Build mit Intent-Extra (`--ez wavFixture de_pause`) oder
   `BuildConfig`-Feld; **Produktions-APK enthält keine WAV-/Textinjektion** (DESIGN 10.2).
4. Fixtures: `testdata/de_*.wav` vorhanden; zusätzlich per `tools/make_fixtures.py`
   erzeugen (0,3 s / 1 s / 3 s Ausschnitte aus `de_smoke`, 300 s Konkatination aus
   `de_pause`, 30 s Stille). Erzeugte Dateien sind nicht versioniert.

### D.4 Testen ohne die 671 MB

- Standard-CI (`test`, `lint`, `assembleDebug`) ist **modellfrei**: `FakeAsrEngine` +
  `WavInjectionPipelineTest` decken Commit/Delta/Zustand ab.
- ORT-Integration ohne Parakeet: winzige **selbst erzeugte** ONNX-Testgraphen
  (Identität/Add) prüfen Tensor-Lebensdauer, Dtypes und Fehlerpfade — sie belegen **keine**
  ASR-Qualität und dürfen nicht als Golden-Ersatz gelten.
- Der echte Modellpfad (`GoldenDecodeTest`, `OnnxContractSmokeTest`) läuft nur opt-in:
  Modelle liegen lokal unter `.models/`, im Emulator per
  `adb push .models/* /sdcard/Android/data/de.voicekeyboard/files/models/parakeet-tdt-0.6b-v3-int8/`
  (Debug-`ModelStore` liest zuerst dieses Verzeichnis; **Pfad im ersten Emulator-Lauf verifizieren**).
  Kein Download in CI.

### D.5 Golden-Set (Mindestumfang)

| Fixture | Dauer | Erwartung |
|---|---|---|
| `de_smoke.wav` | 6,40 s | Text aus `ASR_CONTRACT.md` §4 |
| `de_pause.wav` | 9,90 s | Text aus §4 **plus** Pausen-Regression |
| `de_part1.wav` | 2,80 s | Text aus §4; **kein** Beleg für `min_audio_s` mehr (alte Aussage war ein Artefakt des Decoder-Bugs, ASR_CONTRACT §4.3) |
| `de_part2.wav` | 3,10 s | Text aus §4 |
| `de_smoke_1.0s.wav` / `de_pause_2.0s.wav` | 1,0 s / 2,0 s | `min_audio_s`-Grenzfall: 1,0 s muss geblockt werden, 2,0 s muss transkribieren (ASR_CONTRACT §4.4) |
| generiert: 0,3 s / 1 s / 3 s / 30 s Stille / 300 s | — | kein Dummytext, leeres Ergebnis zulässig; 300 s ohne Kürzung |

**Beleg für `min_audio_s = 2,0 s` und `whole_buffer`:** In `ASR_CONTRACT.md` §4.4 ist mit dem korrigierten
Decoder gemessen, dass 1,0-s-Fenster halluzinieren (3 Fremdsprachen-Halluzinationen, 1 Totalausfall) und
ab 2,0 s in 23 Proben kein Fehlfall mehr auftritt; 3,0 s bringt keinen messbaren Vorteil. Die frühere
Begründung über `de_part1` („2,8-s-Fenster dekodiert schlechter als im Kontext") ist **widerlegt** — sie war
ein Artefakt des State-Updates bei Blank. `whole_buffer` bleibt Default, aber begründet über Deadlock-Freiheit
und Commit-Logik (KONZEPT.md §5.1), nicht über Fensterqualität. Reproduktion:
`.venv-asr/bin/python tools/make_cut_windows.py && .venv-asr/bin/python tools/measure_min_audio.py --markdown`.

---

## E. Build- und CI-Kommandos

```bash
# --- Umgebung (jede Shell) ---
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH
printf 'sdk.dir=/opt/android-sdk\n' > <workdir>/local.properties   # untracked

# --- einmalig: Wrapper auf die vorhandene Gradle-Version pinnen ---
cd <workdir> && gradle wrapper --gradle-version 8.10.2

# --- Standard (modellfrei, ohne Emulator) ---
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# --- Release-APK mit vorhandenem Keystore ---
./gradlew :app:assembleRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk   # Fingerprint muss 76:0F:B5:A9:12:9D:8A:05:…:AD:EB sein
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -5

# --- Instrumented (Emulator muss laufen) ---
./gradlew :app:connectedDebugAndroidTest
# nur ein Gate:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.voicekeyboard.asr.OnnxContractSmokeTest
# echter Modellpfad (opt-in, Modelle vorher gepusht):
./gradlew :app:connectedDebugAndroidTest -PvkRealModel=true \
  -Pandroid.testInstrumentationRunnerArguments.class=de.voicekeyboard.asr.GoldenDecodeTest
```

`keystore/release.jks` existiert (PKCS12, Alias `voicekeyboard`, Store-/Keypass
`<in local.properties, untracked>`, geprüft per `keytool -list`). `keystore.properties` bleibt untracked;
Build bricht mit klarer Meldung ab, wenn sie fehlt und `assembleRelease` verlangt wird.

---

## F. Emulator-Verifikationsrezept (API 36, x86_64, KVM)

```bash
export ANDROID_HOME=/opt/android-sdk
AVDM=$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager
# Neuanlage (frisches Gerät, nicht die vorhandenen AVDs wiederverwenden)
$AVDM create avd -n vk-api36 -k "system-images;android-36;google_apis;x86_64" \
      -d pixel_7 --sdcard 2048M
# Start (KVM vorhanden: /dev/kvm, Modus 666; 16 Kerne/60 GB RAM im Host)
$ANDROID_HOME/emulator/emulator -avd vk-api36 -no-window -no-snapshot \
      -gpu swiftshader_indirect -accel on -memory 6144 -cores 4 -no-boot-anim \
      -no-audio &                     # -no-audio nur für WAV-Tests; sonst weglassen
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
```

`-no-audio` bewusst: AudioRecord-Tests laufen über `WavAudioSource`; der Emulator kann
Mikrofon-/BT-Routing ohnehin nicht belegen (`-memory 6144`, weil der int8-Encoder allein
schon ~900 MB RSS braucht — gemessen in der Python-Referenz; im Emulator **verifizieren**).

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r -g $APK
adb shell ime enable de.voicekeyboard/.ime.VoiceKeyboardService
adb shell ime set    de.voicekeyboard/.ime.VoiceKeyboardService
adb shell ime list -s                      # muss den Service listen
adb shell dumpsys input_method | grep -m2 -i 'mCurMethodId\|mInputShown'
adb exec-out screencap -p > /tmp/vk-$RANDOM.png     # Sichtbarkeit belegen
adb shell input text "hallo"               # nur Ergänzung, beweist die IME NICHT
adb logcat -c && adb logcat -s VoiceKeyboard:V Asr:V Capture:V > /tmp/vk.log &
```

**Diktat-Simulation ohne Mikrofon:** Debug-Build mit `WavAudioSource`, dann
`adb shell am broadcast -a de.voicekeyboard.DEBUG_WAV --es fixture de_pause` (nur Debug;
Intent-Filter ausschließlich im Debug-Manifest). Ablauf im Screenshot-Log:
`STARTING → RECORDING → STOPPING → FINALIZING → Text im Host-Editor`.
**Modell-Push:** siehe D.4; alternativ `adb shell run-as de.voicekeyboard` für interne Ablage.
**Screenshot-Serie** je Meilenstein in `docs/screens/` (Beleg, kein Produktartefakt).

---

## G. Risiken, Frühwarnindikatoren, Abarbeitungsreihenfolge

| Rang | Risiko | Frühwarnindikator (messbar) | Abgearbeitet in |
|---|---|---|---|
| 1 | **Whole-buffer-Live-Inferenz zu langsam/RAM-intensiv auf ARM** | RTF im Emulator > 0,5; p95 pro 1-s-Update > 1 s; native RSS > 1,5 GB; erster Text später als 8 s; Thermik-Drossel | M5-Messung, dann `docs/PERF.md`; Default **nicht** still auf Chunking ändern |
| 2 | **Textintegrität in fremden Apps (InputConnection)** | irgendein Testfall, in dem fremder Text verschwindet; `snapshot()==null`-Rate > 0 in Host-Apps; `Rejected`-Rate unerwartet hoch | M4 (Zero-Tolerance-Test), danach jede Änderung an `editor` |
| 3 | **Finaldecode über 300 s** (Latenz/OOM) | 60-s-Fixture-Final > 10 s; OOM/`incomplete` bei 300 s; Peak-RSS nahe Emulator-RAM | M5 (300-s-Fixture), offen bis Gerätemessung |
| 4 | **Mic-FGS-Start aus sichtbarer IME auf API 36** | `ForegroundServiceStartNotAllowedException`/`SecurityException`; fehlende Notification im Startfenster | M2 (Spike, Ergebnis in `docs/DECISIONS.md`) |
| 5 | ONNX-Vertragsdrift Android↔Python | `OnnxContractSmokeTest` rot; `outputs` ≠ `[1,1,1,8198]`; Duration konstant; Schrittlimit greift | M1 (Gate). Durch `ASR_CONTRACT.md` weitgehend entschärft, Regressionsschutz bleibt |
| 6 | Modellinstallation (671 MB, Platz, Hash) | Hash-Mismatch; freier Speicher < 3 GB; Resume nach ETag-Wechsel inkonsistent | M7 |
| 7 | Audio-Routing/Bluetooth | Route nach 3 s nicht bestätigt; stille Aufnahme; Quelle wechselt unerwartet | M6 (Implementierung) + M8 (Gerät) |
| 8 | Kurze Äußerungen halluzinieren | 0,3-s-/1-s-Fixture liefert fremdsprachigen Text; erster Commit ohne Agreement | M5 (Golden-Set) |
| 9 | 15 Layouts sprachlich unvollständig | Abdeckungstest findet fehlende Zeichen; TR-/EL-Sonderfälle rot | M3 |
| 10 | Emulator ≠ Zielgerät (ARM/BT/Thermal/Akku) | alle Tests grün, aber kein ARM64-Lauf möglich | M8: als **offen** dokumentieren, nicht als grün |
| 11 | Secrets/Keystore im Repo | `keystore.properties`/`.models/` in `git status` sichtbar | M0 (.gitignore), jede Commit-Prüfung |

**Reihenfolge:** 5 (Gate) → 4 (Spike) → 2 (Editor) → 1/3 (Messung M5) → 8 → 6 → 9 → 7/10
(Gerät) → 11 (dauerhaft, bei jedem Commit).

---

## H. Arbeitsanweisung für den Coder

### H.1 Reihenfolge (Dateien, in dieser Ordnung anlegen)

1. `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`,
   `local.properties`, `gradle wrapper --gradle-version 8.10.2`.
2. `app/build.gradle.kts`, `app/proguard-rules.pro`, `AndroidManifest.xml`, `res/xml/method.xml`,
   `res/values/strings.xml`, Theme/Drawables minimal.
3. `util/Clock.kt`, `util/Log.kt` → `audio/AudioSource.kt` (+`PcmBlock`), `asr/AsrEngine.kt`
   (+`Hypothesis`), `editor/EditorSink.kt` (+`EditPlan`/`EditResult`), `model/ModelStore.kt`.
   **Zuerst die Interfaces, dann Implementierungen** (Testbarkeit).
4. `ime/VoiceKeyboardService.kt` minimal (nur `onCreateInputView` mit Platzhalter-View)
   → **M0 bauen und auf dem Emulator belegen**, erst dann weiter.
5. `layouts/LayoutAsset.kt`, `layouts/LayoutParser.kt`, `assets/schema/layout.schema.json`,
   `assets/layouts/de-DE.json`, `en-US.json` → Parser-Tests → dann restliche 13 Assets.
6. `keyboard/KeyboardModel.kt`, `keyboard/KeyboardView.kt`, `keyboard/KeyGesture.kt`,
   `layouts/CaseMapper.kt`, `layouts/DeadKeyEngine.kt`, `layouts/LayoutRepository.kt`
   → `ime/ImeRootView.kt` verdrahten → M3-Kriterium belegen.
7. `editor/Graphemes.kt`, `editor/Utf16.kt`, `editor/DeltaTyper.kt`, `editor/OwnershipTracker.kt`,
   `editor/InputConnectionSink.kt` + `FakeEditorSink` → M4.
8. `asr/AsrContract.kt` (Werte **wörtlich** aus `ASR_CONTRACT.md`), `asr/Vocabulary.kt`,
   `asr/OnnxSessions.kt`, `asr/TokenBudget.kt`, `asr/TdtGreedy.kt`, `asr/ParakeetAsrEngine.kt`
   → **`OnnxContractSmokeTest` (M1) vor der ersten Zeile `TdtGreedy`**.
9. `audio/PcmWindowStore.kt`, `commit/TextAlign.kt`, `commit/LocalAgreement.kt`,
   `commit/CommitPlanner.kt`, `dictation/*` → `WavInjectionPipelineTest`, dann
   `GoldenDecodeTest` (M5).
10. `audio/AudioRecordSource.kt`, `audio/AudioRouter.kt`,
    `audio/CaptureForegroundService.kt`, `ime/SetupActivity.kt` (Mic-Recht) → M2/M6.
11. `model/Manifest.kt`, `model/FileModelStore.kt`, `model/Downloader.kt`,
    `ime/SettingsActivity.kt`, `settings/Settings.kt` → M7.
12. Release: Signing, `lint`, `assembleRelease`, `docs/PERF.md`, `docs/DECISIONS.md`,
    Release-Gate-Tabelle → M8.

### H.2 Worauf pro Schritt besonders achten

- **M0:** `applicationId` = `de.voicekeyboard`, Service-Name stabil, `android:exported="true"`
  + `BIND_INPUT_METHOD`; sofort `ime enable/set` testen — nicht erst nach der UI.
- **Layouts:** Breiten relativ, IDs pro Ebene eindeutig, gleiche physische Taste behält ID über
  Ebenen; Actions nur aus der Allowlist; keine stille QWERTY-Ergänzung.
- **DeltaTyper:** Löschumfang in **UTF-16-Einheiten**, Grenzen auf **Graphemclustern**,
  Limit 60 **Codepoints** (nicht Zeichen der Tail-Länge); bei Unsicherheit `Rejected` statt
  „anhängen".
- **ASR:** Encoder **einmal pro Fenster** über alle Mel-Frames; Decoder **frame-weise** mit
  **einem** Frame; Zustände nur bei Non-Blank fortschreiben; `t += duration` (Index = Framewert,
  belegt durch `tools/reference_decode.py`); Blank mit `dur=0` ⇒ `max(dur,1)`; Budget auf
  **gesamte** Fensterlänge; `incomplete` sichtbar machen.
- **Stop-Protokoll:** Stopmarker → `read` entblocken → Thread join → Queue-Drain (ältere Blöcke)
  → letzter Read-Block → EOS → Final. Jedes Sample genau einmal.
- **Zustandsmaschine:** jede Änderung nur auf dem Main-Thread; `sessionId`, `runId`,
  `editorGeneration` prüfen, bevor irgendetwas getippt wird.
- **FGS:** Notification **vor** Modell-/Audio-Init; FGS endet mit der Mikrofonfreigabe;
  kein Warmhalte-Service.

### H.3 Was der Coder NICHT tun darf

1. **Keine erfundenen ONNX-Tensornamen, Achsen, Dtypes oder Logit-Splits.** Einzige Quelle:
   `docs/ASR_CONTRACT.md`. Abweichung ⇒ Test rot, nicht Code anpassen.
2. **Kein `AccessibilityService`, kein `setText`, keine Zwischenablage-Injektion** — nur
   `InputConnection` der aktiven IME.
3. **Kein `windowed`/Chunk-Streaming als Default.** Whole-buffer ist verbindlich; die
   Chunk-Strategie (W16/H8/O8/R2) bleibt deaktiviertes Experiment, Aktivierung nur mit
   dokumentierter Freigabe.
4. **Keine Cloud-, Analytics- oder Netzwerk-Calls außer dem einmaligen Modelldownload**
   (und der muss abschaltbar sein).
5. **Keine nachträgliche Textnormalisierung** (keine Regex-Großschreibungs-, NFC-, Case- oder
   Interpunktionskorrektur des Transkripts).
6. **Kein VAD-Gate**, das Sprache verwerfen könnte; kein „stumm = nicht transkribieren".
7. **Kein fixes Tokenlimit**, kein stilles Kappen: Budget aus der Formel, Sättigung ⇒
   einmaliger Retry mit größerem Budget, danach sichtbarer Fehler.
8. **Kein NNAPI/GPU-EP**, kein zweites ASR-Backend, kein Modellaustausch „weil kleiner".
9. **Kein `AudioRecord.stop()`-Restpuffer annehmen** (den gibt es nicht); Reihenfolge nach H.2.
10. **Keine Audio-/Textlogs im Release**, keine Audiodumps im Produktbetrieb, keine
    Erfassung von Passwortfeldern (Mic dort deaktiviert).
11. **Kein Modell ins APK**, kein Download in CI, keine `.models/`- oder
    `keystore.properties`-Datei committen.
12. **Keine Checkliste „grün" ohne Beleg** (Screenshot/Testlauf/Logzeile). Nicht messbare
    Gerätegates bleiben `offen`.

### H.4 Definition of Done pro Datei

- Kompiliert unter `./gradlew :app:assembleDebug` (keine Warnungen, die auf fehlende API-Level
  hindeuten).
- Öffentliches Verhalten durch mindestens einen Test abgedeckt (Unit bevorzugt; instrumented
  nur, wenn Android-Typen im Spiel sind).
- Keine `TODO`-Platzhalter in Pfaden, die ein Meilenstein-Kriterium berühren.
- Bezug zu `KONZEPT.md`-Abschnitt im KDoc, wenn eine Regel implementiert wird
  (z. B. „KONZEPT 2.8 Shutdown-Reihenfolge").

---

## I. Offene Punkte („zu verifizieren", nicht annehmen)

| Punkt | Wann | Wie |
|---|---|---|
| `org.json` unter Robolectric verfügbar (LayoutParser-Tests) | M0/M3 | erster Testlauf |
| JDK-`HttpServer` im `androidTest` nutzbar | M7 | erster `DownloadResumeTest` |
| Emulator-RAM für 652-MB-Encoder (Peak-RSS) | M1 | `adb shell dumpsys meminfo de.voicekeyboard` + `Perf`-Log |
| Push-Pfad `/sdcard/Android/data/de.voicekeyboard/files/models/` lesbar | M1 | erster Emulator-Lauf |
| AGP 8.8.2 + `compileSdk 36`: `lint` ohne Blocker | M8 | `lintDebug`/`lintRelease` |
| ORT-AAR ProGuard-Regeln bei `minifyEnabled=true` | M8 (v1: `false`) | Release-Testlauf |
| `duration`-Klassen {0,1,2,3,4} = Encoderframes (Index als Wert) | M5 | Golden-Text-Identität gegen Python-Referenz |
| ARM64-Latenz/RSS/Thermal, Bluetooth-Routing, FGS auf GrapheneOS | M8 / Gerät | echte Hardware; ohne Gerät **offen** lassen |

**Nicht-Ziele (v1):** Cloud-ASR, NNAPI/GPU, Always-on/Wakeword, Hardware-PTT-Tasten,
Beam Search, Übersetzung, lernende Autokorrektur, Swipe-Typing, Plugins/Themes,
Autokorrektur-Wörterbücher (DESIGN §7.1, §11).
