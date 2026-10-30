# Memo: zweite Modellvariante downloadbar + in der App umschaltbar

**Stand:** 2026-10-28 · **Rolle:** Entscheidungs-Memo (keine Code-/Build-Änderung)
**Grundlage:** Orchestrator-Messungen (unten), `docs/ASR_CONTRACT.md` §4.1/§4.4,
`docs/KONZEPT.md` §2.6/§2.7, `docs/DESIGN.md` §5.4/§6, `docs/COMMIT_RULE_DECISION_2.md` (E8),
`app/src/main/java/de/voicekeyboard/asr/Hypothesis.kt` (Interface `AsrEngine`),
`docs/NPU_LITERT_FEASIBILITY.md` (G3), `docs/RUNTIME_SWITCH_NPU_ASSESSMENT.md` (G2).

**Gemessene Artefakte (Orchestrator):** A = `istupakov/…-onnx` (ONNX, dynamisch int8, 670,6 MB
gesamt); B = `Arm/parakeet-tdt-0-6b-v3-int8-litert` (4 `.tflite`, **683,2 MB**, cached streaming);
C = `litert-community/…` (i8_stateful 614 MB, f32 2,42 GB, AOT-NPU f32 1,26 GB, 5-s-Fenster).

---

## 1. Was ein „Toggle" technisch ist

**Bestätigt: der Toggle ist keine Quantisierungs-Flagge, sondern eine zweite Engine.**
A läuft über ONNX Runtime (CPU/XNNPACK, `ParakeetAsrEngine`), B/C über **LiteRT** mit anderen
Dateiformaten (`.tflite`), anderem Decoder-Loop (Cached-State vs. ONNX-Frame-Schleife) und anderer
Threading-/Speicher-Charakteristik. „int8" ist bei beiden nur die Gewichtsklasse — die Laufzeit ist
eine andere. Ein Flag `quant=int8|fp16` würde **nichts** umschalten.

**Die dünnste Abstraktion existiert bereits** — `asr/Hypothesis.kt:51`:

```kotlin
interface AsrEngine {
    fun load(modelDir: File)
    suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis
    fun close()
}
```

Darauf aufbauend, **ohne** `commit/`, `editor/`, `dictation/` anzufassen:

| Baustein | Aufgabe | Invarianten, die bleiben |
|---|---|---|
| `AsrEngine` (unverändert) | eine Hypothese pro Fenster, gleiche `Hypothesis`-Form (`tokenIds`, `text`, `frameTimesMs`, `incomplete`) | E8-Commit-Politik, DeltaTyper, Ownership sehen **nur** `Hypothesis` |
| `asr/EngineFactory` (neu) | wählt Engine anhand `variant ∈ {A_ONNX, B_LITERT}` + Verfügbarkeit; **fällt immer auf A zurück** | `dictation/` bekommt weiterhin genau eine Engine |
| `model/ModelVariant` (neu) | Variante → Manifest, Verzeichnis, Dateiliste, Hash | `ModelStore` bleibt Quelle der Wahrheit |
| `LiteRtAsrEngine` (neu) | implementiert `AsrEngine`; hält den LiteRT-`CompiledModel` + Streaming-State | `transcribe()` ist `suspend` und **darf** intern puffern (siehe §2) |
| Lebenszyklus | `load()` einmal, `transcribe()` pro Hypothese, `close()` beim Session-Ende/Wechsel | C-4-Leak-Regel (Engine schließen) |
| Fehlerpfad | jeder LiteRT-Fehler → `Hypothesis` mit `incomplete=true` **oder** Engine-Wechsel auf A, nie Crash, nie stille Fremdsprache | E3 „Tastatur blockiert nie" |

**Kritisch:** `transcribe()` ist heute zustandslos gedacht (Fenster rein → Text raus). B ist
**cache-basiert** (Streaming-State über Aufrufe). Der State gehört in die Engine-Instanz und **muss**
bei `reset()`/neuer Session (`sessionId`-Wechsel) verworfen werden — genau wie der Commit-State.

## 2. Welcher Kandidat passt zu unserem Streaming?

| | B (Arm, cached) | C (litert-community) |
|---|---|---|
| Fenster | inkrementell, **Cache** zwischen Schritten | **5-s-Gleitfenster** mit Überlappung |
| Hypothese alle 0,3–1 s | **ja** (nur neue Frames rechnen) | **nein** — frühestens nach 5 s Audio, dann alle 5 s |
| Gefühlte Latenz | wie heute (2 s `min_audio_s`) | **+3 s** bis zur ersten Hypothese, danach 5-s-Takt |
| E8-Commit-Politik (3 Hypothesen, 2 Wörter ungeschützt) | passt: 3 Hypothesen in ~1–3 s | **bricht**: 3 Hypothesen = 15 s Audio; „fest" erst nach ~15 s |
| Zusatz-Dependency | keine (nur LiteRT) | empfohlen wird **LiteRT-LM `OmniEngine`/`omni_runner`** |

**Entscheidung:** **B** ist der einzige Kandidat, der unsere Chunk-/Commit-Architektur **nicht**
verändert. C ist für unser Push-to-Talk-Muster ungeeignet (5-s-Fenster ⇒ Commit-Latenz ×5) und
seine NPU-Variante ist **f32 1,26 GB** — als *Forschungs*pfad notiert, nicht als Produktvariante.

## 3. Kosten

| Posten | Zahl |
|---|---|
| Download B | 648.784.320 + 27.705.640 + 6.466.576 + 262.864 = **683.219.400 B ≈ 683 MB** (+ SHA256SUMS/metadata ≈ KB) |
| Download C (i8_stateful) | **614 MB** (f32 2,42 GB / NPU 1,26 GB nicht empfohlen) |
| Beide installiert | 670,6 MB (A) + 683,2 MB (B) = **≈ 1,35 GB** Modell­daten |
| Peak-Speicher während Download von B | `.part` + Staging ≈ **+683 MB** ⇒ **≥ 2,0 GB frei** nötig (heute: ≥ 2,5 GB Check bleibt gültig) |
| APK-Zuwachs LiteRT | **`ANNAHME:` 12–25 MB** (Runtime + JNI); NPU-Dispatcher später +5–20 MB (G3) |
| Erst-Start-Download | **bleibt 671 MB** — B wird **optional** nachgeladen („Zusatzvariante"), nicht im Setup-Default |

**Regel:** Varianten werden **einzeln** geladen; `ModelStore` bekommt je Variante ein eigenes
`versions/v-*`-Verzeichnis und einen eigenen `active.txt`-Zeiger. Kein gemeinsames Staging.

## 4. Qualitäts-Anker (Toleranz)

`ASR_CONTRACT.md` §4.1 bleibt **exakt** — aber nur für **A (ONNX)**. Für eine andere Engine ist
„exakt" nicht sinnvoll (anderer Decoder, andere Quantisierung). Verbindliche Toleranz für B/C:

1. **Wort-Identität ≥ 95 %** (Levenshtein auf Wortebene gegen §4.1-Text, über die 4 WAVs).
2. **Zeichen-Edit-Distanz ≤ 2 %** der Zeichenzahl.
3. **Kein fremdsprachiger Output** (die „Vikti/Yeah"-Klasse) — harte Null-Toleranz.
4. **Ankerwörter vorhanden**: `Diktats`, `zweite` (bzw. `Telefon`, `funktioniert` für `de_smoke`).

Messung **ohne neue Schuld**: ein Vergleichslauf-Skript über die **vorhandenen** 4 WAVs
(`testdata/*.wav`), das A- und B-Text nebeneinander ausgibt (Tabelle + Diff) — kein neues Testmaterial,
keine neuen Goldens. Golden-Dateien werden **pro Variante** abgelegt (`golden/A.json`, `golden/B.json`);
§4.1 bleibt die Quelle für A.

## 5. Was auf dem x86_64-Emulator beweisbar ist

| Stufe | beweisbar hier? |
|---|---|
| Engine-Factory/Flag/Persistenz | **ja** (JVM) |
| Varianten-Download + Hash + Resume | **ja** (JDK-`HttpServer`, wie `DownloadResumeTest`) |
| LiteRT-**CPU**-Inferenz (B, 4 `.tflite`) | **ja** — `.tflite` ist SoC-neutral, XNNPACK läuft auf x86_64; Qualitätsvergleich §4 möglich |
| LiteRT-**GPU/NPU**/AOT (C) | **nein** (kein Tensor-G5, kein Dispatcher) |
| Reale Latenz/Thermik (SME2, ARM) | **nein** — nur Nutzergerät |

## 6. Stufenplan (TDD-Ketten, max. 4 Dateien je Auftrag)

| Stufe | Test ZUERST | Dateien | max. | Abbruch | Go/No-Go |
|---|---|---|---|---|---|
| **S0** (verhaltensneutral) | JVM: `EngineFactory` liefert bei `variant=null/A` **denselben** Engine-Typ wie heute; alle 232 Tests + Goldens grün | `asr/EngineFactory.kt` (neu), `asr/Hypothesis.kt` (nur Doku) | 2 h | irgendein Test rot | Go für S1 |
| **S1** | JVM: `ModelVariantTest` — Manifest mit 2 Varianten, `ModelStore` löst je Variante eigenes Verzeichnis + Zeiger auf; bestehende Hash-/Resume-Tests grün | `model/ModelVariant.kt` (neu), `model/ModelManifest.kt`, `model/ModelStore.kt` | 3 h | Hash/Resume-Test rot | Go für S2 |
| **S2** | Instrumented/JVM: `LiteRtEngineTest` lädt die 4 `.tflite` (aus `/data/local/tmp`), transkribiert `de_smoke.wav`, prüft §4-Toleranz (Wort-Identität ≥ 95 %, kein Fremdtext) | `asr/LiteRtAsrEngine.kt` (neu), `app/build.gradle.kts` (Dependency) | 4 h | Crash, leerer Text oder < 95 % | Go für S3 |
| **S3** | JVM: `AsrVariantSwitchTest` — Default A, Wechsel auf B persistiert (`vk_layout`/`asr_variant`), fehlende B ⇒ Fallback A, Wechsel während RECORDING gesperrt | `ime/SetupActivity.kt`, `asr/EngineFactory.kt` | 3 h | Zustand geht verloren oder Wechsel im Diktat möglich | Go für S4 |
| **S4** | Gerät: A/B-Messung (§4-Zahlen) + Qualitätsvergleich; Go nur bei **B ≥ 1,3× schneller** bei Wort-Identität ≥ 95 % | `docs/PERF_VARIANTS.md` (neu) | 3 h | B langsamer oder Qualität < 95 % | Release-Entscheidung |

## 7. Empfehlung

- **Engine-Umschalt-Infrastruktur (S0–S2) gehört in v0.1.x** — verhaltensneutral, macht den
  Weg frei und ist auf dem Emulator vollständig beweisbar.
- **B als optionale Zusatzvariante in v0.2** (Download on demand, Default bleibt A).
- **C nicht ausliefern** — 5-s-Fenster bricht unsere Commit-Latenz; die NPU-Variante ist f32 1,26 GB
  und braucht LiteRT-LM. Als Forschungsnotiz (G3) behalten.

## ANNAHME-Zeilen

- `ANNAHME: LiteRT-APK-Zuwachs 12–25 MB — warum: Runtime+JNI-Größe nicht gemessen,`
  `Erfahrungswert aus ORT-AAR (34 MB). Muss als APK-Delta gemessen werden.`
- `ANNAHME: B läuft auf x86_64-Emulator — warum: .tflite ist SoC-neutral, XNNPACK hat x86-Kernel;`
  `nicht verifiziert (kein Build in diesem Auftrag).`
- `ANNAHME: B-Streaming-State ist pro Session isolierbar — warum: Cache-Modell von Arm;`
  `Schnittstelle nicht dokumentiert, muss in S2 getestet werden.`
