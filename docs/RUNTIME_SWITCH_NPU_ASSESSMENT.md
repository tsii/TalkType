# Runtime-Wechsel für NPU-Zugang (<device>, Tensor G5, GrapheneOS)

**Stand:** 2026-10-28 · **Rolle:** Analyse (keine Code-/Build-Änderung)
**Aufbau auf:** `docs/GPU_ACCELERATION_ANALYSIS.md` (G1)
**Legende:** **[BELEGT]** = offizielle Doku/Quelle · **[ANNAHME]** = nicht verifiziert ·
**[NICHT PRÜFBAR]** = im Container/Gerät nicht messbar.
**Kurzfassung:** Ein Runtime-Wechsel bringt auf diesem Gerät **keinen** NPU-Zugang.
Es gibt keinen öffentlichen Weg von einer Dritt-App zum Tensor-G5-NPU — weder über
NNAPI noch über LiteRT, ExecuTorch, MediaPipe oder ONNX Runtime.

---

## 1. NPU-Zugänglichkeit auf Pixel: die Historie und der Ist-Stand

| Generation | NPU über NNAPI erreichbar? | Beleg |
|---|---|---|
| Pixel 6 / 6 Pro (Tensor G1) | **ja** — Edge TPU als NNAPI-Device gemappt | [BELEGT] developer.android.com/ndk/guides/neuralnetworks (Geräteliste), Google-Blog „Pixel 6 ML" |
| Pixel 7 / 7 Pro (Tensor G2) | ja (NNAPI) | [BELEGT] dieselbe Liste |
| Pixel 8 / 8 Pro (Tensor G3) | **nein** — Google hat den NNAPI-TPU-Treiber nicht mehr ausgeliefert; NPU nur noch über Googles internen LiteRT/AICore-Stack | [BELEGT, mit Vorbehalt] Google-Issue-Tracker/Android-Doku „NNAPI supported devices"; [ANNAHME] exakte Grenze G3 vs. G4 |
| Pixel 9 (Tensor G4) | nein | [ANNAHME] Fortschreibung |
| **<device> (Tensor G5)** | **nein** — kein öffentlicher NNAPI-Treiber für die NPU; NNAPI selbst ist seit **Android 15 deprecated** | [BELEGT] developer.android.com/about/versions/15/behavior-changes-15 (NNAPI deprecated); [NICHT PRÜFBAR] ob GrapheneOS einen Vendor-Treiber mitbringt (tut es nicht, es ist AOSP + Treiberblobs ohne Google-KI-Stack) |

**Fazit 1:** Es gibt für dieses Gerät **keinen** Drittanbieter-NNAPI-Treiber, der auf
die NPU mappt. Die NPU ist über Googles privaten Stack (AICore/Gemini-Nano-Pfad)
angebunden; ohne GMS ist dieser Stack nicht vorhanden.

## 2. Kandidaten-Runtimes — Unterstützungsmatrix

| Runtime | NPU auf Tensor G5? | GPU? | Braucht GMS? | Modellkonvertierung? | Offline? |
|---|---|---|---|---|---|
| **ONNX Runtime 1.31 (heute)** | nein (NNAPI ohne TPU-Treiber; QNN = Snapdragon) | nein (Vulkan-EP nicht im Android-AAR, s. G1) | nein | keine | **ja** |
| **LiteRT (TFLite) + NPU-Delegate** | **nein** — der NPU-Delegate ist herstellerspezifisch (Qualcomm QNN, MediaTek NeuroPilot); Tensor ist **nicht** gelistet [BELEGT] ai.google.dev/edge/litert/next/npu_delegate | GPU-Delegate: OpenCL/GLES, aber schwache Op-Abdeckung für Transducer/LSTM [ANNAHME] | nein | **ja, vollständig** (ONNX→LiteRT) | ja |
| **ExecuTorch** | nein — Delegates: Qualcomm, MediaTek, Arm Ethos-U, XNNPACK, Vulkan (preview); kein Tensor-NPU-Backend [BELEGT] docs.pytorch.org/executorch | Vulkan-Backend: preview, kein int8 [BELEGT] | nein | **ja, vollständig** (PyTorch→ET) | ja |
| **MediaPipe / ML Kit** | nein — nur eigene/vorgegebene Modelle (Gesture/Text/LLM), kein freies ASR-Modell [BELEGT] ai.google.dev/edge/mediapipe | — | ML Kit teils Play-Dienste | nicht möglich | teilweise |
| **AICore / Gemini Nano** | ja, aber **nur Gemini Nano** und **nur mit GMS** | — | **ja** | nicht anwendbar (fremdes Modell) | nein |
| **sherpa-onnx** | nein (nutzt ONNX Runtime → gleiche EP-Lage) | nein | nein | keine (nutzt ONNX direkt) | ja |

**Fazit 2:** Der einzige Kandidat, der die NPU dieses Geräts erreicht, ist
**AICore/Gemini Nano** — und der ist (a) GMS-gebunden, also auf GrapheneOS
ausgeschlossen, und (b) kein Runtime-Wechsel für *unser* Modell, sondern ein fremdes
Modell mit fremder Qualität.

## 3. Konvertierungsaufwand für Parakeet TDT 0.6B v3

Selbst wenn es einen NPU-Delegate gäbe, wäre der Weg teuer:

| Graph-Teil | Risiko bei ONNX→LiteRT/ExecuTorch |
|---|---|
| **Log-Mel-Frontend** (`nemo128.onnx`) | eigene STFT-/Mel-Ops; in LiteRT nicht 1:1 vorhanden → müsste als Vorverarbeitung in Kotlin/C++ nachgebaut werden (Genauigkeitsverlust → Golden-Texte brechen) [ANNAHME] |
| **Conformer-Encoder** (Conv1d/Depthwise, Multi-Head-Attention, LayerNorm) | Conv1d/Attention sind in LiteRT vorhanden, aber die **int8-Aktivierungsquantisierung** braucht Kalibrierung; ORT-`MatMulInteger`/`DynamicQuantizeLinear` hat kein TFLite-Äquivalent → **Neu-Quantisierung** [ANNAHME] |
| **TDT-Decoder (LSTM-State-Paar) + Joint** | TFLite hat nur `UnidirectionalSequenceLSTM`; zustandsbehaftete zweilagige PredNet-States sind nicht abbildbar → Decoder müsste auf CPU bleiben [ANNAHME] |
| **Schlimmster Fall** | Teil-Graph auf NPU, Decoder + Fallback-Ops auf CPU → **gemischte Ausführung** mit Copy-/Sync-Overhead pro Hypothese; Ergebnis kann **langsamer** sein als heute, und die Golden-Tests (`ASR_CONTRACT.md` §4.1) sind neu zu erzeugen |

## 4. Erwarteter Speedup (Schätzung, keine Messung)

| Variante | Throughput (10 s Clip) | Latenz je Hypothese (0,3–1 s Takt) | Kaltstart |
|---|---|---|---|
| **(a) CPU/XNNPACK (Status quo)** | Referenz (RTF ≈ 0,1–0,4 auf ARM, geschätzt) | Referenz | ~1–2 s Modell-Laden (671 MB von Flash) |
| **(b) Runtime-Wechsel + NPU** | **0×** auf diesem Gerät (kein Zugang, §1/§2) | — | — |
| **(c) Runtime-Wechsel + GPU** (LiteRT-GPU/ExecuTorch-Vulkan) | **0,8–1,3×** — Gewichte (600 MB int8) müssen pro Pass durch dasselbe LPDDR5X; GPU teilt die Bandbreite [ANNAHME, begründet in G1 §4] | **0,9–1,2×**, aber **+Konvertierungs-/Sync-Overhead** bei Teil-Graph-Ausführung | **schlechter**: Graph-Kompilierung/Shader-Start 1–5 s zusätzlich, Gewichts-Upload |

**Wann der Wechsel langsamer wäre (ausdrücklich):** (i) sobald Teile des Graphen auf
CPU zurückfallen (Sync pro Hypothese), (ii) bei kurzen Clips (2–5 s), weil Kaltstart und
Kernel-Init dominieren, (iii) wenn für den Delegate auf **fp16** umquantisiert werden
muss (1,2 GB statt 671 MB ⇒ doppelte Bandbreite), (iv) bei jedem App-Start (Modell-Laden).

## 5. Was billiger mehr bringt (ohne NPU)

| Hebel | Größenordnung | Aufwand | Bewertung |
|---|---|---|---|
| **Hypothesen-Intervall / Chunk-Größe** (statt 1 s → 1,5–2 s; Encoder nicht über den ganzen Puffer pro Takt) | **−30…−50 % CPU-Zeit und Akku**, Erstwort-Latenz +0,5 s | 1 Konstante + 1 Test | **größter Effekt bei unserer Last** — die Last ist „viele volle Pässe", nicht „langsame Matrix-Multiplikation" |
| **Thread-/Quota-Setting** (`intra_op` 4 vs. 8, `inter_op=1`) | −10…−25 % Wall-Clock | 1 Zeile, messbar in 10 min | sofort machen (A0/A1 in G1 §6) |
| **fp16-Modell statt int8** | Gewichte 671 → ~335 MB ⇒ **halbe Bandbreite**; auf arm64 mit FP16-SIMD plausibel **1,2–1,6×** schneller, **aber** Genauigkeit neu prüfen (Golden-Texte!) | Konvertierung + neue Goldens | mittel; nur wenn Messung Bandbreite als Engpass zeigt |
| **Kleineres Modell** (z. B. Parakeet-CTC-0.1B / FastConformer-small) | 3–6× schneller, aber **andere Texte** ⇒ Golden-/Qualitäts-Neuaufbau, evtl. Deutsch-Schwäche | hoch | nicht für v0.1; Option für v0.2 |

## 6. Entscheidungsvorschlag

- **(a) v0.1.x:** **kein** Runtime-Wechsel. Stattdessen: `intra_op`-Messung (A0/A1) und
  Hypothesen-Intervall prüfen — beides kostenlos und wirkt direkt auf Akku/Latenz.
- **(b) v0.2:** Runtime-Wechsel nur als **Mess-Experiment** und nur, wenn ein öffentlicher
  Tensor-NPU-Weg existiert (heute: nein). Realistischer Kandidat bleibt **fp16-ONNX**
  (gleiche Runtime, halbe Bandbreite) — kein Runtime-Wechsel nötig.
- **(c) Messversuch auf dem Nutzergerät:** zuerst die **vier Zahlen** aus G1 §6 erheben:
  (1) RTF pro 10 s Sprache, (2) Erstwort-Latenz, (3) RAM-Peak, (4) 3-min-Dauerlauf
  (RTF-Drift/Temperatur). Erst wenn RTF > 0,6 und die CPU-Hebel ausgeschöpft sind, ist
  ein Runtime-Experiment überhaupt zu rechtfertigen.

## ANNAHME-Zeilen

- `ANNAHME: Pixel 8 (Tensor G3) ist die Grenze, ab der NNAPI die TPU nicht mehr sieht —`
  `warum: Google hat den NNAPI-TPU-Treiber nicht weiter ausgeliefert; exakte Generation`
  `nicht im Container verifizierbar. Für die Entscheidung unerheblich, weil <device> in jedem`
  `Fall betroffen ist.`
- `ANNAHME: LiteRT-NPU-Delegate listet Tensor nicht — warum: Doku nennt nur Qualcomm/`
  `MediaTek; ein Google-interner Tensor-Delegate ist nicht öffentlich.`
- `ANNAHME: Speedup-Spannen in §4 sind Analogieschlüsse aus G1 (memory-bound), keine`
  `Messung auf Tensor G5.`
