# Review F15 — KeySurface (D11: Rückmeldung gedrückte Taste, verstärkter Press, Caps-Akzent)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `keyboard/KeySurface.kt` (neu), `KeyboardView.onDraw` (D11-Naht), `KeyboardTheme.kt:88/103` (Press-Alphas), `KeySurfaceTest`/`KeyboardThemeTest`. Kein Build/Emulator.

---

## Befundtabelle (nur Notes — kein P0/P1)

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| Note | `KeySurface.kt:38-45` (`surface`-Reihenfolge) | **Kein Fehler — GLMs „Abweichung" ist exakt D11.** `micRecording > CAPS_FILL > pressed > normal` entspricht wörtlich DESIGN_STOCK_LOOK.md „Gedrückt+Caps behält diese Akzentfüllung … **ohne zusätzlichen Overlayauftrag**". Der „verlorene" Fall (Caps-active Shift zeigt beim Drücken keine Press-Verdunklung) ist **vom Entscheid gewollt**; die Taste zeigt Akzent + `⇪`, das Deaktivieren (zweiter Tap) ändert die Fläche erst beim Loslassen. Kein Nutzerproblem. | Keine. |
| Note | `KeySurface.kt:42-44` (`capsFill`-Gate) | **Caps-Füllung korrekt nur aus echtem `capsLock`.** `istShiftKey && capsLock && !symbolShift && !activeLayerIsSymbols` — nie `shiftTemporary` (Z.529 „nur Glyphen-Akzent"), nie `symbolsShift`, nie symbols-Ebene. Abgeleitet **live** je `onDraw` (kein Cache) → kann nach Schicht-/Editorwechsel nicht kleben (`resetShiftState` Z.142/156 nullt `capsLock` zusätzlich). | Keine. |
| Note | `KeyboardTheme.kt:88,103` + Kontrastrechnung | **Kontrast ≥ 3:1 erfüllt.** Hell `micRecordingGlyph #37474F` auf `accent #4DB6AC` ≈ **4,2:1**; Dunkel `#263238` auf `#80CBC4` ≈ **7,0:1** (Relative-Luminanz, handgerechnet). Kein P1. | Keine. |
| Note | `KeyboardView.kt:288-315` (`when`-Kette) | Kein verdeckter Zustand: Häkchen (`micCheckShowing`) ist **Glyphe** (`keyToggledGlyph`, Z.311) und wird auf jeder Fläche gezeichnet; `micRecording` ist höchste Priorität (D10 unverändert); Press-Overlay ändert die Textfarbe nicht; Dunkel-Lesbarkeit bleibt (Text auf minimal aufgehellter Fläche). | Keine. |
| Note | `KeySurfaceTest`/`KeyboardThemeTest` (14 Tests) | **Verhalten + Konstanten getestet.** `KeySurfaceTest` prüft die Prioritätskette + „shiftTemporary füllt nicht" + „symbolsShift kein Caps-Fill" (würden bei Rückfall rot); `KeyboardThemeTest` assertet die **exakten** `keyPressed`-Werte `0xFFA3ABB0`/`0xFF676F74` (= `compose` aus 0x66/0x4D, nachgerechnet) — bei Rückfall auf 0x26/0x19 rot. | Keine. |
| Note | (alle Dateien) | **Regeltreu:** nur vorhandene Tokens, kein Popup/Rahmen/Schatten, keine Geometrie-/Touchflächen-/Layoutänderung (KeySurface ist reine Entscheidung, `onDraw` ändert nur `keyPaint.color` + Glyphe), keine Inhalte in Text/Log (nur Int-Enums). | Keine. |

---

## Antworten A–F

- **A:** GLMs Priorität ist **keine** Abweichung — sie ist die wörtliche D11-Vorgabe („ohne zusätzlichen Overlayauftrag"). Der einzige „Verlust" (Caps-active Shift ohne Press-Verdunklung) ist spezifikationsgemäß gewollt; der `⇪`-Marker + die Akzentfläche bleiben die Rückmeldung. Kein Nutzerproblem.
- **B:** korrekt — `capsFill` nur aus `capsLock && !symbolShift && !activeLayerIsSymbols`; live abgeleitet, kein Kleben; `resetShiftState` (Z.142/156) + Doppeltipp (Z.481-487) nullen den echten Caps-Zustand.
- **C:** Kontrast Hell ≈ 4,2:1, Dunkel ≈ 7,0:1 → **≥ 3:1**, kein P1.
- **D:** kein verdeckter Zweig; Häkchen/Toggle/Mic-Akzent/Dunkel bleiben lesbar (Häkchen = Glyphe, Mic-Recording = höchste Surface-Priorität).
- **E:** Tests sind aussagekräftig (Verhalten in `KeySurfaceTest`, exakte Sollwerte in `KeyboardThemeTest`); Rückfall auf 0x26/0x19 und Caps-Füllung aus `shiftTemporary` würden **rot**.
- **F:** regelkonform — nur Tokens, keine Flächen-/Geometrie-/Layout-Änderung, keine Regel-9-Verletzung.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 0** (nur Notes). F15 setzt D11 treu um.

**Offen (zum Nachmessen — ich kann es selbst belegen):**
- Kontrastwerte sind **handgerechnet**, nicht gerätegemessen; für eine harte Abnahme bitte die Relative-Luminanz auf Gerät/Device-Dump bestätigen (Hell ≈ 4,2:1).
- Die 14 Tests habe ich gelesen, nicht ausgeführt (kein Build).
- Sichtbarer Gerätebeweis (Hell/Dunkel „normal/gedrückt" + „Caps an/aus") steht laut D11-Abnahme noch aus.