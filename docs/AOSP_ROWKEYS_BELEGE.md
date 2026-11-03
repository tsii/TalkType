# AOSP-Reihen-Belege für F24 (14 Sprachen ohne de-de)

**Zweck:** Harte Belege, was AOSP LatinIME (`com.android.inputmethod.latin` — Stock auf
GrapheneOS, **nicht** Gboard) pro Sprache wirklich tut: `keySpec`-Folge, `keyHintLabel`
(Ziffern), `additionalMoreKeys` (Ziffer auch im Popup ≙ unser `LongPressMenu.withHint`),
`moreKeys` (Sonderzeichen ≙ unsere `alternates`). **Ohne Beleg keine Zeile.**
Grundlage für `docs/DESIGN_KEY_MULTIPLEX.md` §4 und die F24-Coder-Aufträge.

## Methode & Quellen (wiederholbar)

- Clone `https://android.googlesource.com/platform/packages/inputmethods/LatinIME` —
  hier benutzt: flacher Clone bei **HEAD `127336e` (2025-02-26, Zweig main)**.
  Einzelne Datei ohne Clone:
  `curl -s "https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/res/xml/<datei>.xml?format=TEXT" | base6 -d`
- Attribute: `latin:keySpec` (NICHT `keyLabel`), `latin:keyHintLabel`,
  `latin:additionalMoreKeys`, `latin:moreKeys`.
- **Layout-Auflösungskette (in diesem HEAD so gemessen):**
  1. `java/res/xml/method.xml`: Subtype-ExtraValue `KeyboardLayoutSet=…` setzt NUR
     el→greek, pt_BR/pt_PT→qwerty, fr_BE→azerty, de_CH/fr_CH/it_CH→swiss, es_US/es_419→spanish.
  2. Fehlt dort das Feld (cs, da, es, fi, fr, it, nl, pl, ru, sv, tr, en_US, en_GB …),
     greift der Fallback `sLocaleAndExtraValueToKeyboardLayoutSetMap`
     (`java/src/com/android/inputmethod/latin/utils/SubtypeLocaleUtils.java:329-346`)
     aus dem String-Array **`java/res/values/donottranslate.xml`
     (`locale_and_extra_value_to_keyboard_layout_set_map`)**.
  3. Ergebnis → `java/res/xml/rows_<set>.xml` → inkludiert die `rowkeys_*.xml`.
- **Effektive Zuordnung unsrer 15 Layout-Ids** (donottranslate.xml, Zeilen des Arrays):
  cs→**qwertz**, da/fi/sv→**nordic**, es→**spanish**, fr→**azerty**, ru→**east_slavic**,
  el→**greek**, en/it/nl/pl/pt/tr→**qwerty**. (de→qwertz, Belege in
  `docs/DESIGN_KEY_MULTIPLEX.md` / `docs/DESIGN_DEDE_ZIELBILD.md`.)
- **Zwei Namens-Korrekturen** gegenüber früheren Annahmen: `rowkeys_nordic_qwerty1.xml`
  und `rowkeys_turkish_q*.xml` **existieren nicht** (Verzeichnisliste `java/res/xml/`);
  richtig: `rowkeys_nordic1/2.xml`, Türkisch läuft auf reinem `rowkeys_qwerty*`.
  `rowkeys_spanish1/3.xml` existieren nicht — spanish nutzt `rowkeys_qwerty1` +
  `rowkeys_spanish2` + `rowkeys_qwerty3`.
- **moreKeys-Inhalte:** `java/src/com/android/inputmethod/keyboard/internal/KeyboardTextsTable.java`
  (Arrays `NAMES` ab Z. 84, `TEXTS_DEFAULT` ab Z. 267, pro Sprache `TEXTS_cs` Z. 895,
  `TEXTS_da` 973, `TEXTS_de` 1048, `TEXTS_el` 1116, `TEXTS_en` 1128, `TEXTS_es` 1326,
  `TEXTS_fi` 1690, `TEXTS_fr` 1746, `TEXTS_it` 2168, `TEXTS_nl` (nach it), `TEXTS_pl`,
  `TEXTS_pt` 3017, `TEXTS_ru`, `TEXTS_sv`, `TEXTS_tr`; Locale-Tabelle ab Z. 4161).
  %-Zeichen in Popups (fr/… `%,`) ist AOSP-Standard, bei uns aber Regel-9-/Audit-Frage —
  wie de-de mit „offen" geführt.
- **Rückentaste (⌫): in ALLEN sieben betroffenen Satz-Dateien identisch** —
  `rows_qwerty/qwertz/nordic/spanish/azerty/east_slavic/greek.xml`: Reihe 3 beginnt mit
  `shiftKeyStyle`, **endet mit `deleteKeyStyle`** (Zeile 49 in `rows_qwerty.xml`, analog
  in den anderen), Fußzeile = `row_qwerty4.xml`. ⌫ steht also bei Stock **am Ende der
  dritten Buchstabenreihe**, nie oben. (de-de-Ausnahme R8/Zielbild bleibt davon unberührt.)
- **Ziffern-Hints:** trägt in ALLEN Sätzen **nur die obere Buchstabenreihe**, und zwar als
  `keyHintLabel="1".."0"` **plus** `additionalMoreKeys="1".."0"` — beides immer gemeinsam,
  d. h. die Ziffer erscheint bei Stock **auch im Popup** (für uns: `withHint=true`).
  Sonderzeichen-Sondertasten (å æ ø ñ щ …) und die Reihen 2/3 haben **nie** Hints.
  **Befund: keines der 14 Layouts bleibt hint-frei** — alle sieben Sätze oben haben 1..0.

---

## qwerty-Satz (en-us, it-it, nl-nl, pl-pl, pt-pt, tr-tr)

Reihen aus `rows_qwerty.xml` = `rowkeys_qwerty1` (= `rowkeys_qwerty1_left5` +
`rowkeys_qwerty1_right5`) + `rowkeys_qwerty2` (= `rowkeys_qwerty2_left5` +
`rowkeys_qwerty2_right4`) + `rowkeys_qwerty3` (= `rowkeys_qwerty3_left4` +
`rowkeys_qwerty3_right3`, in Reihe 3 gewickelt mit ⇧ davor, ⌫ dahinter) + `row_qwerty4`.

| Reihe | keySpec-Folge (belegt) | Hints / additionalMoreKeys |
|---|---|---|
| r0 | q w e r t y u i o p (10) | **Hints 1..0** + `additionalMoreKeys` **ja** (q mit `morekeys_q`; p ohne moreKeys) |
| r1 | a s d f g h j k l (9) | keine Hints; moreKeys u. a. auf a,d,g,h,j,k,l |
| r2 | z x c v b n m (7) — davor ⇧, dahinter **⌫** | keine Hints |

### en-us — TEXTS_en (`KeyboardTextsTable.java:1128`)
| Taste | moreKeys (Position 1 fett) |
|---|---|
| a | **à** ,á,â,ä,æ,ã,å,ā |
| c | **ç** | e | **é** ,è,ê,ë,ē | i | **í** ,î,ï,ī,ì | n | **ñ** |
| o | **ó** ,ô,ö,ò,œ,ø,ō,õ | s | **ß** | u | **ú** ,û,ü,ù,ū |
**Bei uns:** en-us ist bereits exakt die qwerty-Reihe ohne Sondertasten → ändert sich
**nichts an den Tasten**, nur Hints 1..0 + Popups kämen nach Stock dazu.

## it-it — qwerty + `TEXTS_it`
moreKeys: a **à** ,á,â,ä,…; e **è** ,é,ê,ë,…; i **ì** ,í,î,ï,…; o **ò** ,ó,ô,…; u **ù** ,ú,û,ü,ū.
**Bei uns ändern:** eigene Tasten `è é ù à ì ò` (r0×2, r1×3, r2×1) hätte Stock **nicht** —
Wanderungskandidaten (à/è/ì/ò/ù je Position 1; **é nur Position 2** bei it).

## nl-nl — qwerty + `TEXTS_nl`
moreKeys: a **á** ,ä,…; e **é** ,ë,…; i **í** ,ï,…+**ĳ** (letzte); y **ĳ**; n **ñ** ,ń; o **ó** ,ö,…; u **ú** ,ü,….
**Bei uns ändern:** nl hat keine Sondertasten → wie en: **Tasten bleiben**, nur Hints+Popups.

## pl-pl — qwerty + `TEXTS_pl`
moreKeys: a **ą** ,á,…; c **ć** ,ç,č; e **ę** ,è,é,…; l **ł**; n **ń** ,ñ; o **ó** ,ö,…; s **ś** ,ß,š; z **ż** ,ź,ž.
**Bei uns ändern:** 9 eigene Sondertasten (r0 `ą ę`, r1 `ł ń ó`, r2 `ż ź ć ś`) — alle sechs
Polen-Zeichen + ż/ź liefert Stock **nur als Popup** (je Position 1 außer ź=2). Umbau wie de-de.

## pt-pt — qwerty + `TEXTS_pt` (Z. 3017)
moreKeys: a **á** ,ã,à,â,…; c **ç** ,č,ć; e **é** ,ê,è,…; i **í** ,î,…; o **ó** ,õ,ô,…; u **ú** ,ü,….
**Bei uns ändern:** pt-pt ist unser **einziger 5-Zeilen-Satz** mit 12 eigenen Akzent-Tasten
(r0 `á â`, r1 `ç ã à`, r2 `õ`, r3 `é ê í ó ô ú`). Stock = flaches qwerty (4 Reihen, keine
Sondertaste). Umbau = 5→4-Zeilen-Rebuild — größte F24-Baustelle, Nutzerentscheidung offen.

## tr-tr — qwerty + `TEXTS_tr` (kein `rowkeys_turkish_q*`, Dateiexistenz geprüft)
moreKeys: a **â** ,ä,á; c **ç** ,ć,č; e **ə** ,é; g **ğ**; i **ı** ,î,ï,ì,í,į,ī; n **ň** ,ñ;
o **ö** ,ô,…; s **ş** ,ß,ś,š; u **ü** ,û,ù,ú,ū; y **ý**; z **ž**.
**Bei uns ändern:** Stock-Tastenfolge = reines qwerty; **ı ist erste Alternative von i**
(AOSP-Ranking), ğ/ş/ç/ö/ü alle Popup. Unsere Tasten `ğ ü ş i(8.) ö ç` + `ı`-Direkttaste
wären alle Alternativen. **Konflikt bleibt offen** (A19-Entscheidung tr i/ı): unsr
Sprachwunsch schreibt ı-Direkttaste fest, Stock nicht.

---

## qwertz-Satz — cs-cz (`rows_qwertz.xml`: `rowkeys_qwertz1` + `rowkeys_qwerty2` + `rowkeys_qwertz3`)

| Reihe | keySpec (belegt) | Hints |
|---|---|---|
| r0 | q w e r t z u i o p (10) | 1..0 ja + additionalMoreKeys ja (q/p ohne moreKeys) |
| r1 | a s d f g h j k l (9) | nein |
| r2 | y x c v b n m (7) + ⇧/⌫ (Wrapper) | nein |
moreKeys `TEXTS_cs`: c **č** ,ç,ć; e **é** ,ě,è,ê,ë,ę,ė,ē (**ě nur Position 2!**);
n **ň** ,ñ,ń; r **ř**; s **š** ,ß,ś; t **ť**; u **ú** ,ů,…; y **ý** ,ÿ; z **ž** ,ź,ż; a **á** ,à,…; i **í** ,î,….
**Bei uns ändern:** cs-cz hätte Stock als **QWERTZ** (y in r2!) — unser cs-cz.json ist
QWERTY mit **8 eigenen Sonderzeichen-Tasten** (ě š á í č é ú ů ý ř ž). Stock: ř/š/č/ž/ň/ť je
Popup-Position 1, **ě und á/é/í/ú/ý/ů tiefer bzw. auf anderen Positionen**. Doppel-Änderung
(Zeilen + Sondertasten) → größter Umbau nach pt-pt.

## azerty-Satz — fr-fr (`rows_azerty.xml`: `rowkeys_azerty1/2/3`)

| Reihe | keySpec (belegt) | Hints |
|---|---|---|
| r0 | a z e r t y u i o p (10) | 1..0 ja + additionalMoreKeys ja |
| r1 | q s d f g h j k l m (10) | nein |
| r2 | w x c v b n **?** **'** (8) + ⇧/⌫ | nein |
moreKeys `TEXTS_fr`: a **à** ,â,%…; c **ç** ,%…; e **é** ,è,ê,ë,%…; i **î** ,%ï…; o **ô** ,œ,%…; u **ù** ,û,%…; y **%** ,ÿ.
**Befunde:** kein ç/é/è/ù/à als Direkttaste; r2 endet mit `?` und `'` (Apostroph-Taste mit
morekeys_single_quote), **nicht** mit ç/à. Die `keyspec_swiss_*`-Platzhalter in `TEXTS_fr`
gehören zum swiss-Layout (de_CH/fr_CH/it_CH), nicht zu azerty.
**Bei uns ändern:** eigene Tasten `é ê` (r0), `è ù` (r1), `à ç` (r2) — alle sechs wären
Popups (é/à/ù/ç/î/ô Position 1, è Position 2, **ê nur Position 3** bei fr).

## east_slavic-Satz — ru-ru (`rows_east_slavic.xml`: `rowkeys_east_slavic1/2/3`)

| Reihe | keySpec (belegt) | Hints |
|---|---|---|
| r0 | й ц у к е н г ш **щ** з **х** (11) | **1..0 auf den ersten 10** (щ=9, з=0; х ohne Hint) + additionalMoreKeys ja |
| r1 | ф **ы** в а п р о л д ж **э** (11) | nein |
| r2 | я ч с м **и** т ь б ю (9) + ⇧/⌫ | nein |
(Platzhalter aufgelöst über `TEXTS_ru`: `keyspec_east_slavic_row1_9`=щ, `row2_2`=ы, `row2_11`=э, `row3_5`=и.)
moreKeys `TEXTS_ru`: е→`morekeys_cyrillic_ie`= **ё**; ь→`morekeys_cyrillic_soft_sign`= **ъ**.
(Weitere moreKeys-Referenzen (у к г а о …) verweisen auf Tabellen-Namen, die für ru
**leer** sind.)
**Bei uns ändern:** eigene Direkttasten `ъ` (r1-Position 1!), `э` (r2), `ё` (r2-Ende) —
Stock hat **keine** davon als Taste: **ё ist Popup auf е, ъ ist Popup auf ь**; э/щ/ы sind
echte Tasten an Stock-Positionen (r1-Ende bzw. r0-9/r1-2). Unser r1 beginnt mit ъ →
Versatz der ganzen Reihe gegenüber Stock. Hinweis: `keyspec_east_slavic_row3_5`=и macht
den east_slavic-Satz zur **gemeinsamen Basis von ru/uk/kk/be** — uk würde dort і einsetzen;
für uns nur ru relevant.

## greek-Satz — el-gr (`rows_greek.xml`: `rowkeys_greek1/2/3`)

| Reihe | keySpec (base, Switches aufgelöst) | Hints |
|---|---|---|
| r0 | **;** ς ε ρ τ υ θ ι ο π (10) | **1..0** ja (1 auf `;`, 2 auf ς … 0 auf π) + additionalMoreKeys ja |
| r1 | α σ δ φ γ η ξ κ λ (9) | nein |
| r2 | ζ χ ψ ω β ν μ (7) + ⇧/⌫ | nein |
(Switch in `rowkeys_greek1.xml`: base zeigt `;`, manuell geshiftet `:` — mehrKeys
gegeneinander + `·`. In `rowkeys_greek2/3.xml` liegen die Akzent-Popups **inline** im XML,
z. B. ε→έ,%,…; `TEXTS_el` enthält nur `keylabel_to_alpha=ΑΒΓ` — Beleg dafür, dass Greek
moreKeys **nicht** in der Sprach-Tabelle stehen.)
**Bei uns ändern:** el-gr-Folge ς…π / α…λ / ζ…μ ist **identisch** zu unserem el-gr.json —
**keine Sondertasten-Wanderung nötig**; es fehlen nur die Hints. AOSP-Hint 1 wohnt auf der
`;`-Taste, die wir nicht haben → Vorschlag: Hint 1 entfällt und ς..π tragen 2..0 wie bei
Stock — **offen, nicht entscheiden**, Stock-Vorbild ist die `;`-Taste).

## nordic-Satz — da-dk, fi-fi, sv-se (`rows_nordic.xml`: `rowkeys_nordic1` + `rowkeys_nordic2` + `rowkeys_qwerty3`)

`rowkeys_nordic1` = **include `rowkeys_qwerty1`** (q..p mit Hints 1..0) + eine Sondertaste
`!text/keyspec_nordic_row1_11`; `rowkeys_nordic2` = **include `rowkeys_qwerty2`**
(a..l) + zwei Sondertasten `row2_10`/`row2_11` mit je eigenem moreKeys. r3 = qwerty3
(z x c v b n m, ⇧/⌫-Wrapper). **Sondertasten haben keine Hints** (Hints bleiben q..p=1..0).
Platzhalter je Sprache (`TEXTS_da/fi/sv`) — der eigentliche Sprachunterschied:

| Sprache | r0-11 | r1-10 | r1-11 | moreKeys der Sondertasten |
|---|---|---|---|---|
| da | **å** | **æ** | **ø** | æ→ä, ø→ö (`morekeys_nordic_row2_10/11`) |
| fi | **å** | **ö** | **ä** | ö→ø, ä→æ |
| sv | **å** | **ö** | **ä** | ö→ø,œ, ä→æ |
Weitere moreKeys (Auszug, je Position 1): da: a **å** …(å auch auf a!), o **ø** ,ö…, d **ð**,
l **ł**; fi: a **ä** ,å…, u **ü**, s **š** ,ß,ś; sv: a **ä** ,å…, o **ö** ,ø…, s **ś** ,š,ş,ß,
t **ť** ,þ, d **ð** ,ď, r **ř**.
**Bei uns ändern:** da/fi/sv liegen **schon fast auf Stock** (r0 q..p+å, r1 a..l+2, r2 z..m
— fi/sv exakt, da: r2 hat bei uns ⇧2/⌫2-Breite statt Stock-Breite, aber gleiche Tasten).
Es fehlen nur Hints 1..0 (+ additionalMoreKeys) auf q..p und die Popup-Inhalte.
**Keine Sondertasten-Wanderung** — å/æ/ø bzw. å/ö/ä bleiben Direkttasten (Stock-Vorbild).

---

## spanisch-Satz — es-es (`rows_spanish.xml`: `rowkeys_qwerty1` + `rowkeys_spanish2` + `rowkeys_qwerty3`)

r0 = qwerty1 (Hints 1..0 + additionalMoreKeys), r2 = qwerty3.
`rowkeys_spanish2` = include qwerty2 + Sondertaste `!text/keyspec_spanish_row2_10`;
Auflösung `TEXTS_DEFAULT` (Z. 351) = **ñ** (ca=ç, eo=ĉ — nur esrelevant Default).
moreKeys `TEXTS_es`: a **á** ,à,ä,…; c **ç** ,ć,č; e **é** ,è,ë,ê…; i **í** ,ï…; n **ñ** ,ń;
o **ó** ,ò,…; plus `morekeys_punctuation` mit **¡ ¿**.
**Bei uns ändern:** es-es.json = r0 q..p, r1 a..l+**ñ**, r2 z..m → **exakt Stock**
(ñ ist bei Stock echte Sondertaste r1-10 UND Popup auf n). Es fehlen nur Hints + Popups.

---

## Kurzfassung: 14 Zeilen × 3 Spalten (für F24-Aufträge, je ≤ 4 Sprachen)

| Sprache | Buchstaben-Reihen (Stock) | Ziffern-Hints (r0) | Sondertasten bei Stock (= bleiben) vs. bei uns zusätzlich (= Popup-Kandidaten) |
|---|---|---|---|
| en-us | qwertyuio p / asdfghjkl / zxcvbnm | **ja 1..0** + Popup | keine / keine → **nur Hints+Popups nachrüsten** |
| nl-nl | wie en | **ja 1..0** + Popup | keine / keine → nur Hints+Popups |
| it-it | wie en | **ja 1..0** + Popup | keine / è é ù à ì ò (uns) → Popup-Kandidaten |
| pt-pt | wie en (4 Reihen!) | **ja 1..0** + Popup | keine / 12 Akzent-Tasten + 5. Reihe (uns) → **Rebuild** |
| tr-tr | wie en | **ja 1..0** + Popup | keine / ğ ü ş i ö ç + ı (uns) → alle Popup; ı=1. Alt. von i (Konflikt offen) |
| pl-pl | wie en | **ja 1..0** + Popup | keine / ą ę ł ń ó ż ź ć ś (uns) → alle Popup (je 1., ź=2.) |
| cs-cz | **qwertz**: qwertyuiz…p / asdfghjkl / **y**xcvbnm | **ja 1..0** + Popup | keine / 8 Sondertasten (uns) + QWERTY→QWERTZ-Versatz; ě=2. Alt.! |
| da-dk | nordic: q..p+å / a..l+æ ø / z..m | **ja 1..0** + Popup (Sondertasten ohne) | å æ ø bleiben / keine → Hints+Popups |
| fi-fi | nordic: q..p+å / a..l+ö ä / z..m | **ja 1..0** + Popup | å ö ä bleiben / keine → Hints+Popups |
| sv-se | wie fi | **ja 1..0** + Popup | å ö ä bleiben / keine → Hints+Popups |
| es-es | q..p / a..l+**ñ** / z..m | **ja 1..0** + Popup | ñ bleibt / keine → Hints+Popups |
| fr-fr | azerty: azertyuiop / qsdfghjklm / wxcvbn**?'** | **ja 1..0** + Popup | keine Akzente / é ê è ù à ç (uns) → Popup (ê=3.!); r2-Ende `'` statt ç |
| ru-ru | й…х(11) / ф…э(11) / я…ю(9) | **ja 1..0** + Popup (щ=9 з=0, х ohne) | щ э ы sind echte Tasten / ъ э ё-Direkttasten (uns) → ё auf е, ъ auf ь |
| el-gr | ; ς..π(10) / α..λ(9) / ζ..μ(7) | **ja 1..0** auf ; ς..π | `;`-Taste (uns fehlt sie) / keine → nur Hints; Hint-Zuordnung ς offen |

**Gruppen für Coder-Aufträge (je ≤ 4 Sprachen, ≤ 4 Dateien):**
A) en, nl (+ it) — nur hint-Feld nachziehen, keine Tastenbewegung.
B) da, fi, sv, es — Hints+Popups, Sondertasten bleiben.
C) pl, tr, pt — Sondertasten→Popups (pt = Rebuild 5→4).
D) cs (QWERTZ!), fr (r2-Umbau), ru (r1-Versatz), el (nur Hints, `;`-Frage).

## Abdeckung

- **14 von 14 Sprachen mit echtem Datei-Beleg** (`rowkeys_*.xml` + Zuordnungs-Array +
  `KeyboardTextsTable.java` bzw. Inline-moreKeys bei el). **0 nicht beschaffbar.**
- Verwendete AOSP-Dateien: `java/res/xml/method.xml`, `java/res/values/donottranslate.xml`,
  `java/src/com/android/inputmethod/latin/utils/SubtypeLocaleUtils.java`,
  `java/src/com/android/inputmethod/keyboard/internal/KeyboardTextsTable.java`,
  `rows_qwerty|qwertz|azerty|nordic|spanish|east_slavic|greek.xml`,
  `rowkeys_qwerty1|qwerty1_left5|qwerty1_right5|qwerty2|qwerty2_left5|qwerty2_right4|qwerty3|qwerty3_left4|qwerty3_right3.xml`,
  `rowkeys_qwertz1|qwertz3.xml`, `rowkeys_azerty1|azerty2|azerty3.xml`,
  `rowkeys_nordic1|nordic2.xml`, `rowkeys_spanish2.xml`,
  `rowkeys_east_slavic1|2|3.xml`, `rowkeys_greek1|2|3.xml`, `row_qwerty4.xml`.
- Regel 9 eingehalten: nur Tastennamen, Ziffern und Quellpfade — keine Nutzerinhalte.
