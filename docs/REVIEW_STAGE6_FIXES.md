# Review Stage 6 — FIX pass (A1 ghost write, A2 E5 mapping)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Subject:** resolution of A1/A2 from `docs/REVIEW_STAGE6.md`. **Gradle lock → no build** (pure reading).

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| F1 | ✓ | `InputConnectionSink.kt:91-116` | **A1 (ghost write) fixed.** One `AtomicBoolean abandoned` per call; the posted block checks it **as the very first thing** (`if (abandoned.get()) return@execute`) and becomes a no-op; `abandoned.set(true)` stands **before** the throw. Invariant upheld: after a timeout no editor touch. Remaining window: theoretically the block can miss the timeout signal between `get()` (false) and `block()` — practically unreachable, because a running block triggers `countDown()` immediately and thus `await` returns before the 2-s limit; a *truly* late block sees `abandoned==true`. | Line 93 `if (abandoned.get()) { …; return@execute }`; line 112 `abandoned.set(true)` before `throw`. | None. |
| F2 | Note | `InputConnectionSink.kt` + `DeltaTyper` | **Timeout ⇒ no drift in the normal case; rule: abort the session.** `apply()` sets `currentText` only after a successful `sink.replaceBeforeCursor` → on a timeout `ownSpan` (sink), `currentText` (DeltaTyper) and the editor remain atomically consistent; `commitUpTo` clamps to `currentText.length`. **Remaining vector:** the `>60` branch writes `committedLength = currentText.length` **before** `apply()` (finding S4-02) — a timeout there leaves a pre-advanced `committedLength`. Since the timeout signals a blocked main thread, follow-up edits are unreliable. **Rule:** editor sync timeout ⇒ `abort()` the running dictation session (do not keep revising). | `InputConnectionSink.onEditExecutor` (throw); `DeltaTyper.apply` (sink first, then `currentText`). | Inspect/clean up the `>60` branch (`committedLength` only after `apply`); treat a timeout in the `DictationController` as a session error (abort). |
| F3 | P2 | `DictationController.kt` `feedAudio`/`stop` (`catch (e: Exception)`) | **A2 (g) OOM covered only at start, not during `transcribe`.** The new `catch (e: OutOfMemoryError)` in `onMicDown` catches OOM in `coordinator.start()` (engine load/audio init). But the native ORT memory error typically occurs **during inference** — `DictationController.feedAudio`/`stop()` catch only `Exception`; an `OutOfMemoryError` (Error) propagates ⇒ coroutine/app crash instead of `dictation_error_out_of_memory`. The `mapVisibleError` pattern `alloc`/`bad_alloc`/`oom` never reaches this path (the error crashes first). | `DictationController`: `try { engine.transcribe(…); applyPartial(…) } catch (e: Exception) { fail }`; `VoiceKeyboardService.onMicDown` `catch (e: OutOfMemoryError)`. | Additionally catch `OutOfMemoryError` (and if needed `Throwable`) in `feedAudio`/`stop` and map it to `fail("oom")` → `dictation_error_out_of_memory`. |
| F4 | ✓ | `VoiceKeyboardService.kt:295-340` + `strings.xml:34-41` | **A2 (E5 a–h + fallback) complete.** All eight strings present (`_permission`, `_model_absent`, `_model_broken`, `_bt_lost`, `_no_mic`, `_out_of_memory`, `_unknown`, `_audio_start`); `mapVisibleError` maps `noMic`/`audioInit:noInputDevice`, `alloc/bad_alloc/oom`, `unknown/internal`, `SecurityException`/`permission`, `model:` (BROKEN before ABSENT), `btLost`/`route:bluetooth`; `startErrorText` assigns `SecurityException`/`BROKEN`/rest. Other `Error`s deliberately keep flying (only OOM specifically caught — correct). | `strings.xml:34-41` (8 keys); `mapVisibleError`/`startErrorText`. | None. |
| F5 | ✓ | `strings.xml:31` | **E6 variant A literal.** "…the app disables its download switch automatically; no further network access takes place then … completely revoke the network permission …" — correctly names the app flag, not a permission revocation. | `setup_privacy_text`. | None. |
| F6 | Note | `VoiceKeyboardService.kt` `updateStatus(extra)` | **The `extra` channel remains untyped.** The dictation paths now map via `mapVisibleError`/`startErrorText`; but `updateStatus(extra: String)` (sticky) still accepts arbitrary strings. Currently no raw code is evidenced (inventory check: callers pass only mapped resources), but the type-safe lock against re-intrusion is missing. | `updateStatus(extra)` sets `stickyError = extra` unchecked. | Optional: replace `extra` with `@StringRes`/enum (not blocking). |

---

## Point-by-point answers

- **(a) IC access after timeout:** no, practically ruled out — the block checks `abandoned` as its first statement; the `catch` branch discards the error (`if (!abandoned.get()) error = t`), `finally` only `countDown()`. No path on which the block still executes `connectionProvider()`/`deleteSurroundingText` after giving up (F1).
- **(b) Drift question (1b):** **no genuine drift in the normal case.** `ownSpan`, `currentText` and the editor stay consistent because `apply()` updates only after a successful sink write and `commitUpTo` clamps. **Rule:** timeout = `abort()` of the running session — a blocked main thread makes follow-up edits unreliable, and the `>60` branch (`committedLength` before `apply`) is the last remaining drift vector (F2).
- **A2 completeness:** mapping + strings complete (8 classes); the only gap = OOM during `transcribe` (F3).
- **E6:** technically correct (app switch, not permission) — F5.

---

## Verdict

**Release for merge.** A1 (the P1) is correctly closed; A2 is complete except for the P2 remainder (OOM during inference). Open: **0 P0, 0 P1, 1 P2** (F3).

## Top-3

1. **F3 (P2)** — Also catch `OutOfMemoryError` in `DictationController.feedAudio`/`stop` → `fail("oom")` → `dictation_error_out_of_memory` (otherwise native ORT OOM crashes during inference).
2. **F2 (Note)** — Review the `>60` branch in `DeltaTyper` (`committedLength` only after `apply`), wire the timeout as a session abort.
3. **F6 (Note)** — Make `updateStatus(extra)` type-safe (`@StringRes`/enum), so no raw-code re-entry is possible.
