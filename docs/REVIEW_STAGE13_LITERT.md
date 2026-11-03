# Review Stage 13 — LiteRT integration (V3/V5/V6a/V6b/V8)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-29
**Subject:** `LiteRtAsrEngine`, `LiteRtFeaturizer`, `FeaturizerStats`, `ModelFile` (storeName/urlPath), variant-aware whitelist, `EngineFactory` fallback. **No build/emulator** (265/0 unit, assembleDebugAndroidTest green — does NOT exercise the encoder load).

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| L1-01 | **P1** | `LiteRtAsrEngine.kt:50-53` (`f.readBytes()` + `ByteBuffer.allocateDirect`) | **Encoder load OOMs the app heap.** `load()` executes **`f.readBytes()`** for each `.tflite` — for the encoder (648,784,320 B) that is a **648-MB Java-heap `ByteArray`** which **guaranteed** blows up the Android app heap (256–512 MB, no `largeHeap`) (`OutOfMemoryError`) — even before the direct copy. Even with largeHeap a triple peak arises (648 MB heap + 648 MB direct + ~648 MB interpreter-native). The instrumented test opens only the 262-KB featurizer → the 648-MB path is untested. `catch (e: Exception)` does **not** catch the `Error`, i.e. an OOM leaves already-opened interpreters open. | `val bytes = f.readBytes()` … `ByteBuffer.allocateDirect(bytes.size).put(bytes)`; `catch (e: Exception)`. | Use `Interpreter(File, options)` (mmap, no heap copy) instead of a byte array, or stream directly into the direct buffer via `FileChannel`; additionally catch `OutOfMemoryError` and close partially opened interpreters. |
| L1-02 | P2 | `LiteRtAsrEngine.kt:118-131` (`openFeaturizerOnly`/`attachFeaturizer`) | **Public test hook bypasses the manifest completeness check.** `openFeaturizerOnly(file)` checks only `file.isFile` (no size/SHA256 against `ModelManifest`); an arbitrary path is loaded as the featurizer. Unreachable in production today (B is not wired into the IME, `EngineFactory.create` is not used in `buildCoordinator`), but a **latent** P1 on wiring — if not resolved now, an unverified path can be loaded via an incarnation of the engine. | `openFeaturizerOnly` `check(file.isFile)` without the manifest; `attachFeaturizer(file)` opens blindly. | Annotate with `@VisibleForTesting` (or remove it) and check the production `attachFeaturizer`/`load` against `ModelManifest` (size+SHA); the test hook must never be callable from production code. |
| L1-03 | P2 | `EngineFactory.kt:31-41` (`create(variant, store)`) | **The fallback to A is not visibly wired.** `Result.usedVariant` carries the *actually* started engine, but no consumer exists that maps it into the status row/debug line — the user would believe B was "chosen" while A is running. The display must show `usedVariant` (not the chosen one). | `data class Result(engine, usedVariant)`; no caller evaluates `usedVariant`. | On wiring, feed the status/diagnostic line from `usedVariant` ("Variante B nicht bereit — ONNX aktiv" instead of a silent B display). |
| L1-04 | Note | `LiteRtFeaturizer.kt` `scrubForUser` | **Rule-9 scrubbing good, but hostname-less remnants remain.** Token-wise: `substringAfterLast('/')`/`('\')` trims paths/URLs to the file name (no host, no `files/` path) — correct. A token **without** `/` `\` (e.g. a bare hostname/IP like `cdn.example.com` or a `Signature=` query as a single token without `/`) would remain; model contents do not appear in LiteRT messages anyway. | `scrubForUser`: `if (tok.contains('/')||tok.contains('\\')) else tok`; `take(160)`. | Additionally discard tokens containing `://`, of `?`/`=`-query form, and non-ASCII. |
| L1-05 | Note | `build.gradle.kts` (`-Xskip-metadata-version-check`) | **Risk low, but real at the edge.** litert 2.3.2 (Kotlin ≥ 2.4 metadata) against compiler 2.0.21: the flag overrides the metadata version check. First friction points would be (a) `inline`/`value class` methods from litert Kotlin code (mangled names/VerifyError) and (b) `@JvmDefault` interface defaults/SAM conversions — both only at runtime (`NoSuchMethodError`/`VerifyError`). litert is mostly Java, hence `ASSUMPTION:` practically uncritical; a runtime smoke test of the featurizer path (already green instrumented) covers the main path. | — | Align the Kotlin version or run a runtime smoke test for every litert API used (already partially covered by the `V6a` instrumented test). |
| L1-06 | Note | (featurizer result) | **The quality gate (≥95% word identity, ≤2% distance, anchor words) is not yet measurable.** B `transcribe()` throws `AsrNotReadyException` (V4/V9/V10 pending); a comparison run A↔B on the same 4 WAVs against `ASR_CONTRACT §4.1` is missing. The gate is defined but there is no test apparatus yet. | `transcribe()` throws; no A/B harness. | As soon as B decodes: A↔B comparison run on the 4 WAVs + golden against §4.1 as a standalone test. |
| L1-07 | Note | `app/build.gradle.kts` `abiFilters` | **The sideload version should be arm64-only.** Debug 82.6 MB → 123.5 MB (litert native ×2 ABIs). For a GrapheneOS Pixel (arm64), x86_64 could be dropped; the x86_64 emulator proof still needs the x86_64 lib → split via a release/sideload flavor (`arm64-v8a`) vs. debug (both ABIs). | `abiFilters += x86_64 + arm64-v8a`; +41 MB. | Limit the release sideload variant to `arm64-v8a`; debug keeps both for the emulator. |
| L1-08 | ✓ | `ModelManifest.kt:17-21,81-82` + `ModelStore.kt:119,215,271-278` | **V5 (stage-12 P1) is fixed.** `ModelFile` separates `storeName` (flat, `token_vocab.bin`) from `urlPath` (`assets/token_vocab.bin`); `isAllowedName(storeName, variant)` is variant-aware and forbids `/`; `activate(staging, variant)` checks against the variant manifest and returns `false` for a missing/hash-wrong file. | `storeName="token_vocab.bin"`, `urlPath="assets/token_vocab.bin"`; `isAllowedName(storeName, variant)`; `activate`→`if(!f.isFile||…) return false`. | — |

---

## Point-by-point answers

1. **Memory:** `readBytes()` (648-MB pile) + `allocateDirect` (648-MB direct) + interpreter-native ⇒ **app-heap OOM** on the encoder (L1-01); `raw_wav` (486 KB) is uncritical. `close()` is called on a partial error (exception branch) and in the service `shutdown`, but the OOM case (Error) is not caught/cleaned up.
2. **Metadata flag:** low risk (litert mostly Java); first friction with inline/value-class calls and `@JvmDefault`/SAM (runtime `NoSuchMethodError`/`VerifyError`).
3. **`openFeaturizerOnly`/`attachFeaturizer`:** latent P1 — public, non-manifest-checked test hook; currently unreachable in production (L1-02).
4. **Fallback contract:** `usedVariant` exists, but nothing displays it → silent A activation; the display must show the *started* engine (L1-03).
5. **Rule 9:** path/URL → file name (correct), but slash-less host/query tokens remain (L1-04).
6. **Quality gate:** defined, but not yet measurable (B does not decode; no A/B run) (L1-06).
7. **ABI:** recommend sideload arm64-only (L1-07).

---

## Verdict

- **V3 (integration): not ready for release** — 1 P1 (L1-01 648-MB `readBytes` heap OOM in the encoder load) + 2 P2 (L1-02/L1-03).
- **V5 (whitelist/path): ready for release** — stage-12 P1 correctly fixed (L1-08).
- **V6a/V6b (featurizer proof + DEX): ready for release** — proof via `runForMultipleInputsOutputs`, the `NEUTRAL` mode professionally justified (dead `log_mel`); not checked itself against real `.tflite`.
- **V8 (featurizer/stats): ready for release with a note** (L1-04/scrub remainder).

**Overall: not ready for release** (L1-01 P1) — remains until the encoder is loaded without a heap copy (mmap/stream).

**Not checked (no device/Gradle):** real loading of the 648-MB encoder, `-Xskip-metadata` runtime effect, `scrubForUser` against real LiteRT error messages, A/B comparison run.

## Top-3

1. **L1-01 (P1)** — Load the encoder via `Interpreter(File, options)` (mmap) or `FileChannel`→direct — never `readBytes()` a 648-MB pile; handle `OutOfMemoryError` with partial-open closing.
2. **L1-02 (P2)** — Put `openFeaturizerOnly`/`attachFeaturizer` behind `@VisibleForTesting` and check size/SHA in the production path.
3. **L1-03 (P2)** — Display `usedVariant` in the status/diagnostic line (make the fallback to A visible).
