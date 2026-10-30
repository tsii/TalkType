# Layout-Schema (maschinenlesbare Kurzreferenz für Coders)

**Quelle:** `docs/LAYOUT_REQUIREMENTS.md` (Rev 3) + realer Parservertrag
(`LayoutParser.kt`, `LayoutAsset.kt`). **Prüfstatus:** gegen die 15 Assets auditiert
(`docs/LAYOUT_AUDIT.md`, 2026-10-27) — Rev 2 dieser Datei enthielt 18 Fehler/
Lücken, die dort einzeln aufgeführt sind.

Datei: `app/src/main/assets/layouts/<id>.json`, UTF-8, `ensure_ascii=false` erlaubt.

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

KEY-Objekt: `{"id":"q","label":"q","width":1,"kind":"text","value":"q"}`
(optional zusätzlich `"caps": "…"` und `"alternates": ["…", …]`)

## Feldvertrag (exakt, geprüft vom Parser)

| Feld | Regel |
|---|---|
| `schemaVersion` | `1` (Integer, Pflicht) |
| `id` | `^[a-z0-9-]+$`, **muss** dem Dateinamen entsprechen (`de-de` → `de-de.json`); das Repository lädt über die ID |
| `language` | genau `tag`, `label`, `caseLocale`, `direction`; alle Pflicht-Strings; `direction ∈ {ltr, rtl}`; `tag` BCP-47 (`^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$`) und **muss** der §2.2-Zeile entsprechen; `caseLocale` = `tag` (CaseMapper nutzt sie) |
| `deadKeys` | optional (Default `{}`); Sub-Schema siehe unten; **kein Asset nutzt es derzeit** |
| `layers` | genau die vier Ebenen `base`, `shift`, `symbols`, `symbolsShift` (keine weiteren) |
| `id` (Taste) | Pflicht, **eindeutig pro Ebene**; dieselbe physische Taste behält ihre ID über Ebenen; jede `base`-Text-Taste braucht eine Taste **gleicher ID** in `shift` (Parser erzwingt das) |
| `label` | Pflicht; bei `kind:"text"` gilt **5a** `label == value` und **5b** genau 1 Codepoint (nach NFC) |
| `width` | Pflicht, Zahl `> 0` (normal 1.0, Space 5.0, Funktionstasten 1.0–1.5) |
| `kind` | `text` \| `action` \| `dead` |
| `value` | bei `text`/`dead` das Zeichen bzw. die Deadkey-ID; bei `action` **nur** aus der Allowlist |
| `caps` | optional (`String`), alternative Beschriftung; Feld existiert, **wird von keinem Asset genutzt** |
| `alternates` | optional, Liste von Strings; Regeln siehe unten |

**Unbekannte Felder** werden vom Parser **abgelehnt** in `language`,
`layers.<ebene>`, im Key-Objekt und in `deadKeys.<id>`. Auf **Top-Level** prüft der
Parser keine zusätzlichen Felder — die fünf oben sind Konvention und sollten nicht
erweitert werden (sonst driftet Doku und Code).

### Erlaubte Aktionen (`kind:"action"`)

```
shift, caps, layer-symbols, layer-base, backspace, space, enter,
language, emoji, mic, cursor-left, cursor-right
```

- `caps` ist in der Allowlist, wird aber von keinem Asset genutzt (Doppeltipp auf
  `shift` = CapsLock ist **nicht** implementiert; nicht darauf aufbauen).
- Action-Tasten tragen **kein** Zeichen aus dem Zeichen-Universum (§2.1/§2.1.2/§2.2)
  als Label, sondern eine Legende aus `⇧ ⌫ ⏎ 🌐 😊 ?123 ABC Leer ‹ ›` oder ein Wort
  (`Leer`, `Διάστημα`) — Regel 5e.

### Deadkeys (dokumentiert, ungenutzt)

```json
"deadKeys": {
  "acute": {"spacing": "´", "combining": "\u0301", "compose": {"a": "á", "e": "é"}}
}
```

Felder je Deadkey: `spacing` (Pflicht), `combining` (optional), `compose` (optional,
Objekt Basiszeichen → Ergebnis). Eine Taste mit `kind:"dead"` **muss** einen hier
definierten `value` referenzieren (Parser erzwingt das).

## Zeilen-Geometrie (wichtig, vom Parser geprüft)

Geprüft wird **paarweise**, nicht global:

1. `shift` und `base` haben **dieselbe Zeilenzahl**, und je Zeilenindex dieselbe
   **Breitensumme** (`Σ width`, Toleranz 1e-9).
2. `symbolsShift` und `symbols` ebenso.
3. Die Buchstaben-Ebenen dürfen eine **andere** Zeilenzahl/Breitenaufteilung haben
   als die Symbol-Ebenen (z. B. `pt-pt`: 5 Reihen Buchstaben, 4 Reihen Symbole).

Die **Tastenzahl** je Zeile darf differieren, solange die Breitensumme stimmt.
Erfahrungswerte (aus den 15 Assets, keine Regel): `symbols`/`symbolsShift` als
4 Reihen mit Textreihen `11/11/10` + Aktionszeile (30–31 Textplätze; `de-de`,
`en-us` = 30, `el-gr`/`da-dk`/`fi-fi` = 31). `§2.1` braucht **30** Textplätze
(20 Gruppe 1 + 10 Ziffern) — die `symbols`-Seite ist damit praktisch voll.

## Pflichtzeichen (Gruppe 1 — MUSS auf `symbols`, je genau 1×)

```
. , ? ! : ; ' " - / @ ( ) & % + = # _ *
```
(20 Zeichen: Punkt, Komma, Frage, Ausruf, Doppelpunkt, Semikolon, Apostroph,
**gerades** Anführungszeichen U+0022, Bindestrich, Slash, At, Klammer auf/zu,
Ampersand, Prozent, Plus, Gleich, Raute, Unterstrich, Stern.)

`&`, `\`, `<`, `>` sind **keine** Artefakte (Rev-2-Fehler, siehe Audit B1/§2.1.3) —
sie sind Pflicht und dürfen nicht durch `§`/`$` o. Ä. ersetzt werden.

## Pflichtzeichen (Gruppe 2 — `symbols` ODER `symbolsShift`, je genau 1×)

```
\ [ ] { } < > ~ | € $ § °
```
(13 Zeichen. Klammern paarweise vollständig: `[ ]`, `{ }` — nicht `] {` ohne Partner.)

## Ziffern

`1 2 3 4 5 6 7 8 9 0` auf `symbols`, je genau einmal. `symbolsShift` trägt
Shift-Gegenstücke wie `¹ ² ³ ¼ ½ ¬ | ° …`.

**Nicht** `! @ # $ % ^ & * ( )` als Ziffern-Shift: diese Zeichen sind bereits
§2.1-Gruppe-1-Pflicht auf `symbols`; eine Wiederholung auf `symbolsShift` verletzt
Regel 8 (doppelte Labels/Values je Ebene).

## Sprachspezifische Pflichtzeichen (`base`, eigene Taste = Stufe A)

`B` = eigene Taste **oder** Alternate in `base`. `shift` muss die Großform analog
führen (Regel 2/§3); Ausnahmen: `ß→ẞ`, `σ/ς→Σ`, `ΐ/ΰ` bleiben unverändert.

| id | eigene Tasten in `base` (A) | erreichbar (B) | Besonderheiten |
|---|---|---|---|
| de-de | ä ö ü ß | é è ê á à â ç | shift: Ä Ö Ü **ẞ** (`"ß".uppercase()` = `SS` ist **Text**-Regel, nicht Label) |
| en-us | — | — (ASCII) | — |
| fr-fr | é è à ç ù | ê ë â ï î ô û ü œ æ ÿ | Asset führt `ê` zusätzlich als eigene Taste (Übererfüllung); shift: É È À Ç Ù |
| es-es | ñ | á é í ó ú ü | shift: Ñ |
| it-it | à è é ì ò ù | á í ó ú î ü | shift: À È É Ì Ò Ù |
| pt-pt | á â ã **à** ç é ê í ó ô õ ú | ü | shift: Á Â Ã À Ç É Ê Í Ó Ô Õ Ú |
| nl-nl | — | á à ä ë é è ê ï ö ü | — |
| pl-pl | ą ć ę ł ń ó ś ź ż | — | shift: Ą Ć Ę Ł Ń Ó Ś Ź Ż |
| tr-tr | ç ğ ı **i** ö ş ü | â î û | Türkisch-Q; shift: Ç Ğ **I İ** Ö Ş Ü (`i→İ`, `ı→I`) |
| ru-ru | а б в г д е ё ж з и й к л м н о п р с т у ф х ц ч ш щ ъ ы ь э ю я | — | ЙЦУКЕН; shift А–Я + Ё |
| cs-cz | ě š č ř ž ý á í é ú ů | ď ť ň ó | `ů` und `ú` sind verschiedene Zeichen |
| sv-se | å ä ö | á à é è ü | — |
| da-dk | æ ø å | ä é ö ü | — |
| fi-fi | ä ö **å** | š ž é | `å` ist Pflicht (`Åland`, `Åbo`) |
| el-gr | α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ ς τ υ φ χ ψ ω | ά έ ή ί ό ύ ώ ϊ ϋ ΐ ΰ | Finalsigma `ς` **und** `σ`; shift Α–Ω + `Σ` (zwangsläufig **zweimal** `Σ`, siehe Regel-8-Ausnahme) |

## Sprachspezifische Satzzeichen (eigene Taste auf `symbols` ODER `symbolsShift`)

Fehlte in Rev 2 komplett — **Ursache von 6 Audit-Befunden**. `{a | b}` = Variante,
mindestens eine. Ausführung als eigene Taste, **nie** nur als Alternate.

| id | Pflicht-Satzzeichen |
|---|---|
| de-de | `„ “` {oder `» «`}, `–`, `€`, `§`, `°` |
| en-us | `'`, `–`, `—`, `…`, `£`, `$`, `°` |
| fr-fr | `« »`, `’`, `€`, `°` |
| es-es | `¿`, `¡`, `« »`, `º`, `ª`, `€` |
| it-it | `« »` {oder `“ ”`}, `’`, `€` |
| pt-pt | `« »`, `º`, `ª`, `€`, `§` |
| nl-nl | `‘ ’ „ ”` {oder `“ ”`}, `€` |
| pl-pl | `„ ”`, `–`, `€` |
| tr-tr | `‘ ’`, `€` |
| ru-ru | `« »`, `–`, `№`, `€` |
| cs-cz | `„ “`, `–`, `€`, `°` |
| sv-se | `”` {oder `» «`}, `–`, `§`, `€` |
| da-dk | `» «` {oder `”`}, `–`, `§`, `€` |
| fi-fi | `”` {oder `» «`}, `–`, `§`, `€` |
| el-gr | `« »`, `·` (**U+00B7**, NFC-stabil), `€` |

Hinweis `el-gr`: U+0387 (Ano Teleia) ist kanonisch äquivalent zu U+00B7 und wird
von NFC dorthin normalisiert; U+037E (griechisches Fragezeichen) ist kanonisch
identisch mit `;` (U+003B, bereits Gruppe 1) — **kein eigener Codepoint** unter
Regel 7.

## Zeichen-Universum `Z` (Regel 5d)

Ein `text`-Label muss in `Z` liegen = Gruppe 1 ∪ Gruppe 2 ∪ Ziffern ∪ Zusatzzeichen
∪ Sprachzeichen der eigenen §2.2-Zeile ∪ die Buchstaben der Sprache (a–z, A–Z).

Zusatzzeichen (erlaubt, **nicht** Pflicht):

```
£ ^ ¹ ² ³ ¼ ½ ¬ ¢ µ ¥ ¶ · † ‡ ‰ ± × ÷ √ π ∆ • © ® ` 
„ “ ” ‘ ’ ‚ « » – — … ¿ ¡ № º ª
```

Alles andere ist ein Artefakt (`⌇` U+2307, `=\<`, `⇧` auf einer Zeichentaste).

## Alternates (Langdruck)

- max. **8** Einträge pro Taste, **genau 1 Codepoint** je Eintrag, NFC.
- nur Zeichen, **keine** Action-Namen, keine leeren Strings, keine Duplikate,
  nie das Zeichen der Taste selbst.
- In `shift` = **Großformen** der `base`-Alternates (gleiche Position) — das gilt
  **nur für Zeichen mit Großform**. Caseless Zeichen (`€ § – — … µ`) werden nicht
  gespiegelt (Regel 3, präzisiert in Rev 3 / Audit D3).
- `alternates` sind Stufe **B**: sie ersetzen **nie** ein §2.1/§2.2-Pflichtzeichen.

## Sonstige Regeln, die ein Asset erfüllen muss

1. **NFC** für alle Labels **und** Alternates (Regel 7).
2. Keine Steuer-/Formatzeichen, keine Kombinationszeichen allein (U+0300–U+036F),
   kein Leerraum als Label (Regel 5c).
3. Kein RTL-Zeichen in `ltr`-Assets (Regel 6).
4. Keine doppelten Labels je Ebene — Ausnahme: `shift`-Tasten, deren `base`-Zeichen
   dieselbe Ein-Codepoint-Großform haben (`σ`/`ς` → `Σ`).
5. Funktionstasten je Ebene: **genau eine** `space`, `backspace`, `enter`, `emoji`,
   `mic` (Regel 4, vom Test erzwungen); `shift` in `base`/`shift`,
   `layer-symbols`/`layer-base` je Symbol-/Buchstabenebene.
   `cursor-left`, `cursor-right` und `language` sind **Asset-Konvention** (in allen
   15 Assets vorhanden), aber **keine** Anforderung aus §1.4.
6. `label != value` ist **immer** ein Fehler (Regel 5a) — auch wenn das Label
   „nur" ein Escape-Rest ist.
