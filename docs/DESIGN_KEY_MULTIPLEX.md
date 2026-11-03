# A19 (Rev 2) — Tasten-Multiplex: Deutsch = Nutzerwunsch, alle anderen = AOSP-Stock

**Stand:** 2026-10-30, 20:4x · **Rolle:** Beratung/Doku — kein Code, kein Build.
**Entscheidung des Nutzers (20:30, zwischenzeitlich):**
- **Deutsch:** exakt seine Wunschliste — `.` statt `‹ ›` in der Fußzeile, Ziffern auf der
  oberen Buchstabenreihe, `.` trägt `, ! ?`, Umlaute/ß **keine** eigenen Tasten (hinter
  a/o/u/s), Rückentaste oben rechts neben `P`.
- **Alle anderen 14 Sprachen:** exakt das, was Stock Android macht.
- **„Stock" = AOSP LatinIME** (`com.android.inputmethod.latin`), **nicht Gboard**. Rev 1
  leitete teils aus Gboard-Gedächtnis ab — Rev 2 ersetzt das durch Belege.

**Belegquelle AOSP:** shallow clone `platform/packages/inputmethods/LatinIME`,
HEAD `127336e` (2025-02-26, Zweig `main`). Alle Zitate: `java/res/xml/…`,
`java/res/values/…`, `java/src/…`. Eigene Layouts: `app/src/main/assets/layouts/*.json`.
Regel 9: keine Inhalte, keine Pfade außer Projekt-/Quelldateinamen.

---

## 1. Stock-Vorlage = AOSP LatinIME (belegt, Datei für Datei)

**1.1 Ziffern sind bei Stock IMMER auf der oberen Reihe — als `keyHintLabel`.**
`rowkeys_qwertz1.xml`: die zehn Tasten q..p tragen `keyHintLabel="1"…"0"`
(q=1 w=2 e=3 r=4 t=5 z=6 u=7 i=8 o=9 p=0). Dasselbe gilt qwerty
(`rowkeys_qwerty1_left5/_right5`: hints 1..0), azerty
(`rowkeys_azerty1`: a z e r t y u i o p mit 1..0), east_slavic
(`rowkeys_east_slavic1`: hints 1..0) und greek (`rowkeys_greek1`).
**Korrektur am Auftragstext:** `moreKeys`-Referenzen in `rowkeys_qwertz1.xml` sind es
**acht** (w e r t **z** u i o → `!text/morekeys_*`), q und p tragen keine — der
Orchestrator zählte z nicht.

**1.2 `keyHintLabel` ≠ `moreKeys` — zwei verschiedene Dinge, zusammen = der Nutzerwunsch.**
- `keyHintLabel` = die **kleine Ziffer, die permanent auf der Taste steht** (Sichtbarkeit:
  „hier wohnt die 3").
- `moreKeys` = **Inhalt des Langdruck-Streifens** (`!text/morekeys_X`). Der Streifen selbst
  ist ein eigenes Mini-Layout: `kbd_more_keys_keyboard_template.xml` (+
  `key_styles_navigate_more_keys.xml` fürs Ziehen/Draufloslassen).
- Zusammenspiel (sichtbares Verhalten): Taste zeigt Buchstabe groß + Ziffer klein; Langdruck
  öffnet den Streifen; Ziffer und Alternates sind darin erreichbar; Loslassen auf der
  Grundtaste = Grundzeichen. Die genaue Sortierung hint-vs-moreKeys im Streifen ist im
  Code (`MoreKeysList`-Aufbau) und wird erst im Implementierungsauftrag Zeile für Zeile
  belegt — hier nicht behauptet.

**1.3 Der Popup-Inhalt wohnt NICHT in strings-Dateien.** `values-de/strings.xml` (23 KB,
geprüft) enthält nur Settings-Texte. Die `!text/morekeys_*`-Auflösung ist eine Java-Tabelle:
**`java/src/com/android/inputmethod/keyboard/internal/KeyboardTextsTable.java`** —
`TEXTS_DEFAULT` plus je Sprache ein `TEXTS_<locale>`-Feld; `null`/leer = „kein Popup".
Die **echten deutschen Listen** (`TEXTS_de`, Zeilen 1048-1101):

| Taste | moreKeys (echt, TEXTS_de) | Hinweis |
|---|---|---|
| a | **ä** % â à á æ ã å ā | ä steht bei Stock **an Position 1** — genau der Nutzerwunsch |
| o | **ö** % ô ò ó õ œ ø ō | ö an 1 |
| e | é è ê ë ė | **kein ę, kein €** (unsere de-de.json hat ę € — Abweichung UNSERSEITS) |
| u | **ü** % û ù ú ū | ü an 1 |
| s | **ß** ś š | ß an 1 |
| n | ñ ń | |
| i, c, r, t, w, z, y | (null → Default EMPTY) | bei Stock-de ohne Popup |

Das `%` in a/o/u ist ein echtes Prozentzeichen als Popup-Eintrag.
Vergleich en (`TEXTS_en`): mehrkeys_s = „ß" (auch Englisch trägt ß im s-Popup).
Fußzeile: `morekeys_period` (Default) = `, ? ! # ) ( / ; ' @ : - " + % &` — die
**Punkt-Taste trägt bei Stock an 1/2/3 exakt `, ? !`** — der Nutzerwunsch
(„Punkt trägt Komma/Ausrufezeichen/Fragezeichen") ist wörtlich Stock-Verhalten.

**1.4 QWERTZ hat keine ä/ö/ü/ß-Tasten.** `rows_qwertz.xml` = `rowkeys_qwertz1` (10 Tasten,
hints) + `rowkeys_qwerty2` (a..l, 9 Tasten) + `rowkeys_qwertz3` (⇧ 15%p, y x c v b n m,
⌫ `fillRight`) + `row_qwerty4`. Zeilen `keyWidth 10%p`. **Unsere de-de.json mit ihren vier
Sondertasten ist die Abweichung von Stock, nicht umgekehrt.**

**1.5 Stock-Fußzeile ohne Pfeiltasten.** `row_qwerty4.xml` baut aus
`key_comma + key_space_5kw + key_period` (+ Shift-/Enter-Endstücke): Komma und Punkt sind
bei Stock **eigene Fußzeilentasten neben der Leertaste**, Cursor-`‹ ›` gibt es nicht.
Der Nutzerwunsch (Punkt in die Fußzeile, keine Pfeile) ist ebenfalls Stock-Form.

**1.6 Was wir davon 1:1 nachbauen können.** Das Datenmodell: `alternates` (unser Pendant zu
moreKeys) existiert in Schema+Parser bereits, hat aber **keinen Verbraucher**; ein
`hintLabel`-Pendant existiert **nicht** — für die sichtbare kleine Ziffer braucht unser
Schema ein neues Feld (z. B. `hint`) in der Parser-Allowlist
(`layouts/LayoutParser.kt:100` = `id,label,width,kind,value,caps,alternates`) plus Audit-
Regeln. Der Langdruck-Code (Timer + Streifen + Ziehen) ist neu (§5e). **Nicht** nachbauen:
Wort-Gleiteingabe (eigene Schicht, nicht gewünscht).

---

## 2. Zeilen-Bild Vor/Nach — de-de (unverändert aus Rev 1; Σ je Zeile gemessen)

IST (`de-de.json layers.base`, Σ 11/11/11/13; shift identisch):

```
r0 |q|w|e|r|t|z|u|i|o|p|ü|                         Σ 11
r1  |a|s|d|f|g|h|j|k|l|ö|ä|                        Σ 11
r2  |⇧1.5|y|x|c|v|b|n|m|ß|⌫1.5|                    Σ 11
r3    |?123 1.5|🌐|😊|  Leer 5  |‹|›|🎤|⏎1.5|       Σ 13
```

SOLL (Nutzerwunsch; ⌫ dorthin, wo heute `ü` steht — Begründung §6):

```
r0 |q|w|e|r|t|z|u|i|o|p|⌫1.5|                      Σ 11.5
r1  |a|s|d|f|g|h|j|k|l|                            Σ 9
r2  |⇧2|y|x|c|v|b|n|m|                              Σ 9
r3    |?123 1.5|🌐|😊|  Leer 5  |.|🎤|⏎1.5|         Σ 12
```

Parser-Geometrie vergleicht **nur paarweise base↔shift / symbols↔symbolsShift**
(`docs/LAYOUT_SCHEMA_REF.md` „Zeilen-Geometrie"); Zeilen-Σ 9…13 koexistieren bereits.
`?123`-Seite bleibt unangetastet (LAYOUT_REQUIREMENTS **Regel 9**: Ziffern und
Pflichtzeichen `. , ? ! …` bleiben dort eigene Tasten; Multiplex ist Zusatz).

---

## 3. de-de Multibelegungs-Matrix (de-de.json als Basis, Stock-Reihenfolge als Vorbild)

Popup-Reihenfolge wie bei Stock: **Grundzeichen vorausgewählt links, sprachnahe Alternates
direkt dahinter (Stock setzt ä/ö/ü/ß auf Platz 1 der moreKeys-Liste), Ziffer am Ende.**

| Taste | hint (neu) | alternates SOLL (de-de.json Basis + Ziffer) |
|---|---|---|
| q w e r t z u i o p | 1 2 3 4 5 6 7 8 9 0 | wie heute + Ziffer rechts; **e**: é è ê ë ę € **3** (unsere Asset-Liste bleibt — Abweichung von Stock-de `ė` ist dokumentiert) |
| u | 7 | u ú ù û **+ ü** (an 1, nach Stock) **7** |
| o | 9 | o ó ò ô **+ ö** (an 1) **9** |
| a | 1 | a **+ ä** (an 1) à á â ã å **1** |
| s | — | s **+ ß** (an 1) ś š |
| . (neu, Fußzeile) | — | . **,** **!** **?** (Stock-Reihenfolge `morekeys_period`: `, ? !` — wir nehmen `, ! ?` wie vom Nutzer genannt) |
| entfallene Tasten | — | ü(r0) ä(r1) ö(r1) ß(r2) — alle vier nur noch als Alternates |

Konvention Caps: Popup-Einträge per NFC-Großform ableiten (Ä, ẞ …), nicht in Assets
duplizieren (Regel 7 NFC; Regel 8 doppelte Labels vermeiden).

---

## 4. Die anderen 14 Sprachen = „was Stock macht" — mit AOSP-Referenz je Zeile

**Sprache → Layout-Set** ist belegt durch `java/res/values/donottranslate.xml`
(string-array `locale_and_extra_value_to_keyboard_layout_set_map`, Zeile 81 ff.) und
`java/res/xml/method.xml` (ExtraValue `KeyboardLayoutSet=` je Subtyp). **Real gelesene
Dateien** stehen in der rechten Spalte.

| Unsere Sprache | AOSP-Set (belegt) | AOSP-Realbau (gelesene rowkeys-Dateien) | Was unsere JSON anders macht → Folge |
|---|---|---|---|
| en-us | qwerty (donottranslate) | `rowkeys_qwerty1_left5/_right5` + `_2` + `_3`; hints 1..0 | unsere r0 = 10 Tasten = identisch; **Änderung: hints + Ziffern, ⌫→r0, Punkt-Fußzeile** |
| de-de | qwertz | §1.4 | §2/§3 (Nutzerwunsch, selbst Stock-nah) |
| cs-cz | **qwertz** (donottranslate: `cs:…qwertz`) | QWERTZ-Schirm **ohne** eigene Sonderzeichen; ě š á í č é ú ů ý ř ž + ň ť ď **alle als moreKeys** (`TEXTS_cs`: e→é ě è ê ë ę ė ē, s→š ß ś, z→ž ź ż, r→ř, d→ď, t→ť, n→ň ñ ń, y→ý ÿ, u→ú ů û ü ù ū) | unsere cs hat 13 eigene Tasten (Σ16-r2!) → **alle entfernen**, alternates auf TEXTS_cs-Stand ziehen. Größter Umbau, größter Zugewinn |
| pl-pl | qwerty | QWERTY + moreKeys (`TEXTS_pl`: a→ą…, e→ę…, n→ń, c→ć, s→ś, z→ż ź… — **ń ź ć ś** sind first-position-Einträge), eigene Tasten: keine | unsere 8 eigenen Tasten (r0 ą ę, r1 ł ń ó, r2 ż ź ć ś) entfernen; **unsere alternates sind leer** → vor dem Entfernen auf TEXTS_pl ziehen, sonst Funktionsverlust |
| es-es | **spanish** (donottranslate) | `rowkeys_spanish2` = qwerty2 + **eine** Extrataste `keyspec_spanish_row2_10` = ñ (Texttabelle); r1 endet auf ñ | unsere r1 endet auf ñ = **identisch zu Stock**; nichts zu entfernen (ñ bleibt eigene Taste — Stock tut es auch!) |
| pt-pt | **qwerty** (method.xml ExtraValue) | reiner QWERTY-Schirm; á ã à õ ç é ê í ó ô ú **alle als moreKeys** (`TEXTS_pt`) | unsere 5-Zeilen-Exotik (12 eigene Tasten) → auf QWERTY + moreKeys zurückbauen; der 5-Zeilen-Sonderbau löst sich damit auf |
| it-it | qwerty (donottranslate) | QWERTY; è é ù à ì ò nur als moreKeys (`TEXTS_it`) | unsere 6 eigenen Tasten entfernen |
| nl-nl | qwerty | QWERTY; moreKeys `TEXTS_nl` (u→ü…, y→ĳ) | keine eigenen Tasten heute → nur hints + Fußzeile |
| fr-fr | **azerty** (donottranslate) | `rowkeys_azerty1..3`; keine Accento-Tasten; à â è é ù ç… alle moreKeys (`TEXTS_fr`: a→à â %…, e→é è ê ë %…, c→ç %…) | unsere 6 eigenen Accento-Tasten (é ê è ù à ç) entfernen — azerty-Basis bleibt |
| ru-ru | **east_slavic** | `rowkeys_east_slavic1..3` (11/12/10er-Joystick-Zeilen, hints 1..0 auf r1); **ё hat keine eigene Taste** — sie ist `morekeys_cyrillic_ie` = „ё" (TEXTS_ru:1030); ь mit moreKeys; harte Zeilen 11/12 | unsere Zeilen 11/12/12(⇧+10+**ё**)→ ё-Taste entfernen, ins е-Popup; Zeilenfolge an east_slavic angleichen |
| el-gr | **greek** (method.xml ExtraValue) | `rowkeys_greek1..3`; r1 **9 Tasten** mit hints [1][1][2][2][3]… (Doppelbelegung ;, und :, siehe Dump); Akzente als große moreKeys-Listen (z. B. έ-Zeile mit 8-16 Entry) | unsere r0 hat 9 Tasten = strukturell deckungsgleich; Änderung: hints + Fußzeile, Akzent-alternates bleiben |
| sv-se | **nordic** | `rowkeys_nordic1` = qwerty1 + 11. Taste `keyspec_nordic_row1_11`=**å**; `rowkeys_nordic2` mit `nordic_row2_10`=**ö**, `_11`=**ä** (+moreKeys ø/æ) — TEXTS_sv | unsere å ö ä stehen genau da, wo Stock sie hat → **Struktur bleibt**, nur hints + Fußzeile |
| da-dk | nordic | dito; TEXTS_da: row1_11=**å**, row2_10=**æ**, row2_11=**ø** | unsere å æ ø = Stock-Positionen → Struktur bleibt |
| fi-fi | nordic | dito; TEXTS_fi: å / ö / ä + moreKeys ø, æ | wie sv |
| tr-tr | **qwerty** (donottranslate: `tr:…qwerty`) | reiner QWERTY-Schirm; ğ ş ö ç ü ı **alle moreKeys** (`TEXTS_tr`: i→**ı** î ï…, s→**ş** ß, c→ç, o→ö, u→ü, a→â); **ein `rowkeys_turkish_q*` existiert im HEAD 127336e nicht** (Korrektur am Auftragstext — nur `serbian_qwertz*` als Sonderfall) | unsere 8 eigenen Tasten (ğ ü ı ş ö ç + das i/ı-Paar) → nach Stock alles Popups; **der i/ı-Streit aus Rev 1 ist damit entscheiden: Stock macht ı zum ersten i-Alternate** |

**Kernsatz:** In AOSP gibt es außerhalb von QWERTZ/Nordic/Spanisch-QWERTY/Serbian **praktisch
keine sprach-spezifischen Sondertasten im Buchstabenschirm** — fast alles läuft über
`keyHintLabel` + `moreKeys` + `keyspec_<region>_rowN_M`. „Andere Sprachen = Stock" heißt für
uns: **eigene Sondertasten fast überall löschen, Alternates aus `TEXTS_<locale>` übernehmen**
— und genau deshalb dürfen die Alternates **vor** dem Löschen eingetragen werden (kein
Funktionsverlust).

**Ausnahmen, die Stock selbst stehen lässt:** es-es `ñ` (eigene r1-Taste), nordic å/ä/ö/æ/ø
(eigene Tasten), el-gr Struktur. Diese lassen wir stehen.

---

## 5. Risiken + Grenzen (Rev-1-Kern, verkürzt; neu: f/g)

a) **48-dp:** Der Umbau vergrößert Zellen (weniger Tasten pro Zeile;
`docs/DESIGN_STOCK_LOOK.md:72-74` erlaubt schmale Buchstabenzellen, 48-dp-Oberleiste
unberührt). Popup-Zellen bewusst größer als Tasten (Stock-Template).
b) **Caps-Alternates:** NFC-Großform zur Laufzeit, nicht in Assets (Regel 7/8).
c) **Ziffern-Zugang:** kein Verlust — `?123` behält alle Ziffern (Regel 9 erzwingt es sogar);
Stock selbst zeigt uns, dass hint+Langdruck der vorgesehene Weg ist.
d) **`‹ ›`-Wegfall:** verliert direktes Cursor-Schieben; billige Notausgänge (nur genannt):
Leertaste wischen, Wort-Doppeltipp. Editor-Longpress bleibt.
e) **Neue Interaktion:** Popup-Code berührt `KeyboardView` + ggf. Fensterhöhe (Streifen über
r0); Micro-Auftrag ≤ 4 Dateien, test-first, Logik rein JVM-tauglich.
f) **Schema-Lücke `hint`:** unser `parseKey`-Allowlist kennt kein Hinweis-Feld →
Parser + Audit (`LayoutArtifactTest`) + Schema-Doku müssen mitziehen (Regel 4/4a:
Assets und Parser zusammen ändern).
g) **Reihenfolge im Daten-umbau:** erst `alternates` ergänzen (aus TEXTS_<locale>), dann
Sondertasten entfernen — niemals umgekehrt.

---

## 6. Umsetzung des Nutzerentscheids (Rev-1-Varianten A/B/C sind damit überholt)

Der Nutzer hat entschieden: **Deutsch = Wunschliste, alle anderen = AOSP-Stock.**
Die Rev-1-Frage „Popup vs. Mini-Ziffernreihe" stellt sich nicht mehr: AOSP beantwortet sie
mit **keyHintLabel + Langdruck** (§1.1) — genau das bauen wir nach. Offene Restpunkte, die
der Nutzer/Orchestrator noch bestätigen sollte:

1. **⌫ neben P (Deutsch, Abweichung von Stock):** bei Stock sitzt ⌫ am Ende von r2
   (`fillRight`); der Nutzer will ihn oben rechts. **Geometrisch wird der Platz frei, weil
   in unserer de-de.json `ü` die letzte Taste von r0 ist (`layers.base.rows[0]`, 11. Taste)
   und `ü` per Wunsch 4 ins u-Popup wandert** — der frei werdende r0-Endplatz nimmt ⌫
   (width 1.5, Σ r0 → 11.5) auf; base↔shift-Gleichheit bleibt gewahrt, shift verliert Ü
   analog. Dieselbe Mechanik gilt sinngemäß für jede Sprache, deren r0 mit einer
   Sondertaste endet (cs? — nein: cs r0 endet nach Umbau auf p, weil ě š wegfallen; tr ğ/ü,
   fr ê … analog — nach TEXTS-Umzug immer der freie Endplatz).
2. **de-Alternates: unsere Liste oder TEXTS_de?** Unsere `de-de.json` weicht in Details ab
   (e trägt ę €, Stock-de trägt ė; unsere a-Liste hat kein %). Vorschlag: **unsere Liste
   behalten, ä/ö/ü/ß nach Stock-Position vorn einfügen** — der Nutzerwunsch nennt nur die
   vier Umbauten, keinen Austausch der Fremd-Akzente.
3. **el-gr Doppel-hints** ([1] auf zwei benachbarten Tasten usw., §4) 1:1 übernehmen oder
   vereinheitlichen — Detailentscheid im Implementierungsauftrag.

**Empfehlung der Reihenfolge:** (1) Schema `hint` + Popup-Verbraucher (JVM-Tests),
(2) de-de-Asset auf Zielbild §2/§3, (3) die 14 Sprachen in zwei Batches
(qwerty-Familie, dann azerty/east_slavic/greek/nordic) — jeder Batch ≤ 4 Dateien,
Audit-Tests (Regel 8/9, Σ-Geometrie) als Gate.

---

## ANNAHME/Zweifel-Zeilen

- `BELEGT: clone-HEAD 127336e (2025-02-26). Der Orchestrator-listete rowkeys_turkish_q1..3
  — diese Dateien existieren in diesem HEAD nicht (ls java/res/xml | grep -i turk = 0);
  123 rowkeys-Dateien stimmen überein.`
- `BELEGT: mehrKeys-Referenzen qwertz1 = 8 (w e r t z u i o), nicht 7.`
- `ANNAHME: AOSP-Streifen-Sortierung (hint relativ zu moreKeys) folgt aus
  KeyboardTextsTable + kbd_more_keys-Template; exakte Reihenfolge vor Implementierung im
  Code belegen, nicht hier.`
- `ZWEIFEL (klein): pt-pt auf nackten QWERTY + moreKeys = Rückbau unserer 5-Zeilen-Exotik;
  Nutzerentscheid „Stock" deckt das ab, aber es ist der größte Einzelumbau — eigener
  Micro-Auftrag.`
