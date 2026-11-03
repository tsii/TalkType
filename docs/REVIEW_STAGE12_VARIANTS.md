# Review Stage 12 — Model variants (V0/V1/V2)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-28
**Subject:** `ModelVariant`, `EngineFactory`, `ModelManifest` (per variant), `ModelStore` (variant-separated), S12-01 fix (`deviceRamMb`). **No build/emulator** (orchestrator: 259/0).

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| V1-01 | **P1** | `ModelManifest.kt:79` (`name="assets/token_vocab.bin"`) + `ModelStore.kt` `safeChild`/`safeName` | **Variant B is not storable/activatable — two structural breaks.** (a) The manifest name `assets/token_vocab.bin` **contains `/`**; `safeName` (`'/' !in name`) and `safeChild` forbid that as traversal protection → `partFile`/`movePartToStaging` throw `require("…not whitelisted")` or "path traversal". (b) `safeName`/`safeChild`/`safePart` validate **only** via `isAllowedName(name)` (single-arg = constructor `manifest`, default **A/ONNX**) — the variant-aware `isAllowedName(name, variant)` is used nowhere in the store paths. Consequently **all** B names (including the 4 slug-less `.tflite`) are rejected as long as the store is built with the default manifest. ⇒ the LITERT download can never reach the staging version and `activate(staging, LITERT)` throws instead of returning `false`. | `manifest.any { it.name == name }` (A) in `isAllowedName`; `'/' !in name` in `safeName`/`safeChild`; `assets/token_vocab.bin` with `/`. | Separate storage names from the URL path structure: manifest name = `token_vocab.bin` (flat), URL prefix `assets/` separately in the downloader; build `ModelStore` with the **variant** manifest (or switch `safeName` to `isAllowedName(name, variant)`). |
| V1-02 | Note | `ModelStore.kt` `activate(staging, variant)` | **Verify-vs-path inconsistency.** `activate(LITERT)` checks against `manifestFor(LITERT)` (5 B files), but builds the paths via `safeChild` against the **A** whitelist — the check runs against B, the path construction against A. Even after the V1-01 fix, the whitelist source must be consistent, otherwise `activate` throws an uncaught `IllegalArgumentException` instead of returning `false`. | `for (entry in manifestFor(variant)) { val f = safeChild(…, entry.name) … }`. | Pass `safeChild` the set `manifestFor(variant)`; map the error case (`require`) to `return false`, so `activate` never crashes on a data error. |
| V1-03 | ✓ | `ModelStore.kt:70-80` (`pointerFile`) | **Backward compatibility intact (no P0).** `pointerFile(ONNX_CPU)` uses `active-onnx-cpu.txt`, **if present**, otherwise the legacy `active.txt` (counts as A). An existing device with `versions/v-test` + `active.txt` continues to report `READY` (no forced 671-MB download). | `if (v1.isFile) v1 else legacy`. | — |
| V0-01 | ✓ | `ModelVariant.kt` (`fromId`), `EngineFactory.kt:22` | **Behavior neutrality for A users.** Default = `ONNX_CPU`, `fromId(null/unknown)→ONNX_CPU`, `create(variant)=ParakeetAsrEngine()` (V0: both variants → the existing ONNX engine). Without an explicit choice, engine type, model path (`modelDir()`=A), download size and status display remain identical. | `fun create(variant) = ParakeetAsrEngine()`. | — |
| V2-01 | ✓ | `AsrThreadConfig.kt:73` + `OnnxSessions.kt:52` + `VoiceKeyboardService.kt:159-160` | **S12-01 fix correct.** `deviceRamMb` is set from `ActivityManager.getMemoryInfo().totalMem` (device RAM); `OnnxSessions` now reads only `AsrThreadConfig.deviceRamMb`. No `Runtime.maxMemory()` remainder in production paths (only a comment). `@Volatile var` (0 = unknown) is sufficient for "set once, read many"; an ASR load before the set sees 0 ⇒ no capping ⇒ identical to the old behavior (`min(4, cores)`), no stale value across user environments (process-scoped). | `deviceRamMb = memInfo.totalMem / 1MB`; `ramMb = AsrThreadConfig.deviceRamMb`. | — |

---

## Point-by-point answers

1. **Neutrality:** no path to different behavior without a choice (V0-01). ✓
2. **Manifest B:** sizes/SHA256 entered per spec — **but** the `assets/` path portion is carried as a **storage name** (V1-01) and collides with the traversal guard + the A whitelist.
3. **Backward compatibility:** an existing A stays READY (V1-03); no P0.
4. **Whitelist per variant:** not wired — `isAllowedName(name, variant)` exists but is not used in `safeName`/`safeChild`/`safePart` (V1-01/V1-02). No overwriting of A files recognizable (separate variant pointers), but B is simply non-functional.
5. **V2 fix:** correct (V2-01); the diagnostic line `T=` I could not check against the set thread count for lack of reading time (`ASSUMPTION:` consistent, since `forDevice` is fed only by `deviceRamMb`+`availableProcessors`).
6. **Rules:** no files outside the list; no `maxMemory()` remainder; privacy (rule 9) unviolated in the new paths (only constants/file names logged).

---

## Verdict

- **V0: ready for release** (0 P0, 0 P1).
- **V1: not ready for release** (1 P1 — variant B is not downloadable/activatable due to the `assets/` name + the variant-blind whitelist).
- **V2: ready for release** (0 P0, 0 P1; S12-01 clean).

**Overall: not ready for release** (the V1 P1 blocks the delivered variant function).

**Not checked:** actual loading of the LiteRT `.tflite` files, `LiteRtAsrEngine` (V3, not part of this yet), the `T=` diagnostic line, the HF URL structure of `assets/token_vocab.bin` at the live endpoint.

## Top-3

1. **V1-01 (P1)** — Separate storage names from the URL path structure (`token_vocab.bin` flat; `assets/` only as a URL prefix) and build `ModelStore` with the variant manifest, or make `safeName` variant-aware.
2. **V1-02 (Note)** — `activate` must never throw on a name/data error (`require`→`return false`); put path construction and verification on the same whitelist source.
3. **V2 (Note)** — On the next run, verify the `T=` line against the `forDevice` result.
