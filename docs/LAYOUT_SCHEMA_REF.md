# Layout schema (machine-readable short reference for coders)

**Source:** `docs/LAYOUT_REQUIREMENTS.md` (rev 3) + the real parser contract
(`LayoutParser.kt`, `LayoutAsset.kt`). **Audit status:** audited against the
15 assets (`docs/LAYOUT_AUDIT.md`, 2026-10-27) — rev 2 of this file contained
18 errors/gaps, listed there individually.

File: `app/src/main/assets/layouts/<id>.json`, UTF-8, `ensure_ascii=false`
allowed.

```json
{
  "schemaVersion": 1,
  "id": "de-de",
  "language": {"tag": "de-DE", "label": "Deutsch", "caseLocale": "de-DE", "direction": "ltr"},
  "deadKeys": {},
  "layers": {
    "base":         {"rows": [[KEY, ...], ...]},
    "shift":        {"rows": [[KEY, ...], ...]},
    "symbols":      {"rows": [[KEY, ...], ...]},
    "symbolsShift": {"rows": [[KEY, ...], ...]}
  }
}
```

KEY object: `{"id":"q","label":"q","width":1,"kind":"text","value":"q"}`
(optionally additionally `"caps": "…"` and `"alternates": ["…", …]`)

## Field contract (exact, checked by the parser)

| Field | Rule |
|---|---|
| `schemaVersion` | `1` (integer, mandatory) |
| `id` | `^[a-z0-9-]+$`, **must** match the file name (`de-de` → `de-de.json`); the repository loads via the ID |
| `language` | exactly `tag`, `label`, `caseLocale`, `direction`; all mandatory strings; `direction ∈ {ltr, rtl}`; `tag` BCP-47 (`^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$`) and **must** match the §2.2 row; `caseLocale` = `tag` (CaseMapper uses it) |
| `deadKeys` | optional (default `{}`); sub-schema see below; **no asset uses it currently** |
| `layers` | exactly the four layers `base`, `shift`, `symbols`, `symbolsShift` (no others) |
| `id` (key) | mandatory, **unique per layer**; the same physical key keeps its ID across layers; every `base` text key needs a key with the **same ID** in `shift` (the parser enforces this) |
| `label` | mandatory; for `kind:"text"` rule **5a** `label == value` and **5b** exactly 1 codepoint (after NFC) |
| `width` | mandatory, number `> 0` (normally 1.0, space 5.0, function keys 1.0–1.5) |
| `kind` | `text` \| `action` \| `dead` |
| `value` | for `text`/`dead` the character resp. the dead-key ID; for `action` **only** from the allowlist |
| `caps` | optional (`String`), alternative label; the field exists, **used by no asset** |
| `alternates` | optional, list of strings; rules see below |

**Unknown fields** are **rejected** by the parser in `language`,
`layers.<layer>`, in the key object, and in `deadKeys.<id>`. On **top level**
the parser does not check for additional fields — the five above are a
convention and should not be extended (otherwise docs and code drift apart).

### Allowed actions (`kind:"action"`)

```
shift, caps, layer-symbols, layer-base, backspace, space, enter,
language, emoji, mic, cursor-left, cursor-right
```

- `caps` is in the allowlist but used by no asset (double tap on `shift` =
  CapsLock is **not** implemented; do not build on it).
- Action keys carry **no** character from the character universe
  (§2.1/§2.1.2/§2.2) as label, but a legend from
  `⇧ ⌫ ⏎ 🌐 😊 ?123 ABC Leer ‹ ›` or a word (`Leer`, `Διάστημα`) — rule 5e.

### Dead keys (documented, unused)

```json
"deadKeys": {
  "acute": {"spacing": "´", "combining": "\u0301", "compose": {"a": "á", "e": "é"}}
}
```

Fields per dead key: `spacing` (mandatory), `combining` (optional), `compose`
(optional, object base character → result). A key with `kind:"dead"` **must**
reference a `value` defined here (the parser enforces this).

## Row geometry (important, checked by the parser)

The check is **pairwise**, not global:

1. `shift` and `base` have **the same row count**, and per row index the same
   **width sum** (`Σ width`, tolerance 1e-9).
2. `symbolsShift` and `symbols` likewise.
3. The letter layers may have a **different** row count/width split than the
   symbol layers (e.g. `pt-pt`: 5 letter rows, 4 symbol rows).

The **key count** per row may differ, as long as the width sum matches.
Rules of thumb (from the 15 assets, not a rule): `symbols`/`symbolsShift` as
4 rows with text rows `11/11/10` + action row (30–31 text slots; `de-de`,
`en-us` = 30, `el-gr`/`da-dk`/`fi-fi` = 31). §2.1 needs **30** text slots
(20 group 1 + 10 digits) — the `symbols` side is thereby practically full.

## Mandatory characters (group 1 — MUST be on `symbols`, each exactly 1×)

```
. , ? ! : ; ' " - / @ ( ) & % + = # _ *
```
(20 characters: period, comma, question, exclamation, colon, semicolon,
apostrophe, **straight** quotation mark U+0022, hyphen, slash, at, open/close
parenthesis, ampersand, percent, plus, equals, hash, underscore, asterisk.)

`&`, `\`, `<`, `>` are **not** artifacts (rev 2 error, see audit B1/§2.1.3) —
they are mandatory and must not be replaced by `§`/`$` or similar.

## Mandatory characters (group 2 — `symbols` OR `symbolsShift`, each exactly
1×)

```
\ [ ] { } < > ~ | € $ § °
```
(13 characters. Brackets complete in pairs: `[ ]`, `{ }` — not `] {` without
a partner.)

## Digits

`1 2 3 4 5 6 7 8 9 0` on `symbols`, each exactly once. `symbolsShift` carries
shift counterparts like `¹ ² ³ ¼ ½ ¬ | ° …`.

**Not** `! @ # $ % ^ & * ( )` as digit shift: these characters are already
§2.1 group 1 mandatory on `symbols`; a repetition on `symbolsShift` violates
rule 8 (duplicate labels/values per layer).

## Language-specific mandatory characters (`base`, own key = level A)

`B` = own key **or** alternate in `base`. `shift` must carry the uppercase
form analogously (rule 2/§3); exceptions: `ß→ẞ`, `σ/ς→Σ`, `ΐ/ΰ` stay unchanged.

| id | own keys in `base` (A) | reachable (B) | Special notes |
|---|---|---|---|
| de-de | ä ö ü ß | é è ê á à â ç | shift: Ä Ö Ü **ẞ** (`"ß".uppercase()` = `SS` is the **text** rule, not the label) |
| en-us | — | — (ASCII) | — |
| fr-fr | é è à ç ù | ê ë â ï î ô û ü œ æ ÿ | the asset additionally carries `ê` as an own key (over-fulfillment); shift: É È À Ç Ù |
| es-es | ñ | á é í ó ú ü | shift: Ñ |
| it-it | à è é ì ò ù | á í ó ú î ü | shift: À È É Ì Ò Ù |
| pt-pt | á â ã **à** ç é ê í ó ô õ ú | ü | shift: Á Â Ã À Ç É Ê Í Ó Ô Õ Ú |
| nl-nl | — | á à ä ë é è ê ï ö ü | — |
| pl-pl | ą ć ę ł ń ó ś ź ż | — | shift: Ą Ć Ę Ł Ń Ó Ś Ź Ż |
| tr-tr | ç ğ ı **i** ö ş ü | â î û | Turkish Q; shift: Ç Ğ **I İ** Ö Ş Ü (`i→İ`, `ı→I`) |
| ru-ru | а б в г д е ё ж з и й к л м н о п р с т у ф х ц ч ш щ ъ ы ь э ю я | — | ЙЦУКЕН; shift А–Я + Ё |
| cs-cz | ě š č ř ž ý á í é ú ů | ď ť ň ó | `ů` and `ú` are different characters |
| sv-se | å ä ö | á à é è ü | — |
| da-dk | æ ø å | ä é ö ü | — |
| fi-fi | ä ö **å** | š ž é | `å` is mandatory (`Åland`, `Åbo`) |
| el-gr | α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ ς τ υ φ χ ψ ω | ά έ ή ί ό ύ ώ ϊ ϋ ΐ ΰ | final sigma `ς` **and** `σ`; shift Α–Ω + `Σ` (necessarily `Σ` **twice**, see the rule 8 exception) |

## Language-specific punctuation (own key on `symbols` OR `symbolsShift`)

Completely missing in rev 2 — **cause of 6 audit findings**. `{a | b}` =
variant, at least one. Executed as an own key, **never** only as an alternate.

| id | Mandatory punctuation |
|---|---|
| de-de | `„ “` {or `» «`}, `–`, `€`, `§`, `°` |
| en-us | `'`, `–`, `—`, `…`, `£`, `$`, `°` |
| fr-fr | `« »`, `’`, `€`, `°` |
| es-es | `¿`, `¡`, `« »`, `º`, `ª`, `€` |
| it-it | `« »` {or `“ ”`}, `’`, `€` |
| pt-pt | `« »`, `º`, `ª`, `€`, `§` |
| nl-nl | `‘ ’ „ ”` {or `“ ”`}, `€` |
| pl-pl | `„ ”`, `–`, `€` |
| tr-tr | `‘ ’`, `€` |
| ru-ru | `« »`, `–`, `№`, `€` |
| cs-cz | `„ “`, `–`, `€`, `°` |
| sv-se | `”` {or `» «`}, `–`, `§`, `€` |
| da-dk | `» «` {or `”`}, `–`, `§`, `€` |
| fi-fi | `”` {or `» «`}, `–`, `§`, `€` |
| el-gr | `« »`, `·` (**U+00B7**, NFC-stable), `€` |

Note `el-gr`: U+0387 (Ano Teleia) is canonically equivalent to U+00B7 and is
normalized there by NFC; U+037E (Greek question mark) is canonically identical
with `;` (U+003B, already group 1) — **no separate codepoint** under rule 7.

## Character universe `Z` (rule 5d)

A `text` label must lie in `Z` = group 1 ∪ group 2 ∪ digits ∪ supplementary
characters ∪ language characters of the asset's own §2.2 row ∪ the letters of
the language (a–z, A–Z).

Supplementary characters (allowed, **not** mandatory):

```
£ ^ ¹ ² ³ ¼ ½ ¬ ¢ µ ¥ ¶ · † ‡ ‰ ± × ÷ √ π ∆ • © ® ` 
„ “ ” ‘ ’ ‚ « » – — … ¿ ¡ № º ª
```

Everything else is an artifact (`⌇` U+2307, `=\<`, `⇧` on a character key).

## Alternates (long-press)

- max. **8** entries per key, **exactly 1 codepoint** per entry, NFC.
- characters only, **no** action names, no empty strings, no duplicates,
  never the key's own character.
- In `shift` = **uppercase forms** of the `base` alternates (same position) —
  this applies **only to characters with an uppercase form**. Caseless
  characters (`€ § – — … µ`) are not mirrored (rule 3, clarified in rev 3 /
  audit D3).
- `alternates` are level **B**: they **never** replace a §2.1/§2.2 mandatory
  character.
- **Symbol layers** (`symbols`/`symbolsShift`): the same alternates rules —
  the long-press menus are **language-neutral** and identical in all 15
  assets (T4/PLAN_LONGPRESS_SYMBOLS.md; digits without a menu, no `hint` on
  symbol keys).
- **Symbol layer grid** (T6/PLAN_SYMBOL_RASTER.md): Σ=[10,10,10,13] per row
  with stable letter slots — ⌫ exactly at the r1 end (width 1.0), ⏎ exactly
  at the r2 end (width 1.0) as in the letter layers; no text character next
  to the space bar (footer: ABC 1.5 · 🌐 1 · 😊 1 · space 7.5 · 🎤 2).

## Other rules an asset must fulfill

1. **NFC** for all labels **and** alternates (rule 7).
2. No control/format characters, no combining characters alone
   (U+0300–U+036F), no whitespace as label (rule 5c).
3. No RTL character in `ltr` assets (rule 6).
4. No duplicate labels per layer — exception: `shift` keys whose `base`
   character has the same single-codepoint uppercase form (`σ`/`ς` → `Σ`).
5. Function keys per layer: **exactly one** `space`, `backspace`, `enter`,
   `emoji`, `mic` (rule 4, enforced by the test); `shift` in
   `base`/`shift`, `layer-symbols`/`layer-base` per symbol/letter layer.
   `cursor-left`, `cursor-right` and `language` are **asset convention**
   (present in all 15 assets), but **not** a requirement from §1.4.
6. `label != value` is **always** an error (rule 5a) — even if the label is
   "only" an escape leftover.
