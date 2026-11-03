# Review F24e/e0-e5 — Aktionszellen, Bewaffnungszone, 15er-Fußzeilen

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `LongPressMenu` (cellAction/stillArmed-mit-Breite), `KeyboardView` (actionCells/commitAlternate/DIAG-Logs), `LayoutParser`/`LayoutAsset` (cellActions), 15 Layouts (period-Zelle), Tests. Kein Build/Emulator (Orchestrator-Gerätebefunde übernommen).

---

## Befundtabelle

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| **P1** | `LongPressMenu.kt:63-66` (`menuLeftPx`-Clamp) vs. `:135` (`stillArmed` `halfW=menuWidthPx/2+slop` um **Tastenmitte**) | **F24e6: Clamp und Bewaffnungszone widersprechen sich.** Die Palette wird per `coerceIn(0, viewWidth−menuWidth)` an den Rand geklemmt, die Arming-Zone aber **symmetrisch um die Tastenmitte** (`downX`) gerechnet — `[centerX−halfW, centerX+halfW]`. Für eine Taste nahe dem Rand (Fusszeilen-Punkt, Breite 2, view 1080) liegt die überstehende **linke** Palettenhälfte (Zellen 0/1) **außerhalb** dieser Zone ⇒ Schieben dorthin disarmt und das Menü öffnet nie (gemessen: Zelle 0 x=468 und Zelle 1 x=594 ohne popup-open; `,`/`!` damit sprachlich unerreichbar). | Bewaffnungszone aus dem **tatsächlich geklemmten** `left` (Ergebnis von `menuLeftPx`) ableiten: `offset ∈ [left−downX, left+menuWidth−downX]` statt `±menuWidth/2` um die Mitte. Dann ist jede Menüzelle per Schieben erreichbar, ohne Überstand (`Überstand erlauben` verwirft der Viewer-Clip, s. o.). |
| **P2** | `VoiceKeyboardService.applyLanguage` (aus F24/F25 offen) | **Stiller de-de-Fallback ohne Hinweis bleibt.** Fehlt das Asset der Zielsprache, lädt `loadOrFallback` de-de, der Status zeigt aber das Ziel-Endonym. (Zurückgestellter P2.) | Fallback-IST-Sprache erkennen und ehrlich anzeigen. |
| **P2** | `KeyboardView.drawKeyHint` (aus F23 offen) | **Latente Hint-Kollision in 12er-Reihen bleibt.** Horizontale Enge (Hint rechts oben vs. Mittel-Label) ungeprüft; aktuell kein Hint-Layout mit 12er-Reihe. (Zurückgestellter P2.) | `hintAnchorX` gegen das Label-Ink-Rechteck klemmen oder Hint bei `keyWidth<Schwelle` ausblenden. |

---

## Antworten / Prüfungen

- **Mechanik stimmt.** `cellAction(index, actionCells) = getOrNull(index)?.takeIf { isNotBlank() }` (LongPressMenu:38-39): `""`/`"   "` → `null` ⇒ Text-Commit (F24e5-Fix korrekt; der Geraetefund „verpuffte Action" ist weg). `stillArmed` wächst die seitliche Zone auf `max(keyWidth/2, menuWidth/2)+slop` (Z.135) — die P1 oben betrifft nur die **Mittensymmetrie**, nicht die Breite.
- **Aktionsfeld + Shift:** die einzigen `cellActions` sind `cursor-left`/`cursor-right` (Leerstring für Textzellen); `commitAlternate` (KeyboardView:509 ff.) sendet `onAction(action)` **oder** `onTextInput`. Shift wird wie gehabt nur bei echtem Text-Commit verbraucht; die Cursor-Aktion löst ausschließlich den bestehenden `DPAD_LEFT/RIGHT`-Pfad (VKS:973/976) aus — keine Shift-/Diktat-Interaktion neu geschaffen. Ein Cursor-Aufruf im RECORDING ist Fremd-Edit (Ownership-Verlust) — identisch zum früheren Tasten-Fuß, **kein** neuer Pfad.
- **Index-Parallelität:** `alternates = [",","!","?","‹","›"]` (5) ↔ `cellActions = ["","","","cursor-left","cursor-right"]` (5); `KeyboardView:456` nimmt `cellActions.take(n)` parallel zu `glyphs.take(n)` — bei einem `hint` (Ende von `withHint`) liegt er **hinter** den Aktionswerten und hat `cellAction==null` ⇒ Text. Kein Drift.
- **Zeichensatz `‹`/`›`:** nur als **Glyphen-Label** der Cursor-Zellen, richtungsneutral und sprachunabhängig — vertretbar für alle 15.
- **Fusszeilen:** alle 15 (base **und** shift) haben period; Σ width = 13 (`space 5 + period 2` bei 14, `space 6 + period 1` bei de-de); keine cursor-Taste mehr — die DRIFT-Ursache aus `REVIEW_FUSSZEILE_MODELLE_PFADE.md` ist damit (im Quellbaum) beseitigt.
- **Regel 9 — bestätigt:** DIAG-Logs `popup-open cells=$n left=$left top=$top` und `popup-commit cell=$cell action=${action ?: "-"}` führen **nur** Zahlen + einen statischen `KeyActions`-Namen (`cursor-left`), **kein** Glyph, Feldinhalt oder Pfad; `debuggable()`-geschützt. Kein Verstoß.
- **Tests:** `LongPressMenuTest` deckt `""`/`"   "`/5er-Menü-Offset `−270` true / `−360` false; `FooterAcrossAssetsTest` deckt Σ/ID-Parallelität; `LayoutParserTest` hint-Fälle. (Nicht selbst ausgeführt — offen.)

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 3** (ursprünglich 1 P1 eingetragen, nach Klärung zurückgestuft).

**KORREKTUR (nachträglich, Orchestrator-Messfehler geklärt):**
1. Der vermeintliche „F24e6-P1" beruhte auf einem **Stale-Build**: `install -r` lässt den IME-Prozess weiterlaufen — ohne `am force-stop de.voicekeyboard` (und ohne `settings get secure default_input_method` = `de.voicekeyboard/.ime.VoiceKeyboardService`; eine Runde lief auf dem Google-TTS-IME) misst man alten Code. **Statisch ist `menuWForArm` korrekt gefüllt** (KeyboardView:666 `menuWidthPx(cellPx, cellCount(alternatesOf(k)), padPx)` = 756 für die 5-Zellen-Periode) und wird an `stillArmed(…, menuWidthPx=menuWForArm)` übergeben → die Zone wächst auf `max(keyWidth/2, menuW/2)+slop` und deckt die Palettenzellen ab. Rest bleibt nur ein ~44-px-Splitter am weit außen liegenden Rand einer **geklemmten** Randtastenpalette (Zone symmetrisch um die Taste, Palette asymmetrisch geklemmt) — deshalb **Zonenbasis = tatsächliches (geklemmtes) `left`** statt `±menuWidth/2` (**P2**, keine Unerreichbarkeit eines ganzen Sprachzeichens).
2. „Asset-Modell-Drift" aus `REVIEW_FUSSZEILE_MODELLE_PFADE.md` ist **FALSE POSITIVE**: `D10_FOOTER.composeRow` (KeyboardModel:140ff) baut die Fußreihe **absichtlich** um (Mic rechts, Enter vorletzt, Space 5→4, Mic 1→2); der VK-HIT-Befund war schlicht das **alte** Asset. Zurückstufen auf „angedacht, vom Design gedeckt"; die neue `period`-Taste (width 2) bleibt durch `compose` unverändert (÷ Σ 13, 166 px ≤ 200, passt auf 1080).

**Offen (zum Belegen):** eigener `./gradlew test`-Lauf (429/0), Volltext der neuen `LongPressMenuTest`/`FooterAcrossAssetsTest`-Methoden, eigene Reproduktion der Zelle-0/1-Nichtöffnung nach dem Zone-Fix.