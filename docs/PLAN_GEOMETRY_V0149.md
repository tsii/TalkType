# PLAN — Tastatur-Geometrie nach Nutzer-Vorlage (V0.1.49)

Erstellt vom Planer (my/my-qwen38-flash-next, Run sa-f98998be); Zahlen gegen
`KeyboardModel.kt:66-90`, `KeyboardTheme.kt:87-95`, `LayoutAsset.kt:56-72`,
`de-de/cs-cz/ru-ru.json` und die 4 Asset-Tests geprüft.

## 0. Ziel / Nicht-Ziele
**Ziel:** Zeilen 0–2 (nicht die Spacebar-Zeile) von base/shift/symbols/symbolsShift nach
AOSP-Vorlage neu ausrichten; **ein** Backspace rechts von `l` (Mittelzeile); `.` zurück in
die Fußzeile zwischen Spacebar und 🎤 (Spacebar schmaler); Enter größer (Vorlagenanteil);
Umschaltung Zahlen↔Sonderzeichen über Z2 ganz links.
**Nicht-Ziele (unantastbar):** `KeyboardTheme` (Farben, `KEY_RADIUS_DP`, `KEY_FUGE_DP`,
SP-* Schriftgrößen), `KeyboardHeight`-Fit, Zeichen-Kodierung/Alternates-Inhalt, Emoji-Panel,
`D10_FOOTER` (KeyboardModel.kt:130-172 — toter Pfad, bleibt unangetastet).
**Geometrie-Basis:** `ROW_GAP_DP = 0`, `KEY_GAP_DP = 0`, `horizontalPadding = 0`
⇒ `unit = usable / Σ(Zeile)`; **nur die Zeilensumme zählt.** Referenzbreite 1080 px.

### Orchestrator-Entscheid zur ⌫-Zahl (dokumentiert, Vorlage §8-Knopf)
Der Plan sah zwei ⌫ nebeneinander (`backspace` 0.75 + `backspace-right` 0.75). Nutzerauftrag
sagt „in der mittleren Zeile rechts von `l` **ein weiterer** Backspace" — im Sinne von
„abweichend von der Vorlage *behalte* dort einen Backspace". Die Vorlagen selbst haben genau
**einen** ⌫. Deshalb wird **Variante A (ein ⌫)** gebaut — der vom Plan selbst benannte
1-Zeilen-Knopf (§8, „kein Testumbau"): die beiden 0.75-Flügel werden zu **einem ⌫ à 1.500**
(ru: 1.250), alle anderen Zahlen unverändert. Kein zweiter ⌫.

## 1. Soll-Tabelle Buchstaben-Ebenen (base + shift identisch), 13 Standard-Assets
(da-dk de-de el-gr en-us es-es fi-fi fr-fr it-it nl-nl pl-pl pt-pt sv-se tr-tr), Σ(Z0..Z2)=10.000
⇒ u=108 px; Z3 Σ=13.000 ⇒ u=83.077 px

| Z | Keys (Breiten in Einheiten) | Σ | px@1080 |
|---|---|---|---|
| Z0 | q w e r t y u i o p = 10 × **1.000** | 10.000 | 10×108.0 |
| Z1 | `m-left` **0.500** · a s d f g h j k l = 9 × **0.888889** · `backspace` **1.500** | 10.000 | 54.0 · 9×96.0 · 162.0 |
| Z2 | `shift` **1.500** · y x c v b n m = 7 × **0.928571** · `enter` **2.000** | 10.000 | 162.0 · 7×100.29 · 216.0 |
| Z3 | `symbols` 1.500 · `language` 1.000 · `emoji` 1.000 · `space` **6.500** · `period` **1.000** · `mic` 2.000 | 13.000 | 124.6 · 83.1 · 83.1 · 540.0 · 83.1 · 166.2 |

Δ zu heute: Z0 unverändert; Z1-Buchstaben 0.94444→0.888889 (102→96 px, −5.9 %), ⌫ 1.0→1.5;
Z2-Buchstaben −1.7 %, `period` wandert nach Z3, Enter 132→216 px (+63 %); Z3 Space 624→540 px
(−84 px = genau eine Fußzeilen-Einheit) finanziert `.`; 🎤 bleibt 166 px und rechts außen.

## 2. Soll-Tabelle Zahlen-/Sonderzeichen-Ebenen (symbols + symbolsShift), alle 15

| Z | Keys | Σ | px@1080 |
|---|---|---|---|
| Z0 | 1…0 (symbols) bzw. 10 Sonderzeichen (symbolsShift) = 10 × **1.000** | 10.000 | 10×108.0 (unverändert) |
| Z1 | `m-left` **0.500** · 10 Zeichen × **0.850** · `backspace` **1.000** | 10.000 | 54.0 · 10×91.8 · 108.0 |
| Z2 | Toggle **1.000** · 10 Zeichen × **0.750** · `enter` **1.500** | 10.000 | 108.0 · 10×81.0 · 162.0 |
| Z3 | `base` 1.500 · `language` 1.000 · `emoji` 1.000 · `space` 7.500 · `mic` 2.000 | 13.000 | unverändert (Space 624.0) |

## 3. Ausnahmen (Tabellenzeilen im Skript, keine Handarbeit)

| Asset | Abweichung |
|---|---|
| cs-cz | Z1 `m-left` 0.5 + 10 × **0.800** + ⌫ **1.500**; Z2 `shift` **2.000** + 6 × **1.000** + `enter` **2.000** (10 Heimzeilen-Buchstaben, nur 6 in Z2; Enter exakt 20 %) |
| ru-ru | Z0 **unverändert** (Σ10); Z1 `m-left` **0.250** + 11 × **0.772727** + ⌫ **1.250** = Σ10; Z2 **Σ12**: `shift` 1.000 + 10 × **0.900** + `enter` **2.000**; Z3 unverändert (hat `.`/space 6.5 bereits) |

## 4. Entscheidungen 1–10
1. **Raster:** Σ je Zeile bleibt 10/10/10 (Fuß 13) — nur dann ist u=108 px in allen drei oberen
   Zeilen gleich und die (unveränderte) 26-sp-Schrift wirkt wie bisher; Pixelprobe als Test.
2. **⌫:** ein Key, ID `backspace`, `kind:action`, `value:"backspace"`, rechts von `l`. Die
   Backspace-Logik hängt an `value == KeyActions.BACKSPACE` (KeyboardView.kt:872/939/1030/1251/1442)
   ⇒ Repeat (B2), Wort-Delete (B2w), Glyphe und TalkBack „Delete" greifen ohne Service-Änderung.
3. **Enter:** **2.000 = 20 %** auf base/shift (216 px; cs 216 px; ru 180 px bei Σ12), Position Z2
   rechts außen. Auf symbols/symbolsShift **1.500 (15 %)**, weil Z2 dort zusätzlich den Toggle (1.0)
   trägt (1.0 + 10×0.75 + 2.0 = 10.75 > 10 wäre rechnerisch unmöglich).
4. **Z2-Buchstaben:** `shift` 1.5 + 7 × 0.928571 + `enter` 2.0; `period` zieht nach Z3.
5. **Z3-Reihenfolge:** `symbols | language | emoji | space | period | mic`; ID **`period`**
   (nicht `dot` — `dot` belegt bereits Z2 der Symbol-Ebenen; `period` übernimmt das F54-Menü
   `, ! ? : ; ( )`); ru behält `@` als alternates[0]. ⏎ nicht in Z3 (weiter genau 1× in Z2).
6. **Symbol-Ebenen:** Inventar (10 Zeichen in Z1, 10 in Z2) lässt die Vorlagen-9er-Heimzeile nicht
   zu ⇒ Z1 = ½-Einrückung + 10 × 0.85 + ⌫ 1.0; Z2 = Toggle + 10 × 0.75 + ⏎ 1.5. `shift` entfällt
   auf beiden Symbol-Ebenen; `symbolsShift` nur noch über den neuen Z2-Links-Toggle erreichbar.
7. **Toggle:** neue Aktion **`layer-symbols-shift`** (`LayoutAsset.kt` + `KeyActions.ALL`),
   Label **`#+=`**, symbols Z2[0]; Rückweg **`?123`** mit `layer-symbols` auf symbolsShift Z2[0];
   `KeyboardView.kt:1345` muss von `switchLayer(if (symbolShift) …)` auf **immer `"symbols"`**
   (symbolShift-Reset passiert in :1361). Unten links bleiben `?123`/`ABC` unverändert.
8. **Alle 15 Assets** (Tests iterieren 15; de-only wäre gespaltene UX); cs/ru als Tabellen-Ausnahmen.
9. **Migration:** Skript `tools/apply_geometry_v0149.py` (Muster `tools/rebuild_r2_t11.py`:
   Tabelle als Quelle, idempotent, harte Asserts, 2-Space-Format). Asserts pro Asset+Layer:
   Zeichen-Inventar (Values ∪ alternates) **vor == nach**, Σ je Zeile == Soll ±1e-3, IDs
   layer-eindeutig, Z3-IDs == [symbols,language,emoji,space,period,mic].
10. **Reihenfolge:** Test rot → Skript → Code-Zweige → Altests nachziehen (§7).

## 5. Zustandstabelle Umschaltung

| von | Tap (Position) | Aktion | nach | symbolShift |
|---|---|---|---|---|
| base / shift | `?123` Z3[0] | `layer-symbols` | symbols | false |
| symbols (Zahlen) | `#+=` **Z2[0]** | `layer-symbols-shift` (NEU) | symbolsShift | true |
| symbolsShift | `?123` **Z2[0]** | `layer-symbols` (→ fest Zahlenseite) | symbols | false |
| symbols / symbolsShift | `ABC` Z3[0] | `layer-base` | base | false |
| base ⇄ shift | `⇧` Z2[0] | `shift` (unverändert) | shift/base | — |

Nutzerweg: base → `?123` → Zahlen → `#+=` → Sonderzeichen → `?123` → Zahlen → `ABC` → base.

## 6. Code-Änderungen (bewusst minimal, 3 Stellen)
1. `LayoutAsset.kt`: `const val LAYER_SYMBOLS_ALT = "layer-symbols-shift"` + in `KeyActions.ALL`.
2. `KeyboardView.kt:1345`: `LAYER_SYMBOLS -> switchLayer("symbols", fromSymbols = true)`;
   neuer Zweig `LAYER_SYMBOLS_ALT -> switchLayer("symbolsShift", fromSymbols = true)`.
3. Accessibility (`KeyboardView.kt:1439-1447`): `LAYER_SYMBOLS_ALT -> "Special characters"`.
Keine Änderung in Theme, Height-Fit, Editor-Seam oder Service.

## 7. TDD-Reihenfolge (Datei · Testname · Assertion)

| # | Datei · Test | Präzise Assertion (Toleranz) |
|---|---|---|
| 1a | NEU `layouts/GeometryV0149Test.kt` :: `rowSumsAreTenAndThirteenAcrossAll15` | ∀ 4 Layer, 15 Assets: Σ(Z0..Z2)==10.000 ±1e-3, Σ(Z3)==13.000 ±1e-3; ru Z2 Σ==12.000 ±1e-3 |
| 1b | :: `homeRowHasBackspaceRightOfL` | Z1[0].kind==MARGIN && width==0.500 (ru 0.250); Z1-Z[0].id=="backspace" && value=="backspace" && width==1.500 (ru 1.250); TEXT-Keys 9 × 0.888889 ±1e-3 (cs 10 × 0.800, ru 11 × 0.772727); genau **ein** ⌫ in Z1 |
| 1c | :: `enterTwentyPercentOnLetterLayers` | Z2.last().value=="enter" && width==0.20 × Σ(Z2) ±1e-3; Z2[0] `shift`==1.500 (cs 2.000); TEXT 7 × 0.928571 (cs 6 × 1.000, ru 10 × 0.900); kein `period` in Z2 |
| 1d | :: `footerDotBetweenSpaceAndMicAll15` | Z3-IDs == [symbols,language,emoji,space,period,mic]; space==6.500; mic zuletzt 2.000; Σ 13.000; ⏎ in Z3==0, in Z2 genau 1 |
| 1e | :: `symbolPagesToggleAtLeftAboveSpace` | symbols Z2[0]: value=="layer-symbols-shift", label=="#+=", width==1.000; symbolsShift Z2[0]: value=="layer-symbols", label=="?123"; `shift` in symbols ∪ symbolsShift == 0; Z1 == [margin 0.5, 10×0.850, ⌫ 1.000]; Z2 == [1.000, 10×0.750, 1.500] |
| 1f | :: `pxProbeAt1080` | `KeyboardModel(base, 1080f, gap 0, rowGap 0, padding 0)`: je Zeile max(right)==1080 ±0.5; Z1-Buchstaben 96.0; Z2-Buchstaben 100.29, ⏎ 216.0; Z3 space 540.0, mic 166.2 |
| 1g | :: `characterInventoryUnchangedPerAsset` | pro Asset+Layer `sorted(Values ∪ alternates)` == Snapshot (exakte Gleichheit) — Schutz vor Verlust/Erfindung |
| 2 | `tools/apply_geometry_v0149.py` + 15 Assets | §4.9; zweiter Lauf ⇒ byte-identisch (Idempotenz) |
| 3 | `layouts/LayoutCompletenessTest.kt` | ⌫==1 in base/shift (alle 15, auch ru), ⌫==1 in symbols/symbolsShift; „⌫ 1× am Z1-Ende, 1.0" → „⌫ am Z1-Ende, 1.5 (ru 1.25)"; Buchstaben 9×0.888889 / cs 10×0.800; Z2-⌫-Verbot bleibt |
| 4 | `layouts/LetterRowAlignmentAcrossAssetsTest.kt` | ⇧=⏎=1.22224 + 8×0.94444 ersetzen; Symmetrie `c[i]+c[n-1-i]==10` entfällt (⏎ 2.0 ≠ ⇧ 1.5) → „Z1-Buchstabenbreite == Z2-Buchstabenbreite ±0.05"; `period`-Assertions nach Z3 |
| 5 | `keyboard/FooterAcrossAssetsTest.kt` | `checkNewFooter`: 5→6 Keys, space 7.5→6.5, `period` erwartet (1.0, F54-Menü); ru in den Standardast (@ als alternates[0]) |
| 6 | `layouts/SymbolGridAcrossAssetsTest.kt` | ⇧ Z2[0] 1.5 → Toggle 1.0 neue Aktion; ⏎ 1.0 → 1.5; Z1 10×0.9 → margin 0.5 + 10×0.85; `anchoredSets` unverändert |
| 7 | NEU `keyboard/SymbolToggleStateMachineTest.kt` (Robolectric, echte de-de-Assets) | Tap-Folge ?123 → `#+=` → `?123` → ABC == [symbols, symbolsShift, symbols, base] (0 Toleranz) |
| 8 | `./gradlew :app:testDebugUnitTest` | 0 Failures; explizit grün: `SymbolMenuClampAcrossAssetsTest`, `PunctuationCoverageTest`, `ImeRootViewLayoutTest`, `KeyboardTapIdentityTest`, `LayoutArtifactTest` |

## 8. Risiken + Gegenmaßnahmen
- **Zwei ⌫** (Variante B): siehe Entscheid §0 — nicht gebaut; Knopf bleibt im Skript.
- **Schmälere Buchstabenzellen** (96 px, −5.9 %): Glyph bleibt 26 sp (~68 px Tinte) ⇒ kein Überlauf;
  Gegenprüfung über 1f + Render/Emulator.
- **Space 540 px** (kürzeste seit v0.1.30): rückmeldekritisch; Fallback 6.7/0.8 ist eine Tabellenzeile.
- **Symbol-Ebenen brechen die Vorlagen-9er-Zeile** (Inventar erzwingt 10): dokumentierte Abweichung.
- **ru-ru wird angefasst** (D27 erstmals aufgeweicht): eigene Tabellenzeile + Sichtprüfung.
- **Bestehende Asset-Tests blockieren:** Schritte 3–6 im selben Commit.
- **Popup-Clamping** auf schmaleren Zellen: Schritt 8 vor der DoD.

## 9. Definition of Done
1. `./gradlew :app:testDebugUnitTest` → 0 Failures (Zähler in `docs/PROJECT_STATE.md` fortschreiben).
2. Sichtprüfung Emulator: de-de je base/shift/symbols/symbolsShift — Z1 mit Einrückung + ⌫,
   Z2 mit deutlich größerem ⏎, Z3 `.` zwischen Spacebar und 🎤, `#+=`↔`?123`-Weg wie §5;
   Schattierung/Ecken unverändert (Theme-Diff = 0 Zeilen).
3. `CHANGELOG.md`-Zeile V0.1.49 + dieses Dokument; `git diff --stat` nur
   `app/src/main/assets/layouts/*.json`, 4 Testdateien (+2 neue), `LayoutAsset.kt`,
   `KeyboardView.kt`, `tools/`, `docs/`.
