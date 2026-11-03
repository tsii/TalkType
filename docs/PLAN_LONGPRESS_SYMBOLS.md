# Plan v0.1.35 — long-press menus on the digits/symbols layer
**Source situation:** research sa-b804a (21:4x) + orchestrator AOSP
follow-up 21:5x (`rowkeys_symbols1-3.xml` / `rowkeys_symbols_shift1-3.xml`,
AOSP main 2026-11-02).
**Important:** AOSP main references `moreKeys="!text/morekeys_symbols_*"` —
the string contents are no longer in `values/strings.xml` in the main branch
(verification gap, open below). The hard sources remain: the repo evidence
(`AOSP_ROWKEYS_BELEGE.md:198`, `DESIGN_DEDE_ZIELBILD.md:128`:
morekeys_punctuation contains ¡ ¿ for es) + the user's task (¿, brackets) +
Gboard conventions [C].

## 1. Schema & mechanics (evidenced, no new construction)
- Field: **`alternates`** (existing; allowlist `LayoutParser.kt:100`; docs
  `LAYOUT_SCHEMA_REF.md` "Alternates (long-press)": ≤8, 1 codepoint each,
  NFC, never the key's own character). **NO new `longpress` field** (the
  parser rejects unknown fields).
- The menu arming applies layer-independently to every text key with
  alternates (`KeyboardView.kt:856`, content `:491-493`); `sourceKeys` are
  rebuilt per layer (`:270`) ⇒ the symbol menus did not leak into base. **No
  code needed**, assets only.
- Do **not** set `hint` on symbol keys (it only draws base/shift,
  `KeyboardView.kt:469`; `withHint` would otherwise append it as a cell).
- Row-0 geometry (digit row): B1 rule `LongPressMenu.row0MenuBelowKey` — is
  replaced by T1 (two-zone rule, debugger); r0/r1 menus appear ABOVE.

## 2. Menu tables (language-neutral ⇒ identical in all 15 assets)
Sources: [A] user task · [B] AOSP evidence in the repo · [C] Gboard/SwiftKey
convention (AOSP-main verification open, see header) · [D] unclear → no menu.

### symbols (main layer)
| Key | alternates | Source |
|---|---|---|
| 1–0 | — | [D] fraction menus (½ ⅓ ¼ ¹ ₁) deliberately left out (the Z double path is unclear) |
| - | ["–","—"] | [C] dashes (direct keys on the shift layer = comfort double path, precedence DESIGN_KEY_POPUP.md:287) |
| / | ["\\","|"] | [C] |
| ( | ["[","{","<"] | [A]+[C] |
| ) | ["]","}",">"] | [A]+[C] |
| ' | ["‘","’","‚"] | [C] German typography |
| " | ["„","“","”","‘","’","‚"] | [A]+[C] („ first) |
| . | ["…"] | [C] |
| ? | ["¿"] | [A]+[B] |
| ! | ["¡"] | [A]+[B] |
| % | ["‰"] | [C] |
| + | ["±"] | [C] |
| : ; & @ # = _ * | — | [D] (=/</>/~ menus with ≠ ≤ ≥ ≈ need a Z extension — separate scope) |

### symbolsShift
| Key | alternates | Source |
|---|---|---|
| $ | ["€","£","¥"] | [C] de preference € first; € direct key on the same layer ⇒ double path |
| all others | — | [D] |

## 3. Implementation (task T4)
1. `alternates` per the tables in `symbols`/`symbolsShift` of ALL 15
   `layouts/*.json` (IDs in parallel; pattern
   `FooterAcrossAssetsTest.kt:28-96`).
2. No LongPressMenu/KeyboardView code for the menus themselves (T1/T2
   concern geometry/glyphs only).
3. `LAYOUT_SCHEMA_REF.md`: add the sentence "symbol layers: the same
   alternates rules, language-neutral in all 15 assets".

## 4. Tests — `SymbolLongpressAcrossAssetsTest` (new, pattern
FooterAcrossAssets)
- 15 assets × {symbols, symbolsShift}: the digit IDs "1"…"0" have empty
  alternates.
- Exact sets per id (question→["¿"], exclam→["¡"], paren-left/right, hyphen,
  slash, apostrophe, dquote, dot, percent, plus, dollar).
- Hygiene: ≤8, 1 codepoint, NFC, ≠ the key's own character, no duplicates,
  all in Z.
- Parity: the sets per id are identical across all 15 assets.
- Cross-checks (red): a deleted alternate; `alternates` on a digit; `hint` on
  a symbol key.

## 5. Open verification (can be followed up before the merge)
- The [C] rows: AOSP main `values/strings.xml` no longer carries morekeys_*
  — where is the content? (possibly onnx/overlay values; candidate:
  `values-morekeys` in newer patches or Gboard Firebase assets). So far: the
  repo evidence + convention are sufficiently evidenced; an inconsistency
  would be P3 decoration, not a functional risk.
