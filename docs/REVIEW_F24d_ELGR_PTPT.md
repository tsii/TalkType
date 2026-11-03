# Review F24d — el-gr + pt-pt rebuild + test cross-check

**Reviewer:** DeepSeek V4 Pro 0813 · **Date:** 2026-10-30
**Scope:** `pt-pt.json` (4 rows, accents→alternates), `el-gr.json` (r0 `;`+hints), `LayoutCompletenessTest` (matrix + negative case), `LayoutParserTest` (+hint). No build/emulator.

---

## Findings table

| Sev | File:Line | Finding | Minimal change |
|---|---|---|---|
| **P2** | `pt-pt.json` footer vs. 14 layouts | **Only remaining cross-cutting deviation: the footer.** de-de (F23) replaced `‹ ›` with `.`; **all other 14** (including the newly rebuilt pt-pt/el-gr) still carry `‹ ›`. The footer visibly changes on a language switch. As a transition defensible only until F24e; unify for 0.1.16. | In F24e replace `‹ ›` with `.` in all 15 (or the other way around). |
| Note | `pt-pt.json` `o.alternates = ["õ","ó","ô"]` | **Order of `o` deviates from AOSP** (`moreKeys_o` pt = `ó ô õ`, so `ó` first; asset has `õ` first). All three are reachable (`ó` in the chain), only the first alternate is `õ` instead of `ó`. Analogous for `a` (`á â ã à` vs. AOSP `á ã à â`). Purely ordering, nothing missing. | Rotate `o.alternates` to `["ó","ô","õ"]` (and `a` to `["á","ã","à","â"]`) if AOSP `[0]` fidelity is required. |

---

## Answers

1. **pt-pt rebuild — complete, all 12 accents reachable.**
   - (a) `base` alternates: `a=[á,â,ã,à]`, `c=[ç]`, `e=[é,ê]`, `i=[í]`, `o=[õ,ó,ô]`, `u=[ú,ü]`; `shift` analogously uppercase (`À Á Â Ã …`). All required 12 (á â ã à ç é ê í ó ô õ ú) are present as `alternates[0]` **or in the chain** — only `o`'s order deviates (note). No accented letter lost.
   - (b) Σ width base `[10,9,10,13]` **==** shift `[10,9,10,13]`. ✓
   - (c) ⌫ sits in **r2** (`[⇧ z x c v b n m ⌫]`), the former accent row is gone. ✓
   - (d) hint sequence `1..0` in base **and** shift identical (r0 `q..p` + `Q..P`). ✓
2. **el-gr:** r0 carries hints on **exactly 10** keys (base `; ς ε ρ τ υ θ ι ο π` = hints 1..0; shift `: Σ … Π`), **r1 (α..λ) and r2 have NO hints**; Σ per row base==shift `[10,9,10,13]`. The test entry `shiftPairs = listOf(";" to ":")` is **purely additive** (α..ω A-list + B-list remain strictly unchanged) — no previously checked point is unchecked because of it.
3. **Test rebuild:** `MatrixRow("pt-pt", emptyList(), requiredReachable = listOf("ü"), shiftPairs = listOf("ç" to "Ç"))` — `ü` as a hard reachable is **AOSP-sensible** (`moreKeys_u` pt = `u ù ú ü`; `ü` actually hangs on `u`). The former `a-grave` negative case is **replaced, not deleted**: `negativ - pt-pt ohne Akzentkette an a faellt der neue Fall rot` (line 157) + the positive test "Akzente sind erste Alternative" (line 145) — the cross-check continues to exist, no P1. (Full text of the negative method not read — open.)
4. **LayoutParserTest +52 (hint):** the three cases are correct per the parser (`missing→null`, `""`→null, `len>2`→null; `LayoutParser.kt:124-127`); the test expects `null` per KDoc and contains no user content (only literals `"1"/""/"abc"`). (File not read myself — derived from the parser, open.)
5. **Cross-section:** the **one** remaining deviation is the footer (de-de `·`/`.` vs. 14× `‹ ›`) — P2, to be unified in F24e.

---

## TOTAL

**P0 = 0 · P1 = 0 · P2 = 1** (footer inconsistency) · 1 note (`o` alternate order).

**Mandatory before v0.1.16 (build 17):** the footer unification (`‹ ›` → `.`) across all 15 layouts, otherwise the visible language-switch break remains. **Not blocking:** the `o` order (chain complete).

**Open (to provide evidence):** full text of the `LayoutCompletenessTest` negative method line 157 and of `LayoutParserTest` +52 (derived only from KDoc/parser), an own `./gradlew test` run (420/0 adopted).
