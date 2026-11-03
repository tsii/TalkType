# Review F24d — el-gr + pt-pt-Umbau + Test-Gegenprobe

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `pt-pt.json` (4-Reihen, Akzente→alternates), `el-gr.json` (r0-`;`+hints), `LayoutCompletenessTest` (Matrix + Negativfall), `LayoutParserTest` (+hint). Kein Build/Emulator.

---

## Befundtabelle

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| **P2** | `pt-pt.json` Fußzeile vs. 14 Layouts | **Einzige verbleibende Querschnitt-Abweichung: die Fußzeile.** de-de (F23) hat `‹ ›` durch `.` ersetzt; **alle anderen 14** (auch die neu umgebauten pt-pt/el-gr) tragen weiter `‹ ›`. Beim Sprachwechsel ändert sich die Fußzeile sichtbar. Als Übergang nur bis F24e vertretbar; für 0.1.16 vereinheitlichen. | In F24e `‹ ›` in allen 15 durch `.` (oder umgekehrt) ersetzen. |
| Note | `pt-pt.json` `o.alternates = ["õ","ó","ô"]` | **Reihenfolge von `o` weicht von AOSP ab** (`moreKeys_o` pt = `ó ô õ`, also `ó` zuerst; Asset hat `õ` zuerst). Alle drei sind erreichbar (`ó` in der Kette), nur die first-Alternate ist `õ` statt `ó`. Analog `a` (`á â ã à` vs. AOSP `á ã à â`). Rein Ordnung, kein Fehlen. | `o.alternates` auf `["ó","ô","õ"]` (und `a` auf `["á","ã","à","â"]`) drehen, falls AOSP-`[0]`-Treue gefordert. |

---

## Antworten

1. **pt-pt-Umbau — vollständig, alle 12 Akzente erreichbar.**
   - (a) `base` alternates: `a=[á,â,ã,à]`, `c=[ç]`, `e=[é,ê]`, `i=[í]`, `o=[õ,ó,ô]`, `u=[ú,ü]`; `shift` analog groß (`À Á Â Ã …`). Alle geforderten 12 (á â ã à ç é ê í ó ô õ ú) sind als `alternates[0]` **bzw. in der Kette** vorhanden — nur `o`'s Reihenfolge weicht ab (Note). Kein Akzentbuchstabe verloren.
   - (b) Σ width base `[10,9,10,13]` **==** shift `[10,9,10,13]`. ✓
   - (c) ⌫ steht in **r2** (`[⇧ z x c v b n m ⌫]`), die ehemalige Akzentreihe ist weg. ✓
   - (d) hint-Folge `1..0` in base **und** shift identisch (r0 `q..p` + `Q..P`). ✓
2. **el-gr:** r0 trägt hints auf **genau 10** Tasten (base `; ς ε ρ τ υ θ ι ο π` = hints 1..0; shift `: Σ … Π`), **r1 (α..λ) und r2 haben KEINE hints**; Σ je Reihe base==shift `[10,9,10,13]`. Der Testeintrag `shiftPairs = listOf(";" to ":")` ist **rein additiv** (α..ω-A-Liste + B-Liste bleiben unverändert scharf) — kein vorher geprüfter Punkt wird dadurch weggeprüft.
3. **Test-Umbau:** `MatrixRow("pt-pt", emptyList(), requiredReachable = listOf("ü"), shiftPairs = listOf("ç" to "Ç"))` — `ü` als hartes Reachable ist **AOSP-sinnvoll** (`moreKeys_u` pt = `u ù ú ü`; `ü` hängt real an `u`). Der frühere `a-grave`-Negativfall ist **ersetzt, nicht gelöscht**: `negativ - pt-pt ohne Akzentkette an a faellt der neue Fall rot` (Z.157) + der Positiv-Test „Akzente sind erste Alternative" (Z.145) — die Gegenprobe existiert weiter, kein P1. (Volltext der Negativmethode nicht gelesen — offen.)
4. **LayoutParserTest +52 (hint):** die drei Fälle sind aus dem Parser heraus korrekt (`fehlt→null`, `""`→null, `len>2`→null; `LayoutParser.kt:124-127`); der Test erwartet laut KDoc `null` und enthält keine Nutzerinhalte (nur Literale `"1"/""/"abc"`). (Datei nicht selbst gelesen — vom Parser abgeleitet, offen.)
5. **Querschnitt:** die **eine** verbleibende Abweichung ist die Fußzeile (de-de `·`/`.` vs. 14× `‹ ›`) — P2, in F24e zu vereinheitlichen.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 1** (Fußzeilen-Inkonsistenz) · 1 Note (`o`-Alternate-Reihenfolge).

**Vor v0.1.16 (Bau 17) zwingend:** die Fußzeilen-Vereinheitlichung (`‹ ›` → `.`) über alle 15 Layouts, sonst bleibt der sichtbare Sprachwechsel-Bruch. **Nicht blockierend:** die `o`-Reihenfolge (Kette vollständig).

**Offen (zum Belegen):** Volltext von `LayoutCompletenessTest`-Negativmethode Z.157 und von `LayoutParserTest` +52 (nur per KDoc/Parser abgeleitet), ein eigener `./gradlew test`-Lauf (420/0 übernommen).