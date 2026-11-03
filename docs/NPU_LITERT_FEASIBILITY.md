# NPU via LiteRT AOT on Tensor G5 — feasibility for our dictation

**Status:** 2026-10-28 · **Role:** feasibility/plan (no code/build change)
**Builds on:** `docs/GPU_ACCELERATION_ANALYSIS.md` (G1),
`docs/RUNTIME_SWITCH_NPU_ASSESSMENT.md` (G2)
**Legend:** **[EVIDENCED]** official documentation · **[THESIS]** user
research, **not** verifiable here · **[ASSUMPTION]** justified estimate ·
**[NOT CHECKABLE]** impossible in the container/emulator

> **Important on the source situation:** the user's research (LiteRT ≥ 2.1.6,
> `SocModel.TENSOR_G5`, `litert-npu-runtime-google-tensor`, AI pack delivery,
> `useLegacyPackaging` blocker, fail-closed gate) is **plausible and
> detailed**, but I **cannot cross-check it here** (no network, no device, no
> Gradle). I treat it as a **[THESIS]** and structure the plan so that each of
> its assumptions has its **own, cheap check stage**. G2 stands on one point:
> **without** this toolchain there is no NPU route.

---

## 1. Verdict (3 lines)

**Viable — but only with requantization and only as a parallel path**, not as
a replacement: the encoder is a single graph and therefore AOT-capable; the
decoder (stateful LSTM + joint) stays **necessarily** on the CPU.
**The one condition that must be proven first:** a **fail-closed** proof that
the encoder run on the device **really** runs on the NPU (not XNNPACK)
**and** that the golden text from `ASR_CONTRACT.md` §4.1 is preserved
**exactly** in the process.
If one of the two proofs fails, the route is dead — regardless of the RTF
achieved.

---

## 2. Risk table

| Risk | Occurrence | Effect | Proof |
|---|---|---|---|
| **Multi-subgraph** ("ODC only supports Single subgraph models") | **high** | AOT fails **before** the vendor compiler; no sensible error message | We have **3** models: `nemo128.onnx` (mel), `encoder` (652 MB), `decoder_joint` (18 MB). Only the encoder is a candidate; **every** file must be checked individually whether after conversion it has exactly **one** subgraph (`[THESIS]`: the ODC check applies before the vendor compiler) |
| **Static int8 quantization** (EdgeTPU wants `wNa8o8`) | **certain** | Our model is **dynamically** quantized (ORT `MatMulInteger`/`DynamicQuantizeLinear`, per-tensor activations at runtime). Requantization with calibration data needed ⇒ **accuracy risk**, the golden text most likely breaks | WER/golden comparison **before** any NPU work (stage S2) |
| **Silent CPU fallback** | **high** | `CompiledModel.create()` does not report an error; the model runs on XNNPACK, we measure "NPU" and measure nothing; additionally compile/copy overhead ⇒ **slower than today** | fail-closed gate via `LiteRtCompiledModelIsNonCpuFullyAccelerated` **[THESIS]**; without the gate: **no go** |
| **Version pairing toolchain/runtime/dispatcher** | medium | `ai-edge-litert` (compile time) ↔ `litert` runtime ↔ `libLiteRtDispatch_GoogleTensor.so` must match; mismatch = SIGABRT/"No dispatch library found" **[THESIS]** | version pinning in the build + `nm`/`readelf` check in CI (JVM/emulator stage S3) |
| **`useLegacyPackaging=true`** | **certain** (necessary) | libs are stored **uncompressed** in the APK and extracted to `/data/app/.../lib/arm64/` on installation ⇒ the APK **grows** by the dispatch lib (order of magnitude 5–20 MB **[ASSUMPTION]**), installation time/space increase; without the flag the lib sits compressed in the split APK and the dispatcher does not find it (SIGABRT) **[THESIS]** | APK size diff + `adb shell ls /data/app/*/lib/arm64/` (device, stage S3) |
| **AI pack/asset pack via sideload** | **knockout risk** | Play Asset Delivery (fast-follow/on-demand) is **delivered by Play**; with sideload (`adb install`, F-Droid, direct download) there is **no** delivery service. "Install-time" packs sit in the APK/split, "fast-follow"/"on-demand" **not** [EVIDENCED] developer.android.com/guide/playcore/asset-delivery | **Check:** either (a) put the AOT model **into our own `ModelStore` download** (we already have resume/hash/SHA) or (b) install-time pack ⇒ APK + model size (unbearable at 671 MB). Recommendation: **(a)** |
| **GrapheneOS permissions** | low | no GMS needed for LiteRT itself; NPU access via vendor HAL/dispatcher lib needs **no** special rights **[ASSUMPTION]**; the risk remains that the dispatcher is not loaded without Play services | device test S3 (logcat: dispatcher load, `IsFullyAccelerated`) |
| **Device binding of the AOT model** | **high** | AOT is **SoC-specific** (`TENSOR_G5`); for all other devices (minSdk 29!) we still need the CPU path ⇒ **two model artifacts**, two downloads, two golden sets | stage S1 (manifest/feature flag), S5 (device) |
| **Frontend/decoder on CPU** | certain | mel (`nemo128.onnx`) and decoder stay on CPU; per hypothesis ~512 KB encoder output NPU→CPU, decoder steps 25–40 × 4 KB ⇒ **small**, but latency-additive per step | measurement S5 (latency per hypothesis) |

**Conclusion of the table:** two items are knockouts: **sideload delivery**
(solvable via our own downloader) and the **missing fail-closed gate**
(checkable on the device only).

---

## 3. Stage plan (TDD chains, each stage with an executable proof)

Rule: **test first**, abort = no further building. Stages S0–S2 run **here**
(JVM/x86_64 emulator), S3–S5 only on the user's device.

| Stage | Goal | Test FIRST | Files | max. duration | Abort criterion |
|---|---|---|---|---|---|
| **S0** | parallel-path scaffolding without behavior change | JVM: with `feature=npu` `AsrEngineFactory` delivers **the same** CPU engine type as today; all 232 tests + goldens stay green | `asr/EngineFactory.kt`, `model/ModelStore.kt` (read only) | 2 h | any existing test red |
| **S1** | fail-closed gate (the core!) | JVM fake: `NpuGate.isFullyAccelerated()` returns `false` ⇒ the engine chooses **CPU**, log line "NPU not available", **no** download of the NPU model; `true` ⇒ NPU path | `asr/NpuGate.kt` (new), `asr/EngineFactory.kt` | 3 h | the gate allows NPU without proof |
| **S2** | quantization metadata + manifest | JVM: `ModelManifest` knows `quant=dynamic-int8|static-int8`; test: the NPU path requires **static-int8**, the CPU path accepts both; hash/size check | `model/ModelManifest.kt`, `model/ModelStore.kt` | 2 h | a manifest without a `quant` field is accepted |
| **S3** | AOT artifact + dispatch lib present | device test: after installation `libLiteRtDispatch_GoogleTensor.so` **is in `/data/app/.../lib/arm64/`** (not in the APK), `IsFullyAccelerated==true`; otherwise **abort** | `app/build.gradle.kts` (`useLegacyPackaging`, `abiFilters`), `model/` | 4 h | lib missing **or** gate `false` |
| **S4** | golden equivalence | device test `s1_realEngine_realWav` with the NPU path: text **exactly** §4.1; additionally WER over 20 sentences (to be recorded) | `androidTest/`, `testdata/` | 3 h | text ≠ §4.1 **and** WER difference > 1 percentage point |
| **S5** | measurement | RTF/latency/RAM/thermal (§4) on the device | `docs/PERF_NPU.md` (new) | 2 h | RTF ≥ CPU RTF |

**Total:** ~16 h build time + the user's device access. Stages S0–S2 can be
completed without a device; **from S3 the emulator is worthless** (x86_64
without a Tensor NPU).

---

## 4. Measurement protocol for the user's device

| Number | How | Threshold for "the rework is worth it" |
|---|---|---|
| **RTF** per 10 s of speech (`de_pause.wav`) | 3 runs, median, NPU vs. CPU | **NPU RTF ≤ 0.6 × CPU RTF** (i.e. ≥ 1.7×) |
| **First-word latency** | start → first displayed word | **not worse** than CPU (+0 s) |
| **RAM peak** | maxRSS | **≤ CPU + 10 %** |
| **Thermal 3 min** | RTF drift + battery temperature | **drift < 15 %**, no throttling |
| **Fail-closed question** | `IsFullyAccelerated` **and** logcat without XNNPACK kernels for the encoder | **must be `true`** — otherwise everything else is worthless |

**Why the 1.7× threshold:** the encoder is ~85–90 % of the compute time, but
the load is **memory-bandwidth-bound** (600 MB int8 weights per pass, shared
LPDDR5X). An NPU can only win if it does **not** stream the weights from DRAM
per pass — at 600 MB > on-chip SRAM that is unlikely **[ASSUMPTION]**.
Expectation therefore **1.0–1.6×**, not 5×.

---

## 5a. What can be lost along the way (and how we secure it)

| Loss | Safeguard |
|---|---|
| **Recognition quality** (static int8 activations) | golden text §4.1 **exactly** as the gate; additionally WER over 20 sentences; the user decides at a WER difference > 1 percentage point |
| **Golden tests** (new model artifacts) | the CPU path **stays** unchanged and keeps today's goldens; the NPU path gets its **own** goldens (`§4.1-npu`) |
| **APK size / installation** | `useLegacyPackaging` + dispatch lib + possibly NPU model ⇒ measure the APK delta; the NPU model **not** into the APK, but into the existing 671 MB download (only on Tensor-G5 devices) |
| **Maintainability** | feature switch `feature=npu` (default **off**), one code path, two artifacts; toolchain versions pinned |
| **Device binding** | AOT artifact only for Tensor G5; all other devices stay CPU (minSdk 29 unchanged) |

---

## 6. Open points to clarify **first** (order)

1. **AI pack without Play Store?** → **circumventable** by putting the AOT
   artifact into our own `ModelStore` download (resume/hash present). No Play
   needed.
2. **Fail-closed gate public?** → **[THESIS]** no; we build it ourselves (S1)
   and treat it as a **mandatory gate**.
3. **Static int8 for the encoder** → calibration data missing (only 4 espeak
   WAVs) ⇒ record **20–50 sentences of German material**, run the quantizer,
   measure WER.
4. **Single-subgraph check** → check per file (S2) before compile time is
   invested.

## ASSUMPTION lines

- `ASSUMPTION: The user's research on LiteRT ≥ 2.1.6 / TENSOR_G5 / the AI`
  `pack is correct, but not verifiable here (no network, no device, no`
  `Gradle).`
- `ASSUMPTION: Dispatch lib size 5–20 MB; the APK delta must be measured.`
- `ASSUMPTION: Encoder share of total time 85–90 %; decoder stays CPU.`
