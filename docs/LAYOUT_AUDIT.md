# Layout audit (rev 3) — assets, schema reference, rule gaps

**Status:** 2026-10-27 · **Audit subject:** `app/src/main/assets/layouts/*.json`
(15 files), `docs/LAYOUT_SCHEMA_REF.md`, binding source
`docs/LAYOUT_REQUIREMENTS.md` (rev 3).

**Method:** read-only Python audit (`/tmp/audit2.py`, deliberately **not** in
the repo) against the JSON parser contract (`LayoutParser.kt`,
`LayoutAsset.kt`) and rules 1–9 / §2.1 / §2.2 from LAYOUT_REQUIREMENTS. No
Gradle, no emulator interaction, no changes to Kotlin or assets. Raw findings:
`/tmp/audit2.json`.

**Result in one sentence:** the **mandatory symbols (§2.1) are complete in all
15 languages** (the `&` rollback is done), schema/rows/`label==value` are
clean — still open are **one language matrix gap (§2.2 "punctuation") in 5
languages (6 findings)**, **3 missing `B` characters**, **1 missing `A`
character**, and **2 real rule conflicts in the specification** (NFC vs.
U+0387/U+037E, rule 8 vs. σ/ς→Σ).

## (a) Audit matrix language × check

Legend: ✓ = passed · ✗ = fix needed · ! = hint (rule/clarification, not an
asset error)

| Language | Schema/rows | IDs/labels | label=value | NFC | §2.1 G1 | §2.1 G2 | §2.2 A | §2.2 B | §2.2 P | shift | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `de-de` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix needed** |
| `en-us` | ✓ | ✓ | ! | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix needed** |
| `fr-fr` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `es-es` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `it-it` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | **Fix needed** |
| `pt-pt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ | **Fix needed** |
| `nl-nl` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | **Fix needed** |
| `pl-pl` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix needed** |
| `tr-tr` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `ru-ru` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix needed** |
| `cs-cz` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `sv-se` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `da-dk` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `fi-fi` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `el-gr` | ✓ | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix needed** |

Individual criteria checked (per asset, all 4 layers): top-level fields,
`schemaVersion=1`, file name==`id`, `language` mandatory fields +
`direction=ltr` + tag from §2.2, row count **pairwise** (`shift↔base`,
`symbolsShift↔symbols`) and width sums per row index, no duplicate IDs, no
duplicate labels, `kind`/action in the allowlist, `width>0`, rules 5a–5d
(incl. `=\<`, `⌇`), rule 6 (RTL), rule 7 (NFC for labels **and** alternates),
rule 3 (max 8, one codepoint, no action names, no duplicates), rule 4
(space/backspace/enter/emoji/mic per layer, shift or layer switch), §2.1
group 1 + digits (each exactly 1× on `symbols`), group 2 (1× on `symbols` or
`symbolsShift`), §2.2 A/B/P, rule 2 (shift uppercase forms).

## (b) Findings

### B1 — Assets needing fixes

| Asset | Rule | Finding | Evidence (field) | Recommended fix |
|---|---|---|---|---|
| `de-de` | §2.2-P | `–` (U+2013) missing as its own key | `layers.symbolsShift` contains `„ … “ — ¿ ¡` — no `–` | `hyphen`-shift or a free key in `symbolsShift` → `–` |
| `en-us` | §2.2-P | `–` and `—` only as alternates, not as own keys | `layers.base['m'].alternates = "—…–"`; `symbolsShift` has `…`, but no `–`/`—` | `–`, `—` as own keys in `symbolsShift` (space is available: 30/31 text slots) |
| `it-it` | §2.2-B | `ü` is not reachable anywhere | `layers.base`: `ú í î ó á` present, `ü` missing; no `alternates` with `ü` | add `ü` as an alternate on `u` (mind the max-8 limit: `u` has only `ú`) |
| `nl-nl` | §2.2-B | `ä` is not reachable anywhere | `layers.base['a'].alternates = "áà"`; `e→ëéèê`, `u→ü`, `i→ï`, `o→ö` | add `ä` as an alternate on `a` (`áàä`) |
| `pt-pt` | §2.2-A | `à` only an alternate; §2.2 requires an own key (level A) | `layers.base['a'].alternates = "à"` | `à` as its own `base` key (rows 1/2 have space) **or** downgrade the §2.2 row to `B` (professionally defensible, see D2) |
| `pl-pl` | §2.2-P | `”` missing | `layers.symbolsShift` contains `„ … “ –` — no `”` | add `”` in `symbolsShift` |
| `ru-ru` | §2.2-P | `–` missing | `layers.symbolsShift` contains `« » … №` — no `–` | add `–` in `symbolsShift` |
| `el-gr` | §2.2-P | `·` (Ano Teleia) not an own key | `layers.symbols['semicolon'].alternates` contains U+0387, no own key | own key with **U+00B7** (NFC-stable) in `symbolsShift` |
| `el-gr` | R7 | alternate `·` (U+0387) is **not NFC** (NFC → U+00B7) | `layers.symbols['semicolon'].alternates[0]` | switch the alternate to U+00B7 (or remove it) |
| `el-gr` | R7 | alternate `;` (U+037E) is **not NFC** (NFC → `;`) | `layers.symbols['question'].alternates[0]` | remove the alternate — `;` is already in group 1 |
| `el-gr` | R8 | two `shift` keys with the label `Σ` (`σ`, `ς`) | `layers.shift`: `final-sigma→Σ`, `σ→Σ` | **no asset fix** — rule 8 exception in the requirements (D1) |

### B2 — Hints (not asset errors, but rule/documentation clarifications)

| Asset | Rule | Finding | Evidence | Recommendation |
|---|---|---|---|---|
| `en-us` | R8 | text label `‹`/`›` collides with the legend of the cursor actions | `layers.symbolsShift` (text `‹›`) vs. `cursor-left/right` | limit rule 8 explicitly to **text keys**; optionally replace the text characters |
| `fr-fr` | §2.2-A/B | `ê` is an **own** base key; §2.2 requires only `B` ("reachable") — over-fulfillment, but it collides with the note "not directly mapped on AZERTY" | `layers.base` = `…opéêq…`, additionally `e.alternates` contains `ê` | documentation: soften the §2.2 note ("may be an own key") + clean up the double path `e`/`ê` |
| `fr-fr` | R3 | `à` is an own key **and** an alternate of `a` | `layers.base['a'].alternates = "æâà"` | remove the double path (rule 3 means uniqueness of the typing path) |
| `de-de`, `en-us` | R3-alt | shift alternates mirror base alternates only for **letters**; caseless characters (`€ § – — … µ`) are missing there — professionally correct | `de-de shift['e'].alternates = ÉÈÊËĘ` (without `€`), `en-us shift['s']` without `§` | clarify rule 3: mirroring only for characters **with** an uppercase form (D3) |

## (c) Correction list `docs/LAYOUT_SCHEMA_REF.md`

All items are **implemented** (file corrected). Short version of the errors:

| # | Spot | Was wrong/imprecise | Correct |
|---|---|---|---|
| 1 | row lengths | "`base`/`shift` equally long" — sounded like key count | what is checked is **equal row count + equal width sum per row index**, pairwise `shift↔base` and `symbolsShift↔symbols` (`LayoutParser.validateSemantics`) |
| 2 | top level | "no further fields" | the parser enforces that only in `language`, `layers.<layer>`, key object, `deadKeys.<id>`; at top level there are 5 fields **by convention** |
| 3 | `id` | format was missing | `^[a-z0-9-]+$`, the file name **must** be `<id>.json` (the repository loads via the ID) |
| 4 | `language` | fields named, rules not | exactly `tag`/`label`/`caseLocale`/`direction`; `direction ∈ {ltr,rtl}`; `tag` BCP-47 (`^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$`); `tag` must match the §2.2 row |
| 5 | `deadKeys` | only `{}` shown, structure unknown | sub-schema documented: `{"<id>": {"spacing": str, "combining": str?, "compose": {"<basis>": "<character>"}}}` + mandatory: every `kind:"dead"` key references an existing entry (the parser checks this) |
| 6 | `caps` | "optional alternative label" | the field exists (`String`), **no asset uses it**; the action `caps` is in the allowlist, likewise unused (double-tap semantics not implemented at the moment) |
| 7 | digits | "shift counterparts (`! @ # $ % ^ & * ( )`)" | **wrong and dangerous**: these characters are §2.1 group 1 mandatory on `symbols`; repeating them on `symbolsShift` violates rule 8. Correct: `symbolsShift` carries `¹²³¼½¬°…`, no group 1 characters |
| 8 | duplicates | "a character only once as a `value`" | exception documented: `σ`/`ς` share the label `Σ` in `shift` (rule 8 exception D1); scope = text keys (action legends like `‹ ›` are separate) |
| 9 | function keys | "in **every** layer … cursor-left, cursor-right" | the requirement (§1.4) is weaker: space, backspace, shift/layer-base, enter, emoji, mic; `cursor-*`/`language` are **asset convention**, not mandatory |
| 10 | space control | "`symbols` = 11/11/8" | reality: 4 rows (text + action row), text rows mostly `11/11/10`; `el-gr`/`da-dk`/`fi-fi` 31, `de-de`/`en-us` 30 text slots. State it as an example, not as a rule |
| 11 | language table `fr-fr` | `ê` as a mandatory key | §2.2: `A = é è à ç ù`, `ê` only `B`; the asset has `ê` additionally (over-fulfillment) — table split accordingly |
| 12 | language table `tr-tr` | `i` was missing | `i` **is** an own `base` key (beside `ı`), shift `İ`/`I` |
| 13 | language table `pt-pt` | `à` was missing from the A list | `à` belongs to `A` (open asset fix B1) |
| 14 | punctuation §2.2 | **completely missing** | new table "language-specific punctuation" — the cause of the 6 P findings |
| 15 | rules 5c/5d/6/7/8 | not mentioned | reference + short summary: NFC (labels **and** alternates), no control/combining characters, `label ∈ Z` (mandatory ∪ supplementary ∪ language characters), RTL prohibition, duplicate prohibition |
| 16 | alternates | only "max 8, characters only" | added: exactly 1 codepoint, NFC, no duplicates, not the own character, `shift` alternates = uppercase forms of the `base` alternates **only for characters with an uppercase form** |
| 17 | rule 5e | was missing | action keys carry **no** character from the character universe, only legends (`⇧ ⌫ ⏎ 🌐 😊 ?123 ABC Leer ‹ ›`) or a word (`Leer`, `Διάστημα`) |
| 18 | artifacts | examples `=\<`/`⌇` without a rule reference | reference to rule 5a/5b (label≠value, 2 codepoints) resp. 5d (not in `Z`) |

## (d) Specification corrections (D1–D3), implemented in LAYOUT_REQUIREMENTS.md

- **D1 — rule 8 needs an exception.** Greek has two keys `σ` and `ς`; both
  uppercase forms are `Σ` (Unicode single-codepoint uppercase, §3 defines
  `ς→Σ`). So `shift` necessarily has two keys with the label `Σ` — rule 8
  ("no duplicate labels") and rule 2 ("shift = uppercase form of base") are
  otherwise incompatible. **New wording:** the duplicate prohibition applies
  to text keys; the exception is `shift` keys whose `base` character has the
  same single-codepoint uppercase form (only `σ`/`ς` → `Σ`; asset names `σ`,
  `final-sigma`).
- **D2 — §2.2 `pt-pt`: `à` stays `A`.** In Portuguese (crase) `à` is its own
  grapheme, not just an accent variant of `a`; it belongs on its own key. The
  asset fix is cheap (rows 1/2 have space), hence no downgrade.
- **D3 — rule 3 (alternate mirroring) only for characters with an uppercase
  form.** `€ § – — … µ` are caseless; mirroring them in `shift` would be
  wrong. The mirroring rule applies to letters; caseless characters stay in
  `base` (or may appear identically in `shift` if the implementation needs
  it).

Additionally clarified in §2.2 `el-gr`: the Ano Teleia is tracked as **U+00B7**
(NFC-stable) — U+0387 is canonically equivalent and normalized by NFC to
U+00B7; the "Greek question mark" U+037E is canonically identical with `;`
(U+003B) and thus already covered by group 1 — a separate codepoint cannot be
represented under rule 7 (NFC).

## (e) Verdict per language

| Language | Verdict | Open points |
|---|---|---|
| `de-de` | **Fix needed** | `–` as an own key (§2.2-P) |
| `en-us` | **Fix needed** | `–`, `—` as own keys; hint `‹ ›` label collision |
| `fr-fr` | OK | hint: `ê` over-fulfillment, double path `à`/`a` |
| `es-es` | OK | — |
| `it-it` | **Fix needed** | make `ü` reachable (§2.2-B) |
| `pt-pt` | **Fix needed** | `à` as an own key (§2.2-A) |
| `nl-nl` | **Fix needed** | make `ä` reachable (§2.2-B) |
| `pl-pl` | **Fix needed** | add `”` (§2.2-P) |
| `tr-tr` | OK | — |
| `ru-ru` | **Fix needed** | add `–` (§2.2-P) |
| `cs-cz` | OK | — |
| `sv-se` | OK | — |
| `da-dk` | OK | — |
| `fi-fi` | OK | — |
| `el-gr` | **Fix needed** | `·` (U+00B7) as an own key; remove two non-NFC alternates; the R8 exception is documentation (D1) |

**Total: 8 of 15 assets needing fixes**, 7 OK. Hints (no fix) exist for
`fr-fr` (over-fulfillment `ê`, double path `à`/`a`) and, within the fix
assets, for `de-de`/`en-us` (rule 3 clarification, audit D3).

## (f) Test coverage — why the tests are green

`LayoutCompletenessTest` checks §2.2 **only for `base`** (the A and B sets)
and §2.1 for the symbol layers. **Not** checked, and therefore slipped
through:

1. the §2.2 column "punctuation" (the 6 P findings) — the matrix has no
   punctuation column.
2. NFC of labels/alternates (rule 7) — `el-gr` U+0387/U+037E.
3. rule 8 outside `symbols`/`symbolsShift` (duplicate text `value`s are
   checked only there) — `el-gr` `Σ` in `shift` goes unnoticed.
4. rule 3 in detail (one codepoint, duplicates, own character, mirroring).
5. rule 5d (character universe `Z`) — only the two example artifacts.

**Recommendation (coders, step 3):** extend `PunctuationCoverageTest` with
the §2.2-P matrix, add `NfcLabelTest` (labels **and** alternates),
`LayoutArtifactTest` (rules 5a–5e + `Z`), and a duplicate check across
**all** layers. That secures the 8 findings permanently instead of repairing
them once.
