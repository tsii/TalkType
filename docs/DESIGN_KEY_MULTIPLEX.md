# A19 (Rev 2) — key multiplexing: German = user's wish, all others = AOSP stock

**Status:** 2026-10-30, 20:4x · **Role:** consultation/documentation — no code, no build.
**User's decision (20:30, in the meantime):**
- **German:** exactly his wish list — `.` instead of `‹ ›` in the footer, digits on the
  top letter row, `.` carries `, ! ?`, umlauts/ß **no** dedicated keys (behind
  a/o/u/s), backspace top right next to `P`.
- **All other 14 languages:** exactly what stock Android does.
- **"Stock" = AOSP LatinIME** (`com.android.inputmethod.latin`), **not Gboard**. Rev 1
  derived partly from Gboard memory — Rev 2 replaces that with evidence.

**AOSP evidence source:** shallow clone `platform/packages/inputmethods/LatinIME`,
HEAD `127336e` (2025-02-26, branch `main`). All quotes: `java/res/xml/…`,
`java/res/values/…`, `java/src/…`. Our layouts: `app/src/main/assets/layouts/*.json`.
Rule 9: no contents, no paths except project/source file names.

---

## 1. Stock template = AOSP LatinIME (evidenced, file by file)

**1.1 Digits are ALWAYS on the top row in stock — as `keyHintLabel`.**
`rowkeys_qwertz1.xml`: the ten keys q..p carry `keyHintLabel="1"…"0"`
(q=1 w=2 e=3 r=4 t=5 z=6 u=7 i=8 o=9 p=0). The same applies to qwerty
(`rowkeys_qwerty1_left5/_right5`: hints 1..0), azerty
(`rowkeys_azerty1`: a z e r t y u i o p with 1..0), east_slavic
(`rowkeys_east_slavic1`: hints 1..0) and greek (`rowkeys_greek1`).
**Correction to the task text:** the `moreKeys` references in `rowkeys_qwertz1.xml`
number **eight** (w e r t **z** u i o → `!text/morekeys_*`), q and p carry none —
the orchestrator did not count z.

**1.2 `keyHintLabel` ≠ `moreKeys` — two different things, together = the user's wish.**
- `keyHintLabel` = the **small digit permanently displayed on the key** (visibility:
  "the 3 lives here").
- `moreKeys` = **content of the long-press strip** (`!text/morekeys_X`). The strip itself
  is its own mini layout: `kbd_more_keys_keyboard_template.xml` (+
  `key_styles_navigate_more_keys.xml` for dragging/release).
- Interaction (visible behavior): the key shows the letter large + the digit small;
  long-press opens the strip; the digit and alternates are reachable in it; releasing
  on the base key = the base character. The exact ordering of hint vs. moreKeys in the
  strip is in the code (`MoreKeysList` construction) and will be evidenced line by line
  only in the implementation assignment — not asserted here.

**1.3 The popup content does NOT live in strings files.** `values-de/strings.xml`
(23 KB, checked) contains only settings texts. The `!text/morekeys_*` resolution is a
Java table: **`java/src/com/android/inputmethod/keyboard/internal/KeyboardTextsTable.java`**
— `TEXTS_DEFAULT` plus one `TEXTS_<locale>` field per language; `null`/empty = "no
popup". The **actual German lists** (`TEXTS_de`, lines 1048-1101):

| Key | moreKeys (actual, TEXTS_de) | Note |
|---|---|---|
| a | **ä** % â à á æ ã å ā | ä sits at stock **position 1** — exactly the user's wish |
| o | **ö** % ô ò ó õ œ ø ō | ö at 1 |
| e | é è ê ë ė | **no ę, no €** (our de-de.json has ę € — deviation on OUR side) |
| u | **ü** % û ù ú ū | ü at 1 |
| s | **ß** ś š | ß at 1 |
| n | ñ ń | |
| i, c, r, t, w, z, y | (null → default EMPTY) | no popup in stock-de |

The `%` in a/o/u is a real percent sign as a popup entry.
Comparison en (`TEXTS_en`): morekeys_s = "ß" (English also carries ß in the s popup).
Footer: `morekeys_period` (default) = `, ? ! # ) ( / ; ' @ : - " + % &` — the **period
key carries exactly `, ? !` at stock positions 1/2/3** — the user's wish
("period carries comma/exclamation mark/question mark") is literally stock behavior.

**1.4 QWERTZ has no ä/ö/ü/ß keys.** `rows_qwertz.xml` = `rowkeys_qwertz1` (10 keys,
hints) + `rowkeys_qwerty2` (a..l, 9 keys) + `rowkeys_qwertz3` (⇧ 15%p, y x c v b n m,
⌫ `fillRight`) + `row_qwerty4`. Rows `keyWidth 10%p`. **Our de-de.json with its four
special keys is the deviation from stock, not the other way around.**

**1.5 Stock footer without arrow keys.** `row_qwerty4.xml` builds from
`key_comma + key_space_5kw + key_period` (+ shift-/enter end pieces): comma and period
are, in stock, **their own footer keys next to the space key**; cursor `‹ ›` does not
exist. The user's wish (period into the footer, no arrows) is also stock form.

**1.6 What we can replicate 1:1.** The data model: `alternates` (our counterpart to
moreKeys) already exists in schema+parser, but has **no consumer**; a `hintLabel`
counterpart does **not** exist — for the visible small digit, our schema needs a new
field (e.g. `hint`) in the parser allowlist
(`layouts/LayoutParser.kt:100` = `id,label,width,kind,value,caps,alternates`) plus audit
rules. The long-press code (timer + strip + dragging) is new (§5e). Do **not**
replicate: word glide typing (its own layer, not desired).

---

## 2. Row picture BEFORE/AFTER — de-de (unchanged from Rev 1; Σ per row measured)

ACTUAL (`de-de.json layers.base`, Σ 11/11/11/13; shift identical):

```
r0 |q|w|e|r|t|z|u|i|o|p|ü|                         Σ 11
r1  |a|s|d|f|g|h|j|k|l|ö|ä|                        Σ 11
r2  |⇧1.5|y|x|c|v|b|n|m|ß|⌫1.5|                    Σ 11
r3    |?123 1.5|🌐|😊|  space 5  |‹|›|🎤|⏎1.5|       Σ 13
```

TARGET (user's wish; ⌫ to where `ü` is today — rationale in §6):

```
r0 |q|w|e|r|t|z|u|i|o|p|⌫1.5|                      Σ 11.5
r1  |a|s|d|f|g|h|j|k|l|                            Σ 9
r2  |⇧2|y|x|c|v|b|n|m|                              Σ 9
r3    |?123 1.5|🌐|😊|  space 5  |.|🎤|⏎1.5|         Σ 12
```

The parser geometry compares **only pairwise** base↔shift / symbols↔symbolsShift
(`docs/LAYOUT_SCHEMA_REF.md` "row geometry"); row Σ 9…13 already coexist.
The `?123` side remains untouched (LAYOUT_REQUIREMENTS **rule 9**: digits and
mandatory characters `. , ? ! …` remain their own keys there; multiplexing is an
addition).

---

## 3. de-de multiplexing matrix (de-de.json as the base, stock order as the model)

Popup order as in stock: **base character preselected on the left, language-close
alternates directly behind it (stock puts ä/ö/ü/ß at slot 1 of the moreKeys list),
digit at the end.**

| Key | hint (new) | alternates TARGET (de-de.json base + digit) |
|---|---|---|
| q w e r t z u i o p | 1 2 3 4 5 6 7 8 9 0 | as today + digit on the right; **e**: é è ê ë ę € **3** (our asset list stays — the deviation from stock-de `ė` is documented) |
| u | 7 | u ú ù û **+ ü** (at 1, per stock) **7** |
| o | 9 | o ó ò ô **+ ö** (at 1) **9** |
| a | 1 | a **+ ä** (at 1) à á â ã å **1** |
| s | — | s **+ ß** (at 1) ś š |
| . (new, footer) | — | . **,** **!** **?** (stock order `morekeys_period`: `, ? !` — we take `, ! ?` as named by the user) |
| dropped keys | — | ü(r0) ä(r1) ö(r1) ß(r2) — all four only as alternates anymore |

Caps convention: derive popup entries via the NFC uppercase form (Ä, ẞ …), do not
duplicate them in assets (rule 7 NFC; rule 8 avoid duplicate labels).

---

## 4. The other 14 languages = "what stock does" — with an AOSP reference per line

**Language → layout set** is evidenced by `java/res/values/donottranslate.xml`
(string-array `locale_and_extra_value_to_keyboard_layout_set_map`, line 81 ff.) and
`java/res/xml/method.xml` (ExtraValue `KeyboardLayoutSet=` per subtype). The **files
actually read** are in the right column.

| Our language | AOSP set (evidenced) | AOSP actual construction (read rowkeys files) | What our JSON does differently → consequence |
|---|---|---|---|
| en-us | qwerty (donottranslate) | `rowkeys_qwerty1_left5/_right5` + `_2` + `_3`; hints 1..0 | our r0 = 10 keys = identical; **change: hints + digits, ⌫→r0, period footer** |
| de-de | qwertz | §1.4 | §2/§3 (user's wish, itself stock-close) |
| cs-cz | **qwertz** (donottranslate: `cs:…qwertz`) | QWERTZ screen **without** its own special characters; ě š á í č é ú ů ý ř ž + ň ť ď **all as moreKeys** (`TEXTS_cs`: e→é ě è ê ë ę ė ē, s→š ß ś, z→ž ź ż, r→ř, d→ď, t→ť, n→ň ñ ń, y→ý ÿ, u→ú ů û ü ù ū) | our cs has 13 of its own keys (Σ16-r2!) → **remove all**, pull alternates to the TEXTS_cs level. Biggest rework, biggest gain |
| pl-pl | qwerty | QWERTY + moreKeys (`TEXTS_pl`: a→ą…, e→ę…, n→ń, c→ć, s→ś, z→ż ź… — **ń ź ć ś** are first-position entries), own keys: none | remove our 8 own keys (r0 ą ę, r1 ł ń ó, r2 ż ź ć ś); **our alternates are empty** → pull them to TEXTS_pl before removing, otherwise loss of function |
| es-es | **spanish** (donottranslate) | `rowkeys_spanish2` = qwerty2 + **one** extra key `keyspec_spanish_row2_10` = ñ (text table); r1 ends on ñ | our r1 ends on ñ = **identical to stock**; nothing to remove (ñ stays its own key — stock does it too!) |
| pt-pt | **qwerty** (method.xml ExtraValue) | pure QWERTY screen; á ã à õ ç é ê í ó ô ú **all as moreKeys** (`TEXTS_pt`) | our 5-row exotic build (12 own keys) → rebuild to QWERTY + moreKeys; the 5-row special construction dissolves with that |
| it-it | qwerty (donottranslate) | QWERTY; è é ù à ì ò only as moreKeys (`TEXTS_it`) | remove our 6 own keys |
| nl-nl | qwerty | QWERTY; moreKeys `TEXTS_nl` (u→ü…, y→ĳ) | no own keys today → only hints + footer |
| fr-fr | **azerty** (donottranslate) | `rowkeys_azerty1..3`; no accent keys; à â è é ù ç… all moreKeys (`TEXTS_fr`: a→à â %…, e→é è ê ë %…, c→ç %…) | remove our 6 own accent keys (é ê è ù à ç) — the azerty base stays |
| ru-ru | **east_slavic** | `rowkeys_east_slavic1..3` (11/12/10-key joystick rows, hints 1..0 on r1); **ё has no own key** — it is `morekeys_cyrillic_ie` = "ё" (TEXTS_ru:1030); ь with moreKeys; hard rows 11/12 | our rows 11/12/12(⇧+10+**ё**)→ remove the ё key, into the е popup; align the row sequence with east_slavic |
| el-gr | **greek** (method.xml ExtraValue) | `rowkeys_greek1..3`; r1 **9 keys** with hints [1][1][2][2][3]… (double assignment ;, and :, see dump); accents as large moreKeys lists (e.g. the έ row with an 8-16 entry) | our r0 has 9 keys = structurally congruent; change: hints + footer, accent alternates remain |
| sv-se | **nordic** | `rowkeys_nordic1` = qwerty1 + 11th key `keyspec_nordic_row1_11`=**å**; `rowkeys_nordic2` with `nordic_row2_10`=**ö**, `_11`=**ä** (+moreKeys ø/æ) — TEXTS_sv | our å ö ä sit exactly where stock has them → **structure remains**, only hints + footer |
| da-dk | nordic | ditto; TEXTS_da: row1_11=**å**, row2_10=**æ**, row2_11=**ø** | our å æ ø = stock positions → structure remains |
| fi-fi | nordic | ditto; TEXTS_fi: å / ö / ä + moreKeys ø, æ | like sv |
| tr-tr | **qwerty** (donottranslate: `tr:…qwerty`) | pure QWERTY screen; ğ ş ö ç ü ı **all moreKeys** (`TEXTS_tr`: i→**ı** î ï…, s→**ş** ß, c→ç, o→ö, u→ü, a→â); **no `rowkeys_turkish_q*` exists in HEAD 127336e** (correction to the task text — only `serbian_qwertz*` as a special case) | our 8 own keys (ğ ü ı ş ö ç + the i/ı pair) → everything is popups per stock; **the i/ı dispute from Rev 1 is thus decided: stock makes ı the first i alternate** |

**Key sentence:** Outside QWERTZ/Nordic/Spanish-QWERTY/Serbian, AOSP has **practically
no language-specific special keys in the letter screen** — almost everything runs via
`keyHintLabel` + `moreKeys` + `keyspec_<region>_rowN_M`. "Other languages = stock"
means for us: **delete our own special keys almost everywhere, adopt alternates from
`TEXTS_<locale>`** — and precisely because of that, the alternates may be entered
**before** deleting (no loss of function).

**Exceptions stock itself leaves standing:** es-es `ñ` (own r1 key), nordic å/ä/ö/æ/ø
(own keys), el-gr structure. We leave those standing.

---

## 5. Risks + limits (Rev-1 core, abridged; new: f/g)

a) **48-dp:** The rework enlarges cells (fewer keys per row;
`docs/DESIGN_STOCK_LOOK.md:72-74` permits narrow letter cells, the 48-dp top row
unaffected). Popup cells deliberately larger than keys (stock template).
b) **Caps alternates:** NFC uppercase form at runtime, not in assets (rule 7/8).
c) **Digit access:** no loss — `?123` keeps all digits (rule 9 even enforces it);
stock itself shows us that hint + long-press is the intended path.
d) **`‹ ›` removal:** loses direct cursor pushing; cheap fallbacks (only named):
swipe on the space key, double-tap a word. Editor long-press remains.
e) **New interaction:** popup code touches `KeyboardView` + possibly the window
height (strip above r0); micro assignment ≤ 4 files, test-first, logic purely
JVM-capable.
f) **Schema gap `hint`:** our `parseKey` allowlist knows no hint field → parser +
audit (`LayoutArtifactTest`) + schema documentation must follow (rule 4/4a: change
assets and parser together).
g) **Order in the data rework:** first extend `alternates` (from TEXTS_<locale>),
then remove special keys — never the other way around.

---

## 6. Implementing the user's decision (Rev-1 variants A/B/C are thereby obsolete)

The user has decided: **German = wish list, all others = AOSP stock.**
The Rev-1 question "popup vs. mini digit row" no longer arises: AOSP answers it with
**keyHintLabel + long-press** (§1.1) — exactly what we are replicating. Remaining open
points that the user/orchestrator should still confirm:

1. **⌫ next to P (German, deviation from stock):** in stock, ⌫ sits at the end of r2
   (`fillRight`); the user wants it top right. **Geometrically the space becomes free
   because in our de-de.json `ü` is the last key of r0 (`layers.base.rows[0]`, 11th key)
   and `ü` moves into the u popup per wish 4** — the freed r0 end slot takes ⌫
   (width 1.5, Σ r0 → 11.5); base↔shift equality is preserved, shift loses Ü
   analogously. The same mechanics apply mutatis mutandis to any language whose r0
   ends with a special key (cs? — no: cs r0 ends on p after the rework because ě š are
   dropped; tr ğ/ü, fr ê … analogous — after the TEXTS move, always the free end slot).
2. **de alternates: our list or TEXTS_de?** Our `de-de.json` deviates in details
   (e carries ę €, stock-de carries ė; our a list has no %). Proposal: **keep our list,
   insert ä/ö/ü/ß at the stock position at the front** — the user's wish names only
   the four reworks, not an exchange of the foreign accents.
3. **el-gr double hints** ([1] on two adjacent keys etc., §4) adopt 1:1 or unify —
   detail decision in the implementation assignment.

**Recommended order:** (1) schema `hint` + popup consumer (JVM tests),
(2) de-de asset to the target design §2/§3, (3) the 14 languages in two batches
(qwerty family, then azerty/east_slavic/greek/nordic) — each batch ≤ 4 files,
audit tests (rule 8/9, Σ geometry) as the gate.

---

## ASSUMPTION/DOUBT lines

- `EVIDENCED: clone HEAD 127336e (2025-02-26). The orchestrator listed
  rowkeys_turkish_q1..3 — these files do not exist in this HEAD (ls java/res/xml |
  grep -i turk = 0); 123 rowkeys files match.`
- `EVIDENCED: moreKeys references qwertz1 = 8 (w e r t z u i o), not 7.`
- `ASSUMPTION: the AOSP strip sorting (hint relative to moreKeys) follows from
  KeyboardTextsTable + the kbd_more_keys template; evidence the exact order in the
  code before implementing, not here.`
- `DOUBT (small): pt-pt on bare QWERTY + moreKeys = a rollback of our 5-row exotic
  build; the user's "stock" decision covers it, but it is the biggest single rework —
  its own micro assignment.`
