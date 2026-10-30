# Layout-Audit (Rev 3) — Assets, Schema-Referenz, Regel-Lücken

**Stand:** 2026-10-27 · **Prüfgegenstand:** `app/src/main/assets/layouts/*.json` (15 Dateien),
`docs/LAYOUT_SCHEMA_REF.md`, verbindliche Quelle `docs/LAYOUT_REQUIREMENTS.md` (Rev 3).

**Methode:** Lesendes Python-Audit (`/tmp/audit2.py`, bewusst **nicht** im Repo) gegen
den JSON-Parser-Vertrag (`LayoutParser.kt`, `LayoutAsset.kt`) und die Regeln 1–9 /
§2.1 / §2.2 aus LAYOUT_REQUIREMENTS. Kein Gradle, keine Emulator-Interaktion, keine
Änderung an Kotlin oder Assets. Rohbefunde: `/tmp/audit2.json`.

**Ergebnis in einem Satz:** Die **Pflicht-Symbole (§2.1) sind in allen 15 Sprachen
vollständig** (der `&`-Rückbau ist erledigt), Schema/Zeilen/`label==value` sind
sauber — offen sind **eine Sprach-Matrix-Lücke (§2.2 „Satzzeichen") in 5 Sprachen (6 Befunde)**,
**3 fehlende `B`-Zeichen**, **1 fehlendes `A`-Zeichen** und **2 echte Regelkonflikte
in der Spezifikation** (NFC vs. U+0387/U+037E, Regel 8 vs. σ/ς→Σ).

## (a) Prüfmatrix Sprache × Prüfung

Legende: ✓ = bestanden · ✗ = Fix nötig · ! = Hinweis (Regel/Präzisierung, kein Asset-Fehler)

| Sprache | Schema/Zeilen | IDs/Labels | label=value | NFC | §2.1 G1 | §2.1 G2 | §2.2 A | §2.2 B | §2.2 SZ | shift | Verdikt |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `de-de` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix nötig** |
| `en-us` | ✓ | ✓ | ! | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix nötig** |
| `fr-fr` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `es-es` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `it-it` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | **Fix nötig** |
| `pt-pt` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | ✓ | **Fix nötig** |
| `nl-nl` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | **Fix nötig** |
| `pl-pl` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix nötig** |
| `tr-tr` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `ru-ru` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix nötig** |
| `cs-cz` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `sv-se` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `da-dk` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `fi-fi` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | OK |
| `el-gr` | ✓ | ✗ | ✓ | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | **Fix nötig** |

Geprüfte Einzelkriterien (je Asset, alle 4 Ebenen): Top-Level-Felder, `schemaVersion=1`,
Dateiname==`id`, `language`-Pflichtfelder + `direction=ltr` + Tag aus §2.2,
Zeilenzahl **paarweise** (`shift↔base`, `symbolsShift↔symbols`) und
Breitensummen je Zeilenindex, keine doppelten IDs, keine doppelten Labels,
`kind`/Aktion in Allowlist, `width>0`, Regel 5a–5d (inkl. `=\<`, `⌇`), Regel 6 (RTL),
Regel 7 (NFC für Labels **und** Alternates), Regel 3 (max 8, ein Codepoint, keine
Action-Namen, keine Duplikate), Regel 4 (space/backspace/enter/emoji/mic je Ebene,
shift bzw. layer-Wechsel), §2.1 Gruppe 1+Ziffern (je genau 1× auf `symbols`),
Gruppe 2 (1× auf `symbols` oder `symbolsShift`), §2.2 A/B/SZ, Regel 2 (shift-Großformen).

## (b) Befunde

### B1 — Assets mit Fixbedarf

| Asset | Regel | Befund | Evidenz (Feld) | Empfohlener Fix |
|---|---|---|---|---|
| `de-de` | §2.2-SZ | `–` (U+2013) fehlt als eigene Taste | `layers.symbolsShift` enthält `„ … “ — ¿ ¡` — kein `–` | `hyphen`-Shift oder freie Taste in `symbolsShift` → `–` |
| `en-us` | §2.2-SZ | `–` und `—` nur als Alternates, nicht als eigene Taste | `layers.base['m'].alternates = "—…–"`; `symbolsShift` hat `…`, aber kein `–`/`—` | `–`, `—` als eigene Tasten in `symbolsShift` (Platz ist da: 30/31 Textplätze) |
| `it-it` | §2.2-B | `ü` ist nirgends erreichbar | `layers.base`: `ú í î ó á` vorhanden, `ü` fehlt; keine `alternates` mit `ü` | `ü` als Alternate auf `u` (max-8-Grenze beachten: `u` hat nur `ú`) |
| `nl-nl` | §2.2-B | `ä` ist nirgends erreichbar | `layers.base['a'].alternates = "áà"`; `e→ëéèê`, `u→ü`, `i→ï`, `o→ö` | `ä` als Alternate auf `a` ergänzen (`áàä`) |
| `pt-pt` | §2.2-A | `à` nur Alternate, §2.2 fordert eigene Taste (Stufe A) | `layers.base['a'].alternates = "à"` | `à` als eigene `base`-Taste (Reihe 1/2 hat Platz) **oder** §2.2-Zeile auf `B` herabstufen (fachlich vertretbar, siehe D2) |
| `pl-pl` | §2.2-SZ | `”` fehlt | `layers.symbolsShift` enthält `„ … “ –` — kein `”` | `”` in `symbolsShift` ergänzen |
| `ru-ru` | §2.2-SZ | `–` fehlt | `layers.symbolsShift` enthält `« » … №` — kein `–` | `–` in `symbolsShift` ergänzen |
| `el-gr` | §2.2-SZ | `·` (Ano Teleia) nicht als eigene Taste | `layers.symbols['semicolon'].alternates` enthält U+0387, keine eigene Taste | eigene Taste mit **U+00B7** (NFC-stabil) in `symbolsShift` |
| `el-gr` | R7 | Alternate `·` (U+0387) ist **nicht NFC** (NFC → U+00B7) | `layers.symbols['semicolon'].alternates[0]` | Alternate auf U+00B7 umstellen (oder entfernen) |
| `el-gr` | R7 | Alternate `;` (U+037E) ist **nicht NFC** (NFC → `;`) | `layers.symbols['question'].alternates[0]` | Alternate entfernen — `;` ist bereits Gruppe 1 |
| `el-gr` | R8 | zwei `shift`-Tasten mit Label `Σ` (`σ`, `ς`) | `layers.shift`: `final-sigma→Σ`, `σ→Σ` | **kein Asset-Fix** — Regel-8-Ausnahme in den Anforderungen (D1) |

### B2 — Hinweise (kein Asset-Fehler, aber Regel-/Doku-Präzisierung)

| Asset | Regel | Befund | Evidenz | Empfehlung |
|---|---|---|---|---|
| `en-us` | R8 | Text-Label `‹`/`›` kollidiert mit der Legende der Cursor-Aktionen | `layers.symbolsShift` (Text `‹›`) vs. `cursor-left/right` | Regel 8 ausdrücklich auf **Text-Tasten** begrenzen; optional Textzeichen ersetzen |
| `fr-fr` | §2.2-A/B | `ê` ist **eigene** base-Taste, §2.2 fordert nur `B` („erreichbar") — Übererfüllung, kollidiert aber mit der Notiz „auf AZERTY nicht direkt belegt" | `layers.base` = `…opéêq…`, zusätzlich `e.alternates` enthält `ê` | Doku: §2.2-Notiz entschärfen („darf eigene Taste sein") + Doppelpfad `e`/`ê` bereinigen |
| `fr-fr` | R3 | `à` ist eigene Taste **und** Alternate von `a` | `layers.base['a'].alternates = "æâà"` | Doppelpfad entfernen (Regel 3 meint Eindeutigkeit des Tippwegs) |
| `de-de`, `en-us` | R3-alt | Shift-Alternates spiegeln base-Alternates nur für **Buchstaben**; caseless Zeichen (`€ § – — … µ`) fehlen dort — fachlich korrekt | `de-de shift['e'].alternates = ÉÈÊËĘ` (ohne `€`), `en-us shift['s']` ohne `§` | Regel 3 präzisieren: Spiegelung nur für Zeichen **mit** Großform (D3) |

## (c) Korrekturliste `docs/LAYOUT_SCHEMA_REF.md`

Alle Punkte sind **umgesetzt** (Datei korrigiert). Kurzfassung der Fehler:

| # | Stelle | War falsch/unpräzise | Korrekt |
|---|---|---|---|
| 1 | Zeilenlängen | „`base`/`shift` gleich lang" — klang nach Tastenzahl | Geprüft wird **gleiche Zeilenzahl + gleiche Breitensumme je Zeilenindex**, paarweise `shift↔base` und `symbolsShift↔symbols` (`LayoutParser.validateSemantics`) |
| 2 | Top-Level | „keine weiteren Felder" | Parser erzwingt das nur in `language`, `layers.<ebene>`, Key-Objekt, `deadKeys.<id>`; top-level sind 5 Felder **Konvention** |
| 3 | `id` | Format fehlte | `^[a-z0-9-]+$`, Dateiname **muss** `<id>.json` sein (Repository lädt über die ID) |
| 4 | `language` | Felder genannt, Regeln nicht | genau `tag`/`label`/`caseLocale`/`direction`; `direction ∈ {ltr,rtl}`; `tag` BCP-47 (`^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$`); `tag` muss der §2.2-Zeile entsprechen |
| 5 | `deadKeys` | nur `{}` gezeigt, Struktur unbekannt | Sub-Schema dokumentiert: `{"<id>": {"spacing": str, "combining": str?, "compose": {"<basis>": "<zeichen>"}}}` + Pflicht: jede `kind:"dead"`-Taste referenziert einen existierenden Eintrag (Parser prüft das) |
| 6 | `caps` | „optionale alternative Beschriftung" | Feld existiert (`String`), **kein Asset nutzt es**; die Aktion `caps` steht in der Allowlist, wird ebenfalls nicht genutzt (Doppeltipp-Semantik derzeit nicht implementiert) |
| 7 | Ziffern | „Shift-Gegenstücke (`! @ # $ % ^ & * ( )`)" | **Falsch und gefährlich**: diese Zeichen sind §2.1-Gruppe-1-Pflicht auf `symbols`; sie auf `symbolsShift` zu wiederholen verletzt Regel 8. Richtig: `symbolsShift` trägt `¹²³¼½¬°…`, keine Gruppe-1-Zeichen |
| 8 | Duplikate | „ein Zeichen nur einmal als `value`" | Ausnahme dokumentiert: `σ`/`ς` teilen in `shift` das Label `Σ` (Regel-8-Ausnahme D1); Scope = Text-Tasten (Action-Legenden wie `‹ ›` sind separat) |
| 9 | Funktionstasten | „in **jeder** Ebene … cursor-left, cursor-right" | Anforderung (§1.4) ist schwächer: space, backspace, shift/layer-base, enter, emoji, mic; `cursor-*`/`language` sind **Asset-Konvention**, keine Pflicht |
| 10 | Platzkontrolle | „`symbols` = 11/11/8" | Realität: 4 Reihen (Text + Aktionszeile), Textreihen meist `11/11/10`; `el-gr`/`da-dk`/`fi-fi` 31, `de-de`/`en-us` 30 Textplätze. Als Beispiel, nicht als Regel formuliert |
| 11 | Sprach-Tabelle `fr-fr` | `ê` als Pflicht-Taste | §2.2: `A = é è à ç ù`, `ê` nur `B`; Asset hat `ê` zusätzlich (Übererfüllung) — Tabelle entsprechend getrennt |
| 12 | Sprach-Tabelle `tr-tr` | `i` fehlte | `i` **ist** eigene `base`-Taste (neben `ı`), shift `İ`/`I` |
| 13 | Sprach-Tabelle `pt-pt` | `à` fehlte in der A-Liste | `à` gehört zu `A` (offener Asset-Fix B1) |
| 14 | Satzzeichen §2.2 | **komplett gefehlt** | neue Tabelle „Sprachspezifische Satzzeichen" — Ursache der 6 SZ-Befunde |
| 15 | Regeln 5c/5d/6/7/8 | nicht erwähnt | Verweis + Kurzfassung: NFC (Labels **und** Alternates), keine Steuer-/Kombinationszeichen, `label ∈ Z` (Pflicht ∪ Zusatz ∪ Sprachzeichen), RTL-Verbot, Duplikatverbot |
| 16 | alternates | nur „max 8, nur Zeichen" | ergänzt: genau 1 Codepoint, NFC, keine Duplikate, nicht das eigene Zeichen, `shift`-Alternates = Großformen der `base`-Alternates **nur für Zeichen mit Großform** |
| 17 | Regel 5e | fehlte | Action-Tasten tragen **kein** Zeichen aus dem Zeichen-Universum, nur Legenden (`⇧ ⌫ ⏎ 🌐 😊 ?123 ABC Leer ‹ ›`) oder ein Wort (`Leer`, `Διάστημα`) |
| 18 | Artefakte | Beispiel `=\<`/`⌇` ohne Regelbezug | Verweis auf Regel 5a/5b (label≠value, 2 Codepoints) bzw. 5d (nicht in `Z`) |

## (d) Spezifikations-Korrekturen (D1–D3), in LAYOUT_REQUIREMENTS.md umgesetzt

- **D1 — Regel 8 braucht eine Ausnahme.** Griechisch hat zwei Tasten `σ` und `ς`;
  beide Großformen sind `Σ` (Unicode-Ein-Codepoint-Großform, §3 legt `ς→Σ` fest).
  Damit hat `shift` zwangsläufig zwei Tasten mit Label `Σ` — Regel 8 („keine
  doppelten Labels") und Regel 2 („shift = Großform von base") sind sonst
  unvereinbar. **Neue Fassung:** Duplikatverbot gilt für Text-Tasten; Ausnahme
  sind `shift`-Tasten, deren `base`-Zeichen dieselbe Ein-Codepoint-Großform haben
  (nur `σ`/`ς` → `Σ`; Asset-Namen `σ`, `final-sigma`).
- **D2 — §2.2 `pt-pt`: `à` bleibt `A`.** `à` ist im Portugiesischen (Crase) ein
  eigenes Graphem, nicht nur ein Akzent-Variant von `a`; es gehört auf eine eigene
  Taste. Der Asset-Fix ist billig (Reihe 1/2 hat Platz), deshalb keine Herabstufung.
- **D3 — Regel 3 (Alternate-Spiegelung) nur für Zeichen mit Großform.** `€ § – — … µ`
  sind caseless; sie in `shift` zu spiegeln wäre falsch. Die Spiegelungsregel gilt
  für Buchstaben; caseless Zeichen bleiben in `base` (oder dürfen in `shift`
  identisch stehen, wenn die Implementierung es braucht).

Zusätzlich in §2.2 `el-gr` präzisiert: die Ano Teleia wird als **U+00B7**
(NFC-stabil) geführt — U+0387 ist kanonisch äquivalent und wird von NFC zu U+00B7
normalisiert; das „griechische Fragezeichen" U+037E ist kanonisch identisch mit
`;` (U+003B) und damit bereits durch Gruppe 1 abgedeckt — ein eigener Codepoint
ist unter Regel 7 (NFC) nicht darstellbar.

## (e) Verdikt je Sprache

| Sprache | Verdikt | Offene Punkte |
|---|---|---|
| `de-de` | **Fix nötig** | `–` als eigene Taste (§2.2-SZ) |
| `en-us` | **Fix nötig** | `–`, `—` als eigene Tasten; Hinweis `‹ ›`-Label-Kollision |
| `fr-fr` | OK | Hinweis: `ê` Übererfüllung, Doppelpfad `à`/`a` |
| `es-es` | OK | — |
| `it-it` | **Fix nötig** | `ü` erreichbar machen (§2.2-B) |
| `pt-pt` | **Fix nötig** | `à` als eigene Taste (§2.2-A) |
| `nl-nl` | **Fix nötig** | `ä` erreichbar machen (§2.2-B) |
| `pl-pl` | **Fix nötig** | `”` ergänzen (§2.2-SZ) |
| `tr-tr` | OK | — |
| `ru-ru` | **Fix nötig** | `–` ergänzen (§2.2-SZ) |
| `cs-cz` | OK | — |
| `sv-se` | OK | — |
| `da-dk` | OK | — |
| `fi-fi` | OK | — |
| `el-gr` | **Fix nötig** | `·` (U+00B7) als eigene Taste; zwei nicht-NFC-Alternates entfernen; R8-Ausnahme ist Doku (D1) |

**Summe: 8 von 15 Assets mit Fixbedarf**, 7 OK. Hinweise (kein Fix) gibt es für
`fr-fr` (Übererfüllung `ê`, Doppelpfad `à`/`a`) und innerhalb der Fix-Assets für
`de-de`/`en-us` (Regel-3-Präzisierung, Audit D3).

## (f) Testabdeckung — warum die Tests grün sind

`LayoutCompletenessTest` prüft §2.2 **nur für `base`** (A- und B-Mengen) und §2.1
für die Symbolebenen. **Nicht** geprüft und deshalb durchgerutscht:

1. §2.2-Spalte „Satzzeichen" (die 6 SZ-Befunde) — die Matrix hat keine Symbol-Spalte.
2. NFC von Labels/Alternates (Regel 7) — `el-gr` U+0387/U+037E.
3. Regel 8 außerhalb von `symbols`/`symbolsShift` (nur dort werden doppelte
   Text-`value`s geprüft) — `el-gr` `Σ` in `shift` fällt nicht auf.
4. Regel 3 im Detail (ein Codepoint, Duplikate, eigenes Zeichen, Spiegelung).
5. Regel 5d (Zeichen-Universum `Z`) — nur die zwei Beispiel-Artefakte.

**Empfehlung (Coders, Stufe 3):** `PunctuationCoverageTest` um die §2.2-SZ-Matrix
erweitern, `NfcLabelTest` (Labels **und** Alternates), `LayoutArtifactTest`
(Regel 5a–5e + `Z`) und eine Duplikatprüfung über **alle** Ebenen. Damit sind die
8 Befunde dauerhaft abgesichert statt einmalig repariert.
