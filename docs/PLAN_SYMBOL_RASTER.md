# Plan v0.1.36 — symbol grid with stable ⌫/⏎ (letter slots)
Source: research sa-076eb (2026-11-02 22:3x), full text in the transcript;
the orchestrator adopted it 1:1.
**User task:** ⌫/⏎ on the digits/symbols layer at the same position as in
the letter grid; distribute the rest sensibly (overlay allowed); **no
character to the right of the space bar** (symbol layer). AOSP keeps ⌫ at the
row end even on the symbol layer; ⏎ AOSP/Gboard pulls into the footer —
**the user's geometry beats the model** (discussed, approved).

## Acceptances (orchestrator decision, documented)
- "Character to the right of the space bar" = the rendered ⏎ of the symbol
  layer (it moves to the r2 end); the "." in the LETTER footer stays (the
  task context = the digits keyboard).
- da-dk/fi-fi surplus symbolsShift: the ‰ direct key gives way in favor of
  the unified grid — reachability via the T4 menu percent→[‰] (double path).
  ¿/¡ stay direct keys.

## Target grid (language-neutral; Σ per row; 1u=108px) — symbols
| Row | Occupancy | Σ |
|---|---|---|
| r0 | 1..0 at 1.0 each (10 digits; hyphen moves out) | 10 |
| r1 | - / : ; ( ) & @ = # at 0.9 each · **⌫ 1.0 at the end** | 10 |
| r2 | ⇧1.5 · . , ? ! ' " % + _ * at 0.75 each · **⏎ 1.0 at the end** | 10 |
| r3 | ABC1.5 · 🌐1 · 😊1 · space 7.5 · 🎤2 (**no ⏎, no text character next to the space bar**; mic last) | 13 |

**symbolsShift mirrors the structure 10/10/10/13** (no jumping on the ⇧
toggle):
r0 10×1.0 (¹ ² ³ ¼ ½ ¬ | ¢ – ° where present); r1 10×0.9ers + ⌫1.0; r2
⇧1.5 + 10×0.75 + ⏎1.0; r3 like symbols. Rows with <10 keys: width = 7.5/n
resp. 9.0/n, Σ stays; never invent characters.
⌫/⏎ exactness: the preceding block 9.0/10 = a 90 % slot like base r1/r2;
KEY_GAP_DP=0 ⇒ pixel-identical (the gap calculation Δ≤0.4px < touch slop,
documented).

## Migration (is→target; no character is lost)
- de-de symbols: hyphen r0→r1; ' " r1→r2; ⌫ r2→r1 end (1.5→1.0); ⏎
  footer→r2 end; space 7→7.5, 🎤1→2. The 30 direct keys stay 30.
- de-de symbolsShift: € r0→r1; „ “ r1→r2; ⌫/⏎/footer analogously. 30 stay
  30.
- da-dk/fi-fi/el-gr: € (symbols r0 position 11) → symbolsShift direct key;
  symbols r0 becomes a pure 10-digit row. da/fi symbolsShift 31→30 (‰→menu,
  see acceptance).
- the remaining 11 assets: pure repositioning; symbols becomes content-wise
  identical in all 15; symbolsShift keeps the asset-specific mandatory
  characters (¿ ¡ º ª № « » · …) as direct keys.
- Digit IDs are asset-local (do not touch); the T4 alternates move with
  their ids.
- Long-press geometry: hyphen r0→middle row ⇒ the middle-row rule applies
  (code present).

## Tests
1. **FooterAcrossAssetsTest**: rewrite `symbolEbenenBehaltenAltFooterMitEnter`
   to Σ13 = ABC1.5/🌐1/😊1/space7.5/🎤2, mic last, NO ⏎ (justified
   migration).
2. **NEW SymbolGridAcrossAssetsTest**: 15×2: Σ=[10,10,10,13]±0.001; ⌫ exactly
   1× r1 end 1.0; ⏎ exactly 1× r2 end 1.0; ⇧ r2[0] 1.5; r0 10 digits at 1.0
   each; per asset the character set (direct keys+menus) before==after
   (anchored migration list).
3. Staying green: LayoutArtifactTest, LayoutCompletenessTest (⇧1.5 + 1×⌫/⏎
   fulfilled), PunctuationCoverageTest (da/fi ‰ via menu),
   SymbolLongpressAcrossAssetsTest (id-based), KeyboardModelTest (footer
   without ⏎ covered).

## Implementation: ONE coder task with a Python asset script (Σ drift
protection)
Files: 15× layouts/*.json + FooterAcrossAssetsTest +
SymbolGridAcrossAssetsTest + 1 sentence in LAYOUT_SCHEMA_REF.md ("symbol
layer grid 10/10/10/13; ⌫ r1 end, ⏎ r2 end like the letters; no text
character next to the space bar"). List the script in the report (tools/ ok).

## M rule (v0.1.37, T7) — r2 overflow: 10 direct cells at 0.75 + menu
handoff
First-run finding: da-dk/fi-fi symbolsShift r2 had 11, el-gr 12 direct
characters — the script distributed the r2 budget 7.5 over 11/12 cells
(0.6818181818 resp. 0.625), below the 0.75 cell width of all other assets.

**Plan deviation (documented):** the T7 task "reduce to 9 at 0.75" is
arithmetically impossible — ⇧1.5 + 9·0.75 + ⏎1.0 = **Σ 9.25 ≠ 10.000** (the
Σ check SymbolGridAcrossAssetsTest would stay red, contradicting the task
"EVERY r2 row exactly 10.000"). A "10-cell r2" at 0.75 is the MAXIMUM of the
budget (10·0.75 = 7.5 exactly). ⇒ **Correction: 10 direct cells at 0.75; the
overflow characters drop out and remain reachable via the existing menus
(menu handoff)**:
- da-dk/fi-fi: "…" drops out (11th cell) → reachable via the T4 dot menu
  [. → …];
- el-gr: "–" and "…" drop out → hyphen menu [–, —] resp. dot menu […].
No new menus, no schema field; the script's before==after character-set
assert stays sharp (the script additionally checks: EVERY dropped character
must appear in the alternates). The script is idempotent from T7 (a second
run on migrated assets: € migration and ‰ drop recognized). Anchored:
SymbolGridAcrossAssetsTest (c2) — r2 ≤ 10 direct cells at 0.75;
SymbolMenuClampAcrossAssetsTest — menu clamp invariant (value+alternates ≤
8, leftmost cell edge ≥ 0, right edge ≤ view width with centering+clamping
over the base key; red evidence: menu size 10 = 10·126 + 11·11 = 1381 px >
1080 px view — NO position holds all cells) + T6 character cross-check (…
” ª º ‰ ± per asset).
