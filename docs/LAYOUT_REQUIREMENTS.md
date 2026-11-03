# Layout requirements (binding, machine-checkable)

Basis: DESIGN.md §7. 15 languages (initial scope), each with a complete layout
in four layers (`base`, `shift`, `symbols`, `symbolsShift`). This file makes
the requirement **machine-checkable**: the tests `LayoutCompletenessTest`,
`CaseMapperTest`, `PunctuationCoverageTest` run without Android (JSON parser),
`LayoutParserTest` checks the schema.

> **Review version 2026-10-27.** Corrected vs. rev 1: Finnish `å` was missing,
> Polish uppercase letters were wrongly listed as alternates, Greek uppercase
> tonos was completely missing, punctuation was not checkably specified (the
> column was missing), the ß→ẞ rule mixed key labels and text transformation,
> and the language list was not reconciled against the ASR language support
> (§5).
>
> **Revision 3 (2026-10-27).** Rule 5 was a **misdecision** and has been
> reworded: it named `\`, `<`, `>` and `&` as "abuse" and led to `&` being
> **removed** from `de-de.json` and `en-us.json` and replaced by `§`/`$`.
> `&` is an everyday character (URL query, "Rock & Roll", English). Rev 3 no
> longer **prohibits any characters**; instead it checks `label == value`,
> exactly one codepoint, and membership in a declared character universe —
> with that `=\<` (artifact) stays prohibited and `&` (a real character)
> stays mandatory. New are the **mandatory character list** (§2.1,
> machine-readable in §2.1.1) and the acceptance test for it (§6 item 8); the
> findings on the current state are in §2.1.3.

## 0. Two requirement levels (instead of a blanket "own key vs. alternate")

- **A (own key):** the character has its own key in the respective layer.
  For characters that occur daily in the language.
- **B (reachable):** the character is either an own key or sits as an
  `alternates` entry on a key of the same layer (exactly **one** long-press).

With that, "reachable" is always checkable via a search over
`labels ∪ alternates` — independent of how the implementation arranges the
keys.

## 1. Basic rules (apply to all 15 layouts)

1. For every language: `A(base) ⊆ B(base)` and `shift` contains the
   **uppercase forms** of all characters from `A(base)` and `B(base)` (see
   rules 2/3).
2. `shift` has **the same key count and the same positions** as `base`. The
   labels differ exclusively through the documented case mapping. Do **not**
   use `String.uppercase()`/`lowercase()`: the default locale gets Turkish
   (`i`→`I`) and German (`ß`→`SS`) wrong, and `"İ".lowercase()` yields two
   codepoints (`i` + U+0307). Binding is the table in §3 (`CaseMapper`),
   verified in `CaseMapperTest`.
3. `alternates` contains **only single characters** (one codepoint, NFC), no
   action names, max. 8 entries per key, no duplicates, and never the key's own
   character. `alternates` in `shift` = uppercase forms of the `alternates` in
   `base` (same position), unless §3 says otherwise.
   **Rev 3 clarification (audit D3):** the mirroring applies only to
   characters **with** an uppercase form (letters). Caseless characters
   (`€ § – — … µ`) are not mirrored — repeating them in `shift` would be
   wrong, and their absence is not an error. A character must not
   simultaneously be an own key and an `alternates` entry of another key in
   the same layer (unique typing path), unless it is deliberately documented
   as a convenience double path.
4. Every layer contains exactly one `space`, one `backspace`, one
   `shift`/`layer-base`, one `enter`, one `emoji`, and one `mic` key.
   **4a — back route of the symbol layers (rev 4, binding):** `symbols`
   **and** `symbolsShift` each contain **exactly one** key with
   `kind:"action"`, `id:"shift"`, `value:"shift"`, `label:"⇧"`, `width:1.5` —
   position **row 2, column 0** (that is where the ⇧ key sits in all 15
   assets). In `symbolsShift` it is the back route to `symbols`;
   `KeyboardView.handleAction` already treats `SHIFT` in both symbol layers
   as a layer toggle (no code change needed). Additionally, in both symbol
   layers the key `id:"base"`, `label:"ABC"`, `value:"layer-base"` (row 3,
   column 0) remains as a direct route into the letter layer.
   **Forbidden:** `value:"layer-base"` with a label ≠ `ABC` (promises a
   symbol board but leads into the letter layer) and any ⇧ key in a symbol
   layer with `value != "shift"` (dead end).
   **Labeling:** in both symbol layers `⇧` — **no** `⇩`, **no** `⇪` (symbol
   layers are never caps-locked, DESIGN §7.1); the meaning "other symbol
   board" follows from the layer itself.
5. **Label = output, exactly one codepoint, characters only from the declared
   universe.** Applies to every key with `kind: "text"` in all four layers,
   checked after NFC. There is **no forbidden character list** — the check is
   a whitelist plus identity/format rules:
   - **5a** `label == value` (codepoint equality; not case-insensitive, not
     trimmed, no Unicode folding).
   - **5b** `label` and `value` consist of **exactly one** codepoint
     (`codePointCount() == 1`); no `k` label, no whitespace, no suffix.
   - **5c** `label` is no control/format character (`Cc`,`Cf`), no combining
     character (U+0300–U+036F), no space separator (`Zs`,`Zl`,`Zp`), no
     invisible character (U+200B–U+200F, U+2060, U+FEFF).
   - **5d** `label` ∈ **character universe `Z`** = mandatory characters (§2.1)
     ∪ allowed supplementary characters (§2.1.2) ∪ language characters of the
     respective row in §2.2. Everything outside `Z` is an artifact — e.g.
     `⌇` (U+2307), `=\<`, `⇧` on a character key.
   - **5e** keys with `kind: "action"` **never** carry a label from `Z`
     (otherwise one sees a typable character and gets an action) and carry a
     label from the legend set `⇧ ⌫ ⏎ 🌐 😊 ?123 ABC Leer ‹ ›` or a word.
   - **5f** `\`, `<`, `>` and `&` are **not** artifacts but regular
     characters and mandatory in §2.1. (Rev 2 had listed exactly these four
     as "abuse"; the actual artifacts from stage 1 were `=\<` — two codepoints,
     5a/5b — and `⌇` — not in `Z`, 5d.)

   **Checkable as:** `LayoutArtifactTest` (§6 item 8) over all 15 assets; a
   violation names `asset`, `layer`, `key.id`, `label`, `value`, and the
   violated sub-rule.
6. **No RTL mixing:** in assets with `direction: "ltr"` no label may lie in
an RTL range (U+0590–U+08FF, U+FB1D–U+FDFF, U+FE70–U+FEFF); codepoint length
and forbidden character classes are handled by rule 5b/5c (do not duplicate
them).
7. **Normalization:** all labels/alternates are NFC. (Otherwise an NFD "ä"
   from `a` + U+0308 would undermine the equality tests and render
   differently on the device.) Check: `Normalizer.isNormalized(s, NFC)`.
8. Within a layer: no duplicate key IDs; **no duplicate labels among text
   keys**. Two exceptions are allowed and must stay:
   - **σ/ς → Σ** (Greek): two `base` keys (`σ`, `ς`) have the same
     single-codepoint uppercase form, so `shift` necessarily carries the
     label `Σ` twice. Rule 2 takes precedence over rule 8 here (audit D1).
   - **Action legends** (the `‹ ›` of the cursor keys) may collide with text
     labels of the same layer; rule 8 applies to text keys, the legends are
     governed by rule 5e.
9. `symbols` contains the digits `0`–`9` exactly once each and the mandatory
   group 1 from §2.1 — as an **own key** (level A), not as a long-press. The
   mandatory group 2 (`\ [ ] { } < > ~ | € $ § °`) may sit on `symbols`
   **or** `symbolsShift`; language-specific characters (§2.2) likewise, but
   never hidden as `alternates`. `symbolsShift` additionally contains the
   shift counterparts of the digits (¹²³ …) and optional characters (§2.1.2).

## 2. Language matrix (mandatory characters)

### 2.1 Mandatory characters on the symbol layers (all 15 languages)

Every language must carry the following characters as an **own key** (level
A, not hidden as `alternates`) and **exactly once** in the stated layer. The
split is binding because it is machine-checked (§2.1.1, §6 item 8).

| Group | Layer | Characters (each exactly once) |
|---|---|---|
| **1 — daily use** | **`symbols`** | `. , ? ! : ; ' " - / @ ( ) & % + = # _ *` (20) |
| **2 — structural/rare** | `symbols` **or** `symbolsShift` | `\ [ ] { } < > ~ \| € $ § °` (13) |
| **Digits** | **`symbols`** | `0 1 2 3 4 5 6 7 8 9` (each exactly once, rule 9) |

Rationale of the split (professional, not taste):

- **Group 1** is what typing needs constantly: `&` in URL query strings and
  "Rock & Roll", `@` in handles/mail addresses, `#` in hashtags/Markdown,
  `_ * =` in identifiers, Markdown and calculations, `+` in phone numbers,
  `" '` as syntax and quotation characters. These characters must **not** sit
  behind a second toggle.
- **Group 2** are brackets, path/pipe characters, and currencies/`§`/`°`. A
  toggle is reasonable here. `€ $ § °` are needed in all languages, and the
  `symbols` side is already full with 30 mandatory character keys (group 1 +
  digits) — that is why they may sit on both layers.
- **Rev 3 change:** the column "punctuation (direct)" in §2.2 now means "own
  key on `symbols` **or** `symbolsShift`". Previously it meant "directly on
  `symbols`"; that could not be fulfilled simultaneously with the mandatory
  list. The **set** of mandatory characters does not shrink because of this;
  only the layer is free.

#### 2.1.1 Machine-readable mandatory list (source for the test)

The tests do **not** duplicate these characters freely in test code but use
exactly these constants (order irrelevant, comparison as sets):

```
MANDATORY_ON_SYMBOLS        = ".,?!:;'\"-/@()&%+=#_*"      # 20, group 1
MANDATORY_ON_SYMBOLS_OR_SHIFT = "\\[]{}<>~|€$§°"         # 13, group 2
MANDATORY_DIGITS            = "0123456789"                # 10, rule 9
```

Check: for every asset and every language in §2.2,
`labels(symbols) ⊇ MANDATORY_ON_SYMBOLS ∪ MANDATORY_DIGITS` and
`labels(symbols) ∪ labels(symbolsShift) ⊇ MANDATORY_ON_SYMBOLS_OR_SHIFT`,
each with **exactly one** occurrence per character in the permitted layer
(rule 8 forbids duplicates within a layer).

#### 2.1.2 Allowed supplementary characters (not mandatory)

Additionally these characters are permitted (own key or `alternates`), as
long as rules 5a–5e and rule 3 (max. 8 alternates) hold — they complete the
character universe `Z`:

```
£ ^ ¹ ² ³ ¼ ½ ¬ ¢ µ ¥ ¶ · † ‡ ‰ ± × ÷ √ π ∆ • © ®
„ “ ” ‘ ’ ‚ « » – — … ¿ ¡ № º ª
`      (U+0060, backtick — permitted but not mandatory; quote it in Markdown!)
```

Everything else (e.g. `⌇` U+2307, `=\<`, `⇧` on a character key) is an
artifact and prohibited by rule 5d.

#### 2.1.3 Current state (status 2026-10-27, checked)

The earlier table here (`de-de`/`en-us` without `& # = " * _`) is
**obsolete**: the §2.1 mandatory list (group 1 + digits each exactly 1× on
`symbols`, group 2 in `symbols ∪ symbolsShift`) is now **fully satisfied in
all 15 assets**. The `&` rollback is done; `amp` delivers `&` again.

Full check including findings, evidence, and fix list:
**`docs/LAYOUT_AUDIT.md`** (8 assets with remaining needs, 7 OK). Still open
there are only §2.2 violations (punctuation column, individual A/B
characters) — **not** §2.1.

### 2.2 Languages

Columns: **A** = own key in `base`, **B** = own key *or* an alternate in
`base`; the shift layer must carry the respective uppercase form analogously
(§3). "Punctuation" = in addition to §2.1 **as an own key** on `symbols`
**or** `symbolsShift` (rev 3; previously "only `symbols`" was required — the
mandatory list from §2.1 needs the second layer, see §2.1 rationale), never
only as an alternate. Variants in `{…}` are alternatives (at least one
variant).

| id | tag | Layout | A in `base` | B in `base` | Punctuation (direct) |
|---|---|---|---|---|---|
| `de-de` | de-DE | QWERTZ | — (R8 exception, see the de-de note below) | **ä ö ü ß** (first alternates of a/o/u/s) é è ê á à â ç | „ “ {or » «}, –, €, §, ° |
| `en-us` | en-US | QWERTY | — | — (ASCII) | ' – — … £ $ ° |
| `fr-fr` | fr-FR | AZERTY | é è à ç ù | ê ë â ï î ô û ü œ æ ÿ | « », ’, €, ° |
| `es-es` | es-ES | QWERTY | ñ | á é í ó ú ü | ¿ ¡, « », º ª, € |
| `it-it` | it-IT | QWERTY | à è é ì ò ù | á í ó ú î ü | « » {or “ ”}, ’, € |
| `pt-pt` | pt-PT | QWERTY | — (R10 exception, see the pt-pt note below) | **á â ã à ç é ê í ó ô õ ú** (accents as first alternates of a/c/e/i/o/u) ü | « », º ª, €, § |
| `nl-nl` | nl-NL | QWERTY | — | á à ä ë é è ê ï ö ü | ‘ ’ „ ” {or “ ”}, € |
| `pl-pl` | pl-PL | QWERTY | ą ć ę ł ń ó ś ź ż | — | „ ”, –, € |
| `tr-tr` | tr-TR | Turkish Q | ç ğ ı i ö ş ü | â î û | ‘ ’, € |
| `ru-ru` | ru-RU | ЙЦУКЕН | а б в г д е ё ж з и й к л м н о п р с т у ф х ц ч ш щ ъ ы ь э ю я | — | « », –, №, € |
| `cs-cz` | cs-CZ | QWERTZ | á č é ě í ý ú ů ř š ž | ď ť ň ó | „ “, –, €, ° |
| `sv-se` | sv-SE | QWERTY | å ä ö | á à é è ü | ” {or » «}, –, §, € |
| `da-dk` | da-DK | QWERTY | æ ø å | ä é ö ü | » « {or ”}, –, §, € |
| `fi-fi` | fi-FI | QWERTY | **ä ö å** | š ž é | ” {or » «}, –, §, € |
| `el-gr` | el-GR | Greek | α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ ς τ υ φ χ ψ ω | ά έ ή ί ό ύ ώ ϊ ϋ ΐ ΰ | « », · (**U+00B7**), € |

Notes on the table (each professionally justified, not taste):

- **`fi-fi`:** `å` is mandatory (DESIGN.md §7.3 names "ä ö å"); it is the
  Swedish-Finnish letter (names, Åland, Åbo) and sits on every Finnish
  hardware layout. Rev 1 had `å` only in `sv-se`/`da-dk`.
- **`pl-pl`:** `Ą Ć Ę Ł Ń Ó Ś Ź Ż` are **not** alternates but the shift layer
  of `A(base)` (rules 1/2). Rev 1 listed them in the alternates column — that
  contradicted its own rule 2.
- **`el-gr`:** the alphabet is spelled out as an explicit list because the
  codepoint range α–ω incidentally also contains the final sigma ς (U+03C2)
  and thereby counts as "complete" without ς. `ς` and `Σ` are mandatory
  (final vs. normal form); the uppercase tonos characters (`Ά Έ Ή Ί Ό Ύ Ώ`)
  and `Ϊ Ϋ` were completely missing in rev 1 — without them "Ά" is not
  typable, although Greek in uppercase keeps the tonos (e.g. "ΆΛΦΑ" —
  orthographically allowed).
  **Rev 3 (audit):** the punctuation character is the Ano Teleia as
  **U+00B7** — U+0387 is canonically equivalent and normalized by NFC to
  U+00B7, so it would not be representable under rule 7. The "Greek question
  mark" U+037E is canonically identical with `;` (U+003B) and thus already
  covered by group 1 in §2.1; a separate codepoint is **not** possible under
  NFC and is no longer required.
- **`cs-cz`:** `ď ť ň ó` stay alternates (on hardware layouts they are not
  directly mapped); `á č é ě í ý ú ů ř š ž` are directly reachable.
- **`ru-ru`:** `ё` lies outside the range а–я (U+0451) and must be tracked
  explicitly; `ъ`/`ь` lie within it and are thereby covered. `№` is the
  Russian numero sign key and did not occur in rev 1.
- **`de-de`:** **Exception (R8 decision 2026-10-30, not up for
  disposition):** `ä ö ü ß` move from column **A** to column **B** — no more
  own base keys, instead the **first alternative** of a/o/u/s. Evidenced on
  AOSP: `java/res/xml/rowkeys_qwertz1.xml` carries as `latin:keySpec` only
  `q w e r t z u i o p` (no `ü`; plus `latin:keyHintLabel` 1..0),
  `rowkeys_qwertz3.xml` only `y x c v b n m` (no `ß`); umlauts/ß live there as
  `latin:moreKeys="!text/morekeys_a/_o/_u/_s"` (resolution `TEXTS_de`,
  umlaut/ß each at position 1). What must **remain reachable** is the capital
  `ẞ` (U+1E9E): it is henceforth the **first alternative of `S`** in `shift`
  (uppercase form per §3/`CaseMapper.upperLabel`, explicitly **not** the text
  transformation — `"ß".uppercase()` is `"SS"`, the official rule; separate
  path visible in `CaseMapperTest`). The earlier wording "`ẞ` is the **key
  label** of the ß key in `shift`" is thereby obsolete. **This exception
  applies only to `de-de`**; for the other 14 languages column A ("own key
  in `base`") remains unchanged, and the reachability principles (mandatory
  characters §2.1, punctuation "never only as an alternate") remain untouched
  — a deliberate, evidenced **per-language exception**, not a general rule
  change.
- **`pt-pt`:** **Exception (R10 decision 2026-10-30, decided today, not up for
  disposition):** `á â ã à ç é ê í ó ô õ ú` move from column **A** to column
  **B** — no own base keys, instead the **first alternative** of
  a/c/e/i/o/u (shift: uppercase forms analogously, §3). The previous rev 3/D2
  text ("pt-pt: A = á â ã à ç é ê í ó ô õ ú as OWN mandatory characters") is
  thereby **repealed by R10** — historically evidenced, but no longer the
  target state. Rationale — AOSP model: `rows_qwerty.xml` with
  `rowkeys_qwerty1..3` does **not** carry the Portuguese accents as own keys
  but as moreKeys on the base letters (evidence: `docs/AOSP_ROWKEYS_BELEGE.md`,
  section "qwerty set (en-us, it-it, nl-nl, pl-pl, pt-pt, tr-tr)": a **á**
  ,ã,à,â…; c **ç** …; e **é** ,ê…; i **í** …; o **ó** ,õ,ô…; u **ú** …,
  resolved from `KeyboardTextsTable.java` `TEXTS_pt`). Analogously to
  de-de/R8, the earlier 5-row structure is dropped as well (stock = flat
  4-row QWERTY).
  **Test consequence:** `LayoutCompletenessTest` — the pt-pt matrix row
  switched to `emptyList()` (column A); new case "pt-pt - accents are first
  alternative instead of own key" (base and shift, each `alternates[0]`;
  negative case "without accent chain on a" contains red). Applies only to
  `pt-pt` (besides `de-de`/R8); the remaining 12 languages stay unchanged.
- **`tr-tr`:** `i` and `ı` are both `A`, `İ` and `I` are both in `shift` —
  four characters, two keys (see §3).
- **`fr-fr`:** `ê` is **not** directly mapped on hardware AZERTY
  (circumflex + e); the requirement is therefore "reachable" (B), not "own
  key". Digits never sit on the letter row with AZERTY — they belong on
  `symbols` (§1 rule 9), which is automatically fulfilled here.

### 2.3 Footer row — unified target design (F24e, decision R14, 2026-10-30)

**All 15 layouts** (base AND shift) carry the same footer row, Σ = **13**:

- **No more `cursor-left`/`cursor-right` keys** in the footer row — the
  earlier `‹ ›` keys are gone in all 15 layouts (in the `symbols` layer the
  punctuation characters remain, §2.1 untouched).
- Instead **one period key** (`id=period`, label `.`) with **five menu
  cells**: `alternates = [",", "!", "?", "‹", "›"]` and the new,
  index-parallel field `cellActions = ["", "", "", "cursor-left",
  "cursor-right"]`. Meaning: empty string = normal text cell (`commitText`),
  non-empty entry = **action** via `listener.onAction` — implemented in
  `layouts/LayoutParser.kt` (field `cellActions` in the allowlist, F24e1) and
  `keyboard/LongPressMenu.kt:48` (`cellAction(…) =
  actionCells.getOrNull(index)?.takeIf { it.isNotBlank() }`). With that,
  cursor left/right are **menu cells of the period key**, not direct keys.
- Width: `period` width **1** in `de-de` (F23 target design), width **2** in
  the other 14 layouts; in each case width neutralization at another spot in
  the row so that Σ = 13 stays (test gate `FooterAcrossAssetsTest`).
- **D10 footnote (why the model ≠ the asset):** the footer row is **not**
  asset-verbatim in the model. `D10_FOOTER.composeRow`
  (`keyboard/KeyboardModel.kt:140ff`) rebuilds it: mic to the right end,
  enter as the second-to-last key, and the **space key gives one unit to the
  mic key** (asset 5 → model 4; asset mic 1 → model 2). All measured
  rectangles therefore deviate from the asset (example de-de: period
  706..789, enter 789..913, mic 913..1080 px) — that is the **target**, not
  an error.
- Device-evidenced (<device>, F24e build): the action cell logged as
  `popup-commit cell=3 action=cursor-left` with a real cursor move without a
  text change; the text cell as `popup-commit cell=2 action=-` with a
  character appended.
- Origin: **user wish**, not AOSP (AOSP view:
  `docs/AOSP_ROWKEYS_BELEGE.md`).
- **Digit hints remain unchanged** (F24a/b/c): r0 carries `hint` 1..0 in base
  AND shift; special keys (å æ ø ñ ł ó ć ę ş ı ç й …) **never** carry a hint.

**F25 (belongs to the footer row):** the globe on the left in the footer row
switches again — `ime/VoiceKeyboardService.kt:979` wires
`KeyActions.LANGUAGE -> switchToNextLanguage()` (function from line 1022;
guards: during dictation only pre-noting + hint, with only one active
language a toast).

## 3. Case mapping table (binding, executably testable)

`CaseMapper` maps per language (label level). Java/Kotlin defaults
(`uppercase()`/`lowercase()` with Locale.ROOT) are **wrong** for Turkish and
German; `-` means "identical".

| Language | Rule | Cross-check (test) |
|---|---|---|
| tr-TR | `i`→`İ`, `ı`→`I`, `İ`→`i`, `I`→`ı`, otherwise standard | `upperLabel("i")=="İ"`, `lowerLabel("I")=="ı"`, `lowerLabel("İ")=="i"` (1 codepoint!) |
| de-DE | label: `ß`→`ẞ`; **text** uppercase: `ß`→`SS` | `upperLabel("ß")=="ẞ"`, `TextTransforms.upper("straße")=="STRASSE"` |
| el-GR | `ς`→`Σ`; `ά`→`Ά`, `έ`→`Έ`, `ή`→`Ή`, `ί`→`Ί`, `ό`→`Ό`, `ύ`→`Ύ`, `ώ`→`Ώ`; `ϊ`→`Ϊ`, `ϋ`→`Ϋ`. **Exception:** `ΐ` (U+0390) and `ΰ` (U+03B0) have no single-codepoint uppercase form (Unicode: `Ι`+U+0308+U+0301 resp. `Υ`+U+0308+U+0301, 3 codepoints) → the label stays unchanged `ΐ`/`ΰ` in `shift` | `upperLabel("ς")=="Σ"`, `upperLabel("ά")=="Ά"`, `upperLabel("ϊ")=="Ϊ"`, `upperLabel("ΐ")=="ΐ"` |
| ru-RU | standard (ё→Ё, ь→Ь …) | `upperLabel("ё")=="Ё"`, `upperLabel("ь")=="Ь"` |
| pl-PL, cs-CZ, sv-SE, da-DK, fi-FI, es-ES, it-IT, pt-PT, nl-NL, fr-FR, en-US | standard 1:1 mapping | `upperLabel("ą")=="Ą"`, `upperLabel("ř")=="Ř"`, `upperLabel("œ")=="Œ"`, `upperLabel("æ")=="Æ"` |

Rule for all: `upperLabel`/`lowerLabel` must return **exactly one codepoint**
(otherwise rule 6/7 fails) — that is why `ß`, `İ`, `ΐ`, `ΰ` are special cases
and no self-runners. Where no single-codepoint uppercase form exists (`ΐ`,
`ΰ`), the character stays unchanged in `shift`; that is the only allowed
exception to rule 2.

## 4. Special cases the tests must cover

- **Turkish:** `i`→`İ`, `ı`→`I`; key labels in `shift` show `İ`/`I`;
  `lowerLabel("İ")` returns `i` (not `i`+U+0307). Additionally: the language
  switch must leave `i` on the **same** key (only the label changes) —
  otherwise the typing motorics change on a language switch.
- **German:** `ẞ` as the shift label of `ß`; text uppercase stays `SS` (§3).
  Never hide `ß` in `alternates`.
- **Greek:** `ς`, `Σ`, `σ` all reachable; tonos vowels lowercase (`A` layer
  optional, B allowed) and uppercase (`Ά Έ Ή Ί Ό Ύ Ώ`) reachable; dialytika
  `ϊ ϋ ΐ ΰ` reachable; Greek question mark `;` (U+037E) and Ano Teleia `·`
  (U+0387) directly on `symbols`.
- **Russian:** `ё`/`Ё`, `ъ`/`Ъ`, `ь`/`Ь` present; `«»` and `№` present.
- **French:** `œ`/`Œ`, `æ`/`Æ`, `ç`/`Ç`, `ÿ`/`Ÿ` reachable; `’` (U+2019) as
  an own key, `«»` direct; digits not on the letter row.
- **Polish:** `ą ć ę ł ń ó ś ź ż` direct, uppercase forms in `shift` (§2.2).
- **Czech:** `ě š č ř ž ý á í é ú ů` direct, `ď ť ň ó` reachable; `ů` and
  `ú` are **different** characters (no mapping onto each other).
- **Swedish/Danish/Finnish:** `å` in all three layouts; `æ ø` Danish, `ä ö`
  Swedish/Finnish — on a language switch `ä/æ` resp. `ö/ø` must **not**
  collide on the same key without the label switching too (otherwise one
  silently types the wrong character).
- **Acute/grave accents** (`é è ê` …) are permitted as alternates on
  `a`/`e`/`i`/`o`/`u` (max. 8 per key, §1 rule 3).

## 5. Reconciliation with the ASR language support (gap, deliberately
documented)

Per its model card the ASR model supports 25 languages (`docs/ASR_CONTRACT.md`
§1): `en es fr de bg hr cs da nl et fi el hu it lv lt mt pl pt ro sk sl sv ru
uk`.

Hence:

- **`tr-tr` has a layout but no ASR support.** Dictation in Turkish does not
  work (the model does not know Turkish); the layout is pure typing
  functionality. This must be visible in the language selection (dictation
  mic disabled or a warning), otherwise a silent failure arises.
- **11 supported languages have no layout:** `bg hr et hu lv lt mt ro sk sl
  uk`. For dictation that is irrelevant (text is inserted via `commitText`,
  the layout is only needed for manual corrections), for post-processing it
  is not. Recommendation: deliver these languages as layouts later (order:
  `sk`, `sl`, `hr`, `ro`, `hu`, `et`, `lt`, `lv`, `bg`, `uk`, `mt`), but not
  in the initial scope — DESIGN.md §7.3 stays at 15.

## 6. Acceptance criteria

1. `LayoutCompletenessTest`: for all 15 assets §2.2 holds (the A and B sets),
   including the uppercase forms in `shift`.
2. `CaseMapperTest`: §3 table complete, including the cross-checks
   (`i`→`İ`, `ß`→`ẞ` vs. `SS`, `ς`→`Σ`, `ą`→`Ą`); `upperLabel`/`lowerLabel`
   always return exactly one codepoint.
3. `PunctuationCoverageTest`: mandatory list from §2.1.1
   (`MANDATORY_ON_SYMBOLS`, `MANDATORY_ON_SYMBOLS_OR_SHIFT`,
   `MANDATORY_DIGITS`) plus §2.2 per country, each as an own key in the
   permitted layer (variants as an or-set); each character **exactly one**
   occurrence.
4. `NfcLabelTest`: all labels/alternates NFC, no combining characters alone,
   no duplicates (§1 rules 6–8).
5. `LayoutParserTest`: accepts all 15, rejects manipulated variants (missing
   `shift` layer, unknown action, duplicate key ID, `width` ≤ 0, `alternates`
   with action names or > 8 entries).
6. Visual check in the emulator: DE shows `ä ö ü ß` directly; the digit row
   appears after `?123`; in the TR layout the shift layer shows `İ` and `I` on
   the `i`/`ı` keys.
7. Language switch in the emulator: the `language` key cycles through the
   active layouts, the selection survives a restart; the dictation mic is
   disabled/marked for languages without ASR support (§5).
8. `LayoutArtifactTest` (replaces `checkNoLabelArtifacts`): rules 5a–5e over
   all 15 assets, per key `kind:"text"`:
   - 5a `label == value`, 5b `codePointCount == 1` for label **and** value,
   - 5c no control/combining/invisible character,
   - 5d `label ∈ Z` (mandatory list §2.1.1 ∪ supplementary characters §2.1.2
     ∪ §2.2 characters),
   - 5e action keys carry no character from `Z`.
   The error message contains `asset`, `layer`, `key.id`, `label`, `value`,
   sub-rule. **There must be no blacklist of individual characters** — in
   particular `& \ < >` must not be tracked as "forbidden" (rev 2 error,
   §2.1.3). Cross-check: `&` on `symbols`, `\` on `symbolsShift`, `=\<` as a
   label and `⌇` as a label — the first two must be **accepted**, the last
   two **rejected**.

9. **Symbol layers back route (rule 4a):** `LayoutCompletenessTest` must
   require for **both** symbol layers per asset exactly 1× `value=="shift"`
   **and** `label=="⇧"` (row 2/column 0, `width==1.5`). The previous
   tolerance is dropped (today `layer-symbols` **or** `layer-base`
   suffices, so a dead end stays green). Additionally: no `layer-base` with a
   label ≠ `ABC`.
   **Cross-checks (must turn red):** (a) asset without this key in
   `symbolsShift` (current state of `cs-cz`, `pl-pl`, `ru-ru`, `sv-se`,
   `tr-tr`); (b) key with `value:"shift"` but `label:"?123"` (current state
   of `da-dk`, `el-gr`, `fi-fi`); (c) key with `label:"⇧"` but
   `value:"layer-base"`.
