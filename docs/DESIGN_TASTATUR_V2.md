# DESIGN Keyboard V2 — full-width grid (normative)

**Basis:** view **1080 px** wide, density **2.625**, 4 rows × **144 px**
(keyboard 1080×576, below it the status row 48 dp = 126 px). **New unit: 1u =
108 px** (r0 = 10 keys **edge-to-edge without margins**, ⌫ leaves r0). Footer r3
remains unchanged (its own unit, see below). Target design de-de; special cases
below.

## 1) r0 — 10 keys without margins
`q w e r t z u i o p`, **108 px** each (10 × 108 = 1080). No side margins, no ⌫
(⌫ newly in r2).

## 2) r1 — 9 letters + @ (variant i, fixed)
Variant i: **offset 54 px (0.5u) + 9 × 102 px + @ 108 px** = 54 + 918 + 108 =
**1080** ✓. a: 54–156, s: 156–258, d: 258–360, f: 360–462, g: 462–564, h:
564–666, j: 666–768, k: 768–870, l: 870–972, @: 972–1080. a center 105 vs.
q/w center division 108 → **3 px deviation, not perceptible**.
*Variant ii (all 108, a from x=0) rejected; iii = i.* **Rationale (two
sentences):** the stock offset (a centered between q and w) is the ergonomic
signature of the QWERTZ stagger row — abandoning it would be a 6 % width gain at
the cost of simultaneously destroying the finger-muscle expectation and the
optical offset language relative to r0. At 102 px, the key is still **+13 %
wider than V1 (90 px)**, so the gain of V2 almost entirely goes to the letters.

## 3) r2 — ⇧ + 7 letters + ⌫ + ⏎ (⌫ newly between M and ⏎)
**⇧ 204 px (1.889u) + 7 × 96 px (y x c v b n m) + ⌫ 96 px (0.889u) + ⏎ 108 px
(1u)** = 204 + 672 + 96 + 108 = **1080** ✓. Letter block 204–876, **center
exactly 540 px (5u)** ✓. Positions: ⇧ 0–204, y 204–300, x 300–396, c 396–492, v
492–588, b 588–684, n 684–780, m 780–876, ⌫ 876–972, ⏎ 972–1080. (The variant
"7 × 108" would force ⇧=162 and squeeze ⌫/⏎ to 81 px — too narrow for ⏎ as a thumb
key; 96 px = +7 % vs. V1 with the 540 center intact.)

> **T8 (v0.1.38) — documented deviation:** the user's assignment for Y row
> centering (Y centered under A/S, M centered under K/L; @ removed, reachable
> via the M long-press menu) overrides the uniformity of this row: r2 = ⇧ 96.5 px
> (0.89352u) + 7 × 119 px (1.10185u) + ⏎ 150.5 px (1.39352u) — r2 becomes the
> WIDEST letter row, the block center moves 540→513 px; cs-cz ⇧=⏎ 1.88889u
> symmetric, ru-ru remains the D27 exception (Σ12).

## 4) r3 — footer **unchanged** (confirmation)
Widths **125 / 83 / 83 / 540 / 83 / 166 px** (1.5/1/1/6.5/1/2 u at **u_footer =
1080/13 = 83.077 px**; px resolution of the sum 1080). Aligning to u=108 is
**explicitly not** proposed: the footer ergonomics (space 6.5u) have been
accepted since F29; a rework risks the accepted thumb space gain.

## 5) Space labeling (endonym of the active language)
The space key shows the **endonym name of the active language** from
`LanguageCycle.ENDONYMS` (`ime/VoiceKeyboardService.kt:45-61`, resolution via
`SpaceLabelRule` through `KeyboardView.displayLabel`) — the V2 graphics show the
de-de display "**Deutsch**". `SpaceLabelRule` ensures that a
**non-whitespace label of the asset does not override the endonym**; fallback
`R.string.key_space_label` ("Space") only when no endonym is available.
Endonyms (de-DE and en-US first): **de-de "Deutsch"**, **en-us "English (US)"**,
fr-fr "Français", es-es "Español", it-it "Italiano", pt-pt "Português", nl-nl
"Nederlands", pl-pl "Polski", tr-tr "Türkçe", ru-ru "Русский", cs-cz "Čeština",
sv-se "Svenska", da-dk "Dansk", fi-fi "Suomi", el-gr "Ελληνικά". Overview:
`docs/preview/space_endonyme.png`.

## 6) Special cases (one line each)
- **fr-fr** (AzERT, 10 letters in r0): r1 = m-left 0.5u + 9 × 0.9444u (102 px) +
  @ 1u — **the same pattern as de-de** (the 10th AzERT letter p sits in r0); the
  implementation (F36) is built exactly that way (deviating from the draft line
  "10×98 + @100, offset 0" — the stagger pattern was kept).
- **ru-ru** (11-letter rows): 11 × **96 px** + **12 px** side grid each
  (1056 + 24 = 1080), digit row ⇧1 + 10 digits + ⏎1 (D27, **Σ 12u**); ⌫ is missing
  in the base/shift rows of r2 (it lives on the symbols layer).
- **cs-cz** (6-key r2): **⇧ 204 + 6 × 112 px + ⌫ 96 + ⏎ 108 = 1080**, letter
  block center 204 + 3×112 = **540** ✓ (same ↕/⌫/⏎ widths as de-de).

## 7) Colors (hex, light / dark) — "calm overall picture"
| Role | light | dark | Contrast (measured) |
|---|---|---|---|
| Background (keyboard base) | `#F2F3F5` | `#202124` | — |
| **Key (surface, lighter than background)** | `#FFFFFF` | `#2B2D31` | key/background Δ 1.11 / 1.17 (subtle delimitation, visible through the 4 px gap, see below) |
| **Pressed** | `#DCE7E6` | `#3A4543` | text/that 13.52 / 8.25 : 1 |
| Divider (1 px below the status row) | `#E0E2E5` | `#3C3F44` | — |
| Text (letters) | `#1A1C1E` | `#E8EAED` | **text/key 17.09 / 11.44 : 1 → WCAG AAA (≥ 7)** |
| Accent (icon ⇧/⌫/⏎, shift active) | `#2E7D74` | `#80CBC4` | icon object 4.88 / 7.39 : 1 (≥ 3 per WCAG 1.4.11) |

**Key delimitation:** key surface = full rectangle minus a **2 px gap** per side
(the gap shows the background), corner radius **10 px**; additionally no own
dividers between keys (gaps = the only delimitation → calm picture).

## 8) Icon geometry (path points in a 1×1 box, stroke width **3.5 dp** ≈ 9.2 px,
`lineCap = round`, `lineJoin = round`; icon box = 40 % of the key width, centered)
- **⌫ = pure left arrow with a base** (no rectangle outline!): shaft
  `(0.80;0.50)→(0.30;0.50)`; head `(0.52;0.30)→(0.30;0.50)→(0.52;0.70)`; base
  (a short bar in front of the head, in the ⇧ style): `(0.18;0.32)→(0.18;0.68)`.
- **⇧ (reference, unchanged):** shaft `(0.50;0.78)→(0.50;0.30)`; head
  `(0.28;0.52)→(0.50;0.30)→(0.72;0.52)`; base `(0.32;0.84)→(0.68;0.84)`.
- **⏎ (reference, return):** bar `(0.74;0.26)→(0.74;0.60)→(0.32;0.60)`; head
  `(0.50;0.44)→(0.32;0.60)→(0.50;0.76)`.
- Footer icons (unchanged): globe/smiley/mic as in V1.

## 9) Preview
Draft: `docs/preview/keyboard_v2_hell.png`, `keyboard_v2_dunkel.png`,
**`keyboard_v2.gif`** (1080×1440; GIF 9 frames × 700 ms; space = "Deutsch") and
`space_endonyme.png` (space label per language). Implementation (F36):
`keyboard_v2_impl_*.png`, **`keyboard_v2_impl.gif`** (5 frames × 650 ms: light
base → pressed → shift → dark base → dark shift) +
`keyboard_v2_impl_sonderfaelle.png` (fr-fr/cs-cz/ru-ru). All rendered with
Python/PIL from assets/theme (offline, `tools/render_keyboard_v2*.py`), without
product code.

## 10) Comparison against the implementation (F36 / v0.1.27)
Subjects: `app/src/main/assets/layouts/*.json` + `keyboard/KeyboardTheme.kt`
(graphics: `docs/preview/keyboard_v2_impl_*.png/.gif`, special cases
`…_sonderfaelle.png`):

| # | Core value | Spec | ACTUAL (evidence) | ✓/✗ |
|---|---|---|---|---|
| 1 | r0 = 10 keys × 108 px **without margins**, without ⌫ | §1 | de-de r0 = 10 × 1.0u (Σ 10.000), `q…p`, no margin/⌫ (`layouts/de-de.json`) | ✓ |
| 2 | ⌫ **between M and ⏎** in r2 (96 px, 876–972) | §3 | r2: `…m:0.88889 backspace:0.88889 enter:1.0` (0.88889u = 96 px) | ✓ |
| 3 | r1 offset 54 + 9 × 102 + @ 108 (variant i) | §2 | r1: `m-left:0.5 a…l:0.94444 at:1.0` (0.94444u = 102 px, Σ 10.000) | ✓ |
| 4 | Footer unchanged 1.5/1/1/6.5/1/2 (13u) | §4 | r3: `symbols:1.5 language:1 emoji:1 space:6.5 period:1 mic:2` (Σ 13.000; all 4 checked assets identical) | ✓ |
| 5 | Surfaces visible: 2 px gap per side + radius 10 px + §7 colors | §6/§7 | `KEY_FUGE_DP = 2/2.625` (KeyboardTheme:86) → `keyFugePx` (KeyboardView:71, 408-413); `KEY_RADIUS_DP = 10/2.625` (KeyboardTheme:82, KeyboardView:68/414); `#F2F3F5/#202124`, `#FFFFFF/#2B2D31`, `#DCE7E6/#3A4543`, `#1A1C1E/#E8EAED`, `#2E7D74/#80CBC4` exact (KeyboardTheme:131-155) | ✓ |
| 6 | Space shows the endonym (de: "Deutsch") | §5 | `displayLabel` → `SpaceLabelRule.resolve(ENDONYMS[tag] ?: label, "Space")` (KeyboardView:771-778); the asset label `"Leer"` takes effect only without an endonym; fallback `key_space_label` (strings.xml:85) | ✓ |

**Deviations/findings (only documented, NOT changed in code):** (a) fr-fr r1 in
the asset is the de pattern (0.5 + 9×0.9444 + 1), not the draft line "10×98 +
@100" — §5 draft corrected accordingly; (b) ru-ru r2 = **Σ 12u** (⇧1 + 10 + ⏎1,
D27) with ⌫ only on the symbols layer; (c) cs-cz r2 = 204 + 6 × **112** + 96 +
108 (0.88889/1.03704u), block center 540 ✓ exactly as spec §6; cs r1 = 0.5 +
10 × 0.85u (91.8 px) + @ 1u.

> **T8 (v0.1.38) — documented deviation (addendum to the comparison):** in 13
> 7-key assets, r2 carries the Y row centering ⇧ 96.5 px + 7 × 119 px + ⏎ 150.5 px
> (0.89352/1.10185/1.39352u), NO @ (reachable via the M long-press menu); the
> block center moves 540→513 px (centering overrides uniformity, see the §3
> addendum); cs-cz ⇧ 1.88889u + 6 × 1.03704u + ⏎ 1.88889u; ru-ru remains the D27
> exception (Σ12).
