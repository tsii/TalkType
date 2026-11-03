# GPU/NPU acceleration for our ASR (<device>, Tensor G5)

**Status:** 2026-10-28 · **Role:** analysis (no code/build changes)
**Basis (measured, undisputed):** `onnxruntime-android:1.31.1`; the arm64
`libonnxruntime.so` (34 MB) exports `OrtSessionOptionsAppendExecutionProvider_Nnapi`,
**no** Vulkan factory symbol, QNN practically absent; the AAR contains only
`libonnxruntime.so` + `libonnxruntime4j_jni.so`; model Parakeet TDT 0.6B v3
**int8, ~671 MB**, streaming with partial commits; minSdk 29 / target 36; one
signed sideload APK; fully offline.

**Source note:** "[doc]" = official documentation quoted from memory (not
verifiable in the container, URL given). "[measured]" = orchestrator measurement.
"`ASSUMPTION:`" = not verified.

## 1. Verdict (3 lines)

**No for v0.1.x** — there is **no** usable GPU path in our delivered AAR, and
int8 models profit hardly at all on mobile GPUs anyway.
**Yes, but only as a measurement experiment in v0.2** — and then first **NNAPI EP
with CPU fallback** (one line, zero APK growth), not Vulkan.
**Condition for any GPU work:** the device measurement (§6) shows RTF > 0.6 for
10 s of speech **and** CPU tuning (threads, partials interval) is exhausted.

## 2. NPU on Pixel — unreachable for third-party apps without GMS

- The Tensor G5 NPU is connected via Google's **private** LiteRT/NNAPI HAL stack;
  a public third-party app SDK for Tensor NPUs does not exist (in contrast to
  Qualcomm QNN / MediaTek NeuroPilot). [doc:
  developer.android.com/ndk/guides/neuralnetworks]
- **NNAPI has been marked deprecated since Android 15** ("no new features; vendors
  should use their own delegates"). It still works on API 36, but it is no way
  forward. [doc: developer.android.com/about/versions/15/behavior-changes-15]
- **GrapheneOS without GMS** does not worsen the situation further — there simply
  is no Google AI service we could co-use. The NPU remains unreachable for us.
  `ASSUMPTION:` vendor NPU access (Tensor) is possible even with GMS only via
  Google-internal libraries, not via a public API.
- **Consequence:** the NPU must not appear in any planning.

## 3. Which GPU paths really exist in ONNX Runtime 1.31.x on Android

| Path | Status | Usable for us? |
|---|---|---|
| **XNNPACK (CPU)** | included in `onnxruntime-android`, default for arm64 | yes — **status quo** |
| **NNAPI EP** | compiled into the binary [measured]; Java provider package present | **yes, one line** (`NnapiExecutionProvider`), but deprecated (§2) and driver-dependent |
| **Vulkan EP** | **experimental/preview**, **not** in the official mobile AARs; requires our own ORT build with `--use_vulkan` [doc: onnxruntime.ai/docs/execution-providers/Vulkan-ExecutionProvider.html] | **no** — no factory symbol in the `.so` [measured]; the 93 "Vulkan" strings are build/SPIR-V leftovers, not an EP registration |
| **QNN EP (Hexagon)** | Snapdragon-only | no — Tensor G5 is not a Snapdragon [measured/model knowledge] |
| **WebGPU/WebNN** | browser-only | no |

## 4. Why int8 usually brings nothing on a mobile GPU (estimate)

- **Data type:** the Vulkan EP computes fp32/fp16; int8 quantization is not
  implemented there → nodes fall back to the CPU individually and the model
  oscillates between GPU and CPU (sync overhead per node). NNAPI can handle
  quantized, but driver coverage for Conformer ops is patchy in a
  device-specific way. [doc: Vulkan EP "supported ops" list; `ASSUMPTION:` for
  Tensor G5 driver details]
- **Memory-bound, not compute-bound:** 671 MB of weights must pass through
  memory per encoder pass. GPU and CPU share the same LPDDR5X → **no** bandwidth
  gain. With 1-s partials, the whole buffer is re-encoded ⇒ the weight streaming
  time dominates any compute speedup.
- **Short clips:** 2–10 s of audio = a few hundred encoder frames. Kernel launch,
  shader compilation (seconds on the first run!) and CPU↔GPU copies eat the
  advantage; the **model load phase** (671 MB) does not get shorter with a GPU,
  rather longer.
- `ASSUMPTION:` rough order of magnitude: even at 3× compute speedup the gain
  stays < 20 %, because the "compute load" share of the total time is small;
  this is proven only by the measurement in §6.

## 5. Options by effort

| Option | Effort | Expected benefit | APK growth | Offline | Risk |
|---|---|---|---|---|---|
| **(a) CPU/XNNPACK (status quo)** | 0 | reference; RTF on ARM still unmeasured | 0 | yes | none |
| **(b) NNAPI EP + CPU fallback** | **~1 line** + fallback logic, 1 test | 0–30 % for int8, device-specific; can be **slower** | 0 (already compiled in) | yes | deprecated (API 36 ok, future unclear), driver bugs, silent fallback |
| **(c) Vulkan EP** | own ORT build (CMake, NDK, Vulkan SDK) + AAR replacement, weeks | low (int8 missing, fp16 conversion needed) | +5–15 MB (own build) | yes | high: experimental, no official mobile delivery, maintenance with every ORT update |
| **(d) LiteRT/TFLite GPU** | rebuild the Parakeet graph in TF/JAX (ONNX→LiteRT is **not** automated for a stateful TDT decoder), requantization, new golden texts | unclear; GPU delegates hardly support LSTM/transducer decoders | +10–20 MB | yes | very high, reference decoder comparison (ASR_CONTRACT) would break |
| **(e) sherpa-onnx** | runtime switch, own streaming pipeline; internally uses **also** onnxruntime | no GPU gain (same EP situation), but a better streaming architecture | +2–4 MB | yes | medium: architecture/golden rebuild |
| **(f) ExecuTorch/Vulkan** | export the model to ExecuTorch, Vulkan backend (preview) | low, no int8 | +8–15 MB | yes | high: preview status, Parakeet export untested |

**Checked against `docs/KONZEPT.md`:** (d) and (f) do not directly violate the
promises "one signed sideload APK, offline, without GMS", but they cost the
reference decoder equivalence (`ASR_CONTRACT.md` §1/§3) and thereby our golden
test basis. (b) is the only variant with a real effort/benefit ratio.

## 6. Measurement protocol for the real device (the emulator is SwiftShader —
worthless)

A/B on the **same** device, the **same** fixtures (`testdata/de_pause.wav`
9.9 s, `de_smoke.wav` 6.4 s), and the same threads, 3 runs each, median:

| Variant | Setting |
|---|---|
| A0 | CPU, XNNPACK, `intra_op=4` |
| A1 | CPU, XNNPACK, `intra_op=8` |
| A2 | CPU, XNNPACK **off** (`setSessionExecutionMode`/XNNPACK disabled) |
| B | NNAPI EP **with** CPU fallback (do **not** set `NnapiFlags.CPU_DISABLED`) |

**Four numbers per variant:** (1) **RTF** = wall clock / 10 s of speech; (2)
**first-word latency** (start → first displayed word); (3) **RAM peak** (maxRSS,
expected ~0.9 GB); (4) **3-minute continuous run** (RTF drift + battery
temperature/throttling).
**Switching is worth it only if:** **RTF < 0.30** (today estimated 0.1–0.4 on
ARM) **and** first-word latency not worse **and** RAM peak ≤ +10 % **and** no
throttling after 3 min (RTF drift < 15 %). Otherwise CPU stays.

## 7. Recommendation

- **v0.1.x:** CPU/XNNPACK stays; **no** GPU work. Instead, measure thread count
  and partials interval on the Pixel (A0 vs. A1) — that is free and takes effect
  immediately.
- **v0.2:** only if §6 shows RTF > 0.6: measure **NNAPI EP as an opt-in with CPU
  fallback** (variant b); Vulkan/LiteRT/ExecuTorch only once ORT officially ships
  Vulkan in the Android AAR or a LiteRT export for Parakeet exists.

## ASSUMPTION lines

- `ASSUMPTION: Tensor G5 GPU = Imagination PowerVR (D series) — why: <device>`
  `reports mention the switch from Mali to PowerVR; not verifiable in the container.`
  `Consequence for the decision: none (applies equally to any mobile GPU).`
- `ASSUMPTION: NNAPI remains functional on API 36 (deprecated ≠ removed) —`
  `why: Android removes HALs slowly; the risk remains the silent CPU fallback.`
- `ASSUMPTION: Vulkan EP is not in the Android AAR in 1.31.1 — why: no`
  `factory symbol in the .so [measured] + official docs do not list it as a mobile feature.`
