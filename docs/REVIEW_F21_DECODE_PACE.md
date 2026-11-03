# Review F21 — cost-aware decode pacing (DecodePace, chunkMs 2000, no sliding window)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `dictation/DecodePace.kt` (new), `DictationController` (gate + counters), `DictationCoordinator:130`, `AsrThreadConfig` (chunkMs 2000 + pace clamps), `DebugInfoFormatter`/`Collector` (+2 fields), tests. No build/emulator.

---

## Findings table

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| **P2** | `docs/DEBUG_INFO_FIELDS.md:181` (+ missing lines) | **Catalog drift:** the two F21 keys `decode_pace_ms`/`decode_skips` are missing; the count "→ 27 fields" (line 181) is outdated — `KEYS` is now **29** (`DebugInfoFormatter.kt:115-116`: …`decode_ms_*`,`buffer_s`,`decode_pace_ms`,`decode_skips`). | Add two table rows and fix line 181 to "→ 29 fields": `decode_pace_ms` — current partial interval in ms (`decodePaceMs()`; `0` = pacing off) · `decode_skips` — partial decodes skipped due to pacing in this session (`decodeSkips()`). |

---

## Answers A–H

- **A) Saturation/edges correct.** `intervalMs` (`DecodePace.kt:30-34`): `base=min(base,max)` ⇒ `base>max` always yields `max`; `bufferMs<base` ⇒ `base`; `doubled` saturates (`>MAX/2 ⇒ MAX`, negative `*2` via `coerceAtLeast(0)` ⇒ `base`), `coerceIn(base,max)`. Recalculated: 0→base, 1→base, base/2→base, base→2·base, max/2→max, max→max, max·3→max, MAX→max, negative→base. Always within `[min(base,max), max]`, never overflow, never negative.
- **B) Lever ON, chain complete.** `DictationCoordinator:130` sets `decodePacing=true`; `DictationController.PACE_BASE_MS/MAX_MS` reference `AsrThreadConfig.DEFAULT.decodePaceBaseMs/MaxMs` (lines 465-466) — **one** source, no double hardcoding. Gate `paceAllowsDecode()` (lines 256-259) is active. No "into the trash" state.
- **C) Pacing is audio-progress-based (deep-sleep-safe).** `paceAllowsDecode` uses `secs − lastDecodeBufferSeconds` (buffer growth), **not** `clock()` — during deep sleep no audio grows ⇒ no decode of the same buffer. A 6000-ms outlier ⇒ `2·6000 → coerceIn → 6000 ms`, i.e. next partial after 6 s of new audio; `maxMs=6000` is the intended clamp and lives in `AsrThreadConfig.DEFAULT_DECODE_PACE_MAX_MS` + DecodePace KDoc. ✓
- **D) Finale independent (no P0).** The gate (line 256) lies exclusively in the partial path `feedAudio`; `stop()`/finale calls `engine.transcribe` (lines 332-333) **without** `paceAllowsDecode` — text loss ruled out.
- **E) chunkMs 1000→2000 without latency/queue regression.** `tickSamples = chunkMs·16000/1000` = 32000 (2 s); `Channel.UNLIMITED` unchanged; `pcm` grows freely; `dropped_blocks` does not depend on block size. First partial still at **2.0 s** (the `minAudioSecondsForPartials=2.0 s` guard dominates; with a 1-s tick the 1-s feed was below the guard anyway) — no later first output.
- **F) Test quality good.** `DictationControllerTest` proves the **skipping** (`decodeSkips()==5`, line 359) and `decodePaceMs()==6000` (line 353) with a slow fake engine, plus "pacing off ⇒ 0/0" (lines 382-383) — reverting `decodePacing=true→false` turns these **red**. `DebugInfoCollectorTest` additionally asserts `assertNotNull(decodePaceMs/decodeSkips)` (lines 139-140) — not the value (would not alone catch a reset, but the controller test does). `AsrThreadConfigTest` pins `chunkMs=2000` and continues testing the **geometry** with explicit `1000` — cleanly separated (default value vs. function), obscures nothing.
- **G) Rule 9 clean:** `decode_pace_ms` (0..6000) and `decode_skips` (counter) are pure numbers, no content/paths.
- **H) Rule 2 met:** the `DecodePace.kt` KDoc explicitly states "no sliding window, the whole window remains, the only lever is WHEN" (reference to KONZEPT §5.1) — no sliding-window interference. Additionally checked: `AsrThreadConfig.forDevice` (lines 83-90) is **unchanged** (`min(4, cores)` for ≤8 cores); no asserted `intra=6` target value.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 1** (docs drift only in `DEBUG_INFO_FIELDS.md`: two missing keys + outdated "27 fields").

**Open (to provide evidence I would need):** a real `./gradlew test` run (385/0 + compile exit 0), the full text of the new `DictationControllerTest` method (grep showed only the asserts, not the fake-engine timing), and a device run to check whether the 6-s partial pace remains subjectively tolerable on slow hardware (pure UX value, no code defect).
