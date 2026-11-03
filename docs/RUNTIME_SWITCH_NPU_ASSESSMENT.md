# Runtime switch for NPU access (<device>, Tensor G5, GrapheneOS)

**Status:** 2026-10-28 · **Role:** analysis (no code/build changes)
**Builds on:** `docs/GPU_ACCELERATION_ANALYSIS.md` (G1)
**Legend:** **[EVIDENCED]** = official documentation/source ·
**[ASSUMPTION]** = not verified ·
**[NOT CHECKABLE]** = not measurable in the container/on the device.
**Short version:** a runtime switch brings **no** NPU access on this device.
There is no public path from a third-party app to the Tensor G5 NPU — not
via NNAPI and not via LiteRT, ExecuTorch, MediaPipe, or ONNX Runtime.

---

## 1. NPU accessibility on Pixel: the history and the current state

| Generation | NPU reachable via NNAPI? | Evidence |
|---|---|---|
| Pixel 6 / 6 Pro (Tensor G1) | **yes** — Edge TPU mapped as an NNAPI device | [EVIDENCED] developer.android.com/ndk/guides/neuralnetworks (device list), Google blog "Pixel 6 ML" |
| Pixel 7 / 7 Pro (Tensor G2) | yes (NNAPI) | [EVIDENCED] the same list |
| Pixel 8 / 8 Pro (Tensor G3) | **no** — Google no longer shipped the NNAPI TPU driver; the NPU is only reachable via Google's internal LiteRT/AICore stack | [EVIDENCED, with reservation] Google issue tracker/Android docs "NNAPI supported devices"; [ASSUMPTION] the exact boundary G3 vs. G4 |
| Pixel 9 (Tensor G4) | no | [ASSUMPTION] extrapolation |
| **<device> (Tensor G5)** | **no** — no public NNAPI driver for the NPU; NNAPI itself has been **deprecated since Android 15** | [EVIDENCED] developer.android.com/about/versions/15/behavior-changes-15 (NNAPI deprecated); [NOT CHECKABLE] whether GrapheneOS ships a vendor driver (it does not, it is AOSP + driver blobs without the Google AI stack) |

**Conclusion 1:** there is **no** third-party NNAPI driver for this device that
maps to the NPU. The NPU is attached via Google's private stack
(AICore/Gemini-Nano path); without GMS this stack is absent.

## 2. Candidate runtimes — support matrix

| Runtime | NPU on Tensor G5? | GPU? | Needs GMS? | Model conversion? | Offline? |
|---|---|---|---|---|---|
| **ONNX Runtime 1.31 (today)** | no (NNAPI without a TPU driver; QNN = Snapdragon) | no (Vulkan EP not in the Android AAR, see G1) | no | none | **yes** |
| **LiteRT (TFLite) + NPU delegate** | **no** — the NPU delegate is vendor-specific (Qualcomm QNN, MediaTek NeuroPilot); Tensor is **not** listed [EVIDENCED] ai.google.dev/edge/litert/next/npu_delegate | GPU delegate: OpenCL/GLES, but weak op coverage for transducer/LSTM [ASSUMPTION] | no | **yes, complete** (ONNX→LiteRT) | yes |
| **ExecuTorch** | no — delegates: Qualcomm, MediaTek, Arm Ethos-U, XNNPACK, Vulkan (preview); no Tensor-NPU backend [EVIDENCED] docs.pytorch.org/executorch | Vulkan backend: preview, no int8 [EVIDENCED] | no | **yes, complete** (PyTorch→ET) | yes |
| **MediaPipe / ML Kit** | no — only own/preset models (gesture/text/LLM), no free ASR model [EVIDENCED] ai.google.dev/edge/mediapipe | — | ML Kit partly Play services | not possible | partially |
| **AICore / Gemini Nano** | yes, but **only Gemini Nano** and **only with GMS** | — | **yes** | not applicable (a foreign model) | no |
| **sherpa-onnx** | no (uses ONNX Runtime → the same EP situation) | no | no | none (uses ONNX directly) | yes |

**Conclusion 2:** the only candidate that reaches this device's NPU is
**AICore/Gemini Nano** — and it is (a) GMS-bound, thus ruled out on
GrapheneOS, and (b) not a runtime switch for *our* model but a foreign model
with foreign quality.

## 3. Conversion effort for Parakeet TDT 0.6B v3

Even if an NPU delegate existed, the path would be expensive:

| Graph part | Risk with ONNX→LiteRT/ExecuTorch |
|---|---|
| **Log-Mel frontend** (`nemo128.onnx`) | own STFT/Mel ops; not present 1:1 in LiteRT → would have to be rebuilt as preprocessing in Kotlin/C++ (accuracy loss → golden texts break) [ASSUMPTION] |
| **Conformer encoder** (Conv1d/depthwise, multi-head attention, LayerNorm) | Conv1d/attention are present in LiteRT, but the **int8 activation quantization** needs calibration; ORT `MatMulInteger`/`DynamicQuantizeLinear` has no TFLite equivalent → **re-quantization** [ASSUMPTION] |
| **TDT decoder (LSTM state pair) + joint** | TFLite only has `UnidirectionalSequenceLSTM`; the stateful two-layer PredNet states cannot be mapped → the decoder would have to stay on the CPU [ASSUMPTION] |
| **Worst case** | partial graph on the NPU, decoder + fallback ops on the CPU → **mixed execution** with copy/sync overhead per hypothesis; the result can be **slower** than today, and the golden tests (`ASR_CONTRACT.md` §4.1) would have to be regenerated |

## 4. Expected speedup (estimate, not a measurement)

| Variant | Throughput (10-s clip) | Latency per hypothesis (0.3–1 s cadence) | Cold start |
|---|---|---|---|
| **(a) CPU/XNNPACK (status quo)** | reference (RTF ≈ 0.1–0.4 on ARM, estimated) | reference | ~1–2 s model loading (671 MB from flash) |
| **(b) runtime switch + NPU** | **0×** on this device (no access, §1/§2) | — | — |
| **(c) runtime switch + GPU** (LiteRT GPU/ExecuTorch Vulkan) | **0.8–1.3×** — the weights (600 MB int8) have to pass through the same LPDDR5X per pass; the GPU shares the bandwidth [ASSUMPTION, reasoned in G1 §4] | **0.9–1.2×**, but **+conversion/sync overhead** with partial-graph execution | **worse**: graph compilation/shader start 1–5 s additionally, weight upload |

**When the switch would be slower (explicitly):** (i) as soon as parts of the
graph fall back to the CPU (sync per hypothesis), (ii) on short clips (2–5 s),
because cold start and kernel init dominate, (iii) if the delegate requires
re-quantizing to **fp16** (1.2 GB instead of 671 MB ⇒ double the bandwidth),
(iv) on every app start (model loading).

## 5. What delivers more, more cheaply (without an NPU)

| Lever | Order of magnitude | Effort | Assessment |
|---|---|---|---|
| **Hypothesis interval / chunk size** (instead of 1 s → 1.5–2 s; no encoder over the whole buffer per tick) | **−30…−50 % CPU time and battery**, first-word latency +0.5 s | 1 constant + 1 test | **the biggest effect at our load** — the load is "many full passes", not "slow matrix multiplication" |
| **Thread/quota setting** (`intra_op` 4 vs. 8, `inter_op=1`) | −10…−25 % wall clock | 1 line, measurable in 10 min | do immediately (A0/A1 in G1 §6) |
| **fp16 model instead of int8** | weights 671 → ~335 MB ⇒ **half the bandwidth**; on arm64 with FP16 SIMD plausibly **1.2–1.6×** faster, **but** accuracy must be re-checked (golden texts!) | conversion + new goldens | medium; only if a measurement shows bandwidth as the bottleneck |
| **Smaller model** (e.g. Parakeet-CTC-0.1B / FastConformer-small) | 3–6× faster, but **different texts** ⇒ golden/quality rebuild, possibly a German weakness | high | not for v0.1; an option for v0.2 |

## 6. Decision recommendation

- **(a) v0.1.x:** **no** runtime switch. Instead: measure `intra_op` (A0/A1)
  and check the hypothesis interval — both free and directly affecting
  battery/latency.
- **(b) v0.2:** a runtime switch only as a **measurement experiment** and only
  if a public Tensor-NPU path exists (today: no). The realistic candidate
  remains **fp16 ONNX** (same runtime, half the bandwidth) — no runtime
  switch needed.
- **(c) measurement attempt on the user's device:** first collect the **four
  numbers** from G1 §6: (1) RTF per 10 s of speech, (2) first-word latency,
  (3) RAM peak, (4) a 3-min continuous run (RTF drift/temperature). Only when
  RTF > 0.6 and the CPU levers are exhausted is a runtime experiment
  justified at all.

## ASSUMPTION lines

- `ANNAHME: Pixel 8 (Tensor G3) is the boundary from which NNAPI no longer`
  `sees the TPU — why: Google stopped shipping the NNAPI TPU driver; the exact`
  `generation cannot be verified in the container. Irrelevant for the decision,`
  `because the <device> is affected in any case.`
- `ANNAHME: the LiteRT NPU delegate does not list Tensor — why: the docs name`
  `only Qualcomm/MediaTek; a Google-internal Tensor delegate is not public.`
- `ANNAHME: the speedup ranges in §4 are analogies from G1 (memory-bound), not a`
  `measurement on Tensor G5.`
