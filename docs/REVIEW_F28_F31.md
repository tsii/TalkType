# Review F28/F31 — Watchdogs, FGS handshake, popup geometry, F29 layout gates, F30 FakeWav

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**State:** v0.1.23 / vc24 · Debug 461/0 · Release 433/0 (not run myself).

---

## Findings table

| Finding | Severity | File:Line | Fix |
|---|---|---|---|
| **SILENT watchdog aborts a running, good session on a natural speech pause (> 3 s).** `SILENCE_RMS = 0.01` (−40 dBFS) + `STALL_LIMIT_MS = 3000`; the silent branch fires as soon as `capBlocks > 0 && (now − lastLoudAtMs) > 3000` — i.e. after 3 s without a block ≥ −40 dBFS. Natural pauses (breathing/room noise typically −50…−60 dBFS) have **no** such block and end the dictation via `errorListener.onError(ERR_SILENT)` → `abort()`. | **P1** | `DictationCoordinator.kt:87-90, 337-338` (`SILENCE_RMS`, `STALL_LIMIT_MS`, silent branch) | Raise the silent threshold to 5000–8000 ms **or** lower `SILENCE_RMS` to ~−55 dBFS **or** check silent only at the end (after `stopAndDrain`), not mid-session. Minimally safe: silent as a warning instead of an abort. |
| **FGS handshake cold-starts near the 2-s deadline.** `awaitFgsReady(2_000)` waits for the FGS's `startForeground` signal; on a cold process/first start `onStartCommand`+`startForeground` can take > 2 s → "no audio start" for an otherwise valid start. No deadlock (the Condition `awaitNanos` releases the lock), but the deadline is tight. | P2 | `VoiceKeyboardService.kt:148, 471, 903-918` | Increase `FGS_READY_TIMEOUT_MS` to 3–4 s or allow a one-time retry handshake. |
| **Stall vs. silent double firing ruled out, race with stop() remains harmless.** The stall branch fires first and `return`s (comment "ERR_STALL hat Vorrang"), silent is never re-checked in the same run; a late watchdog against `stop()` only yields `NoFinal` (no data loss). | Note | `DictationCoordinator.kt:327-338` | — |
| **Reset paths clean.** `capBlocks/lastBlockAtMs/lastLoudAtMs/micRelValue/statusShownValue` are reset in `start()` (lines 202-211) — no leak into the next 🐞 export. | Note | `DictationCoordinator.kt:202-211` | — |
| **Popup top negative (r0) + `clipChildren=false`:** overhang beyond the status bar is intended (lines 72-73, ImeRootView:159-160), but the same `clipChildren=false` applies to **all** root children (including the emoji block) — a future overdraw outside the bounds would now become visible. With 5-row layouts (pt-pt), `rowIndex==0` is still the top row, but I could **not** prove the footer detection ("row contains space") (symbol missing in grep) — source of `isFooterRow` unclear. | P2/open | `KeyboardView.kt:521-533`, `LongPressMenu.kt:90-94`, `ImeRootView.kt:159-160` | Show/test the `isFooterRow` derivation (layout without `space`/ru-ru digit row); if needed set `clipChildren=false` only on the KeyboardView instead of the root. |
| **F29 gates:** the target values (space 6.5, mic 2, @ rule, m-left 0.5, shift 1) are covered by the gate only on a **sum basis**; an "@ as a standalone key AND as an alternate" (dual path) would not violate Σ and might go undetected — a check of the individual values is missing from the findings material. | P2/open | `FooterAcrossAssetsTest` / `LayoutCompletenessTest` | Individual-value asserts (space==6.5, mic==2, @ exactly 1× per layer without an alternate duplicate) instead of Σ only. |
| **F30 FakeWavAudioSource (debug-only):** source set not read; time dependency (sleep-based fast-forward) is a flakiness risk if it runs via `System.sleep` instead of an injectable clock. | open | `debug/FakeWavAudioSource.kt` | Confirm source set (`src/debug`) + injectable clock instead of `sleep`. |

---

## Overall verdict

**P0 = 0 · P1 = 1 · P2 = 3** (overall; the three P2s are the FGS cold-start deadline, popup clip/footer detection unresolved, F29 gates sum-based).

**Pushable?** **No — only after fixing the P1.** The SILENT watchdog (3 s / −40 dBFS) ends a dictation on a natural speech pause > 3 s — that is a false abort in the normal case and must not go into v0.1.24. Minimum fix: raise the silent threshold to ≥ 5 s or lower `SILENCE_RMS` to ~−55 dBFS (or check silent only at session end). The three P2s are not push-blocking but belong before 0.1.25.

**Not proven myself (open):** test runs (461/433), `isFooterRow` derivation, `FakeWavAudioSource` source set/clock, F29 individual-value gates, permission gate from api 33 (signature seen only).

---

## Addendum F31b (P1 fix)

1. **The P1 is CLOSED.** `everLoud` (DictationCoordinator:218/300/330-332) is a one-shot latch: as soon as the first loud block (`rms ≥ SILENCE_RMS`) arrived, SILENT **never fires again** (`:361-362` `!everLoud` gate). A natural speech pause after first sound no longer aborts anything. **Remainder (note, no P1):** `everLoud` is irreversible — a single loud crackle at the start permanently disables the "mic muted" diagnosis; the case remains detectable via the `Kein Text erkannt` toast at stop() (no data loss, only weaker early diagnosis).
2. **4-s FGS deadline sufficient, not too generous.** In the normal case `markFgsReady` signals in <100 ms; the 4 s only take effect in the error/cold-start case (visible upper bound, no latency in the normal case; `VoiceKeyboardService:149,472`).
3. **Individual-value gates now cover the target values.** `r1[0]=0.5`, exactly one `@`, ru-ru without `@` + `period.alternates[0]="@"`, `r2[0] shift=1`, `space 6.5`/`mic 2`/`period 1` are asserted individually — the "sum-based too soft" P2 is resolved; the `@` dual path (key + alternate) is also covered via "exactly one at" + `period.alternates[0]`.

**Updated: P0 = 0 · P1 = 0 · P2 = 1** (remaining: popup clip/`isFooterRow` open) · 1 note (`everLoud` latch).

**Pushable: YES** — the only P1 is cleanly fixed via the `!everLoud` gate; the rest are non-blockers.
