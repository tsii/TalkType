# GPU/NPU-Beschleunigung für unser ASR (<device>, Tensor G5)

**Stand:** 2026-10-28 · **Rolle:** Analyse (keine Code-/Build-Änderung)
**Grundlage (gemessen, unstreitig):** `onnxruntime-android:1.31.1`; arm64-`libonnxruntime.so`
(34 MB) exportiert `OrtSessionOptionsAppendExecutionProvider_Nnapi`, **kein**
Vulkan-Fabriksymbol, QNN praktisch absent; AAR enthält nur `libonnxruntime.so` +
`libonnxruntime4j_jni.so`; Modell Parakeet TDT 0.6B v3 **int8, ~671 MB**, Streaming mit
Teilcommits; minSdk 29 / target 36; eine signierte Sideload-APK; vollständig offline.

**Quellenhinweis:** „[Doku]" = offizielle Doku aus dem Gedächtnis zitiert (im Container
nicht nachprüfbar, URL angegeben). „[gemessen]" = Orchestrator-Messung.
„`ANNAHME:`" = nicht verifiziert.

## 1. Verdikt (3 Zeilen)

**Nein für v0.1.x** — es gibt in unserem ausgelieferten AAR **keinen** nutzbaren
GPU-Pfad, und int8-Modelle profitieren auf Mobil-GPUs ohnehin kaum.
**Ja, aber nur als Mess-Experiment in v0.2** — und dann zuerst **NNAPI-EP mit
CPU-Rückfall** (eine Zeile, Null APK-Zuwachs), nicht Vulkan.
**Bedingung für jede GPU-Arbeit:** die Gerätemessung (§6) zeigt RTF > 0,6 bei 10 s
Sprache **und** CPU-Tuning (Threads, Partials-Intervall) ist ausgeschöpft.

## 2. NPU auf Pixel — für Dritt-Apps ohne GMS nicht erreichbar

- Der Tensor-G5-NPU ist über Googles **privaten** LiteRT/NNAPI-HAL-Stack angebunden;
  ein öffentliches Dritt-App-SDK für Tensor-NPUs existiert nicht (im Gegensatz zu
  Qualcomm QNN / MediaTek NeuroPilot). [Doku: developer.android.com/ndk/guides/neuralnetworks]
- **NNAPI ist seit Android 15 als deprecated markiert** („no new features; vendors
  should use their own delegates"). Auf API 36 funktioniert es noch, ist aber kein
  Zukunftsweg. [Doku: developer.android.com/about/versions/15/behavior-changes-15]
- **GrapheneOS ohne GMS** verschlechtert die Lage nicht zusätzlich — es gibt schlicht
  keinen Google-KI-Dienst, den man mitnutzen könnte. Der NPU bleibt für uns
  unerreichbar. `ANNAHME:` Ein Vendor-NPU-Zugriff (Tensor) ist auch mit GMS nur über
  Google-interne Bibliotheken möglich, nicht über eine öffentliche API.
- **Konsequenz:** NPU darf in keiner Planung vorkommen.

## 3. Welche GPU-Pfade in ONNX Runtime 1.31.x auf Android real existieren

| Pfad | Status | Für uns nutzbar? |
|---|---|---|
| **XNNPACK (CPU)** | in `onnxruntime-android` enthalten, Default für arm64 | ja — **Status quo** |
| **NNAPI-EP** | im Binary einkompiliert [gemessen]; Java-Provider-Paket vorhanden | **ja, eine Zeile** (`NnapiExecutionProvider`), aber deprecated (§2) und treiberabhängig |
| **Vulkan-EP** | **experimentell/preview**, **nicht** in den offiziellen Mobile-AARs; erfordert eigenen ORT-Build mit `--use_vulkan` [Doku: onnxruntime.ai/docs/execution-providers/Vulkan-ExecutionProvider.html] | **nein** — kein Fabriksymbol im `.so` [gemessen]; die 93 „Vulkan"-Strings sind Build-/SPIR-V-Reste, keine EP-Registrierung |
| **QNN-EP (Hexagon)** | Snapdragon-only | nein — Tensor G5 ist kein Snapdragon [gemessen/Modellwissen] |
| **WebGPU/WebNN** | Browser-Only | nein |

## 4. Warum int8 auf mobiler GPU meist nichts bringt (Abschätzung)

- **Datentyp:** Vulkan-EP rechnet fp32/fp16; int8-Quantisierung ist dort nicht
  implementiert → Knoten fallen einzeln auf CPU zurück, das Modell pendelt zwischen
  GPU und CPU (Sync-Overhead pro Knoten). NNAPI kann quantisiert, aber die
  Treiberabdeckung für Conformer-Ops ist gerätespezifisch lückenhaft.
  [Doku: Vulkan-EP „supported ops"-Liste; `ANNAHME:` für Tensor-G5-Treiberdetail]
- **Memory-bound, nicht compute-bound:** 671 MB Gewichte müssen pro Encoder-Pass
  durch den Speicher. GPU und CPU teilen sich dasselbe LPDDR5X → **kein**
  Bandbreiten-Gewinn. Bei 1-s-Partials wird der ganze Puffer neu enkodiert ⇒ die
  Gewichte-Streaming-Zeit dominiert jede Rechenbeschleunigung.
- **Kurze Clips:** 2–10 s Audio = wenige hundert Encoder-Frames. Kernel-Start,
  Shader-Kompilierung (beim ersten Lauf Sekunden!) und CPU↔GPU-Kopien fressen den
  Vorteil; die **Modell-Ladephase** (671 MB) wird durch GPU nicht kürzer, eher länger.
- `ANNAHME:` Grobe Größenordnung: selbst bei 3× Rechenbeschleunigung bleibt der
  Gewinn < 20 %, weil der Anteil „Rechenlast" an der Gesamtzeit klein ist; belegt ist
  das erst durch die Messung in §6.

## 5. Optionen nach Aufwand

| Option | Aufwand | Erwartbarer Nutzen | APK-Zuwachs | Offline | Risiko |
|---|---|---|---|---|---|
| **(a) CPU/XNNPACK (Status quo)** | 0 | Referenz; RTF auf ARM noch ungemessen | 0 | ja | keins |
| **(b) NNAPI-EP + CPU-Rückfall** | **~1 Zeile** + Fallback-Logik, 1 Test | 0–30 % bei int8, gerätespezifisch; kann **langsamer** sein | 0 (schon einkompiliert) | ja | deprecated (API 36 ok, Zukunft unklar), Treiber-Bugs, stiller Fallback |
| **(c) Vulkan-EP** | eigener ORT-Build (CMake, NDK, Vulkan-SDK) + AAR-Ersatz, Wochen | gering (int8 fehlt, fp16-Umbau nötig) | +5–15 MB (eigener Build) | ja | hoch: experimentell, keine offizielle Mobile-Auslieferung, Wartung bei jedem ORT-Update |
| **(d) LiteRT/TFLite-GPU** | Parakeet-Graph nach TF/JAX neu aufbauen (ONNX→LiteRT ist für stateful TDT-Decoder **nicht** automatisiert), Neu-Quantisierung, neue Golden-Texte | unklar; GPU-Delegate unterstützt LSTM/Transducer-Decoder kaum | +10–20 MB | ja | sehr hoch, Referenzdecoder-Vergleich (ASR_CONTRACT) würde brechen |
| **(e) sherpa-onnx** | Runtime-Wechsel, eigene Streaming-Pipeline; nutzt intern **auch** onnxruntime | kein GPU-Gewinn (gleiche EP-Lage), aber bessere Streaming-Architektur | +2–4 MB | ja | mittel: Architektur-/Golden-Neuaufbau |
| **(f) ExecuTorch/Vulkan** | Modell nach ExecuTorch exportieren, Vulkan-Backend (preview) | gering, kein int8 | +8–15 MB | ja | hoch: Preview-Status, Parakeet-Export unerprobt |

**Gegen `docs/KONZEPT.md` geprüft:** (d) und (f) verletzen die Zusagen „eine
signierte Sideload-APK, offline, ohne GMS" nicht direkt, kosten aber die
Referenzdecoder-Äquivalenz (`ASR_CONTRACT.md` §1/§3) und damit unsere
Golden-Test-Basis. (b) ist die einzige Variante mit echtem Aufwand/Nutzen-Verhältnis.

## 6. Messprotokoll für das echte Gerät (Emulator ist SwiftShader — wertlos)

A/B auf **demselben** Gerät, **denselben** Fixtures (`testdata/de_pause.wav` 9,9 s,
`de_smoke.wav` 6,4 s) und denselben Threads, je 3 Läufe, Median:

| Variante | Einstellung |
|---|---|
| A0 | CPU, XNNPACK, `intra_op=4` |
| A1 | CPU, XNNPACK, `intra_op=8` |
| A2 | CPU, XNNPACK **aus** (`setSessionExecutionMode`/XNNPACK deaktivieren) |
| B | NNAPI-EP **mit** CPU-Fallback (`NnapiFlags.CPU_DISABLED` **nicht** setzen) |

**Vier Zahlen je Variante:** (1) **RTF** = Wall-Clock / 10 s Sprache; (2) **Erstwort-Latenz**
(Start → erstes angezeigtes Wort); (3) **RAM-Peak** (maxRSS, erwartet ~0,9 GB); (4)
**Dauerlauf 3 min** (RTF-Drift + Akkutemperatur/Drosselung).
**Umstellung wert** nur wenn: **RTF < 0,30** (heute Schätzung 0,1–0,4 auf ARM) **und**
Erstwort-Latenz nicht schlechter **und** RAM-Peak ≤ +10 % **und** nach 3 min keine
Drosselung (RTF-Drift < 15 %). Andernfalls bleibt CPU.

## 7. Empfehlung

- **v0.1.x:** CPU/XNNPACK bleibt; **keine** GPU-Arbeit. Stattdessen Thread-Zahl und
  Partials-Intervall auf dem Pixel messen (A0 vs. A1) — das ist kostenlos und wirkt
  sofort.
- **v0.2:** nur wenn §6 RTF > 0,6 zeigt: **NNAPI-EP als opt-in mit CPU-Rückfall**
  (Variante b) messen; Vulkan/LiteRT/ExecuTorch erst, wenn ORT Vulkan offiziell im
  Android-AAR ausliefert bzw. ein LiteRT-Export für Parakeet existiert.

## ANNAHME-Zeilen

- `ANNAHME: Tensor G5 GPU = Imagination PowerVR (D-Serie) — warum: Pixel-10-Reports`
  `nennen den Wechsel von Mali zu PowerVR; im Container nicht verifizierbar.`
  `Konsequenz für die Entscheidung: keine (gilt für jede Mobil-GPU gleichermaßen).`
- `ANNAHME: NNAPI bleibt auf API 36 funktionsfähig (deprecated ≠ entfernt) —`
  `warum: Android entfernt HALs langsam; Risiko bleibt der stille CPU-Fallback.`
- `ANNAHME: Vulkan-EP ist in 1.31.1 nicht im Android-AAR — warum: kein`
  `Fabriksymbol im .so [gemessen] + offizielle Doku listet ihn nicht als Mobile-Feature.`
