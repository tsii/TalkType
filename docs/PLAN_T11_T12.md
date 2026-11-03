# PLAN T11/T12 — r2 boundary grid + symbol layer redistribution (build spec)

Status: adopted from oracle analysis 2026-11-03 · Supersedes parts of the T8
derivation (LetterRowAlignmentAcrossAssetsTest.kt:20-26, DESIGN_TASTATUR_V2 §10)
and the T6 plan order (SymbolGridAcrossAssetsTest.kt:66-68).

T11 — base/shift r2 rebuild: 8 equal keys (7 letters + a new period key before
enter), each key CENTER on a boundary point of the home row (r1); shift/enter take
the remainder. Footer r3: period out, space 6.5 -> 7.5 (Sigma stays 13).
T12 — symbols/symbolsShift redistribution: ': ;' down from r1 to r2, '% +' up from
r2 to r1, r2 sequence mirrored (dot far right); shift 1.5 / enter 1.0 stay fixed.

Units: 1 u = 108 px for rows r0-r2 (Sigma 10, KEY_GAP=0, KeyboardModel.kt:70-72
per-row unit = usable/Sigma); footer rows (Sigma 13): u_f = 1080/13 = 83.07692 px.
Tolerance +/-0.001 u (<=0.11 px, LayoutCompletenessTest.kt:546).

## 1. Terms (as-is, all 15 assets)
- 13 std assets base/shift: r0 10x1.0; r1 m-left 0.5 + 9x0.94444 + backspace 1.0;
  r2 shift 0.89352 + 7x1.10185 + enter 1.39352 (T8); r3 footer Sigma13.
- cs-cz: r1 0.5 + 10x0.85 + 1.0; r2 shift 1.88889 + 6x1.03704 + enter 1.88889.
- ru-ru (D27): r2 shift 1 + 10x1.0 + enter 1 = Sigma12; footer period carries @ as
  alternates[0].

## 2. T11 standard assets
Boundaries g_i = 0.5 + i*0.94444 (i=1..8): 1.44444 2.38888 3.33332 4.27776
5.22220 6.16664 7.11108 8.05552 (156/258/360/462/564/666/768/870 px).
g1 = 13/9 (T8 Y center), g8 = 145/18 (T8 M center), delta <= 0.004 px.
New r2 key width w = 0.94444 (102.00 px) — cells shrink 119->102 px.

### Variant A — boundary-exact, asymmetric shift/enter
r2 = shift 0.97222 + 8x0.94444 (letters then period) + enter 1.47226, Sigma 10.
px: 105.00 / 8x102.00 / 159.00. Centers exactly on g1..g8; M moves from g8 to g7,
period takes g8. Block center 513 px (T8 value).

### Variant B — symmetric shift/enter
r2 = shift 1.22224 + 8x0.94444 + enter 1.22224, Sigma 10. px: 132.00 / 8x102.00 /
132.00. Centers 1.69446..8.27774, symmetric about 5.00 (540 px); every center
+0.25002 u (+27.00 px) right of its boundary.

Variant C (both): out of scope (breaks F40 gates).

### Period key (r2 slot 8, 14 assets, base AND shift)
id 'period', kind text, value '.', width 0.94444; alternates = F54 standard cells
(",","!", "?", ":", ";", "(", ")") — the footer period's menu moves up with it
(menu checks position-independent). M keeps its @ alternate.

### cs-cz (10-key home row)
Boundaries h_i = 0.5 + i*0.85 (i=1..9). 7 keys (6 letters + period) a w=0.85.
A': span h2..h8: shift 1.77500 / 7x0.85 / enter 2.27500.
B': symmetric: shift=enter=2.02500.

### ru-ru — EXEMPT from T11 (D27 locked, Sigma12, Cyrillic 11-letter home row,
@ on footer period; user read-only rule). ru keeps 6-key footer.

### Footer r3 (14 assets): ?123 1.5 + globe 1 + emoji 1 + space 7.5 + mic 2
(Sigma 13; space px 540 -> 623.08). ru unchanged.

## 3. T12 (ALL 15 assets incl. ru — symbol layers not D27-locked)
symbols r1 (10x0.9 + backspace 1.0), D2=A:  - + / ( ) & @ = # %
symbols r2 (shift 1.5 + 10x0.75 + enter 1.0):  : ; , ' " _ * ! ? .
Menus travel id-keyed (percent keeps permille, plus keeps plusminus, etc.).
symbolsShift: full analog redistribution per asset (D4 primary): dot-family far
right in r2; colon/semicolon ids down to r2 lead; two rightmost r2 cells up to r1
vacancies; migration invariant before==after per asset (char sets of both symbol
layers) is the hard gate — T12 is a pure permutation.
de-de example: r1 -> euro _ \ [ ] { } § „ “ ; r2 -> ¶ · $ ~ > < ¡ ¿ — …

## 4. Applicability
13 std + cs: T11 yes (cs own variant), T12 yes. ru: T11 NO, T12 yes.

## 5. Sum control
r0/r1: 10->10 everywhere (r1 untouched in letter layers). r2 letter layers:
10->10 std/cs; ru 12->12 untouched. r3: 13->13 (period out, space 7.5; ru intact).
symbols r1/r2: 10->10 contents only.

## 6. Test impact
- LayoutCompletenessTest checkGeometry: new want widths incl. period in letter
  count; T8 center check -> T11 invariant per chosen D1; M-key lookup must exclude
  id 'period' (~line 675). F54 period test green unchanged.
- LetterRowAlignmentAcrossAssetsTest: rewrite constants/invariants (cs 7x0.85);
  ru untouched; '@ ueber M' lookup excludes period.
- FooterAcrossAssetsTest: 14 assets 5-key footer, space 7.5, no period; ru branch
  keeps 6-key footer.
- SymbolGridAcrossAssetsTest: r1 order '- + / ( ) & @ = # %', r2 order
  ': ; , \' " _ * ! ? .'; anchoredSets UNCHANGED (pure permutation proof).
- SymbolLongpressAcrossAssetsTest, PunctuationCoverageTest, LayoutParserTest,
  KeyboardModelTest, TrailingSpace: green unchanged.
Target: 1191/0 + px evidence.

## 7. Decision points (ENTSCHIEDEN 2026-11-03 19:0x)
D1=B (Nutzerwahl: symmetrisch, +27px) · D2=A (fixed) · D3 entfaellt · D4 voll-analog (T12 umgesetzt, verifiziert 4/0).

Former open points:
D1: A (boundary-exact) vs B (symmetric, +27 px offset). D2: A (fixed by user word
'Plus zum Minus'). D3: h2..h8 (only if D1=A). D4: full analog (primary).

## 8. Implementation order
1. sign-off D1-D4. 2. T12 first (script rebuild_symbol_layers pattern) + grid test
update + suite. 3. T11 (13 std + cs, period up, footer) + 3 test files + suite.
4. px evidence + DESIGN_TASTATUR_V2 §10 note + MESSWERTE doc.

## 9. Umsetzungstands (orchestrator)
- T12 DONE (sa-2e63bf30): 15/15 Assets Permutation, Zeichenvorrat vor==nach hart, Symbol-Suite 4/0 (orchestrator nachgefahren). Anmerkung: es/fr/it/nl/pt trugen keine colon/semicolon-Slot-IDs in symbolsShift -> dort kein WTA (vakuum), Inhalte unangetastet — Review-Punkt.
- T11 LAEUFT (sa-efdc347a, Variante B).
