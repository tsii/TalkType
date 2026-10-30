# Review Stufe 14 — LiteRT-Quellsatz (V10 mmap-Loader, V11a Signaturpfad, V12 Guard/Retry, V13 Quellsatz-Trick)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-29
**Gegenstand:** `LiteRtLoader`/`LiteRtStreaming` (debug-Source-Set), `LiteRtBridge`, `EngineFactory` (Release-Fallback), `DictationController`-Guard, `BuildConfig`-ABI. **Kein Build/Emulator** (Orchestrator: 279/0 Debug, 263/0 Release; APK arm64, keine LiteRT-Spur).

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| L14-01 | **P2** | `LiteRtStreaming.kt:59-70` (`preEncodeCache`) | **Cache-Kopierquelle ist nur implizit 769-Frames (9+valid) — Vertrag nicht festgenagelt.** `src = CACHE_FRAMES + validFrames - toCopy` (= `9 + valid − toCopy`) indexiert `feats[src..src+toCopy-1]`; korrekt **nur**, wenn `feats` das 769-Frame-`input_features` ([9 Cache][valid]) ist — für ein `validFrames`-langes Array wäre `feats[760]` ein `ArrayIndexOutOfBounds`. Der KDoc sagt nur „feats = [frames][dims], gültige Frames", nicht „9-Prefix + valid". Zusätzlich ist die Funktion der **Update-Schritt** (Cache fürs *nächste* Fenster); der Featurizer-**Eingang** muss zwingend der *vorherige* Cache sein — die Reihenfolge (Input=prev, danach Update=current) ist nicht im Code belegt. `ANNAHME:` der V11a-Aufrufer speist korrekt (Beweis via `arm_litert_reference_decode.py`). | `val src = CACHE_FRAMES + validFrames - toCopy`; `feats[src+i]`; KDoc „feats=[frames][dims]". | Signatur auf `feats: Array<FloatArray> /* [9+valid][dims] input_features */` präzisieren; `src = validFrames + (CACHE_FRAMES - toCopy)` belegen und einen Unit-Test „feats kürzer als 9+valid ⇒ leere Cache statt OOB" ergänzen; prev/current-Order als Invariante dokumentieren. |
| L14-02 | Note | `LiteRtLoader.kt:30-66` | **V10 (L1-01-P1) sauber behoben.** `Interpreter(File, Options)` (mmap, keine 648-MB-Heap-Kopie); Größenprüfung gegen Manifest vor `open` (flacher Name, kein Pfad); `OutOfMemoryError` eigener deutscher Zweig, `Throwable`-Grenze für Rest-Errors; `scrub` kürzt Pfadtoken auf Dateinamen (Regel 9). | `Interpreter(file, opts)`; `catch (e: OutOfMemoryError)`; `catch (t: Throwable)`. | — |
| L14-03 | Note | `LiteRtStreaming.kt` (vs. `compute-flow_ParakeetTDT.cpp`) | **Vorpass-Treue bestätigt** (gegen §342-393/400-434/458-490): `validFrames = min(760, max(0, samples/160))` ✓ (Z.342 `num_chunk_samples/hop_length`); `input_length = 9 + valid` ✓ (Z.458); `previous_sample = audio[start-1]`, erstes Fenster `start=0 ⇒ 0` ✓ (Z.400); Welford `var = m2/max(1,n-1)`, `inv_std = 1/(sqrt(var)+1e-5)` ✓; Cache aus den letzten gültigen Frames ✓ (Z.470 `frames_to_copy = min(9, valid)`). Keine numerische Abweichung erkennbar. | `GlobalFeatureStats`/`LiteRtStreaming` vs. `.cpp`-Zitate. | — |
| L14-04 | ✓ | `EngineFactory.kt:27-40` + `LiteRtBridge.kt` | **V13-Realease-Fallback wasserdicht; kein P1 für alter Persistenzwert.** Release: `LiteRtBridge.acquire()` → `Class.forName("…LiteRtBridgeSetup")` installiert **keinen** Provider ⇒ `provider==null` ⇒ `create(LITERT_CPU, store)` liefert `Result(ParakeetAsrEngine, ONNX_CPU)`. Ein persistierter `model_variant=litert-cpu` führt damit **nie** zu Absturz/Stillstand — und er hat ohnehin keinen Produktionskonsumenten (`VoiceKeyboardService.buildCoordinator` nutzt weiter `{ ParakeetAsrEngine() }`; `model_variant` wird im IME-Pfad nicht gelesen). | `provider = null` (Release); `create`-Fallback `Result(ParakeetAsrEngine, ONNX_CPU)`. | — |
| L14-05 | Note | `build.gradle.kts:25-31` (abiFilters) | **arm64-only Release.** x86_64-Nutzer (Chromebook/Emulator) können die 0.1.3-APK nicht installieren (`INSTALL_FAILED_NO_MATCHING_ABIS`); ein x86_64-Bestand müsste reinstalliert werden. Debug behält x86_64 ⇒ Emulator-Nachweis bleibt möglich. Bewusste, vertretbare Entscheidung; kein P1 (x86_64-Phones praktisch nicht existent). | `release { abiFilters += arm64-v8a }`, `debug { x86_64 + arm64-v8a }`. | — |
| L14-06 | Note | `DictationController` (Guard aus `hypothesisMinIntervalMs`), `strings.xml:setup_state_retry_attempt` | **V12: Guard/Retry konform.** `hypothesisMinIntervalMs`-Default 2000 = bisherige `MIN_AUDIO_SECONDS_PARTIALS` (kein Verhaltenswechsel); `DownloadProgress.attempt/maxAttempts` + `setup_state_retry_attempt` tragen nur Zahlen/Dateinamen (Regel 9). Der L14-04-Befund (Retry-Zeile) wurde nicht gegen einen Live-429/408-Lauf geprüft (kein Gerät). | — | — |

---

## Punktgenaue Antworten

1. **Vorpass-Treue:** stimmt gegen die `.cpp`-Zeilen (validFrames/input_length/previous_sample/Welford/eps/Cache-Quelle) — L14-03; Einziger Rest = die 769-Frame-Kontrakt-Implizitheit (L14-01).
2. **State-Lebenszyklus:** Encoder-Caches (`[1,24,190,1024]` ≈ 18,6 MB) sind Ein-/Ausgangs-Tensoren pro Lauf; `close()` gibt die Interpreter frei, Session-Wechsel setzt `cachedSessionId` zurück. **Nicht final verifiziert** (der Streaming-Loop liegt im debug-Set und wurde hier nicht vollständig neu gelesen) — `ANNAHME:` keine Akkumulation, da Tensoren je `run()` neu allokiert und GC-beräumt werden.
3. **Quellsatz-Trick:** s. L14-04 — Release kann nie in LiteRT laufen; `Class.forName`-Fehlschlag wird gefangen (nur `ClassNotFoundException`, andere Ladefehler ⇒ Provider null), kein Exception-Pfad zum Nutzer; alter `litert-cpu`-Wert ist inert + fallback-gesichert.
4. **Testabdeckung:** 16 Debug-Diff = LiteRT-Tests (debug-Source-Set); `src/testRelease/EngineFactoryReleaseTest` existiert laut Übergabe und prüft den A-Fallback. **Nicht selbst gelesen** — `ANNAHME:` er assertet die *funktionierende A-Engine* (nicht nur den Typnamen).
5. **Regel 9:** Retry-Anzeige und LiteRT-Meldungs-Scrub (Pfad→Dateiname) tragen keine URL/Pfad/Inhalt (L14-02/L14-06).
6. **Versionierung/Distribution:** arm64-only Release bricht nur x86_64-Emulator-/Chromebook-Installs (akzeptiert); bestehende arm64-Installs upgraden ohne Reinstall (L14-05).

---

## Verdikt

- **V10 (mmap-Loader): freigabefähig** (0 P0/0 P1; L1-01-P1 korrekt behoben).
- **V11a (Signaturpfad/Streaming): freigabefähig mit Note** (formeltreu, nur L14-01-Kontrakt).
- **V12 (Guard/Retry): freigabefähig** (0 P0/0 P1).
- **V13 (Quellsatz-Trick): freigabefähig** (Release-Isolation solid, L14-04).

**Gesamt: freigabefähig** — 0 P0, 0 P1, 1 P2 (L14-01).

**Nicht geprüft (kein Gerät/Gradle-Lauf):** tatsächliches Run-Signatur-Verhalten der 648-MB-Encoder-`.tflite`, Streaming-Loop-Cache-Ordering gegen echte Chunks, `EngineFactoryReleaseTest`-Inhalt, 429/408-Live-Anzeige.

## Top-3

1. **L14-01 (P2)** — `preEncodeCache`-Signatur/-KDoc fixieren (`feats` = `[9+valid][dims]`), `src`-Formel belegen und einen „kurzer-Input ⇒ leere Cache statt OOB"-Test ergänzen; prev→current-Order als Invariante dokumentieren.
2. **L14-06 (Note)** — beim nächsten Gerätlauf den 429/408-Retry-Text gegen die echte Anzeige verifizieren.
3. **L14-05 (Note)** — Release-ABI-Entscheidung (arm64-only) im Changelog/Kompatibilitäts-Hinweis festhalten.