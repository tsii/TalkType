# Review Stage 11 — D2 (download hardening) + S12 (performance levers)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-28
**Subject A:** `DownloadPolicy` (416→"fortsetzung"), `ModelError.detailForUser` (allowlist), `SetupActivity`, post-download `ModelDownloader` retry. **B:** `asr/AsrThreadConfig` + wiring in `OnnxSessions`. **No build/emulator.**

---

## Assignment 1 — D2

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| D2-01 | **P2** | `ModelError.kt:41-48` (`scrub`) | **URL-encoded remnants slip through.** The token filter recognizes only **literal** `://`, `Signature`, `Bearer`, `?`-prefix; a URL-encoded fragment `%3A%2F%2Fcdn.example.com%2F…` contains no literal `://` and `%` is **in** the character allowlist → it survives `File(...).name` (no `/`) unchanged and passes the character filter (letters/digits/`%`). Likewise an IPv6 literal `2001:db8::1` (digits/`:`) survives — `:` is allowed. No current leak (the actual details carry only file names/IOException texts), but the allowlist is leaky at this spot. | `tok.contains("://")` literal only; `cleaned.filter { … ch=='%' … }`. | Explicitly discard `%`-hex pairs (`%[0-9A-Fa-f]{2}`) and `::` brackets; allow `%` only after `[0-9]` ("100%") or remove it from the allowlist entirely. |
| D2-02 | Note | `ModelError.kt:45` (`File(name).name`) | **`File(name).name` can come out empty.** A token consisting only of `/` or ending with `/` yields `File("/").name == ""` → for `Network`, `detailForUser()` is then the **empty** line (the main message remains). The HTTP code and `HashMismatch.fileName` remain readable in contrast (the code is a separate `Int` branch). | `java.io.File(tok.substringBefore('?')).name`. | Map an empty scrub to null (do not render an empty detail line). |
| D2-03 | ✓ | `DownloadPolicy.kt:62-64` | **`416 → "fortsetzung"` correct.** `Http(416)` reaches the user only in the double-416 case (a fresh range-less request returned 416 again); the **complete case** (a full `.part`) returns in the downloader as a success and produces no message. "fortsetzung" thus only hits genuine resume anomalies. The HTTP code always remains visible in `detailForUser` (`is Http -> "HTTP $statusCode"`). | `418/429 → "warte"; 400..499 → "server"` with `416` taking precedence; `ModelDownloader` 416-complete case `return true`. | — |
| D2-04 | ✓ | `ModelError.detailForUser` | **Retry text/behavior consistent.** 4xx (incl. 408/429/416) are **not** retryable in the downloader (`retryable = Network || Http>=500`); all three texts ("warte"/"fortsetzung"/"server") therefore correctly demand a **manual** new attempt; none suggests a running auto-retry. 5xx → "netz" (there the internal retry actually runs). | `ModelDownloader.download` retry condition. | — |

**Assignment 1 verdict: ready for release** — 0 P0, 0 P1, 1 P2 (D2-01) + 1 note.

---

## Assignment 2 — S12

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| S12-01 | **P1** | `OnnxSessions.kt:47-51` | **`ramMb = Runtime.maxMemory()` is the app's Java heap, not the device RAM → intra gets forced to 2 on EVERY device.** `forDevice` evaluates `ramMb in 1..2047` as "little RAM ⇒ intra=2". But `Runtime.getRuntime().maxMemory()` returns the Java heap limit (typically 256–512 MB, never ≥ 2 GB without `largeHeap`) — not the physical RAM. Consequence: **every** Android device (even 8 GB/8 cores) lands in the low-memory branch ⇒ intra 4→2 ⇒ ASR inference **slower than before** (`min(4, cores)` = 4). The default path is thus **not** identical to the old behavior — it is a perf regression rather than a lever. | `AsrThreadConfig.forDevice(cores=availableProcessors(), ramMb=maxMemory()/1MB)`; `forDevice`: `if (ramMb in 1..2047) intra = intra.coerceAtMost(2)`. | Pass device RAM from `ActivityManager.getMemoryInfo().totalMem` (B) instead of `maxMemory()`; target the 2-GB threshold at genuine low-end (1–2 GB RAM), not at the already small app heap. |
| S12-02 | Note | `AsrThreadConfig.kt` (chunkMs/hypothesisMinIntervalMs) | **Lever 2 not yet wired (S12b open).** Currently defaults `chunkMs=1000`/`hypothesisMinIntervalMs=2000`, identical to `TICK_SAMPLES_1S`/`MIN_AUDIO_SECONDS_PARTIALS`. With S12b, `chunkMs` must mandatorily be synchronized with the coordinator's `tickSamples` aggregation, otherwise audio aggregation and transcription chunk cut at different spots (cracks/double feeding). `hypothesisMinIntervalMs` < 2000 does **not** violate E8 (commit is count- and word-edge based, not time-based; the same-audio dedup guard remains effective). | Defaults; `chunkBoundaries`/`hypothesisEmissionTimes` pure logic. | When wiring S12b, write a chunk/audio-tick alignment test. |
| S12-03 | ✓ | `AsrThreadConfig.kt:30-34` (`applyTo`) | **`applyTo` is one-shot & safe.** Pure call of `setIntraOpNumThreads`/`setInterOpNumThreads` on the receiver; no null receiver (non-null), no double setting (called in `OnnxSessions` exactly once per `SessionOptions`). Clamp 2..6 correct. | `applyTo(receiver)` + `OnnxSessions` single call. | — |

**Assignment 2 verdict: not ready for release** — 1 P1 (S12-01 perf regression: intra 4→2 everywhere).

---

## Overall verdict

- **Assignment 1 (D2): ready for release** (0 P0, 0 P1, 1 P2).
- **Assignment 2 (S12): not ready for release** (1 P1 — `maxMemory()` as a RAM proxy throttles the threads to 2 across the board and makes dictation slower).

**Not checked (no device):** a real CDN behind a VPN (403/429/range redirects), actual ORT thread scaling on ARM64 under 2 vs. 4 threads, `ActivityManager.getMemoryInfo` correctness in the sideload context.

## Top-3

1. **S12-01 (P1)** — Pass device RAM instead of `Runtime.maxMemory()` (or target the low-RAM threshold at genuine 1–2-GB devices); otherwise the default stays identical to `min(4, cores)`.
2. **D2-01 (P2)** — Discard `%`-hex pairs (`%3A%2F%2F`) and IPv6 brackets in the scrub (sharpen the allowlist).
3. **S12-02 (Note)** — On S12b wiring, chunkMs↔tickSamples alignment + test.
