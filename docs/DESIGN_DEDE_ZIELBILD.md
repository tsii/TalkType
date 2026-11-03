# A19-K — de-de-Zielbild: Daten-Vorlage für F23 (Hint-Ziffern, Umlaute als Alternates, Punkt-Fußzeile, ⌫ oben)

**Stand:** 2026-10-30 · **Rolle:** Doku/Daten-Vorlage — kein Code, kein Build, kein Commit.
**Zweck:** exakte Ziel-Daten für `app/src/main/assets/layouts/de-de.json`, damit ein Coder
F23 ohne Rückfragen umsetzen kann. Entscheidungen des Nutzers (20:30): q..p tragen Hints
1..0; **keine** ü/ö/ä/ß-Eigenschlüsseltasten (→ Alternates von u/o/a/s); Punkt statt `‹ ›`
(neben der Leertaste, trägt `, ! ?`); Rückentaste ans Ende von r0 neben `P`.

**Parser-Iststand (belegt):** `layouts/LayoutAsset.kt:13-21` — `LayoutKey` kennt exakt
`id, label, width, kind(text|action|dead), value, caps?, alternates`. Ein `hint`-Feld gibt es
**nicht** (grep `hint` in `layouts/*.kt` = 0 Treffer). **Aber der Popup-Verbraucher existiert
schon (F22/D12):** `keyboard/LongPressMenu.kt` + `KeyboardView.kt` (Zeichnung ab `:372`,
Trefferzone, `cellCount` deckelt auf **8** Zellen; `glyphs = alternatesOf(key)` `:385`).
F23a braucht also nur das **hint-Feld** (Modell + Parser + Zeichnung), keinen Popup von Grund auf.

AOSP-Belegquelle wie in A19 Rev 2: Clone `platform/packages/inputmethods/LatinIME`
HEAD `127336e`; Zitate unten als Dateiname + Attribut.

---

## 1. Tastenfolge VORHER / NACHHER (de-de.json, rows-Indizes wie im Asset)

Breiten in vorhandenen Einheiten; **base und shift immer paarweise** (Geometrie-Regel
`docs/LAYOUT_SCHEMA_REF.md`). Nicht genannte Tasten = unverändert.

### r0 — base

VORHER: `q w e r t z u i o p ü`  (Σ 11; `ue` = id `ue`, value `ü`, width 1)

NACHHER (10 Buchstaben + ⌫; `ue` entfällt):

| id | value | hint | alternates (Ziel) | width | Änderung |
|---|---|---|---|---|---|
| q | q | "1" | — | 1 | + hint |
| w | w | "2" | — | 1 | + hint |
| e | e | "3" | `é è ê ë ę €` (IST bleibt) | 1 | + hint; **offen — siehe §2, Z4** |
| r | r | "4" | — | 1 | + hint |
| t | t | "5" | — | 1 | + hint |
| z | z | "6" | — | 1 | + hint |
| u | u | "7" | **`ü`** `ú ù û` | 1 | + hint; **ü NEU an 1** |
| i | i | "8" | `í ì î ï` (IST bleibt) | 1 | + hint |
| o | o | "9" | **`ö`** `ó ò ô` | 1 | + hint; **ö NEU an 1** |
| p | p | "0" | — | 1 | + hint |
| backspace | backspace | — | — | **1.5** | **von r2 hierher verschoben** |

Σ r0: 10×1 + 1.5 = **11.5** (shift analog 11.5 → Paarregel ok).

### r1 — base

VORHER: `a s d f g h j k l ö ä` (Σ 11; ids `oe`, `ae`)

NACHHER (9 Tasten; `oe`/`ae` entfallen):

| id | value | hint | alternates (Ziel) | width | Änderung |
|---|---|---|---|---|---|
| a | a | — | **`ä`** `à á â ã å` | 1 | **ä NEU an 1** |
| s | s | — | **`ß`** `ś š` | 1 | **ß NEU an 1** |
| d f g h j k l | — | — | IST bleibt (leer) | 1 | — |

Σ r1: **9**.

**shift analog (r1)** — Großformen, Werte aus `de-de.json layers.shift.rows[1]`, Positionen
wie base (Regel 2 der LAYOUT_REQUIREMENTS):

| id | value | alternates (Ziel) | Änderung |
|---|---|---|---|
| a | A | **`Ä`** `À Á Â Ã Å` | Ä NEU an 1 |
| s | S | **`ẞ`** `Ś Š` | **ẞ (U+1E9E) NEU an 1** — das große Sharp-S bleibt erreichbar; Großform des base-Alternates `ß` gemäß `CaseMapper` (ß ↔ ẞ, LAYOUT_REQUIREMENTS §2.2/§3 — NICHT `"ß".uppercase()` = `SS`) |
| d…l | D…L | IST bleibt | — |

Das `ẞ` war bisher das Tastenlabel der ß-Taste in `shift`; mit deren Wegfall (R8) wird es
zur **ersten Alternative des S in shift**. *Beleg-Status:* AOSP-seitig ist die Großform-
Ableitung für shift-Popups nicht extrahiert (`TEXTS_de` definiert `morekeys_s` nur klein:
„ß,ś,š“) — **Annahme — nicht belegt**: Großbuchstaben-Popup folgt bei AOSP derselben
Tabelle in Großschreibung; für uns ist die Anforderung durch `CaseMapperTest` gedeckt.

### r2 — base

VORHER: `⇧(1.5) y x c v b n m ß(1) ⌫(1.5)` (Σ 11; ids `shift`, `eszett`, `backspace`)

NACHHER (⌫ → r0, `eszett` entfällt; ⇧ füllt das ß-Loch):

| id | value/aktion | width | alternates | Änderung |
|---|---|---|---|---|
| shift | shift | **2.0** | — | 1.5 → 2.0 |
| y x c v b n m | — | 1 | c `ç ć č`, n `ñ ń`, m `µ` bleiben | sonst — |

Σ r2: 2 + 7 = **9**.

### r3 — Fußzeile (base UND shift)

VORHER: `?123(1.5) 🌐 😊 Leer(5) ‹ › 🎤 ⏎(1.5)` (Σ 13)

NACHHER: `?123(1.5) 🌐(1) 😊(1) Leer(6) .(1) 🎤(1) ⏎(1.5)` → Σ **13** (Testvertrag §3!)

| id | value | kind | width | alternates | Änderung |
|---|---|---|---|---|---|
| cursor-left / cursor-right | — | — | — | — | **löschen** |
| period | `.` | text | **1** | `,` `!` `?` | **NEU** zwischen space und mic |
| space | space | action | **6** (war 5) | — | +1 (Füllregel §3) |

Darstellung: `D10_FOOTER` (`keyboard/KeyboardModel.kt:125-145`) verschiebt in der
**Darstellung** Mic hinter ⏎ und gibt Space 1 Einheit ab (6→5, mic 2) — Asset-Rohtext bleibt
`… . 🎤 ⏎`; Komposition + Tests (`FooterAcrossAssetsTest`) passen automatisch, **0 Code** dafür.

### r3 der SYMBOL-Ebenen (symbols, symbolsShift) — Sonderregel!

`‹ ›` auch hier löschen (Nutzerwunsch gilt sichtbar für die Ebene), aber **zweiter Punkt ist
verboten**: LAYOUT_REQUIREMENTS **Regel 9** verlangt die Pflichtzeichen `. , ? ! …` auf
`symbols` **je genau 1×** — der Punkt wohnt dort bereits, ein zweiter wäre Pflichtzeichen-
Verstoß. Also: `‹ ›` löschen, **kein** zweiter Punkt, **space 5 → 7** → Σ 1.5+1+1+7+1+1.5 = **13**.
Symbole-Ebenen (`symbols`, `symbolsShift`) bekommen **keine** Punkt-Taste (sonst Pflicht-
zeichen 2× → Regel 9-Verstoß); nur base/shift bekommen die Punkt-Taste.

---

## 2. Quellen je alternates-Wert (AOSP-Quelle oder „offen")

Neu **hinzugekommene** Zeichen (das ist die gesamte Neuerungsmenge):

| # | Taste | neuer Zeichen-Zusatz | AOSP-Beleg (Datei + Attribut) | Status |
|---|---|---|---|---|
| u-Popup | `ü` an 1 | `rowkeys_qwertz1.xml`: u-Key `latin:moreKeys="!text/morekeys_u"`; Auflösung `KeyboardTextsTable.java` `TEXTS_de`: `morekeys_u = "ü,%,û,ù,ú,ū"` — **ü an Position 1** |
| o → `ö` an 1 | `rowkeys_qwertz1.xml` o-Key `!text/morekeys_o`; `TEXTS_de`: `morekeys_o = "ö,%,ô,ò,ó,õ,œ,ø,ō"` — ö an 1 |
| a → `ä` an 1 | `rowkeys_qwerty2_left5.xml`: a-Key `latin:moreKeys="!text/morekeys_a"`; `TEXTS_de`: `morekeys_a = "ä,%,â,à,á,æ,ã,å,ā"` — ä an 1 |
| s → `ß` an 1 | `rowkeys_qwerty2_left5.xml`: s-Key `!text/morekeys_s`; `TEXTS_de`: `morekeys_s = "ß,ś,š"` — **Reihenfolge ß ś š = exakt unser Zielbild** |
| **shift-S → `ẞ` an 1** | Großform des base-Eintrags `ß` per `CaseMapper` (ß ↔ ẞ, U+1E9E; LAYOUT_REQUIREMENTS §2.2 de-de-Notiz/§3, geprüft in `CaseMapperTest`). AOSP-Gegenstück **nicht extrahiert**: `TEXTS_de` definiert `morekeys_s` nur klein ("ß,ś,š") — **Annahme — nicht belegt**: shift-Popups folgen derselben Tabelle in Großschreibung |
| Punkt → `, ! ?` | `key_period.xml`: `latin:moreKeys="!text/morekeys_period"`; Default-Auflösung `morekeys_period = !text/morekeys_punctuation` = `", ? ! # ) ( / ; ' @ : - , + % &"` (autoColumnOrder) — Stock trägt an 1/2/3 `, ? !`; **der Nutzer wollte `, ! ?` — Reihenfolge Vornehme des Nutzers gewinnt, Quelle ist trotzdem belegt** |

Unverändert übernommene IST-Alternates (`e i c n m …`) sind **Asset-Herkunft**
(`de-de.json layers.base.rows[*]`), nicht AOSP-de — das ist erlaubt (der Nutzer hat für
Deutsch nur die fünf Punkte entschieden, keinen Austausch der Fremd-Akzente).

**Offen — nicht belegt / nicht entscheiden:**
- **Z4 (e):** `TEXTS_de` hat `morekeys_e = "é,è,ê,ë,ė"` (**ė**, ohne ę/€); unser Asset führt
  `é è ê ë ę €`. Nichts löschen/ersetzen ohne Nutzerwort — Vorlage lässt den IST stehen.
- Prozentzeichen `%` als Popup-Eintrag (Stock a/o/u an Position 2): Übernahme **offen**
  (unsere Assets führen nirgends `%` in alternates; Regel-9-/Audit-Frage ungeprüft).
- Doppel-Labels beim Audit: `period`-Eintrag `.` in base/shift ist zulässig (in diesen
  Ebenen einmalig), aber in `symbols`/`symbolsShift` **verboten** (Regel 9 „Pflichtzeichen
  genau 1×" auf der Symbolseite) — deshalb dort kein zweiter Punkt (§3).

Belegt vs. offen: **5 von 6 geänderten** alternates-Zeilen AOSP-belegt
(u, o, a, s, period; + shift-S `ẞ` = CaseMapper-belegt, AOSP-Ableitung als Annahme
markiert); **2 offene Detailfragen** (Z4 e-Liste, %-Eintrag) — beide mit „IST behalten"
aufgelöst.

---

## 2. (Zahlen zu §1 oben: AOSP-Referenzen je Taste)

r0-Hints: `rowkeys_qwertz1.xml` `latin:keyHintLabel="1".."0"` über q..p (1:1 übernommen).
r1/r2 haben bei Stock **keine** Hints (Zeilen `rowkeys_qwerty2`, `rowkeys_qwertz3` ohne
keyHintLabel — belegt per grep = 0). Fußzeile: `row_qwerty4.xml` = `key_comma +
key_space_5kw + key_period` ohne Pfeiltasten; `key_period.xml` `latin:moreKeys="!text/
morekeys_period"` (an zwei Stellen definiert durch `morekeys_punctuation,morekeys_z` —
Detail: Stock-Komma trägt `!text/morekeys_q,%`; für UNS gilt nur die Nutzerliste `, ! ?`).

---

## 3. Wohin die frei werdende Breite fließt (und welcher Test das überwacht)

| Frei geworden | Aufgenommen durch | Überwachender Test |
|---|---|---|
| `ue`-Slot r0 (1 Einheit) | `backspace` r0 (1.5) — Zeile wächst 11 → **11.5** | `keyboard/KeyboardModelTest.kt` (Breiten/Σ-Normalisierung je Reihe), `layouts/LayoutParserTest.kt` (Pflichtfeld width) |
| `oe`+`ae` r1 (2) | bleiben **frei**: r1 hat 9 statt 11 Tasten → Reihen-Normalisierung macht sie breiter (Zeile füllt immer full-width) | `KeyboardModelTest.kt` |
| `eszett` r2 (1) | `shift` 1.5 → **2.0** | `KeyboardModelTest.kt` |
| `‹ ›` r3 (2) | `period` (1) + **`space` 5 → 6** (1) | **`keyboard/FooterAcrossAssetsTest.kt`**: Fussreihe = **13 Einheiten** über alle 15 Assets × 4 Ebenen; ID-Menge invariant; `D10_FOOTER` verschiebt im Compose Mic an den Rand (Gewicht 2), Space gibt genau 1 ab → Roh-Raum 6 erscheint im Compose als 5 — erfüllt |
| Ebenen-Gleichheit | base↔shift gleiche Σ je Zeile; `symbols`/`symbolsShift` **ohne** period (Regel 9 Pflichtzeichen 1×!): `‹ ›` weg, **`space` 5 → 7**, Σ bleibt 13 | `layouts/LayoutArtifactTest.kt` (Regel 5e/8), `layouts/PunctuationCoverageTest.kt`, `NfcLabelTest.kt` (NFC), `LayoutCompletenessTest.kt` |

**Höhenvertrag 650 px / 829 px:** unverletzt — die Tastenzahl je **Zeile** ändert sich, die
**Anzahl Zeilen** nicht (de-de bleibt 4/4/4/4). `keyboard/KeyboardHeightTest.kt:12-24` verlangt:
bei 650 px (Nav 21) Alle-Reihen-sichtbar + scale 0,873, bei 829 px Budget 578 ≥ Bedarf 576;
Zeilen verschwinden/ersetzen darf nichts. Da der Umbau nur Breiten und IDs innerhalb
bestehender Zeilen verschiebt, bleibt der Vertrag erfüllt; F23b muss den Test unverändert
grün bekommen (Gate, nicht Änderung).

---

## 4. Kleinster Umsetzungsschnitt (drei Aufträge, je ≤ 4 Dateien)

**F23a — hint-Feld (Modell+Parser+Zeichnung, ohne de-de-Daten):**
1. `layouts/LayoutAsset.kt` — `LayoutKey` + `val hint: String? = null` (Feld 8).
2. `layouts/LayoutParser.kt` — Allowlist (`parseKey`, Zeile 100) um `"hint"` erweitern;
   Pflicht: wenn vorhanden String, Länge 1 (Ziffer); sonst `null`.
3. `keyboard/KeyboardView.kt` (+ ggf. `keyboard/KeySurface.kt`) — kleine Ziffer oben rechts
   auf der Taste (Stock-Vorbild `keyHintLabel`); **und**: LongPressMenu-Zellen =
   `[hint?] + base + alternates` — `cellCount`-Deckel 8 beachten (e hat dann 8 Zellen:
   hint+base+6 → exakt an der Decke, Test dokumentiert das).
4. `layouts/LayoutParserTest.kt` — hint round-trip + Fehlervalidierung (`hint` mit 2 Zeichen ⇒
   `LayoutException`).
*Gate: alle bestehenden Tests grün; de-de.json wird in F23a NICHT angefasst.*

**F23b — de-de-Daten (nur 1 Datei):**
`app/src/main/assets/layouts/de-de.json` exakt nach §1 (base+shift+symbols+symbolsShift;
§3-Regeln beachten). Gates: `LayoutParserTest`, `LayoutArtifactTest`,
`LayoutCompletenessTest`, `PunctuationCoverageTest`, `NfcLabelTest`,
`FooterAcrossAssetsTest`, `KeyboardHeightTest`.
*(Kein anderer Asset wird geändert — 14 andere Sprachen bleiben unangetastet.)*

**F23c — Geräteabnahme (Orchestrator, kein Coder):**
Sichtkontrolle Hint-Ziffern (10) auf r0, Punkt-Fußzeile ohne `‹ ›`, ⌫ oben rechts,
Longdruck-Popups u/o/a/s mit Umlaut an 1, `.`-Popup `, ! ?`; Lösch-/Enter-/Space-Verhalten;
IME-PID stabil. (Koordinaten/Typ-Prozedur macht der Orchestrator selbst — hier bewusst nicht
spezifiziert.)

**Nicht ändern:** `KONZEPT.md`, Parser-Geometrie-Logik, Audit-Regeln, andere 14 Assets,
`symbols`-Zeilen 0-2 (Pflichtzeichen-Seite).

---

## ANNAHME-Zeilen

- `ANNAHME: hint wird als kleinesglyph rechts oben auf der Taste gezeichnet (Stock-Form);
  exakte Größe/Farbe = F23a-Geschmack, kein Datenvertrag.`
- `ANNAHME: user-Wunsch "Punkt traegt , ! ?" meint die Popup-Reihenfolge in dieser Reihenfolge
  (Stock-Reihenfolge ware ", ? !").`
- `BELEGT: LongPressMenu existiert bereits (F22) — F23a ist hint-Feld + Zeichnung, kein Popup-Neubau.`
