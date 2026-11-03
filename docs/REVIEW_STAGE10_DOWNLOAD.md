# Review Stage 10 — Download (D1: error classification, 416/200 resume, retry, remote diagnosis)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-28
**Subject:** `DownloadPolicy.userMessageKey`, `ModelError.detailForUser`, `ModelDownloader` (416/200/retry), `SetupActivity`, `strings.xml`. **No build/emulator** (orchestrator: 242 tests/0 failures; SHA of the CDN = manifest, Range/206 served).

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| D1-01 | **P2** | `ModelError.kt:47-51` (`scrub`) | **Scrub is not exhaustive (defense-in-depth gap, no current leak).** `scrub` removes only `https?://\S+` and `[?&](Signature|Expires|xip)=\S*` — a URL **without** a scheme (bare hostname/path, `ftp://`) or a filesystem path under `files/` would remain; URL-decoded remnants (`%2F`) are also not handled. In **this** code base, however, a URL never gets into a detail: `Http.detail="for <filename>"` (German original "für"), `HashMismatch.fileName` (whitelisted), `Storage` → fixed "Storage" (translated from German "Speicher"), only `Network.detail = IOException.message` (system-generated, mostly "Connection refused/network unreachable", without URL). A future `opener` that writes URLs into exception text would be only partially caught by the scrub. | `scrub = s.replace(Regex("https?://\\S+"),"").replace(…Signature…Expires…xip…,"").trim().take(48)`. | Harden the scrub: also remove `ftp://`, `\S+://`, bare `\S+/\S+` paths and `files/…` patterns; additionally a "only words/digits/-/·" allowlist instead of a blacklist. |
| D1-02 | Note | `DownloadPolicy.kt:64` (`in 400..499 -> "server"`) | **`Http(416)` falls under "server" and reads as "server refused the file" — misleading** for a range-satisfiability/resume problem. 416 reaches the user only after the single-restart fallback (a fresh range-less request nevertheless returned 416 = a genuine server anomaly), so rarely — but "refused" does not fit the causality. | `when(statusCode){ 408,429->"warte"; in 400..499->"server"; else->"netz" }` (416 ∈ 400..499). | Give 416 explicit precedence (its own key "datei"/`setup_error_range` or continue "neues versuchen"), so the message does not suggest "server refuses". |
| D1-03 | Note | `ModelDownloader.kt` (retry loop + backoff) | **Up to 5 attempts (initial + 4 retries) × 652 MB on an unstable line.** Backoff 1/2/4/8 s; the `.part` is reused across attempts (no restart, except `200`/`>sizeBytes` truncate) — progress resets only on a genuine truncation, which is correct. On a persistently flapping connection the total can produce ~5+ min of "retry with no visible progress"; no endless loop (`attempt<=maxRetries`), `Cancelled` never retried. | `while (attempt <= maxRetries)`, `Thread.sleep(backoffBaseMs shl (attempt-1).coerceAtMost(4))`. | Optional: feed the progress listener with an "attempt n/5" signal, so the UI does not stand still silently. |
| D1-04 | Note | `ModelDownloader.kt` (`else { written = part.length() }`) | **Dead branch.** After the early 416 handling, `input` is never `null` again (416 returns beforehand; 200/206 always provide an `inputStream`) — the `else` branch with `written = part.length()` is leftover code. Harmless, but confusing. | `val input = connection.inputStream` (never null after the 416 return); `else { written = part.length() }`. | Remove the branch or leave it consciously empty. |
| D1-05 | ✓ | `ModelDownloader.kt` (200/416/`>sizeBytes`) | **Integrity holds in every branch.** 200 instead of 206 ⇒ `RandomAccessFile.setLength(0)` (no appending) + digest from 0; 416 complete ⇒ direct hash; 416 short ⇒ one restart, then `Http(416)`; `written > sizeBytes` ⇒ `.part` to 0 + `Network` (retryable). The end-to-end hash runs in **every** path (prefix read into the digest + body, `written != sizeBytes` → error). No truncated `.part` is ever considered complete. | `RandomAccessFile(part,"rw").use{ it.setLength(0) }` on `!rangeAccepted`; `raf.setLength(0); throw Network(...)` on `written>sizeBytes`. | — |
| D1-06 | ✓ | `DownloadPolicy.kt:56-68` + `SetupActivity.kt:291-299` | **Classification direction correct + fully wired.** 408/429 → "warte" (temporary), 400..499 → "server" (permanent 4xx), 5xx/rest → "netz". The dead `if(>=500) "netz" else "netz"` branch is eliminated. `SetupActivity` maps `"server"→setup_error_server`, `"warte"→setup_error_wait`, `"netz"→setup_error_network` and additionally shows `detailForUser()` (no fallback-to-"abgebrochen" regression). | Lines 295-296 `"server"/"warte"`; line 299 `detailForUser()`. | — |
| D1-07 | ✓ | `ModelError.kt:56-66` (`detailForUser`) | **Remote diagnosis short + dictable.** `"HTTP 403 · encoder-model.int8.onnx"` (≤48 chars, without URL/token); `Network` only the scrubbed `detail`; `Storage`/`Cancelled` fixed words. Contains nothing that would be dangerous via remote dictation. | `take(48)`, `removePrefix("für ")` (literal as in code at review time). | — |

---

## Point-by-point answers

1. **Privacy:** in the current code `detailForUser` never delivers a URL/path/header — `Http` carries only the file name, `HashMismatch` likewise, `Storage`→"Speicher"; the only open spot is `Network.detail` (IOException text), which is system-generated and URL-free. The regex scrub is **not** exhaustive (scheme-less URLs/bare paths slip through), hence a P2 as a hardening gap, not a current leak (D1-01).
2. **Classification:** direction correct (4xx permanent, 408/429 temporary, 5xx transient); as a 4xx, 416 falls under "server" and sounds slightly wrong ("file refused") for the resume scenario (D1-02).
3. **Integrity:** no path in which a truncated `.part` is considered complete without a hash; 200 truncation and `>sizeBytes` are covered (D1-05).
4. **Retry:** bounded (5 attempts, backoff), resume preserved, no endless loop; only the duration of ~5+ min is possible (D1-03).
5. **Regressions:** cancel never retried ✓, all new keys wired (D1-06), no new permissions/network side effects recognizable.
6. **Remote diagnosis:** short, dictable, without anything sensitive (D1-07).

---

## Verdict

**ready for release for a diagnosis/release APK** — **0 P0, 0 P1, 2 P2** (D1-01 scrub hardening, D1-02 416 labeling) + 2 notes. The D1 real classification correctly fixes the reported "network error on file 2" bug; integrity and retry are solid.

**Not checked (no real CDN behind a VPN):** the CDN's actual 403/429 behavior, range redirects (200 instead of 206) at the real endpoint, WireGuard route interaction, progress perception across multiple 652-MB retries.

## Top-3

1. **D1-01 (P2)** — Switch `scrub` to an allowlist (only words/digits/`·`/`-`), so that scheme-less URLs/bare paths are excluded in the future too.
2. **D1-02 (Note)** — Give `Http(416)` its own key (no "server refused").
3. **D1-04 (Note)** — Remove the dead `else` branch in `downloadAttempt`.
