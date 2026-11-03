# AOSP row evidence for F24 (14 languages without de-de)

**Purpose:** Hard evidence of what AOSP LatinIME (`com.android.inputmethod.latin` — stock on
GrapheneOS, **not** Gboard) actually does per language: `keySpec` sequence, `keyHintLabel`
(digits), `additionalMoreKeys` (digit also in the popup ≙ our `LongPressMenu.withHint`),
`moreKeys` (special characters ≙ our `alternates`). **No line without evidence.**
Basis for `docs/DESIGN_KEY_MULTIPLEX.md` §4 and the F24 coder assignments.

## Method & sources (repeatable)

- Clone `https://android.googlesource.com/platform/packages/inputmethods/LatinIME` —
  used here: shallow clone at **HEAD `127336e` (2025-02-26, branch main)**.
  Individual file without a clone:
  `curl -s "https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/res/xml/<file>.xml?format=TEXT" | base6 -d`
- Attributes: `latin:keySpec` (NOT `keyLabel`), `latin:keyHintLabel`,
  `latin:additionalMoreKeys`, `latin:moreKeys`.
- **Layout resolution chain (measured as such in this HEAD):**
  1. `java/res/xml/method.xml`: subtype extra value `KeyboardLayoutSet=…` sets ONLY
     el→greek, pt_BR/pt_PT→qwerty, fr_BE→azerty, de_CH/fr_CH/it_CH→swiss, es_US/es_419→spanish.
  2. If the field is missing there (cs, da, es, fi, fr, it, nl, pl, ru, sv, tr, en_US, en_GB …),
     the fallback `sLocaleAndExtraValueToKeyboardLayoutSetMap`
     (`java/src/com/android/inputmethod/latin/utils/SubtypeLocaleUtils.java:329-346`)
     from the string array **`java/res/values/donottranslate.xml`
     (`locale_and_extra_value_to_keyboard_layout_set_map`)** applies.
  3. Result → `java/res/xml/rows_<set>.xml` → includes the `rowkeys_*.xml`.
- **Effective mapping of our 15 layout IDs** (donottranslate.xml, lines of the array):
  cs→**qwertz**, da/fi/sv→**nordic**, es→**spanish**, fr→**azerty**, ru→**east_slavic**,
  el→**greek**, en/it/nl/pl/pt/tr→**qwerty**. (de→qwertz, evidence in
  `docs/DESIGN_KEY_MULTIPLEX.md` / `docs/DESIGN_DEDE_ZIELBILD.md`.)
- **Two name corrections** versus earlier assumptions: `rowkeys_nordic_qwerty1.xml`
  and `rowkeys_turkish_q*.xml` **do not exist** (directory listing of `java/res/xml/`);
  correct: `rowkeys_nordic1/2.xml`, Turkish runs on plain `rowkeys_qwerty*`.
  `rowkeys_spanish1/3.xml` do not exist — spanish uses `rowkeys_qwerty1` +
  `rowkeys_spanish2` + `rowkeys_qwerty3`.
- **moreKeys contents:** `java/src/com/android/inputmethod/keyboard/internal/KeyboardTextsTable.java`
  (arrays `NAMES` from line 84, `TEXTS_DEFAULT` from line 267, per language `TEXTS_cs` line 895,
  `TEXTS_da` 973, `TEXTS_de` 1048, `TEXTS_el` 1116, `TEXTS_en` 1128, `TEXTS_es` 1326,
  `TEXTS_fi` 1690, `TEXTS_fr` 1746, `TEXTS_it` 2168, `TEXTS_nl` (after it), `TEXTS_pl`,
  `TEXTS_pt` 3017, `TEXTS_ru`, `TEXTS_sv`, `TEXTS_tr`; locale table from line 4161).
  % characters in popups (fr/… `%,`) is AOSP standard, but for us a rule-9/audit question —
  tracked as "open" like de-de.
- **Backspace (⌫): identical in ALL seven affected set files** —
  `rows_qwerty/qwertz/nordic/spanish/azerty/east_slavic/greek.xml`: row 3 starts with
  `shiftKeyStyle`, **ends with `deleteKeyStyle`** (line 49 in `rows_qwerty.xml`,
  analogously in the others), footer = `row_qwerty4.xml`. So on stock, ⌫ sits **at the
  end of the third letter row**, never at the top. (The de-de exception R8/target design
  remains unaffected by this.)
- **Digit hints:** in ALL sets, only the top letter row carries them, as
  `keyHintLabel="1".."0"` **plus** `additionalMoreKeys="1".."0"` — always both together,
  i.e. on stock the digit appears **also in the popup** (for us: `withHint=true`).
  Special-character special keys (å æ ø ñ щ …) and rows 2/3 **never** have hints.
  **Finding: none of the 14 layouts remains hint-free** — all seven sets above have 1..0.

---

## qwerty set (en-us, it-it, nl-nl, pl-pl, pt-pt, tr-tr)

Rows from `rows_qwerty.xml` = `rowkeys_qwerty1` (= `rowkeys_qwerty1_left5` +
`rowkeys_qwerty1_right5`) + `rowkeys_qwerty2` (= `rowkeys_qwerty2_left5` +
`rowkeys_qwerty2_right4`) + `rowkeys_qwerty3` (= `rowkeys_qwerty3_left4` +
`rowkeys_qwerty3_right3`, wrapped in row 3 with ⇧ before, ⌫ after) + `row_qwerty4`.

| Row | keySpec sequence (documented) | Hints / additionalMoreKeys |
|---|---|---|
| r0 | q w e r t y u i o p (10) | **Hints 1..0** + `additionalMoreKeys` **yes** (q with `morekeys_q`; p without moreKeys) |
| r1 | a s d f g h j k l (9) | no hints; moreKeys a.o. on a,d,g,h,j,k,l |
| r2 | z x c v b n m (7) — ⇧ before, **⌫** after | no hints |

### en-us — TEXTS_en (`KeyboardTextsTable.java:1128`)
| Key | moreKeys (position 1 bold) |
|---|---|
| a | **à** ,á,â,ä,æ,ã,å,ā |
| c | **ç** | e | **é** ,è,ê,ë,ē | i | **í** ,î,ï,ī,ì | n | **ñ** |
| o | **ó** ,ô,ö,ò,œ,ø,ō,õ | s | **ß** | u | **ú** ,û,ü,ù,ū |
**For us:** en-us is already exactly the qwerty rows without special keys → **nothing
changes about the keys**, only hints 1..0 + popups would be added per stock.

## it-it — qwerty + `TEXTS_it`
moreKeys: a **à** ,á,â,ä,…; e **è** ,é,ê,ë,…; i **ì** ,í,î,ï,…; o **ò** ,ó,ô,…; u **ù** ,ú,û,ü,ū.
**Changes for us:** our own keys `è é ù à ì ò` (r0×2, r1×3, r2×1) — stock would **not**
have them — migration candidates (à/è/ì/ò/ù each position 1; **é only position 2** for it).

## nl-nl — qwerty + `TEXTS_nl`
moreKeys: a **á** ,ä,…; e **é** ,ë,…; i **í** ,ï,…+**ĳ** (last); y **ĳ**; n **ñ** ,ń; o **ó** ,ö,…; u **ú** ,ü,….
**Changes for us:** nl has no special keys → like en: **keys stay**, only hints+popups.

## pl-pl — qwerty + `TEXTS_pl`
moreKeys: a **ą** ,á,…; c **ć** ,ç,č; e **ę** ,è,é,…; l **ł**; n **ń** ,ñ; o **ó** ,ö,…; s **ś** ,ß,š; z **ż** ,ź,ž.
**Changes for us:** 9 own special keys (r0 `ą ę`, r1 `ł ń ó`, r2 `ż ź ć ś`) — all six
Polish characters + ż/ź are delivered by stock **only as popups** (each position 1
except ź=2). Rebuild like de-de.

## pt-pt — qwerty + `TEXTS_pt` (line 3017)
moreKeys: a **á** ,ã,à,â,…; c **ç** ,č,ć; e **é** ,ê,è,…; i **í** ,î,…; o **ó** ,õ,ô,…; u **ú** ,ü,….
**Changes for us:** pt-pt is our **only 5-row set** with 12 own accent keys
(r0 `á â`, r1 `ç ã à`, r2 `õ`, r3 `é ê í ó ô ú`). Stock = flat qwerty (4 rows, no
special key). Rebuild = 5→4-row rebuild — the biggest F24 construction site, user
decision open.

## tr-tr — qwerty + `TEXTS_tr` (no `rowkeys_turkish_q*`, file existence checked)
moreKeys: a **â** ,ä,á; c **ç** ,ć,č; e **ə** ,é; g **ğ**; i **ı** ,î,ï,ì,í,į,ī; n **ň** ,ñ;
o **ö** ,ô,…; s **ş** ,ß,ś,š; u **ü** ,û,ù,ú,ū; y **ý**; z **ž**.
**Changes for us:** stock key sequence = plain qwerty; **ı is the first alternative of i**
(AOSP ranking), ğ/ş/ç/ö/ü all popup. Our keys `ğ ü ş i(8th) ö ç` + the `ı` direct key
would all be alternates. **Conflict remains open** (A19 decision tr i/ı): our
language requirement mandates an ı direct key, stock does not.

---

## qwertz set — cs-cz (`rows_qwertz.xml`: `rowkeys_qwertz1` + `rowkeys_qwerty2` + `rowkeys_qwertz3`)

| Row | keySpec (documented) | Hints |
|---|---|---|
| r0 | q w e r t z u i o p (10) | 1..0 yes + additionalMoreKeys yes (q/p without moreKeys) |
| r1 | a s d f g h j k l (9) | no |
| r2 | y x c v b n m (7) + ⇧/⌫ (wrapper) | no |
moreKeys `TEXTS_cs`: c **č** ,ç,ć; e **é** ,ě,è,ê,ë,ę,ė,ē (**ě only position 2!**);
n **ň** ,ñ,ń; r **ř**; s **š** ,ß,ś; t **ť**; u **ú** ,ů,…; y **ý** ,ÿ; z **ž** ,ź,ż; a **á** ,à,…; i **í** ,î,….
**Changes for us:** stock would run cs-cz as **QWERTZ** (y in r2!) — our cs-cz.json is
QWERTY with **8 own special-character keys** (ě š á í č é ú ů ý ř ž). Stock: ř/š/č/ž/ň/ť
each popup position 1, **ě and á/é/í/ú/ý/ů lower or on other positions**. Double change
(rows + special keys) → the biggest rebuild after pt-pt.

## azerty set — fr-fr (`rows_azerty.xml`: `rowkeys_azerty1/2/3`)

| Row | keySpec (documented) | Hints |
|---|---|---|
| r0 | a z e r t y u i o p (10) | 1..0 yes + additionalMoreKeys yes |
| r1 | q s d f g h j k l m (10) | no |
| r2 | w x c v b n **?** **'** (8) + ⇧/⌫ | no |
moreKeys `TEXTS_fr`: a **à** ,â,%…; c **ç** ,%…; e **é** ,è,ê,ë,%…; i **î** ,%ï…; o **ô** ,œ,%…; u **ù** ,û,%…; y **%** ,ÿ.
**Findings:** no ç/é/è/ù/à as direct key; r2 ends with `?` and `'` (apostrophe key with
morekeys_single_quote), **not** with ç/à. The `keyspec_swiss_*` placeholders in `TEXTS_fr`
belong to the swiss layout (de_CH/fr_CH/it_CH), not to azerty.
**Changes for us:** own keys `é ê` (r0), `è ù` (r1), `à ç` (r2) — all six would be
popups (é/à/ù/ç/î/ô position 1, è position 2, **ê only position 3** for fr).

## east_slavic set — ru-ru (`rows_east_slavic.xml`: `rowkeys_east_slavic1/2/3`)

| Row | keySpec (documented) | Hints |
|---|---|---|
| r0 | й ц у к е н г ш **щ** з **х** (11) | **1..0 on the first 10** (щ=9, з=0; х without hint) + additionalMoreKeys yes |
| r1 | ф **ы** в а п р о л д ж **э** (11) | no |
| r2 | я ч с м **и** т ь б ю (9) + ⇧/⌫ | no |
(Placeholders resolved via `TEXTS_ru`: `keyspec_east_slavic_row1_9`=щ, `row2_2`=ы, `row2_11`=э, `row3_5`=и.)
moreKeys `TEXTS_ru`: е→`morekeys_cyrillic_ie`= **ё**; ь→`morekeys_cyrillic_soft_sign`= **ъ**.
(Further moreKeys references (у к г а о …) point to table names that are **empty**
for ru.)
**Changes for us:** own direct keys `ъ` (r1 position 1!), `э` (r2), `ё` (r2 end) —
stock has **none** of them as keys: **ё is a popup on е, ъ is a popup on ь**; э/щ/ы are
real keys at stock positions (r1 end and r0-9/r1-2 respectively). Our r1 starts with ъ →
shift of the whole row versus stock. Note: `keyspec_east_slavic_row3_5`=и makes the
east_slavic set the **common basis of ru/uk/kk/be** — uk would insert і there;
only ru is relevant for us.

## greek set — el-gr (`rows_greek.xml`: `rowkeys_greek1/2/3`)

| Row | keySpec (base, switches resolved) | Hints |
|---|---|---|
| r0 | **;** ς ε ρ τ υ θ ι ο π (10) | **1..0** yes (1 on `;`, 2 on ς … 0 on π) + additionalMoreKeys yes |
| r1 | α σ δ φ γ η ξ κ λ (9) | no |
| r2 | ζ χ ψ ω β ν μ (7) + ⇧/⌫ | no |
(Switch in `rowkeys_greek1.xml`: base shows `;`, manually shifted `:` — moreKeys
swap + `·`. In `rowkeys_greek2/3.xml` the accent popups sit **inline** in the XML,
e.g. ε→έ,%,…; `TEXTS_el` contains only `keylabel_to_alpha=ΑΒΓ` — evidence that Greek
moreKeys are **not** in the language table.)
**Changes for us:** the el-gr sequence ς…π / α…λ / ζ…μ is **identical** to our
el-gr.json — **no special-key migration needed**; only the hints are missing. The AOSP
hint 1 lives on the `;` key, which we do not have → proposal: hint 1 is dropped and
ς..π carry 2..0 as on stock — **open, do not decide**, the stock model is the `;` key).

## nordic set — da-dk, fi-fi, sv-se (`rows_nordic.xml`: `rowkeys_nordic1` + `rowkeys_nordic2` + `rowkeys_qwerty3`)

`rowkeys_nordic1` = **include `rowkeys_qwerty1`** (q..p with hints 1..0) + one special
key `!text/keyspec_nordic_row1_11`; `rowkeys_nordic2` = **include `rowkeys_qwerty2`**
(a..l) + two special keys `row2_10`/`row2_11` each with their own moreKeys. r3 = qwerty3
(z x c v b n m, ⇧/⌫ wrapper). **Special keys have no hints** (hints remain q..p=1..0).
Placeholders per language (`TEXTS_da/fi/sv`) — the actual language difference:

| Language | r0-11 | r1-10 | r1-11 | moreKeys of the special keys |
|---|---|---|---|---|
| da | **å** | **æ** | **ø** | æ→ä, ø→ö (`morekeys_nordic_row2_10/11`) |
| fi | **å** | **ö** | **ä** | ö→ø, ä→æ |
| sv | **å** | **ö** | **ä** | ö→ø,œ, ä→æ |
Further moreKeys (excerpt, each position 1): da: a **å** …(å also on a!), o **ø** ,ö…, d **ð**,
l **ł**; fi: a **ä** ,å…, u **ü**, s **š** ,ß,ś; sv: a **ä** ,å…, o **ö** ,ø…, s **ś** ,š,ş,ß,
t **ť** ,þ, d **ð** ,ď, r **ř**.
**Changes for us:** da/fi/sv are **already almost on stock** (r0 q..p+å, r1 a..l+2, r2
z..m — fi/sv exactly; da: our r2 has ⇧2/⌫2 width instead of stock width, but the same
keys). Only hints 1..0 (+ additionalMoreKeys) on q..p and the popup contents are
missing. **No special-key migration** — å/æ/ø and å/ö/ä remain direct keys (stock model).

---

## spanish set — es-es (`rows_spanish.xml`: `rowkeys_qwerty1` + `rowkeys_spanish2` + `rowkeys_qwerty3`)

r0 = qwerty1 (hints 1..0 + additionalMoreKeys), r2 = qwerty3.
`rowkeys_spanish2` = include qwerty2 + special key `!text/keyspec_spanish_row2_10`;
resolution `TEXTS_DEFAULT` (line 351) = **ñ** (ca=ç, eo=ĉ — only es-relevant default).
moreKeys `TEXTS_es`: a **á** ,à,ä,…; c **ç** ,ć,č; e **é** ,è,ë,ê…; i **í** ,ï…; n **ñ** ,ń;
o **ó** ,ò,…; plus `morekeys_punctuation` with **¡ ¿**.
**Changes for us:** es-es.json = r0 q..p, r1 a..l+**ñ**, r2 z..m → **exactly stock**
(ñ is a real special key r1-10 on stock AND a popup on n). Only hints + popups are
missing.

---

## Summary: 14 rows × 3 columns (for F24 assignments, each ≤ 4 languages)

| Language | Letter rows (stock) | Digit hints (r0) | Special keys on stock (= stay) vs. additional for us (= popup candidates) |
|---|---|---|---|
| en-us | qwertyuio p / asdfghjkl / zxcvbnm | **yes 1..0** + popup | none / none → **only retrofit hints+popups** |
| nl-nl | like en | **yes 1..0** + popup | none / none → only hints+popups |
| it-it | like en | **yes 1..0** + popup | none / è é ù à ì ò (ours) → popup candidates |
| pt-pt | like en (4 rows!) | **yes 1..0** + popup | none / 12 accent keys + 5th row (ours) → **rebuild** |
| tr-tr | like en | **yes 1..0** + popup | none / ğ ü ş i ö ç + ı (ours) → all popups; ı=1st alt. of i (conflict open) |
| pl-pl | like en | **yes 1..0** + popup | none / ą ę ł ń ó ż ź ć ś (ours) → all popups (each 1st, ź=2nd) |
| cs-cz | **qwertz**: qwertyuiz…p / asdfghjkl / **y**xcvbnm | **yes 1..0** + popup | none / 8 special keys (ours) + QWERTY→QWERTZ shift; ě=2nd alt.! |
| da-dk | nordic: q..p+å / a..l+æ ø / z..m | **yes 1..0** + popup (special keys without) | å æ ø stay / none → hints+popups |
| fi-fi | nordic: q..p+å / a..l+ö ä / z..m | **yes 1..0** + popup | å ö ä stay / none → hints+popups |
| sv-se | like fi | **yes 1..0** + popup | å ö ä stay / none → hints+popups |
| es-es | q..p / a..l+**ñ** / z..m | **yes 1..0** + popup | ñ stays / none → hints+popups |
| fr-fr | azerty: azertyuiop / qsdfghjklm / wxcvbn**?'** | **yes 1..0** + popup | no accents / é ê è ù à ç (ours) → popups (ê=3rd!); r2 end `'` instead of ç |
| ru-ru | й…х(11) / ф…э(11) / я…ю(9) | **yes 1..0** + popup (щ=9 з=0, х without) | щ э ы are real keys / ъ э ё direct keys (ours) → ё on е, ъ on ь |
| el-gr | ; ς..π(10) / α..λ(9) / ζ..μ(7) | **yes 1..0** on ; ς..π | `;` key (we lack it) / none → only hints; hint assignment for ς open |

**Groups for coder assignments (each ≤ 4 languages, ≤ 4 files):**
A) en, nl (+ it) — only retrofit the hint field, no key movement.
B) da, fi, sv, es — hints+popups, special keys stay.
C) pl, tr, pt — special keys→popups (pt = rebuild 5→4).
D) cs (QWERTZ!), fr (r2 rebuild), ru (r1 shift), el (only hints, `;` question).

## Coverage

- **14 of 14 languages with real file evidence** (`rowkeys_*.xml` + mapping array +
  `KeyboardTextsTable.java` or inline moreKeys for el). **0 unobtainable.**
- AOSP files used: `java/res/xml/method.xml`, `java/res/values/donottranslate.xml`,
  `java/src/com/android/inputmethod/latin/utils/SubtypeLocaleUtils.java`,
  `java/src/com/android/inputmethod/keyboard/internal/KeyboardTextsTable.java`,
  `rows_qwerty|qwertz|azerty|nordic|spanish|east_slavic|greek.xml`,
  `rowkeys_qwerty1|qwerty1_left5|qwerty1_right5|qwerty2|qwerty2_left5|qwerty2_right4|qwerty3|qwerty3_left4|qwerty3_right3.xml`,
  `rowkeys_qwertz1|qwertz3.xml`, `rowkeys_azerty1|azerty2|azerty3.xml`,
  `rowkeys_nordic1|nordic2.xml`, `rowkeys_spanish2.xml`,
  `rowkeys_east_slavic1|2|3.xml`, `rowkeys_greek1|2|3.xml`, `row_qwerty4.xml`.
- Rule 9 respected: only key names, digits, and source paths — no user content.
