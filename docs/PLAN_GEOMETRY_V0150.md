# PLAN — Geometrie-Korrektur V0.1.50 (Nutzer-Nachbesserung zu 0.1.49)

## Nutzer-Auftrag (wörtlich)
> „Bei dem Original ist das M in der dritten Zeile mittig unterhalb des Is und Os in der ersten
> Zeile. In deiner Version ist das M genau unterhalb des Is. Bitte mach das Enterzeichen
> entsprechend schmaler und passe die Geometrie der Tastatur an. Das gleiche gilt insbesondere
> für die Zeile darüber, die zweite Zeile. Da ist zwar das Backspace dazwischen gekommen, rechts
> neben dem L, aber du musst es sehr viel schmaler machen. Es soll nicht breiter sein als eine
> normale Taste selbst. Eher noch schmaler. Mach sie doch einmal nur drei Viertel so breit wie
> eine typische Key-Taste."

## Ableitung (eine Gleichung, keine Schätzung)
Bei Zeilen-Σ = 10 und Einheit u = 1080/10 = 108 px liegen die Tasten von Z0 bei
`[k·108, (k+1)·108]`. `i` ist k=7 (756–864), `o` ist k=8 (864–972) ⇒ **Fuge i|o = 864 px = 8.0
Einheiten**. M ist die 7. Taste von Z2 (nach ⇧ + y x c v b n m). Mit ⇧ = s, Buchstaben = w:
`M-Mitte = s + 6.5·w`. Soll `s + 6.5·w = 8.0` bei Σ `s + 7w + ⏎ = 10`.
Mit w = 1.0 (= Z0-Tastenbreite, wie in der Vorlage) folgt **s = 1.5** und **⏎ = 1.5** — also
genau die symmetrischen Flügel, die die Vorlage hat, und das Enter wird von 2.0 auf **1.5**
(216 → 162 px) schmaler, wie verlangt.

Backspace: „drei Viertel einer typischen Taste" ⇒ **⌫ = 0.75 · Buchstabenbreite**. Zeile 2
behält die halbe-Taste-Einrückung der Vorlage (0.5), die Buchstaben werden 1.0 ⇒
Σ(Z1) = 0.5 + 9 + 0.75 = **10.25** ⇒ u₁ = 105.37 px, Buchstaben 105.37 px, ⌫ 79.03 px
(= 0.75 · 105.37 ✓), Einrückung 52.7 px (= halbe Taste ✓).

## Soll-Tabellen (Einheiten; Σ je Zeile, da pro Zeile normalisiert wird)

### Buchstaben-Ebenen (base + shift)
| Zeile | Standard (de, en, …) | cs-cz | ru-ru |
|---|---|---|---|
| Z0 | 10 × 1.0 (Σ 10) → 108 px | wie Standard | unverändert (13 Keys, Σ 10) |
| Z1 | `m-left` 0.5 + 9 × 1.0 + `backspace` **0.75** (Σ 10.25) → 105.37 / **79.03** px | 0.5 + 10 × 1.0 + 0.75 (Σ 11.25) → 96.0 / **72.0** px | 0.5 + 11 × 1.0 + 0.75 (Σ 12.25) → 88.16 / **66.12** px |
| Z2 | `shift` **1.5** + 7 × 1.0 + `enter` **1.5** (Σ 10) → **162** / 108 / **162** px | `shift` 2.0 + 6 × 1.0 + `enter` 2.0 (Σ 10, unverändert) | `shift` **1.5** + 10 × 0.9 + `enter` **1.5** (Σ 12) |
| Z3 | unverändert: `symbols`1.5 · `language`1 · `emoji`1 · `space`6.5 · `period`1 · `mic`2 (Σ 13) | unverändert | unverändert |

**Kernprüfung:** M-Mitte = (1.5 + 6.5) · 108 = **864 px** = Fuge i|o ✓

### Symbol-Ebenen (symbols + symbolsShift)
| Zeile | neu |
|---|---|
| Z0 | 10 × 1.0 (unverändert) |
| Z1 | `m-left` 0.5 + 10 × 1.0 + `backspace` **0.75** (Σ 11.25) → Zeichen 96.0 / ⌫ **72.0** px |
| Z2 | unverändert: Toggle 1.0 + 10 × 0.75 + `enter` 1.5 (Σ 10) |
| Z3 | unverändert |

## Was unverändert bleibt
Theme, Schattierung, Ecken, Schriftgrößen, Höhen-Fit; Punkt zwischen Leertaste und Mikrofon;
Mikrofon rechts außen; Umschalt-Logik `#+=` / `?123` (V0.1.49).

## TDD
1. `GeometryV0149Test`: Σ-Assertions (Z1 10.25/11.25/12.25), `⌫ == 0.75 × Buchstabenbreite`,
   `enter == shift`, Pixel-Probe (105.37 / 79.03 / 108 / 162), **neu** `mCenteredUnderTheIAndOGap`
   (M-Mitte == 864 px ±0.5, aus dem echten `KeyboardModel`).
2. `LetterRowAlignmentAcrossAssetsTest`, `LayoutCompletenessTest`, `SymbolGridAcrossAssetsTest`
   nachziehen; Absichten erhalten, Toleranzen nicht aufweichen.
3. Volltest `:app:testDebugUnitTest --rerun-tasks`, XML `/tmp/v150_debug/`.
4. Emulator: Ist-Rects prüfen (⌫ ≈ 79 px, ⇧/⏎ ≈ 162 px, M-Mitte ≈ 864 px) + Sichtprüfung.
