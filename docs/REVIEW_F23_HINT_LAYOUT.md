# Review F23 — Hint-Feld/-Zeichnung, de-de-Zielbild, Hint-Tippbarkeit

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `LayoutAsset`/`LayoutParser` (hint), `KeySurface`/`KeyboardView` (drawKeyHint), `LongPressMenu.withHint`, `de-de.json`, `LayoutCompletenessTest`. Kein Build/Emulator (Orchestrator-Gerätebefunde übernommen).

---

## Befundtabelle

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| **P2** | `KeyboardView.kt:392` (`drawKeyHint`) | **„nur base/shift" ist dokumentiert (§7.4), aber nicht im Code geprüft.** `if (src.kind != TEXT \|\| src.hint == null) return` prüft weder `activeLayerName ∈ {base, shift}` — ein künftiger `hint` auf einer `symbols`-/`symbolsShift`-Texttaste würde gezeichnet (verletzt §7.4 „Zeichnen/löschen bei Ebene"). Heute unschädlich, weil nur `de-de`-`base` (Ziffern über q–p) Hints trägt. | `&& (activeLayerName == "base" \|\| activeLayerName == "shift")` in die Guard-Klausel aufnehmen (Assetsicherheit statt Codetoleranz). |
| Note | `LayoutParser.kt:124-127` (hint-Toleranz) | **Toleranz korrekt, aber ein 2-UTF16-Astral-Hint wird akzeptiert.** `optString` wirft nie (Zahl→"5" wird angenommen, Objekt/Array→toString→`length>2`→null, >2 Zeichen→null) — kein Crash, kein Layoutverlust. Ein Surrogatpaar (z. B. „😀" = 2 Einheiten) passiert das `length>2`-Gate (2 ≤ 2) und würde als Legende gezeichnet. Harmlos, aber nicht grapheme-geprüft. | optional: `h.codePointCount(0,h.length)==1` statt `h.length>2`. |
| Note | `LongPressMenuTest`/`LayoutCompletenessTest` (Matrix) | **Matrix ist Handpflege — „stilles Gutten bleibt möglich".** `MatrixRow("de-de", emptyList(), …)` + separater Test `de-de – Umlaute und szett sind erste Alternative` (Z.95) fängt (a) „de-de verliert Umlaut-Alternate" rot; aber (b) das Gutten von `requiredOwnBaseKeys` einer **anderen** Sprache (z. B. `pl-pl`→`emptyList()`) wird **nicht** rot — die Matrix ist die einzige Quelle, es gibt keinen Quervergleich gegen `LAYOUT_REQUIREMENTS §2.2`. | Ein (Property-)Test, der die Matrix-A-/B-Mengen gegen die §2.2-Tabelle aus `LAYOUT_REQUIREMENTS` abgleicht (sonst bleibt §2.2-Drift unsichtbar). |

---

## Antworten A–H

- **A) Toleranz — robust, kein Crash/kein Tastaturausfall.** `LayoutParser.kt:124-127`: `optString` koerziert (Zahl→„5", Objekt/Array→toString), `h.isEmpty() || h.length > 2 → null`. Kein LayoutException-Pfad über hint; `loadOrFallback` wird nie durch hint getriggert. Einziger Rest = Astral-Hint (Note oben).
- **B) Zeichnung — hint nie transformiert, Kollision→aus; Layer-Gate fehlt.** `drawKeyHint` (KeyboardView:390-410): `kind==TEXT && hint!=null`, `hintPaint` nutzt das rohe `src.hint` (kein Shift/Caps), und `if (baseline+descent > key.bottom) return` (Kollision→aus). **Aber** kein `base/shift`-Gate (P2 oben) — §7.4 ist nur KDoc, nicht Codeprüfung.
- **C) `withHint` — Ziffer am Ende, keine Verdopplung, hint fällt zuerst.** `LongPressMenu.withHint` (Z.31-35): `hint.trim()`; leer/in `alternates`→unverändert; `alternates.size >= maxCells(8)`→hint **weggelassen**. 8 Sprachalternates + hint ⇒ 8 Alternates (hint entfällt) — genau die Vorgabe.
- **D) de-de-Geometrie:** base `[11.5, 9, 9, 13]` **==** shift `[11.5, 9, 9, 13]` ✓; Fußreihe Σ 13 ✓. „Genau ein Punkt je Ebene" und „`. , ? !` auf symbols je genau 1×" habe ich **nicht** per-Charakter nachgezählt (offen — fängt `PunctuationCoverageTest`/Artifact-Test). `ẞ` in shift-`s`/`ß` = aus dem F13-Stand übernommen (unverändert, über `shiftPairs = ß→ẞ` abgedeckt). `git diff --stat` = **nur** `de-de.json` (74+/134−) ⇒ 14 Layouts inhaltlich unverändert.
- **E) Testqualität:** (a) de-de verliert Umlaut-Alternate → separater Test (Z.95) **rot**; (b) andere Sprache still geleert → **nicht rot** (Matrix ohne §2.2-Querverweis, Note oben).
- **F) Cursor-Refs nicht tot:** nur `de-de` hat `‹ ›` entfernt; die **anderen 14 Layouts** tragen sie weiter. Refs bleiben live: `LayoutAsset.kt:60-61` (`KeyActions.CURSOR_LEFT/RIGHT`), `VoiceKeyboardService.onAction` (DPAD-Handler), `LayoutArtifactTest:37` (Allowlist), `MicAndGlobeStateTest:44-45` (Fixture). Kein Orphan-Pfad.
- **G) Regel 9:** VK-HIT bleibt statisches Tasten-label + Geometrie, `FLAG_DEBUGGABLE`-only — keine Nutzer-/Editor-/Audio-Inhalte. Vertretbar.
- **H) Regel 2 (ASR/Diktat unverändert):** hint/Zeichnung/`withHint` berühren ausschließlich `layouts`/`keyboard`; `dictation`/`asr` (Finale, Pace, Commit) sind unangetastet — die Layoutänderung ist rein typografisch/tippbar.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 1** (fehlendes `base/shift`-Gate in `drawKeyHint`) · 2 Notes (Astral-Hint-Toleranz; Matrix ohne §2.2-Quervergleich).

**Offen (zum Belegen bräuchte ich):** einen `./gradlew test`-Lauf (419/0), die per-Zeichen-Prüfung „genau ein `.` je Ebene / `. , ? !` je 1× auf symbols", und die drei Gerätebefunde (Ziffern-Raster, Rückentaste oben rechts, Halten-Zelle vs. ohne Bewegung) als eigene Reproduktion.