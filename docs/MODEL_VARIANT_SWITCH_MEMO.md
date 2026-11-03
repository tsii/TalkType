# Memo: second model variant downloadable + switchable in the app

**Status:** 2026-10-28 · **Role:** decision memo (no code/build change)
**Basis:** orchestrator measurements (below), `docs/ASR_CONTRACT.md` §4.1/§4.4,
`docs/KONZEPT.md` §2.6/§2.7, `docs/DESIGN.md` §5.4/§6,
`docs/COMMIT_RULE_DECISION_2.md` (E8),
`app/src/main/java/de/voicekeyboard/asr/Hypothesis.kt` (interface `AsrEngine`),
`docs/NPU_LITERT_FEASIBILITY.md` (G3), `docs/RUNTIME_SWITCH_NPU_ASSESSMENT.md`
(G2).

**Measured artifacts (orchestrator):** A = `istupakov/…-onnx` (ONNX, dynamic
int8, 670.6 MB total); B = `Arm/parakeet-tdt-0-6b-v3-int8-litert` (4 `.tflite`,
**683.2 MB**, cached streaming); C = `litert-community/…` (i8_stateful 614 MB,
f32 2.42 GB, AOT NPU f32 1.26 GB, 5-s window).

---

## 1. What a "toggle" technically is

**Confirmed: the toggle is not a quantization flag but a second engine.**
A runs on ONNX Runtime (CPU/XNNPACK, `ParakeetAsrEngine`), B/C on **LiteRT**
with different file formats (`.tflite`), a different decoder loop (cached state
vs. ONNX frame loop), and different threading/memory characteristics. "int8"
is only the weight class in both cases — the runtime is a different one. A
flag `quant=int8|fp16` would switch **nothing**.

**The thinnest abstraction already exists** — `asr/Hypothesis.kt:51`:

```kotlin
interface AsrEngine {
    fun load(modelDir: File)
    suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis
    fun close()
}
```

Building on that, **without** touching `commit/`, `editor/`, `dictation/`:

| Building block | Task | Invariants that remain |
|---|---|---|
| `AsrEngine` (unchanged) | one hypothesis per window, the same `Hypothesis` shape (`tokenIds`, `text`, `frameTimesMs`, `incomplete`) | E8 commit policy, DeltaTyper, ownership see **only** `Hypothesis` |
| `asr/EngineFactory` (new) | chooses the engine based on `variant ∈ {A_ONNX, B_LITERT}` + availability; **always falls back to A** | `dictation/` still gets exactly one engine |
| `model/ModelVariant` (new) | variant → manifest, directory, file list, hash | `ModelStore` stays the source of truth |
| `LiteRtAsrEngine` (new) | implements `AsrEngine`; holds the LiteRT `CompiledModel` + streaming state | `transcribe()` is `suspend` and **may** buffer internally (see §2) |
| Lifecycle | `load()` once, `transcribe()` per hypothesis, `close()` at session end/switch | C-4 leak rule (close the engine) |
| Error path | any LiteRT error → `Hypothesis` with `incomplete=true` **or** engine switch to A, never a crash, never silent foreign language | E3 "the keyboard never blocks" |

**Critical:** `transcribe()` is currently thought of as stateless (window in
→ text out). B is **cache-based** (streaming state across calls). The state
belongs in the engine instance and **must** be discarded on `reset()`/a new
session (`sessionId` switch) — exactly like the commit state.

## 2. Which candidate fits our streaming?

| | B (Arm, cached) | C (litert-community) |
|---|---|---|
| Window | incremental, **cache** between steps | **5-s sliding window** with overlap |
| Hypothesis every 0.3–1 s | **yes** (compute only new frames) | **no** — earliest after 5 s of audio, then every 5 s |
| Perceived latency | as today (2 s `min_audio_s`) | **+3 s** until the first hypothesis, then a 5-s cadence |
| E8 commit policy (3 hypotheses, 2 words unprotected) | fits: 3 hypotheses in ~1–3 s | **breaks**: 3 hypotheses = 15 s of audio; "stable" only after ~15 s |
| Extra dependency | none (only LiteRT) | recommended is **LiteRT-LM `OmniEngine`/`omni_runner`** |

**Decision:** **B** is the only candidate that does **not** change our
chunk/commit architecture. C is unsuitable for our push-to-talk pattern (5-s
window ⇒ commit latency ×5), and its NPU variant is **f32 1.26 GB** — noted
as a *research* path, not as a product variant.

## 3. Costs

| Item | Number |
|---|---|
| Download B | 648,784,320 + 27,705,640 + 6,466,576 + 262,864 = **683,219,400 B ≈ 683 MB** (+ SHA256SUMS/metadata ≈ KB) |
| Download C (i8_stateful) | **614 MB** (f32 2.42 GB / NPU 1.26 GB not recommended) |
| Both installed | 670.6 MB (A) + 683.2 MB (B) = **≈ 1.35 GB** of model data |
| Peak memory during the download of B | `.part` + staging ≈ **+683 MB** ⇒ **≥ 2.0 GB free** needed (today: the ≥ 2.5 GB check stays valid) |
| APK increase from LiteRT | **`ASSUMPTION:` 12–25 MB** (runtime + JNI); NPU dispatcher later +5–20 MB (G3) |
| First-start download | **stays 671 MB** — B is loaded **optionally** afterwards ("add-on variant"), not in the setup default |

**Rule:** variants are loaded **individually**; `ModelStore` gets its own
`versions/v-*` directory and its own `active.txt` pointer per variant. No
shared staging.

## 4. Quality anchors (tolerance)

`ASR_CONTRACT.md` §4.1 stays **exact** — but only for **A (ONNX)**. For a
different engine "exact" is not sensible (a different decoder, different
quantization). Binding tolerance for B/C:

1. **Word identity ≥ 95 %** (Levenshtein at the word level against the §4.1
   text, over the 4 WAVs).
2. **Character edit distance ≤ 2 %** of the character count.
3. **No foreign-language output** (the "Vikti/Yeah" class) — hard zero
   tolerance.
4. **Anchor words present**: `Diktats`, `zweite` (resp. `Telefon`,
   `funktioniert` for `de_smoke`).

Measurement **without new debt**: a comparison-run script over the
**existing** 4 WAVs (`testdata/*.wav`) that outputs the A and B texts side by
side (table + diff) — no new test material, no new goldens. Golden files are
stored **per variant** (`golden/A.json`, `golden/B.json`); §4.1 stays the
source for A.

## 5. What is provable on the x86_64 emulator

| Stage | provable here? |
|---|---|
| Engine factory/flag/persistence | **yes** (JVM) |
| Variant download + hash + resume | **yes** (JDK `HttpServer`, like `DownloadResumeTest`) |
| LiteRT **CPU** inference (B, 4 `.tflite`) | **yes** — `.tflite` is SoC-neutral, XNNPACK runs on x86_64; quality comparison §4 possible |
| LiteRT **GPU/NPU**/AOT (C) | **no** (no Tensor-G5, no dispatcher) |
| Real latency/thermals (SME2, ARM) | **no** — only the user's device |

## 6. Stage plan (TDD chains, max. 4 files per task)

| Stage | Test FIRST | Files | max. | Abort | Go/No-Go |
|---|---|---|---|---|---|
| **S0** (behavior-neutral) | JVM: `EngineFactory` with `variant=null/A` delivers **the same** engine type as today; all 232 tests + goldens green | `asr/EngineFactory.kt` (new), `asr/Hypothesis.kt` (docs only) | 2 h | any test red | Go for S1 |
| **S1** | JVM: `ModelVariantTest` — manifest with 2 variants, `ModelStore` resolves its own directory + pointer per variant; existing hash/resume tests green | `model/ModelVariant.kt` (new), `model/ModelManifest.kt`, `model/ModelStore.kt` | 3 h | hash/resume test red | Go for S2 |
| **S2** | instrumented/JVM: `LiteRtEngineTest` loads the 4 `.tflite` (from `/data/local/tmp`), transcribes `de_smoke.wav`, checks the §4 tolerance (word identity ≥ 95 %, no foreign text) | `asr/LiteRtAsrEngine.kt` (new), `app/build.gradle.kts` (dependency) | 4 h | crash, empty text, or < 95 % | Go for S3 |
| **S3** | JVM: `AsrVariantSwitchTest` — default A, a switch to B persists (`vk_layout`/`asr_variant`), missing B ⇒ fallback A, switching during RECORDING locked | `ime/SetupActivity.kt`, `asr/EngineFactory.kt` | 3 h | state lost or switching during dictation possible | Go for S4 |
| **S4** | device: A/B measurement (§4 numbers) + quality comparison; Go only if **B is ≥ 1.3× faster** at word identity ≥ 95 % | `docs/PERF_VARIANTS.md` (new) | 3 h | B slower or quality < 95 % | release decision |

## 7. Recommendation

- **The engine-switch infrastructure (S0–S2) belongs in v0.1.x** —
  behavior-neutral, clears the path, and is fully provable on the emulator.
- **B as an optional add-on variant in v0.2** (download on demand, default
  stays A).
- **Do not ship C** — the 5-s window breaks our commit latency; the NPU
  variant is f32 1.26 GB and needs LiteRT-LM. Keep it as a research note
  (G3).

## ASSUMPTION lines

- `ASSUMPTION: LiteRT APK increase 12–25 MB — why: runtime+JNI size not`
  `measured, rule of thumb from the ORT AAR (34 MB). Must be measured as an`
  `APK delta.`
- `ASSUMPTION: B runs on the x86_64 emulator — why: .tflite is SoC-neutral,`
  `XNNPACK has x86 kernels; not verified (no build in this task).`
- `ASSUMPTION: B streaming state is isolatable per session — why: Arm's cache`
  `model; the interface is not documented, must be tested in S2.`
