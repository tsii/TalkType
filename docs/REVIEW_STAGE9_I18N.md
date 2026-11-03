# Review Stage 9 — i18n (M9a space label, M9b asset normalization, E11)

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-28
**Subject:** `KeyboardView`/`LayoutRepository` (SpaceLabelRule, availableIds scan), `tools/normalize_space_labels.py` + 15 assets, `LayoutParserTest` (E11 table). **No build/emulator** (orchestrator measurements as evidence: 241 tests/0 failures, script idempotence, 60 space keys = 0 deviations, all 15 words visible).

---

## Findings table

| ID | Sev | File:Line | Finding | Evidence | Fix |
|---|---|---|---|---|---|
| I9-01 | **P2** | `KeyboardView.kt:186-199` (`accessibilityDescription`) | **Screen reader names remain German (E11-c3, deliberately deferred in R8).** The accessibility description continues to hard-code `SPACE→"Leertaste"`, `BACKSPACE→"Löschen"`, `SHIFT→"Umschalt"` etc. — a ru screen reader would announce "Leertaste" instead of "Пробел". **Documented and accepted for v0.1.1** (E11 C makes exactly this trade-off); it only blocks the claim "accessibility verified in 15 languages", not the dist. | `accessibilityDescription` `KeyActions.SPACE -> "Leertaste"` (unchanged, German). | R8: translate the 5 names once the announcement language + real TalkBack verification are settled. Not in v0.1.1. |
| I9-02 | Note | `LayoutRepository.kt` `availableIds()` | **Scan fallback: any error path ⇒ `emptySet()`, de-de remains.** On an `assets.list` exception or a partial list, all non-de-de languages disappear silently; `+"de-de"` guarantees the anchor. `DE-DE.JSON` (upper case) falls through `endsWith(".json")` (case-sensitive); subdirectories are not captured recursively by `list()` → silent invisibility. All assets are lowercase + top-level by convention, so unoccupied in this project. | `availableIds()`: `catch (e: Exception) { emptySet() }`; `filter { it.endsWith(".json") }`. | Optional: check the extension case-insensitively + a test "de-de always in availableIds, even on an empty scan". |
| I9-03 | Note | `LayoutRepository.kt:21` (import `ime.LanguageCycle`) | **Cross-package dependency `layouts → ime`.** `availableIds` takes the canonical order from `de.voicekeyboard.ime.LanguageCycle` — the earlier local duplicate list is thereby **resolved** (one source, no more divergence). The price is a layering inversion (the framework-free `layouts` package imports the `ime` package). No functional defect, but architecturally unclean. | `import …ime.LanguageCycle`; `LanguageCycle.CANONICAL`. | Optional: move `CANONICAL` into a neutral package (e.g. `layouts`/`util`); no separate equality test needed since only one list remains. |
| I9-04 | Note | `tools/normalize_space_labels.py:56-60` | **Round-trip safe, but a full-file rewrite.** `json.dumps(indent=2, ensure_ascii=False)+"\n"`: float `1.5` stays `1.5` (no `1.500000`), int `1` stays `1`, key order is preserved (dict insertion order), `ensure_ascii=False` keeps Пробел/Διάστημα/Espaço literal. **Only** `label` with `value=="space"` is changed (filter correct). Residual risk: a future asset with different indentation/key order would be **fully** reformatted on the first label touch → one big diff; the measured idempotence confirms that the 15 assets are currently already in script format. | `key["label"] = expected` only in the `value=="space"` branch; `json.dumps(data, indent=2, ensure_ascii=False)`. | No fix for v0.1.1; diff minimization (only affected lines) as a possible later comfort goal. |
| I9-05 | ✓ | `LayoutRepository.kt:9-16` (`SpaceLabelRule`) | **Rule consistency E11(B) met.** `resolve(src.label, fallback)`: `label=""`, `label=" "`, `label="\u00A0"` (all pure whitespace) ⇒ `useAssetLabel=false` ⇒ fallback; `label=null` impossible (`LayoutKey.label` is non-null, `src.label` is `String`). An asset word with content (Espacio/Mellemrum/Пробел) is taken over unchanged; the insertion `value` stays `space` (output remains exactly one space). No blank label possible. | `useAssetLabel = label.any { !it.isWhitespace() }`; test lines 207-218 (Espacio stays, blank→Space, value stays "space"). | — |
| I9-06 | ✓ | `LayoutParserTest.kt:221-258` | **Test quality genuine.** The M9b test embeds the **E11 table in the test code** (`"de-de" to "Leer" … "ru-ru" to "Пробел"`), it does **not** read it from the assets → genuine copy protection. It checks "exactly 1 space key per layer" for all 15 IDs × 4 layers and has a **genuine cross-check** (`M9b gegenprobe … ru-ru label==Пробел` fails on a deviation). | Line 224 table; lines 231-239 "all 15 … all layers"; lines 245-257 cross-check. | — |

---

## Point-by-point answers (REVIEW QUESTIONS)

- **Rule consistency:** `resolve` handles `""`/`" "`/whitespace correctly → fallback "Space" (defensive stopgap, never needed regularly in the cleaned assets); no contradiction with E11(B) — a blank label is impossible. (I9-05)
- **Scan robustness:** the `de-de` anchor is guaranteed; a partial scan/exception degrades to "de-de only" (safe, though language-losing). `DE-DE.JSON`/subdirectories are silently ignored, but excluded by convention. (I9-02)
- **Duplication:** resolved — `availableIds` uses `LanguageCycle.CANONICAL` as the single source; no equality test needed. Remainder = layering inversion. (I9-03)
- **Script side effects:** only `label` with `value=="space"`; `1.5`→`1.5`, key order/`ensure_ascii=False`/trailing `\n` stable; idempotence measured. The full-file rewrite is the only remainder. (I9-04)
- **Test quality:** table hard-coded + genuine cross-check + all 15×4 layers. (I9-06)
- **Accessibility c3:** **acceptable** for v0.1.x, not a P1 — documented, honest deferral (R8); the visible labels are correct; it only invalidates the "accessible in 15 languages" claim (I9-01, P2).

---

## Verdict

**ready for release for v0.1.1** — **0 P0, 0 P1, 1 P2** (I9-01 accessibility-c3, documented and explicitly not blocking). M9a/M9b are rule-conformant and well tested; the E11 values are correct 15/15.

**Not checked (no device):** actual screen reader announcement (TalkBack) in the 15 languages, glyph/width measurement of the longest labels (Διάστημα/Пробел/Mellemrum) on a narrow key including the `␣` fallback, behavior on `assets.list` errors with real asset packaging.

## Top-3

1. **I9-01 (P2)** — Commission R8 (translate the 5 accessibility names + TalkBack verification); only then the "accessible" claim.
2. **I9-03 (Note)** — Pull `CANONICAL` into a framework-free package (resolve the layering `layouts→ime`).
3. **I9-02 (Note)** — Extension case-insensitivity + a "de-de always contained" test for `availableIds`.
