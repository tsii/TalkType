# Review Stage 6 — Production micro-fix (InputConnectionSink, E5/E6) + E8 decision document

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Subject A:** `InputConnectionSink` (reentrancy + `EDIT_TIMEOUT_MS`), `SetupActivity` (R-3), `VoiceKeyboardService` (E5 mapping), `strings.xml` (E6). **B:** draft `docs/COMMIT_RULE_DECISION_2.md` (E8). **Gradle lock → no build** (pure reading; 197 test runs were verified by the orchestrator).

---

## Findings table

| ID | Sev | Location | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| A1 | **P1** | `InputConnectionSink.kt:74-89` (`onEditExecutor`) | **Ghost write after timeout.** On a `latch.await(2s)` timeout it throws, but the already-posted block (`editExecutor.execute { … block() … }`, via `Handler(main).post`) is **not cancellable** and runs **later** on the main thread. `block()` = `replaceBeforeCursorSync` then calls `connectionProvider()` (→ `currentInputConnection` **at execution time**, possibly a different editor) and writes `deleteSurroundingText`/`commitText` — **after the caller has given up and aborted/started a new session**. Consequence: stale text into the wrong editor, double/ghost writes. That is exactly the P1 the assignment asks about. | Lines 74-80 `editExecutor.execute { try { result = block() } … }`; lines 82-86 `if (!latch.await(…)) throw IllegalStateException("Editor-Sync Timeout…")` — no cancel, no flag. | One `AtomicBoolean abandoned` per call; on timeout set `abandoned=true`; the block checks `if (abandoned) return` **before** `replaceBeforeCursorSync`. (Alternative: make the write path not post-and-wait, but message with a completion callback.) |
| A2 | P2 | `VoiceKeyboardService.kt:296-340` + `strings.xml` | **E5 mapping incomplete.** Missing classes all fall onto `dictation_error_audio_start`: (a) "no microphone" (`dictation_error_no_mic` missing; no `AudioManager.getDevices` check), (g) "low memory" (`dictation_error_out_of_memory` missing; `OutOfMemoryError` is **not** caught — `catch (e: Exception)`/`startErrorText(e: Exception)` do not see an `Error`), fallback `dictation_error_unknown` (falls through to `audio_start` instead of its own text). E5 requires 7 + fallback. | `mapVisibleError` only `permission`/`model:`/`btLost` → else `audio_start`; `startErrorText` only `SecurityException`/`BROKEN` → else `audio_start`; strings.xml has 5 of 8 `dictation_error_*` (no `_no_mic`, `_out_of_memory`, `_unknown`). | Additionally catch `OutOfMemoryError` (Throwable); empty `AudioManager.getDevices(GET_DEVICES_INPUTS)` catalog → `_no_mic`; its own `_unknown` fallback; pattern `alloc`/`bad_alloc` → `_out_of_memory`. |
| A3 | Note | `VoiceKeyboardService.kt:319-339` (`updateStatus(extra)`) | **`extra`/`stickyError` remains a raw-string channel.** `updateStatus(extra)` sets `stickyError = extra` and renders it unchecked; any caller passing a raw code/literal (e.g. a future `updateStatus("… ${e.javaClass.simpleName}")`) lands directly in the UI. Currently the main path maps (`mapVisibleError`), but the `extra` channel is not secured type-safely. | Line 322 `if (extra != null) stickyError = extra`; line 327 `extra != null -> append(extra)`. | Feed `updateStatus` only with enum/string-resource IDs (no free string); replace `extra` with a `sealed` status object or restrict it to the `dictation_error_*` resources. |
| A4 | ✓ | `SetupActivity.kt:112` + `strings.xml:29` | R-3 done: `NetworkDisabled` → `setup_network_disabled` ("Netzwerkzugriff ist deaktiviert — Schalter aktivieren"); a real network error (download-failed path, `:232`) stays with `setup_error_network`. | — | — |
| A5 | ✓ | `strings.xml:31` | E6 variant A implemented **literally** ("…the app disables its download switch automatically; no further network access takes place then. … completely revoke the network permission …"). Correctly covers the technology (app flag, no permission revocation). | — | — |
| A6 | ✓ | `InputConnectionSink.kt:48-58` | Reentrancy guard correct: `isOnMainLooper()` (`Looper.myLooper() === Looper.getMainLooper()`) → inline; also covers "already inside the posted edit block" (that one runs on main). `MAIN_EXECUTOR` lazy with a JVM fallback (no looper in unit tests → inline). `latch`/`result`/`error` are local variables per call → no state leak between calls. | — | — |

---

## Part B — E8 decision document (COMMIT_RULE_DECISION_2.md)

| ID | Sev | Location | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| B1 | P2 | E8 §4.1/§4.3 ("≤ 60 **codepoints**") | **Unit ambiguity.** The document says "60 codepoints", but the existing implementation (`DeltaTyper.maxRevisionChars`, `commonPrefixLength`, `droplast`/`substring`) counts **UTF-16 code units**. 60 emoji (2 UTF-16 units each) = 60 codepoints but 120 units → W3c vs. W3d decides differently depending on the counting. The document nowhere specifies which unit applies. | E8 §4.1/4.3 "≤ 60 Codepoints"; `DeltaTyper.kt` `MAX_REVISION_CHARS=60` + `commonPrefixLength` (UTF-16/`char`-indexed). | Make it explicit: "60 **UTF-16 code units** (like `DeltaTyper.maxRevisionChars`)", or switch both to graphemes (`Graphemes.count`) — but name **one** unambiguous unit. |
| B2 | P2 | E8 §5 T1 ("Vikti ×2 … rollback exactly once") | **T1 contradicts C1 (N=3).** With `commit_agreements=3`, 2 identical "Vikti" hypotheses do **not** commit (only 2 agreements) — so there is nothing to roll back; "Vikti"→"Dikti" is a **normal tail revision (W2)**, not a rollback (W3). But T1 claims "rollback exactly once". The measured device case (2× wrong ⇒ commit) is prevented by C1 alone at N=3; W3 then remains only belt-and-suspenders for "3× wrong". | E8 §1 (N=2 led to the commit) vs. §3 C1 (N=3) vs. §5 T1 ("rollback exactly once"). | Sharpen T1: either (i) T1 = "2× Vikti does **not** commit, the 3rd hypothesis corrects without rollback" (C1 proof), and a **separate** T-test "3× wrong ⇒ commit ⇒ W3 rollback" for the rollback path — two distinct cases instead of one conflated one. |
| B3 | Note | E8 §3 C1/C2/C3 + §6 | **A short session (1–2 words) never commits by design** (C3 ≥3 words), committedLength stays 0; the document says only indirectly (final decode `committed_len=0`) what happens at the end, and does not explicitly clarify that the short text **nevertheless** appears as a partial and is written in full at the finale (the D3 fix in §2). For the implementer it stays open: "no commit" ≠ "no text". | §3 consequence + §2 additional invariant ("always write the tail") — but no sentence about the short-session finale. | Add explicitly: "Session < 3 words: everything partial, nothing committed; the final decode (committed=0) writes/corrects the whole own span." |
| B4 | Note | E8 §2 W4 + terms | **Alignment of `target` (hypothesis) and `own` (currentText) with pre-existing foreign text is not defined.** `common = commonPrefixLen(own, target)`, but `target` is the full transcript text, `own` only the session span (from `ownStart`). If `target` deviates from the content of `own` at a detok/leading edge (`own` is a suffix of the editor), `common=0` results ⇒ spuriously W3 ⇒ rollback **of the whole own span**. W4 claims "cannot occur before ownStart" but presupposes exactly this prefix alignment. | §2 "divergence = commonPrefixLen(own, target) < committedLength"; §2 W4. | Stipulate: `common` is to be computed **inside** `own` (relative alignment, the `own` prefix is matched against the `own` end of `target`), and a mis-alignment ⇒ W1 (not W3). |

---

## Point-by-point answers (assignment)

- **(a) Reentrancy detection:** correct — `myLooper === mainLooper` covers "on main" and thereby also "already inside the posted block" (the block runs on main) → inline (A6). ✓
- **(b) After a timeout:** **P1** — the posted block is not cancellable and writes later (A1).
- **(c) Error at the call site:** the timeout `IllegalStateException` is caught on the partial/final path by `DictationController.feedAudio`/`stop()` (`catch (e: Exception) → fail`) → no IME crash, but it collapses into the collective code `asr_partial_error` (timeout not distinguishable). No C-1 pattern (no crash), but a fuzzy diagnosis.
- **(c) latch/result reset:** clean — local variables per call, no leak (A6). ✓
- **E5 completeness:** three classes missing (a/g/fallback), OOM not catchable (A2); `extra` channel unsafe (A3).
- **E6:** variant A implemented literally, technically correct (A5).
- **W1/W2/W3 gaps:** B4 (alignment/ownStart) is the only "both/neither" gap; the W3d limit (60 codepoints) is ambiguous (B1); T1 inconsistent (B2).

---

## Verdict

**Part A: merge with conditions** — 1 P1 (A1 ghost write) must be fixed before release; R-3/E6/A6 clean, E5 partial (P2).
**Part B (E8 document): block until sharpened** — unsuitable as an implementation basis as long as B1 (unit) and B2 (T1/C1 contradiction) are open; the rule authority must clarify them before the coder implements E8.

**Overall: merge with conditions.**

## Residual risks

1. A1: rare (only with a main thread blocked >2 s), but then text into the wrong editor.
2. B2: an implementer reading E8 literally writes a T1 test that runs against C1 and never exercises the rollback path.
3. B4: with pre-existing editor text, an alignment bug can lead to a full rollback of the own span.

## Top-3 actions

1. **A1 (P1)** — `abandoned` flag: set it on timeout, the posted block checks it before the editor write (no ghost write).
2. **A2 (P2)** — Deliver `OutOfMemoryError`/`AudioManager.getDevices`/`_unknown`, so that E5 (a)/(g)/fallback are distinguishable and no `Error` crashes uncaught.
3. **B1 + B2 (P2)** — Sharpen E8: fix the unit ("60 UTF-16 code units") and split T1/T-rollback into two separate test cases consistent with C1.
