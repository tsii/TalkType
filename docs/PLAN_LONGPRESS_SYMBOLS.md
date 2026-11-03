# Plan v0.1.35 — Langdruck-Menüs auf Zahlen-/Symbolebene
**Quellenlage:** Research sa-b804a (21:4x) + Orchestrator-AOSP-Nachzug 21:5x
(`rowkeys_symbols1-3.xml` / `rowkeys_symbols_shift1-3.xml`, AOSP main 2026-11-02).
**Wichtig:** AOSP-main referenziert `moreKeys="!text/morekeys_symbols_*"` — die
String-Inhalte liegen in der main-Aufzweigung nicht mehr in `values/strings.xml`
(Verifikations-Lücke, unten offen). Harte Quellen bleiben: Repo-Belege
(`AOSP_ROWKEYS_BELEGE.md:198`, `DESIGN_DEDE_ZIELBILD.md:128`: morekeys_punctuation
enthält ¡ ¿ für es) + Nutzer-Auftrag (¿, Klammern) + Gboard-Konventionen [C].

## 1. Schema & Mechanik (belegt, kein Neubau)
- Feld: **`alternates`** (bestehend; Allowlist `LayoutParser.kt:100`; Doku
  `LAYOUT_SCHEMA_REF.md` „Alternates (Langdruck)": ≤8, je 1 Codepoint, NFC, nie das
  eigene Zeichen). **KEIN neues `longpress`-Feld** (Parser weist Unbekanntes ab).
- Menü-Bewaffnung gilt ebenenunabhängig für jede Text-Taste mit Alternates
  (`KeyboardView.kt:856`, Inhalt `:491-493`); `sourceKeys` je Ebene neu (`:270`) ⇒
  Symbole-Menüs leakten nicht in Basis. **Kein Code nötig**, nur Assets.
- Auf Symbol-Tasten **kein `hint`** setzen (Zeichnet nur base/shift, `KeyboardView.kt:469`;
  `withHint` würde es sonst als Zelle anhängen).
- Zeile-0-Geometrie (Ziffernreihe): B1-Regel `LongPressMenu.row0MenuBelowKey` — wird
  durch T1 (Zweizonenregel, Debugger) ersetzt; Menüs r0/r1 erscheinen OBERHALB.

## 2. Menü-Tabellen (sprachneutral ⇒ identisch in allen 15 Assets)
Quellen: [A] Nutzer-Auftrag · [B] AOSP-Beleg im Repo · [C] Gboard/SwiftKey-Konvention
(AOSP-main-Verifikation offen, s. Kopf) · [D] unklar → kein Menü.

### symbols (Hauptebene)
| Taste | alternates | Quelle |
|---|---|---|
| 1–0 | — | [D] Fraktions-Menüs (½ ⅓ ¼ ¹ ₁) bewusst raus (Z-Doppelpfad unklar) |
| - | ["–","—"] | [C] Gedankenstriche (Direkttasten shift-Ebene = Komfort-Doppelpfad, Präzedenz DESIGN_KEY_POPUP.md:287) |
| / | ["\\","|"] | [C] |
| ( | ["[","{","<"] | [A]+[C] |
| ) | ["]","}",">"] | [A]+[C] |
| ' | ["‘","’","‚"] | [C] dt. Typografie |
| " | ["„","“","”","‘","’","‚"] | [A]+[C] („ zuerst) |
| . | ["…"] | [C] |
| ? | ["¿"] | [A]+[B] |
| ! | ["¡"] | [A]+[B] |
| % | ["‰"] | [C] |
| + | ["±"] | [C] |
| : ; & @ # = _ * | — | [D] (=/</>/~ Menüs mit ≠ ≤ ≥ ≈ brauchen Z-Erweiterung — separater Scope) |

### symbolsShift
| Taste | alternates | Quelle |
|---|---|---|
| $ | ["€","£","¥"] | [C] de-Präferenz € zuerst; € Direkttaste derselben Ebene ⇒ Doppelpfad |
| alle übrigen | — | [D] |

## 3. Implementierung (Auftrag T4)
1. `alternates` gemäß Tabellen in `symbols`/`symbolsShift` ALLER 15 `layouts/*.json`
   (IDs parallel; Muster `FooterAcrossAssetsTest.kt:28-96`).
2. Kein LongPressMenu/KeyboardView-Code für Menüs selbst (T1/T2 betreffen nur Geometrie/Glyphen).
3. `LAYOUT_SCHEMA_REF.md`: Satz „Symbolebenen: gleiche Alternates-Regeln, sprachneutral
   in allen 15 Assets" ergänzen.

## 4. Tests — `SymbolLongpressAcrossAssetsTest` (neu, Muster FooterAcrossAssets)
- 15 Assets × {symbols, symbolsShift}: Ziffern-IDs „1"…„0" alternates leer.
- Exakte Mengen je id (question→["¿"], exclam→["¡"], paren-left/right, hyphen, slash,
  apostrophe, dquote, dot, percent, plus, dollar).
- Hygiene: ≤8, 1 Codepoint, NFC, ≠ eigenes Zeichen, keine Duplikate, alle in Z.
- Parität: Mengen je id über alle 15 Assets identisch.
- Gegenproben (rot): gelöschte alternate; `alternates` an Ziffer; `hint` auf Symbol-Taste.

## 5. Offene Verifikation (vor Merge nachziehbar)
- [C]-Zeilen: AOSP main `values/strings.xml` führt morekeys_* NICHT mehr — Inhalt wo?
  (möglicherweise onnx/overlay-Werte; Kandidat: `values-morekeys` in neueren Patches oder
  Gboard-Firebase-Assets). Bisher: Repo-Belege + Konvention ausreichend belegt;
  Unstimmigkeit wäre P3-Dekoration, kein Funktionsrisiko.
