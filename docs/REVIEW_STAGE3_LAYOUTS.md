# Review Stage 3 — Layouts (8 asset fixes) + 3 code fixes

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-27
**Subject:** 8 layout assets (de-de, en-us, it-it, nl-nl, pt-pt, pl-pl, ru-ru, el-gr) per
`docs/LAYOUT_AUDIT.md` + `docs/LAYOUT_REQUIREMENTS.md` (Rev 3); plus manifest (telemetry provider),
`OnnxSessions` (double close), `TdtGreedy` (+ test). **Not executed:** `./gradlew` (parallel
build) — manifest merge truth instead read from `app/build/intermediates/…/AndroidManifest.xml`
(allowed).

**Method:** read-only Python audit over the 8 assets (schema/rows/pairs, §2.1 groups, §2.2
A/B/punctuation, NFC, duplicates, `label==value`, alternates) + file reading of the 3 code fixes.

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| A-1 | Note | `de-de/pl-pl/ru-ru` `symbols`+`symbolsShift` | **`_` duplicated across both symbol layers** (and hence a group-1 character on `symbolsShift`). `symbols` has `underscore`→`_` (mandatory, group 1), `symbolsShift` has `hyphen`→`_` (the shift of `-`). Violates SCHEMA_REF #7 ("`symbolsShift` carries no group-1 characters") and §2.1.1 "exactly one occurrence". **Pre-existing**, not introduced by these fixes, not in the audit fix scope. | `de-de` `symbols`: `{"id":"underscore","value":"_"}`; `symbolsShift`: `{"id":"hyphen","value":"_"}` (analogous pl-pl, ru-ru; en-us clean by contrast). | Put the `hyphen` shift in `symbolsShift` on a different character (e.g. `–`/`¯`) or explicitly document the `_` double assignment in §2.1.1/SCHEMA_REF as a "hyphen-shift exception". |
| A-2 | Note | `pt-pt` `layers.base[0..4]` | **`base`/`shift` have 5 rows** (12/12/8/9/13) instead of the usual 4; `symbols`/`symbolsShift` remain 4-row (11/11/11/13). The pairing rule is met (`shift↔base` 5==5, `symbolsShift↔symbols` 4==4, width sums per row index equal), the parser `validateSemantics` now checks **pairwise** correctly → no parse error. But the keyboard height jumps on the layer switch base↔symbols (5→4 rows); ergonomics, not a rule violation. | `LayoutParser.kt:150-171` (pairwise `shift↔base`, `symbolsShift↔symbols`); `pt-pt.json` base 5 rows, `#text base/shift = 38`. | Leave it consciously or consolidate `pt-pt` to 4 rows (only if §2.2-A can still be met with own keys). |
| A-3 | **P2** | `LayoutCompletenessTest.kt:51,52,53` | **Test matrix is outdated versus §2.2 (Rev 3)** → 3 of the 8 fixes are **not** regression-protected: `it-it` is missing `ü` (=§2.2-B), `nl-nl` is missing `ä`, `pt-pt` lists `à` in `requiredReachable` (B) instead of in the A list. On a revert (leave out ü/ä again, à again only an alternate) the test stays **green** — the fixes are thus not "caught". | Line 51 `it-it … requiredReachable=listOf("á","í","ó","ú","î")` (without `ü`); line 53 `nl-nl … =listOf("ë","ï","é","è","ê","ö","ü","á","à")` (without `ä`); line 52 `pt-pt A=listOf("ç","ã","õ","á","é","í","ó","ú","â","ê","ô")` (without `à`), `requiredReachable=listOf("à","ü")`. | Synchronize the matrix with §2.2 Rev 3: `it-it`+`ü`, `nl-nl`+`ä`, `pt-pt` `à` into the A list (and only `ü` in B). |
| A-4 | **P2** | — (missing tests) | **The 6 §2.2 punctuation fixes, the NFC fix and rules 5a–5e are protected by *no* test.** `PunctuationCoverageTest` (§6.3), `NfcLabelTest` (§6.4) and `LayoutArtifactTest` (§6.8) do **not exist** (`app/src/test/java/de/voicekeyboard/layouts/` contains only `CaseMapperTest`, `LayoutCompletenessTest`, `LayoutParserTest`). `LayoutCompletenessTest.checkMandatorySymbols` checks only §2.1 (group 1/2 + digits), no §2.2 punctuation matrix, no NFC, no rules 5a–5e. A revert of `–` (de-de/ru-ru), `–/—` (en-us), `”` (pl-pl), `·` (el-gr) or the U+0387/U+037E alternates would go unnoticed. | `ls`/grep: no `PunctuationCoverageTest`/`NfcLabelTest`/`LayoutArtifactTest`; `LayoutCompletenessTest.kt:181-203` only `checkMandatorySymbols` (§2.1). Exactly the gap that per `LAYOUT_AUDIT.md §f` let the 6 punctuation findings slip through. | Deliver `PunctuationCoverageTest` (with §2.2 punctuation matrix), `NfcLabelTest` (labels **and** alternates), `LayoutArtifactTest` (5a–5e + `Z`) per §6.3/6.4/6.8. |
| B-1 | ✓ | `AndroidManifest.xml:34-37` + merged | **Telemetry provider now correctly a child of `<application>`** and actually removed: `grep -c TelemetryInitializer` and `grep -c "android.permission.INTERNET"` over `app/build/intermediates/merged_manifest/{debug,release}/process*MainManifest/AndroidManifest.xml` plus `merged_manifests/*` → **all 0**. SB1-07 now verified (no longer a residual risk). | Merged manifest (4 files): `TelemetryInitializer` = 0, `INTERNET` = 0. | — |
| B-2 | ✓ | `OnnxSessions.kt:56-73` | **Double close completely gone.** The inner `try { validate… } catch { s.close(); throw }` is removed. Now: `createSession×3` + `validate×3` + `success=true; return s`; `finally { if (!success) opened.forEach { it.close() } }`. Success → no close (single owner); `createSession(enc/dec)` error → partial sessions exactly 1×; validation error → all 3 exactly 1×. No leak, no use-after-close. | Lines 60-72 (no more inner catch, only one `finally`). | — |
| B-3 | ✓ | `TdtGreedy.kt:50` + `ParakeetAsrEngine.kt:130` + `TdtGreedyTest.kt` | **3-arg overload removed.** Only `decode(joint, frames, tokenBudget, isSpecial)` remains; the `frames<0` clamp now passes `isSpecial` through (line 51 `decode(joint, 0, tokenBudget, isSpecial)`). **All 17 callers** (16 tests + `ParakeetAsrEngine` line 130 `{ vocab.isSpecial(it) }`) use the 4-arg form — no more silent 0..4 wrong path. The test now uses the **real** rule: `miniVocab = Vocabulary.parse("<|de|> 78 <|spltoken0|> 244 …")` + `IS_SPECIAL = { miniVocab.isSpecial(it) }` (instead of an ad-hoc predicate). | grep `TdtGreedy.decode` → 4-arg only; `TdtGreedyTest.kt:44-50` (miniVocab + IS_SPECIAL). | — |
| B-4 | Note | `AndroidManifest.xml:20-31,52-55` | The manifest additionally contains (outside the 3-fix scope): `RECORD_AUDIO`/`FOREGROUND_SERVICE(_MICROPHONE)`/`POST_NOTIFICATIONS` + `CaptureForegroundService` (`foregroundServiceType="microphone"`). Correct in content per TECH_FACTS §4.1; thematically belongs to stage 4, not to this delivery — noted here only for completeness. | Lines 20-31 permissions, lines 52-55 service. | — |

---

## Point-by-point answers to the review assignments

1. **Rules 5a–5f / §2.1 / §2.1.3 / §2.2 / rules 3,7,8:** all **new keys/alternates rule-conformant**. `label==value` holds for every text key (0 deviations), no non-NFC label/value/alternate (el-gr U+0387/U+037E are removed), no alternate violations (≤8, 1 codepoint, no duplicates, not the key's own character). §2.1 group 1 + digits exactly 1× each on `symbols`, group 2 fully present (MISS empty). §2.2 A/B/punctuation complete (the "missing" `»«/”/“` are only `{…}` or-variants, not required). **Displaced characters** (`µ`, `‹ ›`, `¿`, `¡`, `¥`) are all §2.1.2-optional or (`‹ ›`) previously a rule-5d violation — **no mandatory character sacrificed**.
2. **Pairing rule:** met — `shift↔base` and `symbolsShift↔symbols` each pairwise equal row count + equal width sum per index; `base` text IDs == `shift` text IDs (all 8 assets). pt-pt passed despite 5-row letter layers (note A-2).
3. **NFC:** all labels/values/alternates of the 8 assets NFC (el-gr `·` = U+00B7, U+0387/U+037E out). ✔
4. **Duplicates (rule 8):** no new duplicates; only `Σ/Σ` in el-gr `shift` (documented exception D1). No `–` duplicate in de-de, no `”` duplicate in pl-pl. (Note A-1: pre-existing `_` cross-layer.)
5. **Code fixes:** B-1 provider correctly placed + merged manifest **verified** (0× provider, 0× INTERNET). B-2 double close gone, every session exactly 1×. B-3 overload removal complete (no caller without `isSpecial`).
6. **Test quality:** B test (TdtGreedyTest) now genuine (real `Vocabulary.isSpecial` instead of an ad-hoc predicate). **A tests however are gappy** (A-3/A-4): `LayoutCompletenessTest` does **not** catch the 8 asset fixes — on a revert it stays green.

---

## Verdict

- **Part A (8 assets): release for merge** — the assets themselves are rule-conformant (0 P0, 0 P1). The P2s (test gaps A-3/A-4) concern regression protection, not the correctness of the delivered assets.
- **Part B (3 code fixes): release for merge** — B-1/B-2/B-3 complete and (B-1) even empirically verified; 0 P0, 0 P1.

**Overall: release for merge.** No open P0/P1.

## Top-5 actions

1. **A-3 (P2)** — Synchronize the `LayoutCompletenessTest` matrix with §2.2 Rev 3 (`it-it`+`ü`, `nl-nl`+`ä`, `pt-pt` `à` into the A list) — otherwise 3 fixes remain unprotected.
2. **A-4 (P2)** — Deliver `PunctuationCoverageTest` (with §2.2 punctuation matrix), `NfcLabelTest` (labels+alternates) and `LayoutArtifactTest` (rules 5a–5e + `Z`) per §6.3/6.4/6.8 — covers the 6 punctuation fixes, the NFC fix and 5a–5e.
3. **A-1 (Note)** — Clarify the `_` double assignment (`underscore`+`hyphen` shift) in de-de/pl-pl/ru-ru (swap the character or document it as a hyphen-shift exception).
4. **A-2 (Note)** — Consciously confirm or consolidate the pt-pt 5-row letter layers (height jump on layer switch).
5. On the next run, have the 2 instrumented golden tests also check `ASR_CONTRACT §4.1` token IDs (not just text) — equivalent for the decode path to the unit tests.
