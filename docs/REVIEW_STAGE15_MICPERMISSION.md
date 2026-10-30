# Review Stufe 15 — Mikrofon-Berechtigung (V16a) + Orchestrator-Eigenänderungen (LevelPeak, MicCaptureTest)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-29
**Gegenstand:** `DictationPermissions` (RECORD_AUDIO-only), `LevelPeak` (+Test), `DictationCoordinator.notelLevel`, `VoiceKeyboardService.levelListener`, `MicCaptureTest`. **Kein Build/Emulator** (Orchestrator: 288/0; MicCaptureTest 3/3 auf <device>).

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| M-01 | **P2** | `MicCaptureTest.kt:87-103` (m2) | **Schwellen zu lasch — ein reiner Rauschboden besteht m2 ohne injizierten Ton.** RMS ≥ 200 / Peak ≥ 1000 entsprechen dem Plan; bei 65 % Senkenlage ist der gemessene Istwert aber Peak ~19 800 / RMS ~1 900 — also ~10–20× über der Schwelle. Ein Mikrofonrauschen (Peak 1 500) oder ein leises Fremdsignal bestünde die Assertion „viel zu leise" obwohl der Zielton nie ankam. m2 beweist damit nur „nicht Stille", nicht „der Ton kam an". | `assertTrue("RMS $rms unter 200…", rms >= 200)`, `peak >= 1000`; Kommentar nennt Ist ~19 800/1 900. | Schwelle in die Ist-Nähe heben (z. B. RMS ≥ 800, Peak ≥ 6 000) oder ein Band `1 000..32 000` asserten, damit nur der tatsächliche Ton (nicht Rauschen) durchgeht. |
| M-02 | **P2** | `VoiceKeyboardService.kt:363-366` + `DictationCoordinator` (`coordinator`-Feld) | **`coordinator` ist nicht `@Volatile`, wird aber vom Capture-Thread gelesen.** Der `levelListener`-Lambda läuft im AudioRecordSource-Capture-Thread und liest `coordinator`; geschrieben wird es auf dem Main-/IME-Thread (`buildCoordinator()`/`onDestroy`). Cross-Thread ohne Volatile = Sichtbarkeits-Race. Folge ist **nur** trivial (stale `coordinator` → `noteLevel` auf einen Float; null → No-Op; kein Crash, keine Auswirkung auf den Diktattext), aber es ist technisch ein ungesicherter Diagnosezugriff. Ein spät laufender Callback nach `release()`/`onDestroy` ist harmlos (Float-Update, kein Ressourcenzugriff). | `levelListener = { rms, _ -> coordinator?.noteLevel(rms) }`; `private var coordinator: DictationCoordinator?`. | `coordinator` als `@Volatile` deklarieren (oder den Level-Kanal explizit über den Coordinator statt über die Service-Feldreferenz führen). |
| M-03 | Note | `LevelPeak.kt:20-26` (`note`) | **Read-Modify-Write auf `@Volatile max` ist nicht atomar — für einen Diagnose-Zähler ok.** `if (rms > max) max = …` ist kein CAS; zwei konkurrierende `note()` könnten ein Update verlieren. In der Praxis Single-Writer (nur der Capture-Thread ruft `noteLevel`), und ein verlorenes Maximum ändert nur den angezeigten Prozentwert. **Kein** P1; als dokumentierte Single-Writer-Annahme tragfähig. `@Volatile` gewährleistet die Sichtbarkeit des zuletzt gesetzten Werts. | `@Volatile private var max`; `note()` check-then-set. | Optional: KDoc „Aufruf nur vom Capture-Thread (Single-Writer)" ergänzen; sonst bleibt es ein bewusster Diagnose-Tradeoff. |
| M-04 | ✓ | `LevelPeak.kt:22-24,29` | **Clamp/Bereich korrekt.** Lineare 0..1-Quelle: `>1f → 1f` (defensiv), `≤0`/`NaN` ignoriert (Spitze bleibt beim letzten echten Max), `percent = max*100`→`coerceIn(0,100)`. Kein Absturz bei krummen RMS-Werten. | `if (rms > max) max = if (rms > 1f) 1f else rms`. | — |
| M-05 | ✓ | `DictationCoordinator.kt:97` (`Peak=`) | **Regel 9 eingehalten.** `diagnosticsLine` trägt nur einen ganzzahligen Maximalwert (0..100 %), keine Samples, keine Zeitreihen, keine Inhalte. `val levelPeak` + `noteLevel()` als einzigem Setter ist eine saubere Kapselung (das Feld `max` bleibt privat). | `"…Peak=${levelPeak.percent()}"`. | — |
| V16-01 | ✓ | `DictationPermissions.kt:20-30` + grep | **V16a-Trennung sauber.** `REQUIRED_IN_ORDER = [RECORD_AUDIO]`, `OPTIONAL_IN_ORDER = [POST_NOTIFICATIONS]`; `nextStep`/`isReady` ignorieren die optionale Liste. `grep POST_NOTIFICATIONS` über `app/src` belegt: nur Manifest-Deklaration, SetupActivity-Anfrage (einmalig, optional), Kommentare — **keine** Stelle, an der `POST_NOTIFICATIONS` das Diktat sperrt. Der Altzustand (Diktat startete nie bei fehlender Notification-Berechtigung) war tatsächlich der Live-Fehler; der FGS läuft laut `CaptureForegroundService` auch ohne sie. | `REQUIRED_IN_ORDER=listOf(RECORD_AUDIO)`; `isReady` über `nextStep`. | — |
| V16-02 | Note | `SetupActivity.kt:193` (`requiredPermissions`) | **Namens-/Markerverhalten inkonsistent (kosmetisch).** `requiredPermissions()` enthält weiterhin das optionale `POST_NOTIFICATIONS` (trotz „required"-Name) und `evaluatePermissions` schreibt ggf. einen `denied_forever_`-Marker dafür — beides harmlos (der Marker wird nur für RECORD_AUDIO gelesen), aber irreführend. | `list.add(Manifest.permission.POST_NOTIFICATIONS)` in `requiredPermissions()`. | Methode in `requestablePermissions()` umbenennen und optionalen Marker nicht als `denied_forever` führen. |
| M-06 | Note | `MicCaptureTest.kt:37-55` (m1) | **m1 ist ein Geräte-, kein App-Beweis.** Es prüft ein rohes `AudioRecord` (state/recordingState), ohne den App-Permission-Flow (`pm grant` erfolgt extern per Orchestrator-Skript) und ohne die `AudioRecordSource`. Damit belegt es §1.3 „echter Mikrofonpfad liefert", aber **nicht** „die App behandelt RECORD_AUDIO korrekt" (das deckt `DictationPermissionsTest`). Kein Mangel für den Zweck, aber nicht als App-Permission-Nachweis zu werten. | Raw-`AudioRecord` direkt; kein `checkSelfPermission`. | Ggf. im Test-KDoc klarstellen: „Gerätebeweis, Permission-Flow testet DictationPermissionsTest". |

---

## Betriebsrisiko (Geräteauswahl) — Empfehlung

Das `-Pandroid.testDevice=<device>` ist **keine** AGP-Eigenschaft → wurde ignoriert, `connectedDebugAndroidTest` lief auf **beiden** verbundenen Geräten. **Zuverlässigste Fixierung für dieses Projekt:**

1. **`ANDROID_SERIAL=<device> ./gradlew …connectedDebugAndroidTest`** (adb selektiert über die Env-Variable; null Konfiguration) — Primärempfehlung.
2. **AGP Managed Devices** (`testOptions.managedDevices.devices { … }` mit einer benannten AVD) — robust fürs CI, verlangt aber AVD-Definition.
3. Direkter `adb -s <serial> shell am instrument` (bereits für MicCaptureTest genutzt) — für singleton-Läufe am einfachsten.

## Verdikt

**freigabefähig** — 0 P0, 0 P1, 2 P2 (M-01 Lasche m2-Schwellen, M-02 `coordinator` nicht `@Volatile`), Rest Notes. V16a (RECORD_AUDIO-only) ist korrekt; der LevelPeak-Diagnosekanal ist Regel-9-sauber und single-writer-tauglich.

**Nicht geprüft:** Live-Verhalten der m2-Schwellen gegen reines Emulator-Rauschen (kein Gerätelauf), der `levelListener`-Thread-Plan über mehrere Diktatsitzungen, reale `POST_NOTIFICATIONS`-Abfrage in SetupActivity auf API 33+.

## Top-3

1. **M-01 (P2)** — m2-Schwellen in Ist-Nähe (RMS ≥ 800 / Peak ≥ 6 000) oder Band, damit ein Rauschboden nicht als „Ton angekommen" zählt.
2. **M-02 (P2)** — `coordinator` `@Volatile` (Capture-Thread-Read vs. Main-Write).
3. **M-06/V16-02 (Note)** — m1-Zweck im KDoc klarstellen; `requiredPermissions()` umbenennen + optionalen Notification-Marker nicht als `denied_forever` führen.