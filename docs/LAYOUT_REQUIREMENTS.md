# Layout-Anforderungen (verbindlich, prüfbar)

Grundlage: DESIGN.md §7. 15 Sprachen (Startumfang), je vollständiges Layout in vier
Ebenen (`base`, `shift`, `symbols`, `symbolsShift`). Diese Datei macht die
Anforderung **maschinell prüfbar**: die Tests `LayoutCompletenessTest`,
`CaseMapperTest`, `PunctuationCoverageTest` laufen ohne Android (JSON-Parser),
`LayoutParserTest` prüft das Schema.

> **Review-Fassung 2026-10-27.** Korrigiert gegenüber Rev 1: finnisch `å` fehlte,
> polnische Großbuchstaben standen fälschlich als Alternates, griechische
> Groß-Tonos fehlten komplett, Satzzeichen waren nicht prüfbar spezifiziert
> (Spalte fehlte), die ß→ẞ-Regel vermischte Tastenlabel und Text-Transformation,
> und die Sprachliste war nicht gegen den ASR-Sprachsupport abgeglichen (§5).
>
> **Revision 3 (2026-10-27).** Regel 5 war eine **Fehlentscheidung** und ist neu
> gefasst: sie nannte `\`, `<`, `>` und `&` als „Missbrauch" und hat dazu geführt,
> dass `&` aus `de-de.json` und `en-us.json` **entfernt** und durch `§`/`$` ersetzt
> wurde. `&` ist ein alltägliches Zeichen (URL-Query, „Rock & Roll", Englisch).
> Rev 3 verbietet **keine Zeichen mehr**, sondern prüft `label == value`, genau
> einen Codepoint und die Zugehörigkeit zu einem deklarierten Zeichen-Universum —
> damit bleibt `=\<` (Artefakt) verboten, `&` (echtes Zeichen) Pflicht. Neu sind
> die **Pflicht-Zeichenliste** (§2.1, maschinenlesbar in §2.1.1) und der
> Abnahmetest dafür (§6 Punkt 8); die Befunde am Ist-Stand stehen in §2.1.3.

## 0. Zwei Anforderungsstufen (statt „eigene Taste vs. alternate" pauschal)

- **A (eigene Taste)**: Zeichen hat eine eigene Taste in der jeweiligen Ebene.
  Für Zeichen, die in der Sprache täglich vorkommen.
- **B (erreichbar)**: Zeichen ist entweder eigene Taste oder liegt als
  `alternates`-Eintrag auf einer Taste derselben Ebene (genau **ein** Langdruck).

Damit ist „erreichbar" immer durch eine Suche über `labels ∪ alternates` prüfbar —
unabhängig davon, wie die Implementierung die Taste anordnet.

## 1. Grundregeln (gelten für alle 15 Layouts)

1. Für jede Sprache gilt: `A(base) ⊆ B(base)` und `shift` enthält die
   **Großformen** aller Zeichen aus `A(base)` und `B(base)` (siehe Regel 2/3).
2. `shift` hat **dieselbe Tastenzahl und dieselben Positionen** wie `base`. Die
   Labels unterscheiden sich ausschließlich durch die dokumentierte
   Groß-/Kleinschreibung. **Nicht** `String.uppercase()`/`lowercase()` benutzen:
   die Default-Locale macht Türkisch (`i`→`I`) und Deutsch (`ß`→`SS`) falsch, und
   `"İ".lowercase()` ergibt zwei Codepoints (`i` + U+0307). Verbindlich ist die
   Tabelle in §3 (`CaseMapper`), verifiziert in `CaseMapperTest`.
3. `alternates` enthält **nur einzelne Zeichen** (ein Codepoint, NFC), keine
   Aktionsnamen, max. 8 Einträge pro Taste, keine Duplikate, und nie das Zeichen
   der Taste selbst. `alternates` in `shift` = Großformen der `alternates` in
   `base` (gleiche Position), sofern §3 nichts anderes sagt.
   **Präzisierung Rev 3 (Audit D3):** Die Spiegelung gilt nur für Zeichen **mit**
   Großform (Buchstaben). Caseless Zeichen (`€ § – — … µ`) werden nicht gespiegelt
   — sie in `shift` zu wiederholen wäre falsch und ist kein Fehler, wenn sie fehlen.
   Ein Zeichen darf nicht gleichzeitig eigene Taste und `alternates`-Eintrag einer
   anderen Taste derselben Ebene sein (eindeutiger Tippweg), sofern es nicht
   bewusst als Komfort-Doppelpfad dokumentiert ist.
4. Jede Ebene enthält genau eine `space`-, eine `backspace`-, eine
   `shift`/`layer-base`-, eine `enter`-, eine `emoji`- und eine `mic`-Taste.
   **4a — Rückweg der Symbolebenen (Rev 4, verbindlich):** `symbols` **und**
   `symbolsShift` enthalten je **genau eine** Taste mit `kind:"action"`,
   `id:"shift"`, `value:"shift"`, `label:"⇧"`, `width:1.5` — Position **Reihe 2,
   Spalte 0** (dort liegt die ⇧-Taste in allen 15 Assets). In `symbolsShift` ist sie
   der Rückweg nach `symbols`; `KeyboardView.handleAction` behandelt `SHIFT` in beiden
   Symbolebenen bereits als Ebenen-Toggle (kein Codeänderungsbedarf). Zusätzlich
   bleibt in beiden Symbolebenen die Taste `id:"base"`, `label:"ABC"`,
   `value:"layer-base"` (Reihe 3, Spalte 0) als direkter Weg in die Buchstabenebene.
   **Verboten:** `value:"layer-base"` mit einem Label ≠ `ABC` (verspricht eine
   Symboltafel, führt aber in die Buchstabenebene) und jede ⇧-Taste in einer
   Symbolebene mit `value != "shift"` (Dead-End).
   **Beschriftung:** in beiden Symbolebenen `⇧` — **kein** `⇩`, **kein** `⇪`
   (Symbolebenen werden nie gecappt, DESIGN §7.1); die Bedeutung „andere Symboltafel"
   ergibt sich aus der Ebene.
5. **Label = Output, genau ein Codepoint, Zeichen nur aus dem deklarierten
   Universum.** Gilt für jede Taste mit `kind: "text"` in allen vier Ebenen, geprüft
   nach NFC. Es gibt **keine verbotene Zeichenliste** — die Prüfung ist eine
   Whitelist plus Identitäts-/Formatregeln:
   - **5a** `label == value` (Codepoint-Gleichheit; nicht case-insensitiv, nicht
     getrimmt, kein Unicode-Folding).
   - **5b** `label` und `value` bestehen aus **genau einem** Codepoint
     (`codePointCount() == 1`); kein `k`-Label, kein Leerzeichen, kein Suffix.
   - **5c** `label` ist kein Steuer-/Formatzeichen (`Cc`,`Cf`), kein
     Kombinationszeichen (U+0300–U+036F), kein Leerraum (`Zs`,`Zl`,`Zp`), kein
     unsichtbares Zeichen (U+200B–U+200F, U+2060, U+FEFF).
   - **5d** `label` ∈ **Zeichen-Universum `Z`** = Pflichtzeichen (§2.1) ∪
     erlaubte Zusatzzeichen (§2.1.2) ∪ Sprachzeichen der jeweiligen Zeile aus §2.2.
     Alles außerhalb von `Z` ist ein Artefakt — z. B. `⌇` (U+2307), `=\<`,
     `⇧` auf einer Zeichentaste.
   - **5e** Tasten mit `kind: "action"` tragen **nie** ein Label aus `Z` (sonst sieht
     man ein tippbares Zeichen und bekommt eine Aktion) und tragen ein Label aus
     der Legenden-Menge `⇧ ⌫ ⏎ 🌐 😊 ?123 ABC Leer ‹ ›` oder ein Wort.
   - **5f** `\`, `<`, `>` und `&` sind **keine** Artefakte, sondern reguläre
     Zeichen und in §2.1 Pflicht. (Rev 2 hatte genau diese vier als „Missbrauch"
     aufgeführt; die tatsächlichen Artefakte aus Stufe 1 waren `=\<` — zwei
     Codepoints, 5a/5b — und `⌇` — nicht in `Z`, 5d.)

   **Prüfbar als:** `LayoutArtifactTest` (§6 Punkt 8) über alle 15 Assets; ein Verstoß
   nennt `asset`, `layer`, `key.id`, `label`, `value` und die verletzte Teilregel.
6. **Keine RTL-Mischung:** In Assets mit `direction: "ltr"` darf kein Label in
einem RTL-Bereich liegen (U+0590–U+08FF, U+FB1D–U+FDFF, U+FE70–U+FEFF);
Codepoint-Länge und verbotene Zeichenklassen regelt Regel 5b/5c (nicht doppelt
führen).
7. **Normalisierung:** Alle Labels/Alternates sind NFC. (Ein NFD-„ä" aus
   `a` + U+0308 würde sonst die Gleichheitstests unterlaufen und auf dem Gerät
   unterschiedlich gerendert.) Prüfung: `Normalizer.isNormalized(s, NFC)`.
8. Innerhalb einer Ebene: keine doppelten Key-IDs; **keine doppelten Labels unter
   Text-Tasten**. Zwei Ausnahmen sind zulässig und müssen so bleiben:
   - **σ/ς → Σ** (griechisch): zwei `base`-Tasten (`σ`, `ς`) haben dieselbe
     Ein-Codepoint-Großform, also trägt `shift` zwangsläufig zweimal das Label
     `Σ`. Regel 2 hat hier Vorrang vor Regel 8 (Audit D1).
   - **Action-Legenden** (`‹ ›` der Cursor-Tasten) dürfen mit Text-Labels
     derselben Ebene kollidieren; Regel 8 gilt für Text-Tasten, die Legenden
     regelt Regel 5e.
9. `symbols` enthält die Ziffern `0`–`9` genau je einmal und die Pflicht-Gruppe 1
   aus §2.1 — als **eigene Taste** (Stufe A), nicht als Langdruck. Die
   Pflicht-Gruppe 2 (`\ [ ] { } < > ~ | € $ § °`) darf auf `symbols` **oder**
   `symbolsShift` liegen; sprachspezifische Zeichen (§2.2) ebenfalls, aber nie als
   `alternates` versteckt. `symbolsShift` enthält zusätzlich Shift-Gegenstücke der
   Ziffern (¹²³ …) und optionale Zeichen (§2.1.2).

## 2. Sprachmatrix (Pflichtzeichen)

### 2.1 Pflichtzeichen auf den Symbol-Ebenen (alle 15 Sprachen)

Jede Sprache muss die folgenden Zeichen als **eigene Taste** (Stufe A, nicht als
`alternates` versteckt) und **genau einmal** in der genannten Ebene führen. Die
Aufteilung ist verbindlich, weil sie maschinell geprüft wird (§2.1.1, §6 Punkt 8).

| Gruppe | Ebene | Zeichen (je genau einmal) |
|---|---|---|
| **1 — täglich gebraucht** | **`symbols`** | `. , ? ! : ; ' " - / @ ( ) & % + = # _ *` (20) |
| **2 — strukturell/selten** | `symbols` **oder** `symbolsShift` | `\ [ ] { } < > ~ \| € $ § °` (13) |
| **Ziffern** | **`symbols`** | `0 1 2 3 4 5 6 7 8 9` (je genau einmal, Regel 9) |

Begründung der Aufteilung (fachlich, nicht Geschmack):

- **Gruppe 1** ist das, was beim Tippen ständig gebraucht wird: `&` in
  URL-Query-Strings und „Rock & Roll", `@` in Handles/Mailadressen, `#` in
  Hashtags/Markdown, `_ * =` in Bezeichnern, Markdown und Rechnungen, `+` in
  Telefonnummern, `" '` als Syntax- und Zitatzeichen. Diese Zeichen dürfen **nicht**
  hinter einer zweiten Umschaltung liegen.
- **Gruppe 2** sind Klammern, Pfad-/Pipe-Zeichen und Währungen/`§`/`°`. Eine
  Umschaltung ist hier zumutbar. `€ $ § °` sind in allen Sprachen nötig, und die
  `symbols`-Seite ist mit 30 Pflicht-Zeichentasten (Gruppe 1 + Ziffern) bereits
  voll — deshalb dürfen sie auf beiden Ebenen liegen.
- **Änderung Rev 3:** Die Spalte „Satzzeichen (direkt)" in §2.2 bedeutet ab jetzt
  „eigene Taste auf `symbols` **oder** `symbolsShift`". Vorher war „direkt auf
  `symbols`" gemeint; das ließ sich mit der Pflichtliste nicht gleichzeitig
  erfüllen. Die **Menge** der Pflichtzeichen wird dadurch nicht kleiner, nur die
  Ebene ist frei.

#### 2.1.1 Maschinenlesbare Pflichtliste (Quelle für den Test)

Die Tests duplizieren diese Zeichen **nicht** im Testcode frei, sondern benutzen
exakt diese Konstanten (Reihenfolge egal, Vergleich als Menge):

```
MANDATORY_ON_SYMBOLS        = ".,?!:;'\"-/@()&%+=#_*"      # 20, Gruppe 1
MANDATORY_ON_SYMBOLS_OR_SHIFT = "\\[]{}<>~|€$§°"         # 13, Gruppe 2
MANDATORY_DIGITS            = "0123456789"                # 10, Regel 9
```

Prüfung: für jedes Asset und jede Sprache aus §2.2 gilt
`labels(symbols) ⊇ MANDATORY_ON_SYMBOLS ∪ MANDATORY_DIGITS` und
`labels(symbols) ∪ labels(symbolsShift) ⊇ MANDATORY_ON_SYMBOLS_OR_SHIFT`,
jeweils mit **genau einem** Vorkommen pro Zeichen in der zulässigen Ebene
(Regel 8 verbietet Duplikate innerhalb einer Ebene).

#### 2.1.2 Erlaubte Zusatzzeichen (nicht Pflicht)

Zusätzlich sind diese Zeichen zulässig (eigene Taste oder `alternates`), solange
Regel 5a–5e und Regel 3 (max. 8 Alternates) gelten — sie vervollständigen das
Zeichen-Universum `Z`:

```
£ ^ ¹ ² ³ ¼ ½ ¬ ¢ µ ¥ ¶ · † ‡ ‰ ± × ÷ √ π ∆ • © ®
„ “ ” ‘ ’ ‚ « » – — … ¿ ¡ № º ª
`      (U+0060, Backtick — zulässig, aber nicht Pflicht; im Markdown quoten!)
```

Alles andere (z. B. `⌇` U+2307, `=\<`, `⇧` auf einer Zeichentaste) ist ein
Artefakt und in Regel 5d verboten.

#### 2.1.3 Ist-Stand (Stand 2026-10-27, geprüft)

Die frühere Tabelle hier (`de-de`/`en-us` ohne `& # = " * _`) ist **überholt**: die
§2.1-Pflichtliste (Gruppe 1 + Ziffern je genau 1× auf `symbols`, Gruppe 2 in
`symbols ∪ symbolsShift`) ist inzwischen in **allen 15 Assets vollständig erfüllt**.
Der `&`-Rückbau ist erledigt; `amp` liefert wieder `&`.

Vollständige Prüfung samt Befunden, Evidenz und Fixliste:
**`docs/LAYOUT_AUDIT.md`** (8 Assets mit Restbedarf, 7 OK). Offen sind dort nur
noch §2.2-Verstöße (Satzzeichen-Spalte, einzelne A/B-Zeichen) — **nicht** §2.1.

### 2.2 Sprachen

Spalten: **A** = eigene Taste in `base`, **B** = eigene Taste *oder* ein
Alternate in `base`; die Shift-Ebene muss die jeweilige Großform analog führen
(§3). „Satzzeichen" = zusätzlich zu §2.1 **als eigene Taste** auf `symbols`
**oder** `symbolsShift` (Rev 3; vorher war „nur `symbols`" gefordert — die
Pflichtliste aus §2.1 braucht die zweite Ebene, siehe §2.1 Begründung), niemals
nur als Alternate. Varianten in `{…}` sind Alternative (mindestens eine Variante).

| id | tag | Layout | A in `base` | B in `base` | Satzzeichen (direkt) |
|---|---|---|---|---|---|
| `de-de` | de-DE | QWERTZ | ä ö ü ß | é è ê á à â ç | „ “ {oder » «}, –, €, §, ° |
| `en-us` | en-US | QWERTY | — | — (ASCII) | ' – — … £ $ ° |
| `fr-fr` | fr-FR | AZERTY | é è à ç ù | ê ë â ï î ô û ü œ æ ÿ | « », ’, €, ° |
| `es-es` | es-ES | QWERTY | ñ | á é í ó ú ü | ¿ ¡, « », º ª, € |
| `it-it` | it-IT | QWERTY | à è é ì ò ù | á í ó ú î ü | « » {oder “ ”}, ’, € |
| `pt-pt` | pt-PT | QWERTY | á â ã à ç é ê í ó ô õ ú | ü | « », º ª, €, § |
| `nl-nl` | nl-NL | QWERTY | — | á à ä ë é è ê ï ö ü | ‘ ’ „ ” {oder “ ”}, € |
| `pl-pl` | pl-PL | QWERTY | ą ć ę ł ń ó ś ź ż | — | „ ”, –, € |
| `tr-tr` | tr-TR | Türkisch Q | ç ğ ı i ö ş ü | â î û | ‘ ’, € |
| `ru-ru` | ru-RU | ЙЦУКЕН | а б в г д е ё ж з и й к л м н о п р с т у ф х ц ч ш щ ъ ы ь э ю я | — | « », –, №, € |
| `cs-cz` | cs-CZ | QWERTZ | á č é ě í ý ú ů ř š ž | ď ť ň ó | „ “, –, €, ° |
| `sv-se` | sv-SE | QWERTY | å ä ö | á à é è ü | ” {oder » «}, –, §, € |
| `da-dk` | da-DK | QWERTY | æ ø å | ä é ö ü | » « {oder ”}, –, §, € |
| `fi-fi` | fi-FI | QWERTY | **ä ö å** | š ž é | ” {oder » «}, –, §, € |
| `el-gr` | el-GR | Griechisch | α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ ς τ υ φ χ ψ ω | ά έ ή ί ό ύ ώ ϊ ϋ ΐ ΰ | « », · (**U+00B7**), € |

Hinweise zur Tabelle (jeweils fachlich begründet, nicht Geschmack):

- **`fi-fi`:** `å` ist Pflicht (DESIGN.md §7.3 nennt „ä ö å"); es ist der
  schwedisch-finnische Buchstabe (Namen, Åland, Åbo) und liegt auf jedem
  finnischen HW-Layout. Rev 1 hatte `å` nur in `sv-se`/`da-dk`.
- **`pl-pl`:** `Ą Ć Ę Ł Ń Ó Ś Ź Ż` sind **keine** Alternates, sondern die
  Shift-Ebene von `A(base)` (Regel 1/2). Rev 1 führte sie in der
  Alternates-Spalte — das widersprach der eigenen Regel 2.
- **`el-gr`:** Das Alphabet ist als explizite Liste ausgeschrieben, weil der
  Codepoint-Bereich α–ω zufällig auch das Finalsigma ς (U+03C2) enthält und
  dadurch „vollständig" ohne ς als wahr gilt. `ς` und `Σ` sind Pflicht
  (Final- vs. Normalform); die Groß-Tonos (`Ά Έ Ή Ί Ό Ύ Ώ`) und `Ϊ Ϋ` fehlten in
  Rev 1 komplett — ohne sie ist „Ά" nicht tippbar, obwohl Griechisch in
  Großschreibung den Tonos behält (z. B. „ΆΛΦΑ" — orthographisch zulässig).
  **Rev 3 (Audit):** Das Satzzeichen ist die Ano Teleia als **U+00B7** — U+0387
  ist kanonisch äquivalent und wird von NFC nach U+00B7 normalisiert, wäre also
  nach Regel 7 nicht darstellbar. Das „griechische Fragezeichen" U+037E ist
  kanonisch identisch mit `;` (U+003B) und damit bereits durch Gruppe 1 in §2.1
  abgedeckt; ein eigener Codepoint ist unter NFC **nicht** möglich und wird nicht
  mehr gefordert.
- **`cs-cz`:** `ď ť ň ó` bleiben Alternates (auf HW-Layouts sind sie nicht
  direkt belegt); `á č é ě í ý ú ů ř š ž` sind direkt erreichbar.
- **`ru-ru`:** `ё` liegt außerhalb des Bereichs а–я (U+0451) und muss explizit
  geführt werden; `ъ`/`ь` liegen innerhalb und sind damit abgedeckt. `№` ist
  die russische Nummernzeichen-Taste und in Rev 1 nicht vorgekommen.
- **`de-de`:** `ẞ` (U+1E9E) ist **Tastenlabel** der ß-Taste in `shift` (Regel 2).
  Das ist ausdrücklich **nicht** die Text-Transformation: `"ß".uppercase()` ist
  `"SS"` (amtliche Regel), und dieser Unterschied muss im Code sichtbar getrennt
  sein (`CaseMapper.upperLabel(c)` vs. `TextTransforms.uppercase(s)`).
- **`tr-tr`:** `i` und `ı` sind beide `A`, `İ` und `I` sind beide in `shift` —
  vier Zeichen, zwei Tasten (siehe §3).
- **`fr-fr`:** `ê` ist auf Hardware-AZERTY **nicht** direkt belegt (Zirkumflex +
  e); die Anforderung lautet deshalb „erreichbar" (B), nicht „eigene Taste".
  Ziffern liegen bei AZERTY nie auf der Buchstabenreihe — sie gehören auf
  `symbols` (§1 Regel 9), was hier automatisch erfüllt ist.

## 3. Case-Mapping-Tabelle (verbindlich, ausführbar zu testen)

`CaseMapper` bildet für jede Sprache (Label-Ebene) ab. Java/Kotlin-Defaults
(`uppercase()`/`lowercase()` mit Locale.ROOT) sind **falsch** für Türkisch und
Deutsch; `-` bedeutet „identisch".

| Sprache | Regel | Gegenprobe (Test) |
|---|---|---|
| tr-TR | `i`→`İ`, `ı`→`I`, `İ`→`i`, `I`→`ı`, sonst Standard | `upperLabel("i")=="İ"`, `lowerLabel("I")=="ı"`, `lowerLabel("İ")=="i"` (1 Codepoint!) |
| de-DE | Label: `ß`→`ẞ`; **Text**-Uppercase: `ß`→`SS` | `upperLabel("ß")=="ẞ"`, `TextTransforms.upper("straße")=="STRASSE"` |
| el-GR | `ς`→`Σ`; `ά`→`Ά`, `έ`→`Έ`, `ή`→`Ή`, `ί`→`Ί`, `ό`→`Ό`, `ύ`→`Ύ`, `ώ`→`Ώ`; `ϊ`→`Ϊ`, `ϋ`→`Ϋ`. **Ausnahme:** `ΐ` (U+0390) und `ΰ` (U+03B0) haben keine Ein-Codepoint-Großform (Unicode: `Ι`+U+0308+U+0301 bzw. `Υ`+U+0308+U+0301, 3 Codepoints) → Label bleibt in `shift` unverändert `ΐ`/`ΰ` | `upperLabel("ς")=="Σ"`, `upperLabel("ά")=="Ά"`, `upperLabel("ϊ")=="Ϊ"`, `upperLabel("ΐ")=="ΐ"` |
| ru-RU | Standard (ё→Ё, ь→Ь …) | `upperLabel("ё")=="Ё"`, `upperLabel("ь")=="Ь"` |
| pl-PL, cs-CZ, sv-SE, da-DK, fi-FI, es-ES, it-IT, pt-PT, nl-NL, fr-FR, en-US | Standard-1:1-Mapping | `upperLabel("ą")=="Ą"`, `upperLabel("ř")=="Ř"`, `upperLabel("œ")=="Œ"`, `upperLabel("æ")=="Æ"` |

Regel für alle: `upperLabel`/`lowerLabel` müssen **genau einen Codepoint**
zurückgeben (sonst schlägt Regel 6/7 fehl) — deshalb sind `ß`, `İ`, `ΐ`, `ΰ`
Sonderfälle und keine Selbstläufer. Wo keine Ein-Codepoint-Großform existiert
(`ΐ`, `ΰ`), bleibt das Zeichen in `shift` unverändert; das ist die einzige
zulässige Ausnahme von Regel 2.

## 4. Sonderfälle, die Tests abdecken müssen

- **Türkisch:** `i`→`İ`, `ı`→`I`; Tastenbeschriftung in `shift` zeigt `İ`/`I`;
  `lowerLabel("İ")` liefert `i` (nicht `i`+U+0307). Zusätzlich: der
  Sprachwechsel darf das `i` auf **derselben** Taste lassen (nur das Label
  wechselt) — sonst ändert sich die Tippmotorik beim Sprachwechsel.
- **Deutsch:** `ẞ` als Shift-Label von `ß`; Text-Uppercase bleibt `SS`
  (§3). `ß` nie in `alternates` verstecken.
- **Griechisch:** `ς`, `Σ`, `σ` alle erreichbar; Tonos-Vokale klein (`A`-Ebene
  optional, B erlaubt) und groß (`Ά Έ Ή Ί Ό Ύ Ώ`) erreichbar; Dialytika
  `ϊ ϋ ΐ ΰ` erreichbar; griechisches Fragezeichen `;` (U+037E) und Ano Teleia
  `·` (U+0387) direkt auf `symbols`.
- **Russisch:** `ё`/`Ё`, `ъ`/`Ъ`, `ь`/`Ь` vorhanden; `«»` und `№` vorhanden.
- **Französisch:** `œ`/`Œ`, `æ`/`Æ`, `ç`/`Ç`, `ÿ`/`Ÿ` erreichbar;
  `’` (U+2019) als eigene Taste, `«»` direkt; Ziffern nicht auf der
  Buchstabenreihe.
- **Polnisch:** `ą ć ę ł ń ó ś ź ż` direkt, Großformen in `shift` (§2.2).
- **Tschechisch:** `ě š č ř ž ý á í é ú ů` direkt, `ď ť ň ó` erreichbar;
  `ů` und `ú` sind **verschiedene** Zeichen (kein Mapping aufeinander).
- **Schwedisch/Dänisch/Finnisch:** `å` in allen drei Layouts; `æ ø` dänisch,
  `ä ö` schwedisch/finnisch — beim Sprachwechsel dürfen `ä/æ` bzw. `ö/ø`
  **nicht** auf derselben Taste kollidieren, ohne dass das Label mitwechselt
  (sonst tippt man stumm das falsche Zeichen).
- **Alt/Grave-Akzente** (`é è ê` …) sind auf `a`/`e`/`i`/`o`/`u` als Alternates
  erlaubt (max. 8 pro Taste, §1 Regel 3).

## 5. Abgleich mit dem ASR-Sprachsupport (Lücke, bewusst dokumentiert)

Das ASR-Modell unterstützt laut Model-Card 25 Sprachen (`docs/ASR_CONTRACT.md` §1):
`en es fr de bg hr cs da nl et fi el hu it lv lt mt pl pt ro sk sl sv ru uk`.

Damit gilt:

- **`tr-tr` hat ein Layout, aber keinen ASR-Support.** Diktat auf Türkisch
  funktioniert nicht (das Modell kennt Türkisch nicht); das Layout ist reine
  Tipp-Funktion. Das muss in der Sprachauswahl sichtbar sein (Diktat-Mikrofon
  deaktiviert oder Warnung), sonst entsteht ein stiller Fehlschlag.
- **11 unterstützte Sprachen haben kein Layout:** `bg hr et hu lv lt mt ro sk sl
  uk`. Für Diktat ist das egal (Text wird per `commitText` eingefügt, das Layout
  ist nur für manuelle Korrekturen nötig), für die Nachbearbeitung nicht.
  Empfehlung: diese Sprachen als Layout nachliefern (Reihenfolge: `sk`, `sl`,
  `hr`, `ro`, `hu`, `et`, `lt`, `lv`, `bg`, `uk`, `mt`), aber nicht im
  Startumfang — DESIGN.md §7.3 bleibt bei 15.

## 6. Abnahmekriterien

1. `LayoutCompletenessTest`: für alle 15 Assets gilt §2.2 (A- und B-Mengen),
   inklusive der Großformen in `shift`.
2. `CaseMapperTest`: §3-Tabelle vollständig, inklusive der Gegenproben
   (`i`→`İ`, `ß`→`ẞ` vs. `SS`, `ς`→`Σ`, `ą`→`Ą`); `upperLabel`/`lowerLabel`
   liefern immer genau einen Codepoint.
3. `PunctuationCoverageTest`: Pflichtliste aus §2.1.1 (`MANDATORY_ON_SYMBOLS`,
   `MANDATORY_ON_SYMBOLS_OR_SHIFT`, `MANDATORY_DIGITS`) plus §2.2 länderspezifisch,
   jeweils als eigene Taste in der zulässigen Ebene (Varianten als Oder-Menge); je
   Zeichen **genau ein** Vorkommen.
4. `NfcLabelTest`: alle Labels/Alternates NFC, keine Kombinationszeichen allein,
   keine Duplikate (§1 Regeln 6–8).
5. `LayoutParserTest`: akzeptiert alle 15, lehnt manipulierte Varianten ab
   (fehlende `shift`-Ebene, unbekannte Aktion, doppelte Key-ID, `width` ≤ 0,
   `alternates` mit Aktionsnamen oder > 8 Einträgen).
6. Sichtprüfung im Emulator: DE zeigt `ä ö ü ß` direkt; Ziffernreihe erscheint
   nach `?123`; im TR-Layout zeigt die Shift-Ebene `İ` und `I` auf den
   `i`/`ı`-Tasten.
7. Sprachwechsel im Emulator: `language`-Taste zykelt durch die aktiven Layouts,
   Auswahl überlebt Neustart; Diktat-Mikrofon ist für Sprachen ohne
   ASR-Support (§5) deaktiviert/markiert.
8. `LayoutArtifactTest` (ersetzt `checkNoLabelArtifacts`): Regeln 5a–5e über alle
   15 Assets, je Taste `kind:"text"`:
   - 5a `label == value`, 5b `codePointCount == 1` für Label **und** Value,
   - 5c kein Steuer-/Kombinations-/unsichtbares Zeichen,
   - 5d `label ∈ Z` (Pflichtliste §2.1.1 ∪ Zusatzzeichen §2.1.2 ∪ §2.2-Zeichen),
   - 5e Action-Tasten tragen kein Zeichen aus `Z`.
   Fehlermeldung enthält `asset`, `layer`, `key.id`, `label`, `value`, Teilregel.
   **Eine Blacklist einzelner Zeichen darf es nicht geben** — insbesondere dürfen
   `& \ < >` nicht als „verboten" geführt werden (Rev-2-Fehler, §2.1.3).
   Gegenprobe: `&` auf `symbols`, `\` auf `symbolsShift`, `=\<` als Label und `⌇`
   als Label — die ersten beiden müssen **akzeptiert**, die letzten beiden
   **abgelehnt** werden.

9. **Symbolebenen-Rückweg (Regel 4a):** `LayoutCompletenessTest` muss für
   **beide** Symbolebenen je Asset genau 1× `value=="shift"` **und** `label=="⇧"`
   verlangen (Reihe 2/Spalte 0, `width==1.5`). Die bisherige Toleranz entfällt
   (heute genügt `layer-symbols` **oder** `layer-base`, dadurch bleibt ein Dead-End
   grün). Zusätzlich: kein `layer-base` mit Label ≠ `ABC`.
   **Gegenproben (müssen rot werden):** (a) Asset ohne diese Taste in `symbolsShift`
   (Ist-Zustand von `cs-cz`, `pl-pl`, `ru-ru`, `sv-se`, `tr-tr`); (b) Taste mit
   `value:"shift"`, aber `label:"?123"` (Ist-Zustand von `da-dk`, `el-gr`, `fi-fi`);
   (c) Taste mit `label:"⇧"`, aber `value:"layer-base"`.
