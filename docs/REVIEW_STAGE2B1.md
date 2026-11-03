# Review Stage 2 B.1 — ASR engine (Hypothesis / OnnxSessions / ParakeetAsrEngine)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Subject:** `asr/Hypothesis.kt`, `asr/OnnxSessions.kt`, `asr/ParakeetAsrEngine.kt`
(+ `app/build.gradle.kts`, `AndroidManifest.xml`), checked against
`ASR_CONTRACT.md` §2/§3, `IMPLEMENTATION_PLAN.md` B.1, `DESIGN.md`, `TECH_FACTS.md` §3.
**Not executed:** `./gradlew` (parallel build). Read-only access + this one file only.

**Overall verification (positive part):** The tensor contract (§2) and the greedy path (§3) are
implemented **exactly** — names/dtypes/ranks match, `(a)/(b)/(c)` (state advance only on
non-blank, `t += dur` also on blank, per-frame limit 10) are correct, budget (§6) fits.

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Concrete fix |
|---|---|---|---|---|---|
| SB1-01 | **P1** | `asr/TdtGreedy.kt:90` + `asr/AsrContract.kt:49` | **Special-token filter incomplete.** The greedy loop filters only `token !in SPECIAL_TOKEN_IDS` with `SPECIAL_TOKEN_IDS = 0..4`. ASR_CONTRACT §2.4/§7-4 requires: filter **all** IDs matching the pattern `<...>` (0..233 and 244..273) **plus** `ID 8192` — and keep IDs 234..243 (digits). Consequence: language tokens like `<\|de\|>` (78), `<\|0\|>`…`<\|10\|>`, `<\|spkN\|>`, `<\|spltokenN\|>` (244..273) are emitted and land in `Hypothesis.tokenIds` **and** `text` (`Vocabulary.detokenize` does not filter). The complete filter already exists (`Vocabulary.isSpecial`, Vocabulary.kt:52-61) and is correctly tested (`VocabularyTest` checks 78/244), but is **not** called on the decode path. | `TdtGreedy.kt:90` `if (token !in AsrContract.SPECIAL_TOKEN_IDS)`; `AsrContract.kt:49` `SPECIAL_TOKEN_IDS = 0..4`; `TdtGreedyTest.specialTokensNotEmitted` tests only 0/3/4 (not 78/244). | In `TdtGreedy.decode`, switch the emit filter to `Vocabulary.isSpecial(id)` (or put the `<...>`+8192 test in the loop) and extend the test with IDs 78/244. |
| SB1-02 | P2 | `asr/OnnxSessions.kt:56-58,65` vs. `IMPLEMENTATION_PLAN.md:308` | **Error mapping without its own type.** The plan requires "NaN/dim error ⇒ `ModelError`"; no such class exists — the engine throws generic `IllegalStateException`/`IllegalArgumentException` (`check`/`require`). The DictationController cannot catch model errors in a typed way (no `ModelError` catch). | `grep ModelError` → only `IMPLEMENTATION_PLAN.md:308`, no class; `OnnxSessions.checkIo` throws `IllegalStateException`. | Define a `ModelError` class (e.g. `sealed`) in the `asr` package and throw it at the error sites (session validation, NaN, dim errors); controller catch for it. |
| SB1-03 | P2 | `OnnxSessions.kt:56-58` | **Partial session leak on load.** `createSession` for mel/enc/dec runs without try/finally; if `createSession(decoder)` throws (corrupt file), mel+enc remain unclosed (encoder ≈ 848 MB native, ASR_CONTRACT §5). The `s.close()` try/catch (lines 64-66) covers only the **validation**, not the session creation. | Lines 56-58 three `env.createSession(...)` without enclosure; `catch { s.close(); throw }` only from line 64 (validates `validateMel` etc.). | Sequential try/finally: close every already-created session on a follow-up error (or first create all, then validate in one `use` block). |
| SB1-04 | P2 | `ParakeetAsrEngine.kt:44,118-122` | **`transcribe` blocks the caller thread.** `suspend` contains no suspension point and no `withContext(Dispatchers.*)`: `mel.run`/`encoder.run`/`decoder.run` (line 122, native calls) run synchronously on the calling thread. DESIGN §2.3-4 requires a dedicated ASR worker — this file does not enforce that; if the (still missing) `AsrScheduler` calls it directly from the main thread, the UI freezes. | Line 44 `suspend fun transcribe`; no `withContext`/`Dispatchers` in the body, `TdtGreedy.decode` (line 122) blocking. | In the `AsrScheduler` (next stage) mandatorily wrap `transcribe` in `withContext(Dispatchers.Default)`; alternatively document/enforce it here by convention. |
| SB1-05 | P2 | `ParakeetAsrEngine.kt:233,236-237` | **Allocations in the frame loop.** Newly allocated per joint step: `logits` (8198 floats ≈ 32 KB), `o1`/`o2` (1280 floats each), plus `copyOf`/`copyOfRange` and 5 `OnnxTensor` + buffer wraps. At 124 frames (9.9 s) ≈ ~8 MB+ churn; contradicts the memory/GC care of DESIGN §2.3 ("do not constantly duplicate the whole dictation as floats"). `frame`/`targets`/`targetLength` are already held as fields, `logits`/`o1`/`o2` are not. | Line 233 `FloatArray(LOGIT_COUNT)` in `step()`; lines 236/237 `FloatArray(2*PRED_HIDDEN)` per call. | Make `logits`/`o1`/`o2` reused fields of `EncoderJointStep` (like `frame`), hold `FloatBuffer`/`LongBuffer` once. |
| SB1-06 | P2 | `ParakeetAsrEngine.kt:126-131` (after `:122`) | **NaN guard runs after the decode.** `encOut` is checked for `isFinite()` only after `TdtGreedy.decode(...)` — a NaN encoder output first passes through the complete decoder (argmax on NaN → index 0, wasted time), then throws. Functionally no wrong result (it throws), but the guard is in the wrong place. | Line 122 `TdtGreedy.decode`; lines 126-131 the `isFinite()` loop afterwards. | Run the guard before `TdtGreedy.decode` (directly after the encoder run), so no decode runs on broken input. |
| SB1-07 | Note | `AndroidManifest.xml:10-15` vs. `TECH_FACTS.md §3 concept 3` | **Telemetry provider not removed.** INTERNET/ACCESS_NETWORK_STATE are correctly removed via `tools:node="remove"`, but the `ai.onnxruntime.TelemetryInitializer` provider smuggled in by the ORT AAR is missing. TECH_FACTS §3-3: the provider **must** be removed additionally, otherwise the promise "no network connection" does not fully hold (the 1DS client gets initialized; lacking INTERNET it does not send, but reports a telemetry dead run). | The manifest contains only the two `uses-permission` removals; no `<provider … tools:node="remove">`. | Add `<provider android:name="ai.onnxruntime.TelemetryInitializer" tools:node="remove" />`. (INTERNET must come back for the model download, TECH_FACTS §3.) |
| SB1-08 | Note | `build.gradle.kts` `abiFilters` | **x86_64 in the base ABI set.** TECH_FACTS §3 concept 1 classifies `x86_64` as "only for emulator builds"; in the base `defaultConfig.ndk` it inflates the release APK by ~41 MB of native libs. Acceptable for sideloading, but not the reference recommendation. | `ndk { abiFilters += listOf("x86_64", "arm64-v8a") }`. | Move `x86_64` to an emulator/debug variant (release only `arm64-v8a`). |
| SB1-09 | Note | `ParakeetAsrEngine.kt` (entirely) | **`min_audio_s` correctly NOT in the engine.** The 2.0-s guard is deliberately not applied — §7-8 places it in the pipeline (automatic partials only), the final UP decode must handle any length. Empty audio (`nSamples==0`) cleanly returns empty (`ParakeetAsrEngine.kt:55-57`). No defect. | — | Unchanged; the guard belongs in the (already planned) `DictationController`/`AsrScheduler`. |
| SB1-10 | Note | `OnnxSessions.kt:74-76` | **Fragile `zip` over a `Set`.** `expectedNames.zip(expected)` pairs set elements with list entries; correct only because Kotlin's `toSet()` yields a `LinkedHashSet` (insertion order). If the set were switched to a hash set, the dtype/rank assignment would be silently mischecked. | Line 74 `for ((name, exp) in expectedNames.zip(expected))` with `expectedNames = expected.map{it.first}.toSet()` (line 73). | Iterate directly over `expected.forEach { (name, type, rank) -> … }` instead of zip. |
| SB1-11 | Note | `ParakeetAsrEngine.kt:100-102` | **Mel buffer read in full.** `features` (FloatArray over the whole `floatBuffer.remaining()`) is re-framed with `melTime` from `features_lens` as T. Correct only as long as `features_lens == T` (no padding); with a padded mel output there would be a buffer/shape mismatch in `createTensor(…, longArrayOf(1,128,melTime))`. The measured export has no padding (§2) → currently correct, but implicit. | Lines 100-102 `FloatArray(featTensor.floatBuffer.remaining())` + encoder input shape `[1,128,melTime]`. | Take T from `shape[2]` of the features or explicitly `require(features_lens == shape[2])`. |

---

## Point-by-point check of the mandatory questions (short form)

1. **Tensor names/dtypes/ranks §2:** **exact.** All 16 names (mel 4, encoder 4, decoder 8) match `AsrContract`/§2, including dtypes (`FLOAT`/`INT64`/`INT32`) and ranks. `checkIo` rejects additional tensors ("no tolerance"). **No** invented/missing names.
2. **Decoder path §3:** **exact.** `(a)` states only on non-blank (`TdtGreedy.kt:83-88`), `(b)` `t += dur` also on blank, `(c)` per-frame limit 10 with `emitted` reset (`TdtGreedy.kt:92-101`). Logit split 8193/5 correct (`ParakeetAsrEngine.kt:238-239`), `frames = min(encoded_lengths[0], outputs.shape[2])` (`ParakeetAsrEngine.kt:114`).
3. **Budget §6:** `TokenBudget.budgetTokens = max(256, min(4096, ceil(12s)+64))` exact; `incomplete=true`+`reason="budget"` on a hit (`TdtGreedy.kt:91-93`). ✔
4. **Threading/perf:** `OrtEnvironment.getEnvironment()` is a singleton everywhere (no env leak); `close()` closes mel→enc→dec via try/finally; all `OnnxTensor` in `.use { }`. Gaps: SB1-04 (no dispatcher), SB1-05 (frame allocations), SB1-03 (partial session leak).
5. **Error handling:** empty audio ✔, `min_audio_s` correctly absent ✔, NaN guard present (but late, SB1-06). `ModelError` type missing (SB1-02).
6. **Privacy:** no audio/text logs (only durations/metrics, `ParakeetAsrEngine.kt:134-142`), no persistence; INTERNET/ACCESS_NETWORK_STATE removed ✔. Provider removal missing (SB1-07).
7. **abiFilters/ORT:** `arm64-v8a`+`x86_64` present, ORT `1.31.1` ✔ (x86_64 note SB1-08).
8. **Buildability:** the used ORT APIs (`NodeInfo`, `TensorInfo.shape`, `OnnxTensor.createTensor`, `SessionOptions.addCPU/setOptimizationLevel/addConfigEntry`) and Kotlin stdlib (`Float.isFinite`, `LongArray.size`) exist; no obviously non-existent symbol found.

---

## Verdict

**Release only with fixes.**

The core (tensor contract, §3 decoder, budget, privacy logging, tensor lifecycle) is
solid and conforms to the reference — **no P0**. Before release, **SB1-01 (P1)** must be fixed:
the special-token filter violates the mandatory §2.4 rule and can emit language tokens like `<|de|>`
into `tokenIds`/`text` (the correct filter `Vocabulary.isSpecial` already exists
but is not wired). The P2s (SB1-02…06) are quality/robustness items and belong
before the release or with the `AsrScheduler`.

---

## Top-5 actions

1. **SB1-01 (P1)** — Switch `TdtGreedy.decode` to `Vocabulary.isSpecial(id)` (pattern `<...>`+8192);
   extend `TdtGreedyTest` with special-token IDs 78/244.
2. **SB1-02 (P2)** — Introduce a `ModelError` type in the `asr` package and throw it at validation/NaN/dim
   error sites (error mapping per `IMPLEMENTATION_PLAN.md` B.1/tests).
3. **SB1-03 (P2)** — Secure session creation in `OnnxSessions.load` with try/finally
   (prevent a partial native leak on a corrupt model).
4. **SB1-07 (P2)** — Remove the `ai.onnxruntime.TelemetryInitializer` provider via the manifest merger
   (TECH_FACTS §3-3); re-add the INTERNET removal for the model download.
5. **SB1-04/SB1-05 (P2)** — Mandatorily wrap `transcribe` in `withContext(Dispatchers.Default)` in the `AsrScheduler`
   and hold the per-frame allocations (`logits`/`o1`/`o2`) as reused fields
   of `EncoderJointStep`.
