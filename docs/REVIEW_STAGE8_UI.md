# Review Stufe 8 — UI (Emoji V2, KeyboardView-P0-Umbau, Sprach-/Emoji-Verdrahtung)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-28
**Gegenstand:** `ImeRootView.kt`, `EmojiPanelView.kt`, `VoiceKeyboardService.kt` (Emoji/Sprachwechsel), `KeyboardView.kt` (P0-Reparatur), `strings.xml`. **Kein Build/Emulator** (Gradle-Lock); Orchestrator-Messwerte als Geometrie-Beleg genutzt.

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| UI-01 | **P2** | `KeyboardView.kt` `displayLabel` (`KeyActions.SPACE -> "Leer"`) | **Space-Label hartkodiert Deutsch (L-01).** `displayLabel` überschreibt für Action-Tasten das Layout-Label; `SPACE → "Leer"` ignoriert damit das bereits lokalisierte `label` der 15 Layouts (`pt-pt` hat „Espaço", en-us würde „Space" erwarten). Alle Sprachen zeigen „Leer". **Nicht dist-blockierend** (kosmetisch, die Taste funktioniert), aber ein echter i18n-Fehler. | `displayLabel`: `when(src.value){ … KeyActions.SPACE -> "Leer" … else -> src.label }`. | `SPACE` aus der Hardcode-Liste streichen und `src.label` verwenden (die Layouts tragen das Label bereits) — nur echte Symbole (`⌫`, `⏎`, `⇧`/`⇪`) hartkodieren. |
| UI-02 | Note | `ImeRootView.kt:96-135` + gemessene Rects | **Geometrie konsistent — P0-Reparatur bestätigt.** Orchestrator: Reihenmitten 1712/1864/2016/2168 (Abstand exakt 152 px = `keyHeightPx` 52dp=136,5 + `rowGapPx` 6dp=15,75); Emoji-Taste `rect=<214,456,291,593>` (Höhe 137 px ≈ 136,5). Passt zu `keyHeightPx=52*density`, 4 Zeilen. Ein-Winkel-Basis stimmt: `onMeasure` baut bei Breiten- **und** Höhenänderung neu (`lastModelWidth != width || lastMeasuredHeight != measuredHeight`), `onDraw` und `keyAt` teilen dasselbe `model`. Kein Widerspruch. | `KeyboardView.onMeasure/onSizeChanged`, `keyHeightPx=52f*density`, Messwerte. | — (kein Fund). |
| UI-03 | Note | `ImeRootView.kt` (`showEmojiPanel`/`hideEmojiPanel`) + `VoiceKeyboardService` `toggleEmojiPanel`/`onEmojiSelected` | **Kein Fokus-/Ownership-Bruch im Emoji-Pfad.** Panel ist ein Stack-Kind (KbdView GONE, Emoji-Block VISIBLE), kein eigenes Window → `currentInputConnection`/Fokus bleiben. Einfügen ausschließlich über `onTextInput → ic.commitText(text,1)`; kein `restartInput`, kein `finishInput`, kein `setComposingText`. Öffnen **und** Einfügen sind bei `state != IDLE` gesperrt (`toggleEmojiPanel` + `onEmojiSelected`). Mic-Down bei offenem Panel schließt erst (Z. `onMicDown` → `hideEmojiPanel`). `OwnershipTracker`/`InputConnectionSink.ownSpan` werden nicht angefasst. | `onTextInput` = `ic.commitText`; `toggleEmojiPanel`/`onEmojiSelected` Gate `c.state != IDLE`. | —. |
| UI-04 | Note | `VoiceKeyboardService.onDestroy` | **Doppel-`abort()` (harmlos).** `onDestroy` ruft `coordinator?.abort()` und danach `coordinator?.shutdown()` (das intern erneut `abort()` ausführt). `abort()` ist idempotent (`active.set(false)`, `channel?.close`, `controller.abort`) → kein Fehler, nur redundant. Kein Final-Worker-Race mehr (N-3 korrekt beseitigt). | `onDestroy`: `abort()` + `shutdown()` (das `abort()` enthält). | Optional: direkt nur `shutdown()` (enthält `abort()`), `abort()`-Aufruf streichen. |
| UI-05 | Note | `KeyboardView.onMeasure` (Höhen-Tracking) | **`lastMeasuredHeight != measuredHeight` ist verschachtelt.** Der Vergleich läuft gegen das *vorherige* `measuredHeight` statt gegen einen sauberen „letzte Höhe"-Wert; Effekt: der Rebuild-Trigger für Höhenänderungen hängt an `onSizeChanged` (der ohnehin feuert). Funktional durch `onSizeChanged(w,h)` abgedeckt, aber fragil lesbar. | `onMeasure` Bedingung `lastModelWidth != width \|\| lastMeasuredHeight != measuredHeight`. | `lastMeasuredHeight` gegen den in der letzten Runde gesetzten `h` vergleichen (klarer), oder den Höhen-Rebuild allein `onSizeChanged` überlassen. |

---

## Punktgenaue Antworten (PRÜFFRAGEN)

- **Fokus/Eingabe-Kette:** Kein Pfad bricht den Editorfokus — Panel ist reines Stack-Kind ohne eigenes Window; `onStartInput`/`onFinishInputView` schließen das Panel (`V2 §3.7`) **und** rufen `coordinator?.onEditorChanged()`/`stopDictationIfRunning()` — der Cursorbesitz wird dadurch nicht verletzt, sondern korrekt aufgegeben. `resetShiftState` beim Sprachwechsel (L-02) verhindert toten Shift; kein `restartInput`/`finishInput` im Sprach- oder Emoji-Pfad. **ANNAHME:** das Panel teilt den Fokus korrekt, weil `commitText` über die bestehende `currentInputConnection` läuft.
- **Diktat-Zusammenspiel:** Öffnen **und** Einfügen sind bei ≠IDLE gesperrt; ein verspätetes Touch-Event nach `abort()` erreicht ein GONE-Panel nicht (keine Trefferfläche). Der Emoji-Weg nutzt **kein** `onEditExecutor` (direktes `commitText`, main-thread-synchron) → das A1-`abandoned`-Flag ist hier nicht betroffen und es besteht keine Geisterschreib-Gefahr.
- **Geometrie:** eine eindeutige Koordinatenbasis (`model` für `onDraw` **und** `keyAt`); `rebuildModel(width ?: lastModelWidth)` ist sicher, weil `setLayout` zuerst `lastModelWidth>0` setzt (negatives `totalWidthPx` kann nicht auftreten); Messwerte stimmen mit `keyHeightPx`/Zeilenpitch überein (UI-02).
- **Ressourcen:** 8 Kategorienamen exakt wie E10.2 (Gesichter, Personen, Tiere & Natur, Essen & Trinken, Reisen & Orte, Aktivität, Objekte, Symbole) + `emoji_back_to_keyboard` „Zur Tastatur" — 8/8 korrekt. Einzige stilldeutsche Hartkodierung = `SPACE → "Leer"` (UI-01, L-01).
- **Regressionen:** Ping-Pong-Fix symbolShift (`shift` in Symbolebenen → `symbols↔symbolsShift`), Doppel-Tipp-Reihenfolge (P1-02), Backspace-Grapheme, Push-to-Talk über Reihen-Mic → alles konsistent erhalten; keine stille Verhaltensänderung gefunden.

---

## Verdikt

**freigabefähig** (für die dist-APK) — **0 P0, 0 P1, 1 P2** (UI-01 „Leer"). Die P0-Reparatur ist durch die Orchestrator-Messwerte (Abstände/Tastenrects) bestätigt; der Emoji-Pfad ist fokus- und ownership-sicher; der einzige Befund ist die L-01-Kosmetik.

**Nicht geprüft (kein Gerät/Emulator):** echtes Mikrofon + Diktat über Emoji-Panel, tatsächliche Emoji-Glyphen-Rendering/Breite, TalkBack auf Emoji-Raster/Fußleiste, Verhalten bei Hardware-Tastatur/`TYPE_NULL`.

## Top-3

1. **UI-01 (P2)** — `SPACE` nicht mehr auf „Leer" hartkodieren, sondern `src.label` (Layout liefert lokalisiertes Label).
2. **UI-05 (Note)** — `onMeasure`-Höhen-Tracking vereinfachen (gegen den letzten `h` vergleichen), um die Rebuild-Trigger explizit zu machen.
3. **UI-04 (Note)** — `onDestroy` auf ein einzelnes `shutdown()` reduzieren (enthält `abort()`).