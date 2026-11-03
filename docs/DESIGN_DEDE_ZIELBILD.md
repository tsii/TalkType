# A19-K — de-de target design: data template for F23 (hint digits, umlauts as alternates, period footer, ⌫ at top)

**Status:** 2026-10-30 · **Role:** documentation/data template — no code, no build, no commit.
**Purpose:** exact target data for `app/src/main/assets/layouts/de-de.json`, so that a coder
can implement F23 without questions. The user's decisions (20:30): q..p carry hints
1..0; **no** dedicated ü/ö/ä/ß keys (→ alternates of u/o/a/s); period instead of `‹ ›`
(next to the space key, carrying `, ! ?`); backspace to the end of r0 next to `P`.

**Parser actual state (documented):** `layouts/LayoutAsset.kt:13-21` — `LayoutKey` knows
exactly `id, label, width, kind(text|action|dead), value, caps?, alternates`. A `hint`
field does **not** exist (grep `hint` in `layouts/*.kt` = 0 hits). **But the popup consumer
already exists (F22/D12):** `keyboard/LongPressMenu.kt` + `KeyboardView.kt` (drawing from
`:372`, hit zone, `cellCount` capped at **8** cells; `glyphs = alternatesOf(key)` `:385`).
So F23a needs only the **hint field** (model + parser + drawing), not a popup from scratch.

AOSP evidence source as in A19 Rev 2: clone of `platform/packages/inputmethods/LatinIME`
HEAD `127336e`; quotes below as file name + attribute.

---

## 1. Key sequence BEFORE / AFTER (de-de.json, row indices as in the asset)

Widths in existing units; **base and shift always in pairs** (geometry rule
`docs/LAYOUT_SCHEMA_REF.md`). Keys not mentioned = unchanged.

### r0 — base

BEFORE: `q w e r t z u i o p ü` (Σ 11; `ue` = id `ue`, value `ü`, width 1)

AFTER (10 letters + ⌫; `ue` is dropped):

| id | value | hint | alternates (target) | width | change |
|---|---|---|---|---|---|
| q | q | "1" | — | 1 | + hint |
| w | w | "2" | — | 1 | + hint |
| e | e | "3" | `é è ê ë ę €` (actual stays) | 1 | + hint; **open — see §2, Z4** |
| r | r | "4" | — | 1 | + hint |
| t | t | "5" | — | 1 | + hint |
| z | z | "6" | — | 1 | + hint |
| u | u | "7" | **`ü`** `ú ù û` | 1 | + hint; **ü NEW at 1** |
| i | i | "8" | `í ì î ï` (actual stays) | 1 | + hint |
| o | o | "9" | **`ö`** `ó ò ô` | 1 | + hint; **ö NEW at 1** |
| p | p | "0" | — | 1 | + hint |
| backspace | backspace | — | — | **1.5** | **moved here from r2** |

Σ r0: 10×1 + 1.5 = **11.5** (shift analogously 11.5 → pair rule OK).

### r1 — base

BEFORE: `a s d f g h j k l ö ä` (Σ 11; ids `oe`, `ae`)

AFTER (9 keys; `oe`/`ae` are dropped):

| id | value | hint | alternates (target) | width | change |
|---|---|---|---|---|---|
| a | a | — | **`ä`** `à á â ã å` | 1 | **ä NEW at 1** |
| s | s | — | **`ß`** `ś š` | 1 | **ß NEW at 1** |
| d f g h j k l | — | — | actual stays (empty) | 1 | — |

Σ r1: **9**.

**shift analogously (r1)** — uppercase forms, values from `de-de.json
layers.shift.rows[1]`, positions like base (rule 2 of LAYOUT_REQUIREMENTS):

| id | value | alternates (target) | change |
|---|---|---|---|
| a | A | **`Ä`** `À Á Â Ã Å` | Ä NEW at 1 |
| s | S | **`ẞ`** `Ś Š` | **ẞ (U+1E9E) NEW at 1** — the capital sharp S stays reachable; uppercase form of the base alternate `ß` per `CaseMapper` (ß ↔ ẞ, LAYOUT_REQUIREMENTS §2.2/§3 — NOT `"ß".uppercase()` = `SS`) |
| d…l | D…L | actual stays | — |

The `ẞ` was previously the key label of the ß key in `shift`; with its removal (R8) it
becomes the **first alternate of S in shift**. *Evidence status:* on the AOSP side, the
uppercase derivation for shift popups was not extracted (`TEXTS_de` defines `morekeys_s`
only lowercase: "ß,ś,š") — **assumption — not evidenced**: uppercase popups on AOSP follow
the same table capitalized; for us, the requirement is covered by `CaseMapperTest`.

### r2 — base

BEFORE: `⇧(1.5) y x c v b n m ß(1) ⌫(1.5)` (Σ 11; ids `shift`, `eszett`, `backspace`)

AFTER (⌫ → r0, `eszett` is dropped; ⇧ fills the ß gap):

| id | value/action | width | alternates | change |
|---|---|---|---|---|
| shift | shift | **2.0** | — | 1.5 → 2.0 |
| y x c v b n m | — | 1 | c `ç ć č`, n `ñ ń`, m `µ` remain | otherwise — |

Σ r2: 2 + 7 = **9**.

### r3 — footer (base AND shift)

BEFORE: `?123(1.5) 🌐 😊 space(5) ‹ › 🎤 ⏎(1.5)` (Σ 13)

AFTER: `?123(1.5) 🌐(1) 😊(1) space(6) .(1) 🎤(1) ⏎(1.5)` → Σ **13** (test contract §3!)

| id | value | kind | width | alternates | change |
|---|---|---|---|---|---|
| cursor-left / cursor-right | — | — | — | — | **delete** |
| period | `.` | text | **1** | `,` `!` `?` | **NEW** between space and mic |
| space | space | action | **6** (was 5) | — | +1 (fill rule §3) |

Display: `D10_FOOTER` (`keyboard/KeyboardModel.kt:125-145`) shifts, in the
**display**, mic behind ⏎ and gives 1 unit from space (6→5, mic 2) — the asset raw
text remains `… . 🎤 ⏎`; composition + tests (`FooterAcrossAssetsTest`) adapt
automatically, **0 code** for that.

### r3 of the SYMBOL layers (symbols, symbolsShift) — special rule!

Delete `‹ ›` here too (the user's wish applies visibly to this layer), but a **second
period is forbidden**: LAYOUT_REQUIREMENTS **rule 9** requires the mandatory characters
`. , ? ! …` on `symbols` **exactly 1× each** — the period already lives there, a second
one would violate the mandatory-character rule. So: delete `‹ ›`, **no** second period,
**space 5 → 7** → Σ 1.5+1+1+7+1+1.5 = **13**. The symbol layers (`symbols`,
`symbolsShift`) get **no** period key (otherwise mandatory character 2× → rule 9
violation); only base/shift get the period key.

---

## 2. Sources per alternates value (AOSP source or "open")

Newly **added** characters (this is the entire set of changes):

| # | Key | new character addition | AOSP evidence (file + attribute) | Status |
|---|---|---|---|---|
| u popup | `ü` at 1 | `rowkeys_qwertz1.xml`: u key `latin:moreKeys="!text/morekeys_u"`; resolution `KeyboardTextsTable.java` `TEXTS_de`: `morekeys_u = "ü,%,û,ù,ú,ū"` — **ü at position 1** |
| o → `ö` at 1 | `rowkeys_qwertz1.xml` o key `!text/morekeys_o`; `TEXTS_de`: `morekeys_o = "ö,%,ô,ò,ó,õ,œ,ø,ō"` — ö at 1 |
| a → `ä` at 1 | `rowkeys_qwerty2_left5.xml`: a key `latin:moreKeys="!text/morekeys_a"`; `TEXTS_de`: `morekeys_a = "ä,%,â,à,á,æ,ã,å,ā"` — ä at 1 |
| s → `ß` at 1 | `rowkeys_qwerty2_left5.xml`: s key `!text/morekeys_s`; `TEXTS_de`: `morekeys_s = "ß,ś,š"` — **order ß ś š = exactly our target design** |
| **shift-S → `ẞ` at 1** | uppercase form of the base entry `ß` via `CaseMapper` (ß ↔ ẞ, U+1E9E; LAYOUT_REQUIREMENTS §2.2 de-de note/§3, verified in `CaseMapperTest`). AOSP counterpart **not extracted**: `TEXTS_de` defines `morekeys_s` only lowercase ("ß,ś,š") — **assumption — not evidenced**: shift popups follow the same table capitalized |
| period → `, ! ?` | `key_period.xml`: `latin:moreKeys="!text/morekeys_period"`; default resolution `morekeys_period = !text/morekeys_punctuation` = `", ? ! # ) ( / ; ' @ : - , + % &"` (autoColumnOrder) — stock carries `, ? !` at 1/2/3; **the user wanted `, ! ?` — the user's preferred order wins, the source is still evidenced** |

Unchanged adopted actual alternates (`e i c n m …`) are of **asset origin**
(`de-de.json layers.base.rows[*]`), not AOSP-de — that is permitted (the user decided only
the five points for German, not an exchange of the foreign accents).

**Open — not evidenced / not decided:**
- **Z4 (e):** `TEXTS_de` has `morekeys_e = "é,è,ê,ë,ė"` (**ė**, without ę/€); our asset
  lists `é è ê ë ę €`. Do not delete/replace anything without the user's word — the
  template leaves the actual state.
- Percent sign `%` as a popup entry (stock a/o/u at position 2): adoption **open**
  (our assets carry `%` nowhere in alternates; the rule 9/audit question unchecked).
- Duplicate labels in the audit: the `period` entry `.` in base/shift is permissible
  (unique in those layers), but forbidden in `symbols`/`symbolsShift` (rule 9 "mandatory
  characters exactly 1×" on the symbol side) — hence no second period there (§3).

Evidenced vs. open: **5 of the 6 changed** alternates lines evidenced by AOSP
(u, o, a, s, period; + shift-S `ẞ` = CaseMapper-evidenced, the AOSP derivation marked as
an assumption); **2 open detail questions** (Z4 e list, % entry) — both resolved with
"keep the actual state".

---

## 2. (Numbers for §1 above: AOSP references per key)

r0 hints: `rowkeys_qwertz1.xml` `latin:keyHintLabel="1".."0"` above q..p (adopted 1:1).
r1/r2 have **no** hints in stock (rows `rowkeys_qwerty2`, `rowkeys_qwertz3` without
keyHintLabel — evidenced by grep = 0). Footer: `row_qwerty4.xml` = `key_comma +
key_space_5kw + key_period` without arrow keys; `key_period.xml`
`latin:moreKeys="!text/morekeys_period"` (defined in two places via
`morekeys_punctuation,morekeys_z` — detail: the stock comma carries `!text/morekeys_q,%`;
only the user's list `, ! ?` applies to US).

---

## 3. Where the freed width flows (and which test watches it)

| Freed | Absorbed by | Watching test |
|---|---|---|
| `ue` slot r0 (1 unit) | `backspace` r0 (1.5) — row grows 11 → **11.5** | `keyboard/KeyboardModelTest.kt` (widths/Σ normalization per row), `layouts/LayoutParserTest.kt` (mandatory field width) |
| `oe`+`ae` r1 (2) | stays **free**: r1 has 9 instead of 11 keys → row normalization makes them wider (the row always fills full width) | `KeyboardModelTest.kt` |
| `eszett` r2 (1) | `shift` 1.5 → **2.0** | `KeyboardModelTest.kt` |
| `‹ ›` r3 (2) | `period` (1) + **`space` 5 → 6** (1) | **`keyboard/FooterAcrossAssetsTest.kt`**: footer row = **13 units** across all 15 assets × 4 layers; ID set invariant; `D10_FOOTER` shifts mic to the edge in Compose (weight 2), space gives exactly 1 → raw space 6 appears in Compose as 5 — satisfied |
| Layer equality | base↔shift same Σ per row; `symbols`/`symbolsShift` **without** period (rule 9 mandatory characters 1×!): `‹ ›` gone, **`space` 5 → 7**, Σ stays 13 | `layouts/LayoutArtifactTest.kt` (rule 5e/8), `layouts/PunctuationCoverageTest.kt`, `NfcLabelTest.kt` (NFC), `LayoutCompletenessTest.kt` |

**Height contract 650 px / 829 px:** unviolated — the key count per **row** changes, not
the **number of rows** (de-de stays 4/4/4/4). `keyboard/KeyboardHeightTest.kt:12-24`
requires: at 650 px (nav 21) all rows visible + scale 0.873; at 829 px budget 578 ≥
requirement 576; rows must not disappear or be replaced. Since the rework only moves
widths and IDs within existing rows, the contract stays satisfied; F23b must get the test
green unchanged (gate, not a change).

---

## 4. Smallest implementation slices (three assignments, each ≤ 4 files)

**F23a — hint field (model+parser+drawing, without de-de data):**
1. `layouts/LayoutAsset.kt` — `LayoutKey` + `val hint: String? = null` (field 8).
2. `layouts/LayoutParser.kt` — extend the allowlist (`parseKey`, line 100) with
   `"hint"`; mandatory: if present a string of length 1 (a digit); otherwise `null`.
3. `keyboard/KeyboardView.kt` (+ possibly `keyboard/KeySurface.kt`) — a small digit top
   right on the key (stock model `keyHintLabel`); **and**: LongPressMenu cells =
   `[hint?] + base + alternates` — mind the `cellCount` cap of 8 (e then has 8 cells:
   hint+base+6 → exactly at the cap; the test documents that).
4. `layouts/LayoutParserTest.kt` — hint round trip + error validation (`hint` with 2
   characters ⇒ `LayoutException`).
*Gate: all existing tests green; de-de.json is NOT touched in F23a.*

**F23b — de-de data (only 1 file):**
`app/src/main/assets/layouts/de-de.json` exactly per §1 (base+shift+symbols+symbolsShift;
mind the §3 rules). Gates: `LayoutParserTest`, `LayoutArtifactTest`,
`LayoutCompletenessTest`, `PunctuationCoverageTest`, `NfcLabelTest`,
`FooterAcrossAssetsTest`, `KeyboardHeightTest`.
*(No other asset is changed — the other 14 languages remain untouched.)*

**F23c — device acceptance (orchestrator, no coder):**
Visual check of the hint digits (10) on r0, period footer without `‹ ›`, ⌫ top right,
long-press popups u/o/a/s with the umlaut at 1, the `.` popup `, ! ?`; delete/enter/space
behavior; IME PID stable. (The orchestrator does the coordinates/typing procedure itself
— deliberately not specified here.)

**Do not change:** `KONZEPT.md`, parser geometry logic, audit rules, the other 14 assets,
`symbols` rows 0-2 (mandatory-character side).

---

## ASSUMPTION lines

- `ASSUMPTION: hint is drawn as a small glyph top right on the key (stock form); exact
  size/color = F23a taste, not a data contract.`
- `ASSUMPTION: the user's wish "period carries , ! ?" means the popup order in exactly
  this order (the stock order would be ", ? !").`
- `EVIDENCED: LongPressMenu already exists (F22) — F23a is the hint field + drawing, not a
  popup rebuild.`
