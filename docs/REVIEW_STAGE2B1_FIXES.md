# Review Stage 2 B.1 — FIX pass (to REVIEW_STAGE2B1.md)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Subject:** fixes for SB1-01/03/06/07 from `docs/REVIEW_STAGE2B1.md`.
**Not executed:** `./gradlew` (parallel emulator test) → manifest merge report
not accessible; the provider removal is therefore named as a **residual risk**, not claimed as done.

---

## Result per claimed fix

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| SB1-01 ✅ (filter) | — | `TdtGreedy.kt:59,99` + `ParakeetAsrEngine.kt:130` | **Closed.** A new 4-argument overload takes an `isSpecial: (Int)->Boolean` predicate; the main loop uses `if (!isSpecial(token))` instead of the old ID range. The production path wires exactly §2.4: `TdtGreedy.decode(joint, encFrames, budget) { vocab.isSpecial(it) }`. `Vocabulary.isSpecial` (Vocabulary.kt:52-61) implements the `<...>` pattern (0..233, 244..273) **plus** ID 8192 and lets digits 234..243 through — identical to §2.4/§7-4. | `ParakeetAsrEngine.kt:130` `decode(…, budget) { vocab.isSpecial(it) }`; `TdtGreedy.kt:99` `if (!isSpecial(token))`. | — |
| SB1-01b (new) | **P2** | `TdtGreedy.kt:50-51` | **A silent wrong path remains.** The 3-argument overload `decode(joint, frames, tokenBudget)` still defaults to the **old, incomplete** rule `{ it in AsrContract.SPECIAL_TOKEN_IDS }` (only 0..4). A future caller without `isSpecial` gets the §2.4 violation again — without a compiler/test warning. The existing test `specialTokensNotEmitted` (TdtGreedyTest, "(vii)") even exercises exactly this legacy path and thereby legitimizes it. **Assessment:** no current bug (the only production caller uses the 4-arg overload), but a real footgun. | `TdtGreedy.kt:50-51` `fun decode(joint, frames, tokenBudget) = decode(joint, frames, tokenBudget) { it in AsrContract.SPECIAL_TOKEN_IDS }`. | Remove the 3-arg overload (force callers to supply `isSpecial`) or set the default to `Vocabulary.isSpecial`; switch test "(vii)" to the 4-arg path. |
| SB1-03 ⚠️ (session setup) | **P2** | `OnnxSessions.kt:70,76` | **Leak fixed, but double close on the validation error path.** On a `createSession(dec)` error, the `finally` correctly closes mel+enc (line 62 already populated). In the **success case** no double close (`success=true` skips the `finally` close, lines 73/76) — correct. **But:** if the validation throws (`validateMel/Encoder/Decoder`), `s.close()` runs (line 70) **and afterwards** `opened.forEach { it.close() }` (line 76, because `success` is still `false`) → mel/enc/dec get closed twice. No use-after-close (no further access), no leak — but redundant/unsafe closing. | Lines 64-70 inner try/catch with `s.close()`; lines 75-76 `finally { if (!success) opened.forEach { it.close() } }` — both hit the same three `OrtSession` objects (s wraps `opened[0..2]`). | Remove the inner `s.close()` and let only the `finally` close (or a `closed` flag), so every path closes exactly once. |
| SB1-06 ✅ (NaN guard) | — | `ParakeetAsrEngine.kt:118-124` | **Closed.** The guard now necessarily sits **after** the encoder run and **before** `TdtGreedy.decode` (line 130). No more decode on NaN/Inf input. | Lines 118-124 `for (v in encOut) { if (!v.isFinite()) throw … }` before line 130 `TdtGreedy.decode`. | — |
| SB1-07 ⚠️ (telemetry provider) | Note | `AndroidManifest.xml:21-23` | **Mechanism correct, removal unverified.** `<provider android:name="ai.onnxruntime.TelemetryInitializer" tools:node="remove" />` with the correct provider name (per TECH_FACTS §3). Whether the merger actually removes it is **not verifiable** here (no `./gradlew` → no `outputs/logs/manifest-merger-*.txt`). Residual risk: if the ORT AAR declared the provider under a different name/other attributes, `remove` silently does not apply. **Named as a residual risk, not claimed as verified.** | Lines 21-23 `<provider … tools:node="remove" />`. | After the build, check the manifest merge report (the provider must no longer appear in the merged manifest). |
| SB1-01c (new) | Note | `TdtGreedyTest.kt` (2 new tests) | **Tests prove predicate pass-through, not end-to-end.** The two new tests (`specialTokenLanguageTokenNotEmitted` 78, `specialTokenSplTokenNotEmitted` 244) pass a **hard-coded** lambda `{ id in SPECIAL_TOKEN_IDS || id==78 || id==244 }`, not `Vocabulary.isSpecial`/a real vocabulary. They prove that the 4-arg overload respects the predicate for 78/244 — **not** that `vocab.isSpecial` is correctly wired or that the real vocabulary filters 78/244. The latter is covered by `VocabularyTest` (isSpecial(78)=true, isSpecial(244)=true, digits false). Gap: no test wires `vocab.isSpecial` into `decode` (the critical line `ParakeetAsrEngine.kt:130` is evidenced only by visual inspection). | New tests use an inline lambda instead of a real vocabulary; wiring `ParakeetAsrEngine.kt:130` without a unit test. | Add a test that calls `TdtGreedy.decode(joint, frames, budget, ::vocab.isSpecialLike)` with a `Vocabulary.parse` construct (78/244 filtered, 240 kept). |
| SB1-02/04/05 | (deferred) | — | `ModelError` type, dispatcher/`withContext`, per-frame allocations deliberately deferred to the next stage. **Still open** (P2 from the prior review), not re-assessed here, but not done either. | — | Take them up in the follow-up stage. |

---

## Point-by-point answers to the review assignments

1. **SB1-01 really closed?** **Yes.** The production path wires exactly §2.4 (`vocab.isSpecial` = `<...>` pattern + 8192, digits 234..243 allowed). The only residual risk is the **remaining 3-arg overload** with the old rule (0..4) — P2, since currently no caller uses it, but it must be removed as a footgun.
2. **SB1-03:** enc/dec error → mel+enc get closed in the `finally` (✔). Success case → no double close (✔, `success=true`). **But** validation error → double close via `s.close()` + `finally` (P2). No leak, no use-after-close.
3. **SB1-06:** guard necessarily sits before the decode (✔).
4. **SB1-07:** mechanism correct (provider name matches §3), but the removal **cannot be verified in the merged manifest** without a build → residual risk.
5. **New regressions:** the double-close gap (SB1-03) is one; the tests are only predicate pass-through proofs (SB1-01c). Otherwise no functional regressions — the decode loop itself is unchanged, the lambda usage is equivalent.
6. **Mergeability below (verdict).**

---

## Verdict

**Release for merge.**

The only P1 from the prior review (SB1-01, mandatory §2.4 filter) is **genuinely closed** — the production path wires `Vocabulary.isSpecial` exactly per §2.4/§7-4, and the guard fix (SB1-06) as well as the leak removal (SB1-03 core) are correct. **No open P0/P1.** The remaining P2s (3-arg footgun, double close on the validation error path) and the deferred P2s (SB1-02/04/05) are not merge-blocking but belong in the next stage or the cleanup.

## Residual risks

1. **P2 — double close** on validation error (`OnnxSessions.kt:70+76`): unsafe if `OrtSession.close()` is not idempotent (consequence-free, since the path ends in an error anyway).
2. **P2 — footgun 3-arg overload** (`TdtGreedy.kt:50-51`): the old incomplete rule remains reachable as a silent default and is exercised by test "(vii)".
3. **Note — SB1-07 unverified:** prove the provider removal only after a build via the manifest merge report.
4. **Note — test gap:** no test wires `vocab.isSpecial` into `decode` (line `ParakeetAsrEngine.kt:130` visually inspected only).
5. **Open (deferred):** SB1-02 (`ModelError`), SB1-04 (dispatcher), SB1-05 (frame allocations) — per plan in the next stage.
