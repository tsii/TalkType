# Review Stage 14 — LiteRT source set (V10 mmap loader, V11a signature path, V12 guard/retry, V13 source-set trick)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-29
**Subject:** `LiteRtLoader`/`LiteRtStreaming` (debug source set), `LiteRtBridge`, `EngineFactory` (release fallback), `DictationController` guard, `BuildConfig` ABI. **No build/emulator** (orchestrator: 279/0 debug, 263/0 release; APK arm64, no LiteRT trace).

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| L14-01 | **P2** | `LiteRtStreaming.kt:59-70` (`preEncodeCache`) | **The cache copy source is only implicitly 769 frames (9+valid) — the contract is not nailed down.** `src = CACHE_FRAMES + validFrames - toCopy` (= `9 + valid − toCopy`) indexes `feats[src..src+toCopy-1]`; correct **only** if `feats` is the 769-frame `input_features` ([9 cache][valid]) — for a `validFrames`-long array, `feats[760]` would be an `ArrayIndexOutOfBounds`. The KDoc only says "feats = [frames][dims], valid frames", not "9-prefix + valid". Additionally, the function is the **update step** (cache for the *next* window); the featurizer **input** must mandatorily be the *previous* cache — the ordering (input=prev, then update=current) is not evidenced in the code. `ASSUMPTION:` the V11a caller feeds correctly (proof via `arm_litert_reference_decode.py`). | `val src = CACHE_FRAMES + validFrames - toCopy`; `feats[src+i]`; KDoc "feats=[frames][dims]". | Sharpen the signature to `feats: Array<FloatArray> /* [9+valid][dims] input_features */`; document `src = validFrames + (CACHE_FRAMES - toCopy)` and add a unit test "feats shorter than 9+valid ⇒ empty cache instead of OOB"; document the prev/current order as an invariant. |
| L14-02 | Note | `LiteRtLoader.kt:30-66` | **V10 (L1-01 P1) cleanly fixed.** `Interpreter(File, Options)` (mmap, no 648-MB heap copy); size check against the manifest before `open` (flat name, no path); `OutOfMemoryError` gets its own German branch, `Throwable` boundary for other errors; `scrub` trims path tokens to the file name (rule 9). | `Interpreter(file, opts)`; `catch (e: OutOfMemoryError)`; `catch (t: Throwable)`. | — |
| L14-03 | Note | `LiteRtStreaming.kt` (vs. `compute-flow_ParakeetTDT.cpp`) | **Front-end fidelity confirmed** (against §342-393/400-434/458-490): `validFrames = min(760, max(0, samples/160))` ✓ (line 342 `num_chunk_samples/hop_length`); `input_length = 9 + valid` ✓ (line 458); `previous_sample = audio[start-1]`, first window `start=0 ⇒ 0` ✓ (line 400); Welford `var = m2/max(1,n-1)`, `inv_std = 1/(sqrt(var)+1e-5)` ✓; cache from the last valid frames ✓ (line 470 `frames_to_copy = min(9, valid)`). No numerical deviation recognizable. | `GlobalFeatureStats`/`LiteRtStreaming` vs. `.cpp` quotes. | — |
| L14-04 | ✓ | `EngineFactory.kt:27-40` + `LiteRtBridge.kt` | **V13 release fallback watertight; no P1 for an old persisted value.** Release: `LiteRtBridge.acquire()` → `Class.forName("…LiteRtBridgeSetup")` installs **no** provider ⇒ `provider==null` ⇒ `create(LITERT_CPU, store)` delivers `Result(ParakeetAsrEngine, ONNX_CPU)`. A persisted `model_variant=litert-cpu` thereby **never** leads to a crash/standstill — and it has no production consumer anyway (`VoiceKeyboardService.buildCoordinator` continues to use `{ ParakeetAsrEngine() }`; `model_variant` is not read on the IME path). | `provider = null` (release); `create` fallback `Result(ParakeetAsrEngine, ONNX_CPU)`. | — |
| L14-05 | Note | `build.gradle.kts:25-31` (abiFilters) | **arm64-only release.** x86_64 users (Chromebook/emulator) cannot install the 0.1.3 APK (`INSTALL_FAILED_NO_MATCHING_ABIS`); an x86_64 stock install would have to be reinstalled. Debug keeps x86_64 ⇒ the emulator proof remains possible. Conscious, acceptable decision; not a P1 (x86_64 phones practically nonexistent). | `release { abiFilters += arm64-v8a }`, `debug { x86_64 + arm64-v8a }`. | — |
| L14-06 | Note | `DictationController` (guard from `hypothesisMinIntervalMs`), `strings.xml:setup_state_retry_attempt` | **V12: guard/retry conformant.** `hypothesisMinIntervalMs` default 2000 = the previous `MIN_AUDIO_SECONDS_PARTIALS` (no behavior change); `DownloadProgress.attempt/maxAttempts` + `setup_state_retry_attempt` carry only numbers/file names (rule 9). The L14-04 finding (retry line) was not checked against a live 429/408 run (no device). | — | — |

---

## Point-by-point answers

1. **Front-end fidelity:** correct against the `.cpp` lines (validFrames/input_length/previous_sample/Welford/eps/cache source) — L14-03; the only remainder = the 769-frame contract's implicitness (L14-01).
2. **State life cycle:** encoder caches (`[1,24,190,1024]` ≈ 18.6 MB) are input/output tensors per run; `close()` frees the interpreters, a session switch resets `cachedSessionId`. **Not finally verified** (the streaming loop lives in the debug set and was not fully re-read here) — `ASSUMPTION:` no accumulation, since tensors are newly allocated per `run()` and GC-cleaned.
3. **Source-set trick:** see L14-04 — release can never run into LiteRT; the `Class.forName` failure is caught (only `ClassNotFoundException`; other load errors ⇒ provider null), no exception path to the user; an old `litert-cpu` value is inert + fallback-secured.
4. **Test coverage:** 16 debug diff = LiteRT tests (debug source set); `src/testRelease/EngineFactoryReleaseTest` exists per the handover and checks the A fallback. **Not read myself** — `ASSUMPTION:` it asserts the *working A engine* (not just the type name).
5. **Rule 9:** the retry display and the LiteRT message scrub (path→file name) carry no URL/path/content (L14-02/L14-06).
6. **Versioning/distribution:** arm64-only release breaks only x86_64 emulator/Chromebook installs (accepted); existing arm64 installs upgrade without reinstall (L14-05).

---

## Verdict

- **V10 (mmap loader): ready for release** (0 P0/0 P1; L1-01 P1 correctly fixed).
- **V11a (signature path/streaming): ready for release with a note** (faithful to the formulas, only the L14-01 contract).
- **V12 (guard/retry): ready for release** (0 P0/0 P1).
- **V13 (source-set trick): ready for release** (release isolation solid, L14-04).

**Overall: ready for release** — 0 P0, 0 P1, 1 P2 (L14-01).

**Not checked (no device/Gradle run):** actual run-signature behavior of the 648-MB encoder `.tflite`, streaming-loop cache ordering against real chunks, `EngineFactoryReleaseTest` content, 429/408 live display.

## Top-3

1. **L14-01 (P2)** — Pin down the `preEncodeCache` signature/KDoc (`feats` = `[9+valid][dims]`), document the `src` formula and add a "short input ⇒ empty cache instead of OOB" test; document the prev→current order as an invariant.
2. **L14-06 (Note)** — On the next device run, verify the 429/408 retry text against the real display.
3. **L14-05 (Note)** — Record the release ABI decision (arm64-only) in the changelog/compatibility note.
