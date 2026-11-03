# Statische Untersuchung — Fußzeilen-Modell-Pfade (Gerätewiderstreit)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Frage:** Warum meldet VK-HIT `cursor-left/right`-Rects, obwohl das Asset (Working-Tree **und** gepulltes APK) keine Cursor-Tasten mehr enthält?

---

## Antworten (mit Zeilen)

**1) Wer entscheidet die Fußreihe im Modell — keine Cursor-Tasten werden erzeugt.**
`KeyboardModel.kt:69` baut jede Zeile über `D10_FOOTER.compose(layer.rows)`; `composeRow` (KeyboardModel ~127-159) macht **nichts** anderes als: bei `mic`+`enter` in der Zeile → `space` um −1, `mic` ans Ende mit Breite 2, Rückgabe sonst unverändert. **Es wird keine Taste hinzugefügt, also auch kein `cursor-left/right`.** Die Mic-Taste ist danach **Modell-Taste** (nicht das Statusleisten-`micButton`, das ein eigenes `Button` in `ImeRootView.kt:75/112` ist und mit dem Popup-/Fußreihen-Mic nichts zu tun hat). Mit dem aktuellen `de-de.json`-Fuß (row3 = `[symbols 1.5, language 1, emoji 1, space 6, period 1, mic 1, enter 1.5]`, Σ 13) ergibt `compose` zwingend `[symbols, language, emoji, space 5, period, enter 1.5, mic 2]` — **kein** cursor-Paar.

**2) Kein zweiter Layout-Pfad mit `‹ ›`.** `grep -rn "cursor-left" app/src/main/java` liefert ausschließlich: `LayoutAsset.kt:67-68`/`:72` (die `KeyActions.CURSOR_LEFT/RIGHT`-**Konstanten**), `VoiceKeyboardService.kt:973/976` (Befehls-Zweige in `onAction`) und `KeyboardView.kt:913-914` (A11y-Labels „Cursor links/rechts"). **Kein** eingebettetes Fallback-Layout, keine `res/xml`-Fußzeile, kein In-Code-`LayoutAsset`, das cursor-Tasten materialisiert.

**3) VK-HIT ist an das **aktuelle** Modell gebunden — und das wird bei `setLayout` neu gebaut.** `KeyboardView.setLayout` (Z.188) → `rebuildModel` (Z.213) → `model = KeyboardModel(…)` (Z.220) ersetzt die Instanz; `keyAtVisible` (Z.315-316) liest `model?.keyAt(x,y)`, und die VK-HIT-Zeile (Z.724) loggt genau dessen `id`/`rect`. Ein „veraltetes Modell" wäre nur erklärbar, wenn `setLayout` **nicht** liefe.

**4) / Folgerung — der Gerätebefund ist mit diesem Code unvereinbar ⇒ Build-Drift, nicht Code-Bug.** Die gemessenen Rects (`cursor-left 623..706`, `cursor-right 706..789`, `enter 789..913` bei unit 83,08 px) entsprechen **exakt** dem **alten** `de-de`-Fuß (`space 5` + `cursor-l/r` + `enter 1.5` + `mic 2`) nach `compose`. Das gepullte APK-Asset ist aber der **neue** Fuß (`space 6` + `period`). Da der Code keine cursor-Tasten synthetisiert (1+2) und `setLayout` das Modell ersetzt (3), bleibt nur: **das installierte v0.1.15 wurde aus einem gemischten Stand gebaut** (Asset neu committed, Code/`KeyboardView`/Modell noch vom alten Stand kompiliert) — ein **Build-/Versions-Drift**, der im Quellbaum nicht reproduzierbar ist.

---

## Befundtabelle

| Sev | Ort | Befund | Fix |
|---|---|---|---|
| **P1** | installiertes APK v0.1.15 (nicht im Quellbaum reproduzierbar) | **Asset↔Modell-Drift:** APK enthält neuen `de-de`-Fuß, aber die laufende View baut das Modell aus dem alten Fuß (cursor-l/r). Im Working-Tree konsistent (Asset==Modell). | Vor jedem weiteren Fußzeilen-Umbau: `assembleDebug` aus **sauberem** Stand + `adb install -r` + `force-stop` **und** VK-HIT gegen die neuen Rects prüfen; Ursache = gemischter Build-Zustand, nicht F23/F24d-Code. |
| Note | `LayoutAsset.kt:67-68` u. a. | `CURSOR_LEFT/RIGHT`-Konstanten/Befehle verbleiben als toter-/Fremdsprachen-Rest (alle 14 Nicht-de-de nutzen sie weiter) — **kein** Bug, aber die Unschärfe, die den Drift auffallen ließ. | Nach F24e (alle 15 auf Punkt-Taste) die Konstanten/`onAction`-Zweige/A11y-Labels entfernen — dann ist der einzige Beleg verschwunden, dass es je cursor-Tasten gab. |

---

## SUMME

**P0 = 0 · P1 = 1 · P2 = 0.** Der „Widerstreit" ist ein **Build-/Versions-Drift** des installierten APK, nicht ein Fehler in F23/F24d (Quellbaum: Asset und Modell sind konsistent, kein zweiter Pfad, `setLayout` ersetzt das Modell).

**Vor F24e zwingend zu klären (Reihenfolge):** (1) sauberes `assembleDebug` aus demselben Commit wie `de-de.json`; (2) `adb shell dumpsys package … | grep versionName` gegen den Build abgleichen; (3) VK-HIT auf dem frischen APK erneut messen und `period`/`mic`-Rects (nicht `cursor`) bestätigen. Erst dann sind die 13 weiteren `‹ › → .`-Umbauten (F24e) verifizierbar-sicher.

**Regel 9:** erfüllt — keine Nutzerinhalte, nur Tastennamen/Ziffern/Geometrie.