# Weighing fp16 vs int8 (Parakeet TDT 0.6B v3, ONNX Runtime 1.31.1)

**Status:** 2026-10-30 · **Role:** measurement/decision (no code/model changes)
**Assignment:** check whether the already converted fp16 variant brings anything —
**without the phone**.
**Measurement location:** container CPU (x86_64), `onnxruntime 1.31.1` = the
**same** ORT version as in the Android AAR. The emulator is out for native ORT
runs (SIGSEGV in `libonnxruntime.so`, ARM bridge, cf. `HANDOFF.md`), which is why
**all** numbers here are ratios, **no phone RTF**.
**Rule 9:** no audio/text content in this document — only numbers and speech
samples as length indications.

---

## 1. Inventory & structure (step 1)

`.models-fp16/` (measured, `sha256sum`):

| File | Bytes | SHA-256 |
|---|---|---|
| `encoder-model.float16.onnx` | 1,238,960,452 | `4fdbec7d6fc14ca97ed81f5f4eb61792a4eea752a292f81f63c5ee619b25795b` |
| `decoder_joint-model.float16.onnx` | 36,269,630 | `acc2eda03bf3a81ed53a2c529720c1ffa6fc16ca94c23953ca0d9b5b83f84d8e` |
| `nemo128.onnx` | 139,764 | `a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f` (identical to int8) |
| `vocab.txt` | 93,939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` (identical to int8) |
| `config.json` | 97 | `666903c76b9798caf2c210afd4f6cd60b08a8dbf9800ec8d7a3bc0d2148ac466` |

Sum of the **four** model files (that is what `ModelStore` counts):
**1,275,463,785 B** vs. int8 **670,619,706 B** → **1.90×**.

**Structure specification (not implemented, only described):** `ModelStore`
expects per variant `active-<variant>.txt` → `versions/v-<id>/` with the
**storeNames** from `model/ModelManifest.kt` (`nemo128.onnx`,
`encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`, `vocab.txt`). The
fp16 files are named `…float16.onnx` ⇒ a variant `ONNX_FP16` needs **own
storeName entries** (or renamed files) — merely copying into `versions/v-*/`
would count as `BROKEN`.

## 2. Quality (step 2) — **identical**

Reference path `tools/reference_decode.py` (int8) or a copy under `/tmp` with
fp16 file names; material `testdata/de_smoke.wav` (6.40 s) and
`testdata/de_pause.wav` (9.90 s), target text from `docs/ASR_CONTRACT.md` §4.1.

| File | int8 tokens | fp16 tokens | Token IDs identical | Text identical | Characters |
|---|---|---|---|---|---|
| `de_smoke.wav` | 27 | 27 | **yes** | **yes** | 79 / 79 |
| `de_pause.wav` | 25 | 25 | **yes** | **yes** | 79 / 79 |

⇒ **word identity 100 %, CED 0 %** against the respective int8 reference (and thus
also against §4.1). **No** quality gain from fp16 on our material — expected,
since int8 is already error-free there.

### 2a. Finding: the delivered fp16 files are **not loadable**

Without intervention, session setup fails reproducibly:

```
onnxruntime.capi.onnxruntime_pybind11_state.Fail: [ONNXRuntimeError] : 1 : FAIL :
Load model from .models-fp16/encoder-model.float16.onnx failed: Type Error:
Type parameter (T) of Optype (Add) bound to different types
(tensor(float) and tensor(float16) in node (/pre_encode/Add).
```

**Cause (graph-exact):** all 612 initializers are fp16 (type 10), the graph IOs
are deliberately fp32 (`keep_io_types`), **but** three `Cast` nodes in the
`pre_encode` block (`/pre_encode/Cast`, `Cast_1`, `Cast_2`) cast to **float32**
(`to=1`) and feed fp16 `Add` nodes with that — a type conflict the converter did
not resolve.

**Diagnostic patch (only for measuring, `to=1` → `to=10`, copy under `/tmp`,
repo untouched):** with that, the model loads and delivers **exactly** the int8
texts (§2).

**Important — the patch is NOT a production solution:** `pre_encode` then computes
in fp16; integer frame counts are exact in fp16 only up to **2048** (11-bit
mantissa). Uncritical at 990 Mel frames (9.9 s), but with long dictations
(≥ ~20.5 s ⇒ ≥ 2048 frames) **rounding of the frame arithmetic** looms. The clean
approach: leave `pre_encode` in **fp32** and insert a `Cast` to fp16 **after** the
frame count computation (converter option `keep_io_types` + correct cast
insertion, or a Neo model with explicit casts).

## 3. Runtime (step 3) — fp16 is **slower**

3 runs each, median, same CPU, `--threads 8`:

| Material | Variant | RTF (compute) | Encoder | Decoder | Wall clock per process (incl. session load) |
|---|---|---|---|---|---|
| `de_smoke` (6.40 s) | int8 | **0.0162** | 97 ms | 4 ms | **1.34 s** |
| `de_smoke` | fp16 | 0.0216 | 122 ms | 8 ms | 3.09 s |
| `de_pause` (9.90 s) | int8 | **0.0136** | 122 ms | 4 ms | **1.59 s** |
| `de_pause` | fp16 | 0.0173 | 147 ms | 16 ms | 3.12 s |

**Ratios fp16 / int8:**

| Metric | `de_smoke` | `de_pause` | Mean |
|---|---|---|---|
| **RTF (compute)** | 1.333 | 1.272 | **≈ 1.30** ⇒ fp16 **30 % slower** |
| **Wall clock (incl. load)** | 2.30 | 1.97 | **≈ 2.13** ⇒ **a good twice as slow** at start |
| **maxRSS during load** (child process) | — | 924 MB → **3,838 MB** | **≈ 4.2× memory** |

**Rationale:** ORT CPU has no end-to-end fp16 kernels on x86; the model is
internally upcast to fp32 (additional copies) **and** the 1.90× larger weights
must pass through the same memory bus. Together this explains the 30 % extra
compute time and the doubled start time (reading 1.24 GB from disk + converting
initializers).

## 4. Decision template

| Criterion | int8 (today) | fp16 | Rating |
|---|---|---|---|
| Size (4 files) | **670,619,706 B** | 1,275,463,785 B (**1.90×**) | int8 |
| Download (10 / 25 / 50 Mbit/s) | 9.0 / 3.6 / 1.8 min | 17.0 / 6.8 / 3.4 min | int8 |
| Quality on our material | 100 % (§4.1 exact) | **100 %** (identical IDs) | **tie** |
| Compute RTF (container CPU) | 0.0136–0.0162 | 0.0173–0.0216 (**1.30×**) | int8 |
| Start time per process | 1.34–1.59 s | 3.09–3.12 s (**2.13×**) | int8 |
| maxRSS during load | 924 MB | **3,838 MB** (4.2×) | int8 (**critical on 4-GB devices**) |
| Deliverable today | yes, proven on device | **no** (3 cast nodes, see §2a) | int8 |

### Recommendation: **do not ship fp16.** int8 stays the variant.

Rationale: fp16 brings **zero** quality gain (identical token IDs), costs **1.90×
download**, **1.30× compute time**, **2.13× start time**, and **4.2× memory
during load** — on a phone with 4–8 GB RAM the memory point is the hard
disqualifier (the 671-MB encoder already needs ~0.9 GB RSS today; fp16 lands at
~3.8 GB). The widespread assumption "fp16 is faster on ARM" holds only with
**FP16 SIMD kernels in the runtime backend** (XNNPACK/MLAS with `fp16arith`) —
ORT CPU does not use them for this model, as the measurement shows.

### What the result does **not** say

- **No phone RTF.** Neither ARM64 nor Tensor G5 was measured; the ratio of 1.30
  is an x86 CPU value. On ARM, fp16 could *theoretically* turn out differently
  (FP16 SIMD exists on ARMv8.2+), but that cannot be claimed without a device
  measurement.
- **No statement about an NPU path** (fp16 on GPU/NPU is a different topic, see
  `NPU_LITERT_FEASIBILITY.md`).

## 5. If fp16 is to be pursued anyway (order)

1. **Converter fix:** `pre_encode` in fp32 + cast to fp16 (no `to=10` hack),
   regenerate the model, **load test** in ORT 1.31.1 (Python) and in the Android
   AAR — without this test, any further measurement is worthless.
2. **Check frame arithmetic:** 300-s fixture ⇒ Mel frames ≫ 2048; compare the
   result against int8 (token IDs).
3. **Only then** a device measurement (RTF/start time/RSS) — and only if 1./2.
   are green.

## ASSUMPTION lines
- `ASSUMPTION: The 3 cast nodes are the only load error; after patching, both
  reference files ran without further type errors (observed, not formally proven).`
- `ASSUMPTION: The x86 RTF factor of 1.30 is not directly transferable to ARM (FP16 SIMD).`
