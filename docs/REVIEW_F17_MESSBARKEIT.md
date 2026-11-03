# Review F17b/F17c — Decode measurability (decode_ms_max/p95, buffer_s)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `DictationController` (decode ring), `DebugInfoFormatter`/`Collector` (+3 fields), `DictationCoordinator` pass-through, `DebugInfoCollectorTest`. No build/emulator.

---

## Findings table

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| **P2** | `DictationController.kt:115-119` (`decodeTimesMs`/`decodeSeq`/`decodeSamples`) + `lastDecodeBufferSeconds` | **Data race (diagnostics): worker writes, Main reads without `@Volatile`/fence.** `decodeTimesMs[i]=ms; decodeSeq+=1; decodeSamples+=1` runs in the worker (transcribe→noteDecode), `decodeMsMax()`/`decodeMsP95()`/`bufferSeconds()` read on the 🐞 Main thread. Consequences are **not** negative/torn (`coerceAtLeast(0)`, arm64 64-bit long = atomic), but **stale** — a 🐞 read can show `0`/"never decoded" despite decodes having occurred (missing visibility of the count). | `@Volatile` on `decodeSamples` (and `lastDecodeBufferSeconds`); keep the write order "array slot first, count last" — the volatile write/read on the count creates the happens-before for the array contents. |
| **P2** | `docs/DEBUG_INFO_FIELDS.md` (catalog) | **Field catalog drift:** the three new keys are missing from the docs (KEYS is now 27). B-`grep decode_ms_max/decode_ms_p95/buffer_s` → empty. | Add the following three lines to the catalog: `decode_ms_max  — largest decode runtime of a transcribe call in ms (0 = never decoded)` · `decode_ms_p95 — p95 of the last ≤64 decode runs in ms` · `buffer_s — buffer length (seconds) at the last decode run (correlates with decode_ms)`. |
| Note | `DictationController.kt:132-139` (`decodeMsP95`) | **"p95" is the p95 of the last ≤64 ring samples, not of the session.** Naming slightly misleading; fine as a diagnostics sample (KDoc says "deterministic sample"). No defect. | Optionally rename KDoc/field to "decode_ms_p95_last64" or add a note "sample" — purely documentary. |

---

## Answers A–G

- **A) Measurement correct.** `ms = ((clock()−start)/1e6).coerceAtLeast(0)`: 0-ms run → 0, `nanoTime` wrap → 0 (never negative), empty history → max==p95==0, one run → max==p95 (idx = `(1*95+99)/100−1 = 0`). Ring 64: `decodeSamples` caps at 64, older values are correctly overwritten, and `decodeMsMax/P95` iterate exactly the 64 valid slots.
- **B) Harmless but real race (P2 above):** no negative/torn values (clamp + 64-bit), but **stale** `0` display possible. Minimal fix = `@Volatile decodeSamples` + "array-before-count" write order.
- **C) `bufferSeconds()` = buffer of the **last** decode run** (`lastDecodeBufferSeconds = audioSeconds` in `noteDecode`, i.e. including the finale with the full buffer). Consistent with the export claim ("at the last decode run", not current recording duration). ✓
- **D) No behavior/allocation/blocking effect:** `noteDecode` = 1 array write (0 allocations per decode); `decodeMsP95` allocates `copyOf(≤64)` only on the 🐞 read. `window()` remains unchanged (`PcmWindow(pcm.copyOf(totalSamples), …`, line 407). ✓
- **E) Field-count drift:** correct in the **formatter** (KEYS 24→27, output 28 lines = 1 header + 27 fields, `check(lines.size == KEYS.size + 1)` line 137 active). **Docs drift** in `docs/DEBUG_INFO_FIELDS.md` = P2 (above).
- **F) Test quality — good, genuine chain.** `DebugInfoCollectorTest` (lines 95-145) builds a real `DictationCoordinator` (+ `QueueSource` + fake engine with `calls`), waits ≤3 s, `stop()`, waits for IDLE, asserts `engine.calls >= 1` **and** `bufferSeconds > 0.0` (a genuine final decode, cannot pass with buffer 0) and `assertNotNull(snapshot.decodeMsMax/P95/bufferSeconds)` (lines 135-137) — deleting collector lines 101-103 turns exactly these asserts **red**. Additionally regex asserts on the export lines (lines 143-145).
- **G) Rule 9 clean:** only numbers (`decode_ms_max/p95` ms, `buffer_s` s) — no content/paths/personal data.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 2** (worker/Main visibility without `@Volatile`; three missing keys in `docs/DEBUG_INFO_FIELDS.md`) · 1 note (p95 naming).

**Open (to provide evidence I would need):** a real run (`./gradlew test`) for the 376/0 claim and the green-test proof; a 32-bit emulator for the torn-long question (theoretical, irrelevant on arm64); the full `QueueSource` text to confirm "really delivers ≥1 block" (grep showed only start/stop/deadline).

---

## Addendum F19 (explicit p95 index + AsrThreadConfig cross-check)

- **p95 index recalculated** (`DictationController.kt:136` `idx = (size*95 + 99)/100 - 1`): n=1 → 0 ✓ (max==p95); n=2 → 1 (the larger value) ✓; n=64 → 60 (= ceil(0.95·64)−1 = 61st index, ~95th percentile) ✓. Tiny: the pulled-forward `+99` is `ceil(0.95·n)`; for n=64 this gives 60 (0-based), correct.
- **G) AsrThreadConfig UNCHANGED** (`AsrThreadConfig.kt:83-90`): `intra = if (safeCores <= 8) minOf(4, safeCores) else minOf(MAX_THREADS, safeCores)`, clamp 2..6, RAM cap `≤2 GB → 2`, `deviceRamMb` unchanged, `DEFAULT=intra=4`. The asserted target value `forDevice(cores=6)→intra=6` is **not** present — no behavior change, existing rule `min(4, cores)` intact.
