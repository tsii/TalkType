# DESIGN Tastatur V2 — Vollbreiten-Raster (normativ)

**Basis:** View **1080 px** breit, Dichte **2,625**, 4 Zeilen × **144 px** (Keyboard 1080×576,
darunter Statusleiste 48 dp = 126 px). **Neue Einheit: 1u = 108 px** (r0 = 10 Tasten
**randlos von Rand zu Rand**, ⌫ verlässt r0). Fußzeile r3 bleibt unverändert (eigene
Einheit, s. u.). Zielbild de-de; Sonderfälle unten.

## 1) r0 — 10 Tasten randlos
`q w e r t z u i o p`, je **108 px** (10 × 108 = 1080). Keine Seitendrücke, kein ⌫
(⌫ neu in r2).

## 2) r1 — 9 Buchstaben + @ (Variante i, festgelegt)
Variante i: **Offset 54 px (0,5u) + 9 × 102 px + @ 108 px** = 54 + 918 + 108 = **1080** ✓.
a: 54–156, s: 156–258, d: 258–360, f: 360–462, g: 462–564, h: 564–666, j: 666–768,
k: 768–870, l: 870–972, @: 972–1080. a-Mitte 105 vs. q/w-Mitten-Teilung 108 → **3 px
Abweichung, nicht wahrnehmbar**.
*Variante ii (alle 108, a ab x=0) verworfen; iii = i.* **Begründung (2 Sätze):** Das
Stock-Offset (a mittig zwischen q und w) ist die ergonomische Signature der QWERTZ-
Stagger-Zeile — Aufgabe wäre ein 6-%-Breitengewinn bei gleichzeitiger Zerstörung der
Fingermuskel-Erwartung und der optischen Versatzsprache zu r0. Bei 102 px bleibt die
Taste dennoch **+13 % breiter als V1 (90 px)**, der Gewinn der V2 geht also fast ganz
an die Buchstaben.

## 3) r2 — ⇧ + 7 Buchstaben + ⌫ + ⏎ (⌫ neu zwischen M und ⏎)
**⇧ 204 px (1,889u) + 7 × 96 px (y x c v b n m) + ⌫ 96 px (0,889u) + ⏎ 108 px (1u)**
= 204 + 672 + 96 + 108 = **1080** ✓. Buchstabenblock 204–876, **Mitte exakt 540 px (5u)** ✓.
Positionen: ⇧ 0–204, y 204–300, x 300–396, c 396–492, v 492–588, b 588–684, n 684–780,
m 780–876, ⌫ 876–972, ⏎ 972–1080. (Variante „7 × 108“ würde ⇧=162 zwingen und ⌫/⏎ auf
81 px drücken — für ⏎ als Daumentaste zu eng; 96 px = +7 % vs. V1 bei intaktem
540-Zentrum.)

> **T8 (v0.1.38) — dokumentierte Abweichung:** Nutzer-Auftrag Y-Reihen-Zentrierung
> (Y mittig unter A/S, M mittig unter K/L; @ raus, erreichbar über M-Langdruckmenü)
> schlägt die Uniformität dieser Zeile: r2 = ⇧ 96,5 px (0,89352u) + 7 × 119 px
> (1,10185u) + ⏎ 150,5 px (1,39352u) — r2 wird die BREITESTE Buchstabenreihe, die
> Blockmitte wandert 540→513 px; cs-cz ⇧=⏎ 1,88889u symmetrisch, ru-ru bleibt
> D27-Ausnahme (Σ12).

## 4) r3 — Footer **unverändert** (Bestätigung)
Breiten **125 / 83 / 83 / 540 / 83 / 166 px** (1,5/1/1/6,5/1/2 u bei **u_footer = 1080/13
= 83,077 px**; px-Auflösung der Summe 1080). Angleichen an u=108 wird **ausdrücklich
nicht** vorgeschlagen: die Footer-Ergonomie (Space 6,5u) ist seit F29 abgenommen,
ein Umbau riskiert den abgenommenen Thumbspace-Gewinn.

## 5) Space-Beschriftung (Endonym der aktiven Sprache)
Die Space-Taste zeigt den **Endonym-Namen der aktiven Sprache** aus
`LanguageCycle.ENDONYMS` (`ime/VoiceKeyboardService.kt:45-61`, Auflösung via
`SpaceLabelRule` über `KeyboardView.displayLabel`) — die V2-Grafiken zeigen die
de-de-Darstellung „**Deutsch**“. `SpaceLabelRule` sorgt dafür, dass ein
**Nicht-Whitespace-Label des Assets den Endonym nicht überschreibt**; Fallback
`R.string.key_space_label` („Space") nur, wenn kein Endonym vorhanden ist.
Endonyme (de-DE und en-US zuerst): **de-de „Deutsch“**, **en-us „English (US)“**, fr-fr
„Français“, es-es „Español“, it-it „Italiano“, pt-pt „Português“, nl-nl „Nederlands“, pl-pl
„Polski“, tr-tr „Türkçe“, ru-ru „Русский“, cs-cz „Čeština“, sv-se „Svenska“, da-dk „Dansk“,
fi-fi „Suomi“, el-gr „Ελληνικά“. Übersicht: `docs/preview/space_endonyme.png`.

## 6) Sonderfälle (je eine Zeile)
- **fr-fr** (AzERT, 10 Buchstaben r0): r1 = m-left 0,5u + 9 × 0,9444u (102 px) + @ 1u —
  **dasselbe Muster wie de-de** (das 10. AzERT-L p sitzt in r0); Umsetzung (F36) ist
  exakt so gebaut (abgewichen von der Entwurfs-Zeile „10×98 + @100, Offset 0“ — das
  Stagger-Muster wurde beibehalten).
- **ru-ru** (11er-Buchstabenreihen): 11 × **96 px** + je **12 px** Seitenraster
  (1056 + 24 = 1080), Ziffernreihe ⇧1 + 10 Ziffern + ⏎1 (D27, **Σ 12u**); ⌫ fehlt in
  den base/shift-Reihen von r2 (liegt auf der symbols-Ebene).
- **cs-cs** (6er-r2): **⇧ 204 + 6 × 112 px + ⌫ 96 + ⏎ 108 = 1080**, Buchstabenblock-
  Mitte 204 + 3×112 = **540** ✓ (gleiche ↕/⌫/⏎-Breiten wie de-de).

## 7) Farben (Hex, hell / dunkel) — „ruhiges Gesamtbild“
| Rolle | hell | dunkel | Kontrast (gemessen) |
|---|---|---|---|
| Hintergrund (Tastatur-Grund) | `#F2F3F5` | `#202124` | — |
| **Taste (Fläche, heller als Hintergrund)** | `#FFFFFF` | `#2B2D31` | Taste/Hintergrund Δ 1,11 / 1,17 (subtile Abgrenzung, sichtbar durch 4 px-Fuge, s. u.) |
| **Gedrückt** | `#DCE7E6` | `#3A4543` | Text/davon 13,52 / 8,25 : 1 |
| Trennlinie (1 px unter Statusleiste) | `#E0E2E5` | `#3C3F44` | — |
| Text (Buchstaben) | `#1A1C1E` | `#E8EAED` | **Text/Taste 17,09 / 11,44 : 1 → WCAG AAA (≥ 7)** |
| Akzent (Icon ⇧/⌫/⏎, Shift-aktiv) | `#2E7D74` | `#80CBC4` | Icon-Objekt 4,88 / 7,39 : 1 (≥ 3 nach WCAG 1.4.11) |

**Tastenabgrenzung:** Tastenfläche = Vollrechteck minus **2 px Fuge** je Seite
(Fuge zeigt den Hintergrund), Eckenradius **10 px**; zusätzlich keine eigenen
Trennlinien zwischen Tasten (Fugen = einzige Abgrenzung → ruhiges Bild).

## 8) Icon-Geometrie (Pfadpunkte in 1×1-Box, Strichstärke **3,5 dp** ≈ 9,2 px,
`lineCap = round`, `lineJoin = round`; Icon-Box = 40 % der Tastenbreite, mittig)
- **⌫ = reiner Links-Pfeil mit Sockel** (keine Rechteck-Kontur!): Schaft
  `(0,80;0,50)→(0,30;0,50)`; Kopf `(0,52;0,30)→(0,30;0,50)→(0,52;0,70)`; Sockel
  (kurze Leiste vor dem Kopf, im ⇧-Stil): `(0,18;0,32)→(0,18;0,68)`.
- **⇧ (Referenz, unverändert):** Schaft `(0,50;0,78)→(0,50;0,30)`; Kopf
  `(0,28;0,52)→(0,50;0,30)→(0,72;0,52)`; Sockel `(0,32;0,84)→(0,68;0,84)`.
- **⏎ (Referenz, Return):** Stange `(0,74;0,26)→(0,74;0,60)→(0,32;0,60)`; Kopf
  `(0,50;0,44)→(0,32;0,60)→(0,50;0,76)`.
- Footer-Icons (unverändert): Globe/Smiley/Mic wie V1.

## 9) Preview
Entwurf: `docs/preview/keyboard_v2_hell.png`, `keyboard_v2_dunkel.png`,
**`keyboard_v2.gif`** (1080×1440; GIF 9 Frames × 700 ms; Space = „Deutsch“) und
`space_endonyme.png` (Space-Label je Sprache). Umsetzung (F36): `keyboard_v2_impl_*.png`,
**`keyboard_v2_impl.gif`** (5 Frames × 650 ms: hell Grund → gedrückt → Shift → dunkel
Grund → dunkel Shift) + `keyboard_v2_impl_sonderfaelle.png` (fr-fr/cs-cz/ru-ru). Alle
per Python/PIL aus Assets/Theme gerendert (offline, `tools/render_keyboard_v2*.py`),
ohne Produktcode.

## 10) Abgleich gegen die Umsetzung (F36 / v0.1.27)
Gegenstände: `app/src/main/assets/layouts/*.json` + `keyboard/KeyboardTheme.kt`
(Grafik: `docs/preview/keyboard_v2_impl_*.png/.gif`, Sonderfälle `…_sonderfaelle.png`):

| # | Kernwert | Spec | IST (Beleg) | ✓/✗ |
|---|---|---|---|---|
| 1 | r0 = 10 Tasten × 108 px **randlos**, ohne ⌫ | §1 | de-de r0 = 10 × 1,0u (Σ 10,000), `q…p`, kein Margin/⌫ (`layouts/de-de.json`) | ✓ |
| 2 | ⌫ **zwischen M und ⏎** in r2 (96 px, 876–972) | §3 | r2: `…m:0,88889 backspace:0,88889 enter:1,0` (0,88889u = 96 px) | ✓ |
| 3 | r1-Offset 54 + 9 × 102 + @ 108 (Variante i) | §2 | r1: `m-left:0,5 a…l:0,94444 at:1,0` (0,94444u = 102 px, Σ 10,000) | ✓ |
| 4 | Footer unverändert 1,5/1/1/6,5/1/2 (13u) | §4 | r3: `symbols:1,5 language:1 emoji:1 space:6,5 period:1 mic:2` (Σ 13,000; alle 4 geprüften Assets identisch) | ✓ |
| 5 | Flächen sichtbar: 2 px Fuge je Seite + Radius 10 px + §7-Farben | §6/§7 | `KEY_FUGE_DP = 2/2,625` (KeyboardTheme:86) → `keyFugePx` (KeyboardView:71, 408-413); `KEY_RADIUS_DP = 10/2,625` (KeyboardTheme:82, KeyboardView:68/414); `#F2F3F5/#202124`, `#FFFFFF/#2B2D31`, `#DCE7E6/#3A4543`, `#1A1C1E/#E8EAED`, `#2E7D74/#80CBC4` exakt (KeyboardTheme:131-155) | ✓ |
| 6 | Space zeigt das Endonym (de: „Deutsch“) | §5 | `displayLabel` → `SpaceLabelRule.resolve(ENDONYMS[tag] ?: label, „Space")` (KeyboardView:771-778); Asset-Label `"Leer"` tritt nur ohne Endonym in Kraft; Fallback `key_space_label` (strings.xml:85) | ✓ |

**Abweichungen/Befunde (nur dokumentiert, NICHT im Code geändert):** (a) fr-fr r1 ist
im Asset das de-Muster (0,5 + 9×0,9444 + 1), nicht die Entwurfs-Zeile „10×98 + @100“
— §5-Entwurf entsprechend berichtigt; (b) ru-ru r2 = **Σ 12u** (⇧1 + 10 + ⏎1, D27)
mit ⌫ nur auf der symbols-Ebene; (c) cs-cz r2 = 204 + 6 × **112** + 96 + 108
(0,88889/1,03704u), Blockmitte 540 ✓ exakt wie Spec §6; cs r1 = 0,5 + 10 × 0,85u
(91,8 px) + @ 1u.

> **T8 (v0.1.38) — dokumentierte Abweichung (Nachtrag zum Abgleich):** r2 trägt in
> 13 7er-Assets die Y-Reihen-Zentrierung ⇧ 96,5 px + 7 × 119 px + ⏎ 150,5 px
> (0,89352/1,10185/1,39352u), KEIN @ (erreichbar über M-Langdruckmenü); Blockmitte
> wandert 540→513 px (Zentrierung schlägt Uniformität, s. §3-Nachtrag); cs-cz
> ⇧ 1,88889u + 6 × 1,03704u + ⏎ 1,88889u; ru-ru bleibt D27-Ausnahme (Σ12).
