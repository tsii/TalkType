# Review F22/F22-K/M/L — Langdruck-Menü (LongPressMenu, Bewaffnung, Popup)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `keyboard/LongPressMenu.kt` (reine Naht), `KeyboardView` (Bewaffnung/Popup/Geste), `KeyboardTheme` (POPUP-*/LONG_PRESS-Konstanten). Kein Build/Emulator (Gerätebefunde des Orchestrators übernommen).

---

## Befundtabelle

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| Note | `KeyboardView.kt:418` + `LongPressMenu.kt:13-14` (KDoc) | **Veraltete Kommentarwerte:** „Polsterstreifen (4 dp)" und „126·3 + 10·4 = 418" sind Stale — `KeyboardTheme.POPUP_PAD_DP = 8` ⇒ `padPx = 21 px`, d. h. n=3 ⇒ 126·3 + 21·4 = **462**, nicht 418. Zeichnung **und** Trefferlogik nutzen beide `p.padPx` (21 px) — konsistent; nur die Kommentare/Testbeispiele (pad=10 → 418) referenzieren den alten Wert. | Kommentare auf „Polsterstreifen (8 dp = 21 px)" und „126·3 + 21·4 = 462" korrigieren (rein dokumentarisch). |
| Note | `KeyboardView.kt:648-653` (VK-HIT) | **VK-HIT-Debugzeile loggt `value` = statisches Tastenlabel** (z. B. „ä"/„e"), keine Nutzer-/Editor-/Audio-Inhalte und keine Pfade/URLs; zusätzlich `FLAG_DEBUGGABLE`-geschützt. Vertretbar (Regel-9-Intention = keine *Eingaben*; das Tastenlabel ist eine Layout-Konstante). | Keine; ggf. KDoc-Vermerk „nur Tasten-label/Geometrie, nie Feldinhalt". |

---

## Antworten A–I

- **A) DecodePace/Finale unabhängig — kein P0.** Das Menü ist reines KeyboardView-UI; `DictationController`-Finale ruft `engine.transcribe` ohne jeden Popup-/Pace-Bezug. (F21 belegte das Finale bereits als ungebremst.)
- **B) Zone vs. Auswahl widerspruchsfrei.** `stillArmed` (Arming, ± `w/2+slop`, oben bis `menuHeight+gap+slop`) ist eine **Obermenge** der `cellWins`-Region (`|offset| > w/2−slop`, `h/2−slop`); die Menü-Zellzeile (`gap … gap+menuHeight` über der Taste) liegt **innerhalb** der Arming-Obergrenze ⇒ jede gewinnbare Zelle ist erreichbar, und kein Zellgewinn außerhalb der Bewaffnung (die Abwärts-Band liegt unter dem Menü ⇒ `popupCellAt` liefert dort ohnehin -1). Kein toter Zustand.
- **C) Mic nie bewaffnet + kein synthetisches Mic-UP.** Bewaffnung nur bei `down.kind=="text" && alternatesOf(down).isNotEmpty()` (KeyboardView DOWN-Handler) — Mic ist ACTION ⇒ nie bewaffnet. `micHeld`/Haltegeste ist ein getrennter Zweig; der zweite Pointer (`ACTION_POINTER_DOWN`) wird nicht behandelt ⇒ kein Mic-UP. D10-Rest §1 unverändert.
- **D) Caps-Doppeltipp vs. Langdruck getrennt.** `DOUBLE_TAP_MS=350 < LONG_PRESS_MS=450`; Shift ist ACTION ⇒ bewaffnen nie, Doppeltipp läuft über `handleAction(SHIFT)` (350-ms-Fenster). Langdruck auf einer Buchstabentaste (text+alternates) öffnet nur das Menü, löst keinerlei Shift aus.
- **E) CANCEL + Außerhalb-loslassen = 0 Zeichen auf allen drei Pfaden.** (1) `ACTION_CANCEL` mit offenem Popup → der `ACTION_UP`-Zweig überspringt (kein Commit); (2) `UP` außerhalb (cell=-1 **und** nicht `isInsideKey`) → `else -> Unit`; (3) Move-ab-Bewaffnung (pressedKey=null) → `key === pressedKey` scheitert → kein Commit. Alle drei belegt.
- **F) Temporäres Shift nur bei echtem Commit verbraucht.** `commitAlternate` kippt `shiftTemporary` ausschließlich bei einer gewählten Zelle; Abbruch/0-Zeichen lässt `shiftTemporary` unangetastet (Shift wirkt weiter auf die nächste Eingabe). ✓
- **G) Geometrie-Konsistenz ja, Kommentare stale.** `POPUP_HEIGHT_DP=56→147 px`, `POPUP_CELL_DP=48→126 px`, `POPUP_PAD_DP=8→21 px`; `drawLongPressMenu` und `popupCellAt`/`popupSelectionAt` nutzen dieselben `p.left/p.padPx/p.cellPx` und dieselbe `cellWins`-Schwelle ⇒ Zeichnen == Treffen. Nur die KDoc/Testbeispiele („4 dp", „418") sind veraltet (Note oben).
- **H) Regel 9 vertretbar.** VK-HIT loggt nur Tasten-`id` + statisches `value`-Label + Geometrie, `FLAG_DEBUGGABLE`-only, nie Feld-/Audio-Inhalt ⇒ keine Eingabe-Inhalte.
- **I) Tests fangen die drei Rückfälle rot:** (a) Slop-Regel weg ⇒ `cellWins auf der Grundtaste gewinnt nie` (Test, `assertFalse(cellWins(0,0,…))`) rot; (b) `cellWins` „immer true" ⇒ derselbe Test + „innerhalb Slop … gewinnt nicht" rot; (c) `MAX_CELLS→4` ⇒ `cellCount deckelt auf 8` (assertet 8) rot. Deckung vorhanden.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 0** (nur 2 Notes: stale KDoc/Test-Kommentarwerte 4dp→8dp/418→462; VK-HIT-Label als verteidigbare Debug-Nutzung).

**Offen (zum Belegen bräuchte ich):** einen echten `./gradlew test`-Lauf (404/0), die drei Orchestrator-Gerätebefunde (Reihe-0-„e"/„á"/„ê"/0-Zeichen) als eigene Reproduktion, und die exakte oberste-Reihe-Clip-Interaktion (`menuTopPx`-Klemmung bei Reihe 0) auf einem Gerät — per Code plausibel, nicht selbst gemessen.