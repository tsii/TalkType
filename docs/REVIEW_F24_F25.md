# Review F24/F25 — Hint-Additivität (9 Layouts) + Sprachwechsel-Verdrahtung

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `VoiceKeyboardService` (onAction/Sprachwechsel/Vormerkung), `git diff` über 9 Layoutdateien, Hint-Freiheit der Sondertasten, hint in base+shift. Kein Build/Emulator. **F24c (tr-tr/pl-pl/ru-ru/el-gr) läuft gerade — nicht geprüft.**

---

## Befundtabelle

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| **P2** | `VoiceKeyboardService.kt:applyLanguage` (loadOrFallback + `putString`) | **Fehlendes Asset ⇒ Status lügt.** `applyLanguage(id)` setzt `putString(KEY_CURRENT_LANGUAGE, id)` und zeigt `language_switched_to(endonym(id))`, aber `repository.loadOrFallback(id)` fällt bei fehlender Datei still auf de-de zurück — die Leertaste/Anzeige zeigen x-Endonym, das Layout ist aber de-de. Alle 15 Assets existieren zwar, der Nachtpfad (korruptes/entferntes Asset zur Laufzeit) ist unmarkiert. | Fallback erkennen (z. B. `loadOrFallback` mit Ist-ID-Rückgabe) und dann den echten (Fallback-)Namen anzeigen, nicht den angeforderten. |
| **P2** | `KeyboardView.kt:376-399` (`drawKeyHint`, Anker rechts oben) | **Kollisionsrisiko bei schmalen Reihen.** Der Ziffernhint liegt rechts oben; bei 12-Tasten-Reihen (~63 px Taste) kann er mit dem Mittel-Label kollidieren — die Guard-Prüfung `baseline+descent > key.bottom` fängt nur **vertikal**, nicht horizontal (kein Abstand zum Label). Aktuell trägt **kein** Hint-Layout eine 12er-Reihe (alle r0 der 9 = 10–11 Tasten), daher latent. | Horizontale Enge berücksichtigen (z. B. `hintAnchorX` gegen das Label-Ink-Rechteck klemmen oder Hint ausblenden, wenn `keyWidth < Schwellwert`). |
| Note | `VoiceKeyboardService.kt:again` | **Kein a11y-Announcement nach Sprachwechsel.** Die Tasten-Beschriftung/`contentDescription` wird per `setLayout` neu gesetzt, und die Statuszeile zeigt das Endonym — aber es gibt kein `announceForAccessibility`. Screenreader erfahren den Wechsel nur durch erneutes Erkunden. | optional `rootView?.announceForAccessibility(endonym)` nach `applyLanguage`. |
| Note | (14 Layouts vs. de-de) | **Fußzeilen-Inkonsistenz.** de-de hat `‹ ›` (Cursortasten) verloren, die anderen 14 tragen sie noch → beim Sprachwechsel ändert sich die Fußzeile. Funktionell unkritisch (keine Sprache bricht), aber als Übergang nur bis F24c/d vertretbar. | In F24c/d vereinheitlichen; bis dahin als bewussten Übergang dokumentieren. |

---

## Antworten

1. **F25 Sprachwechsel — verdrahtet, Vormerkung wird übernommen.**
   a) `onAction` ist **vollständig**: `BACKSPACE/SPACE/ENTER/CURSOR_LEFT/CURSOR_RIGHT/LANGUAGE/MIC/EMOJI` haben alle einen Zweig (`VKS:970-998`; `EMOJI→toggleEmojiPanel`, `MIC→micToggleAction`, `LANGUAGE→switchToNextLanguage`). Kein `KeyActions.*` ohne Zweig.
   b) `pendingLayoutId` wird **auf dem nächsten Mic-Down übernommen**: `applyPendingLayoutBeforeCapture()` ist in `onMicDown` (VKS:373) aufgerufen und wendet `applyLanguage(target)` an, wenn Ziel aktiv und ≠ aktuell. Kein Hängenbleiben.
   c) Fehlendes Asset → stiller de-de-Fallback **mit falscher Anzeige** (P2 oben).
   d) Beschriftung aktualisiert (Leertaste + Status), aber kein a11y-Announcement (Note oben).

2. **F24a/b Additivität — rein hint-additiv, 0 Abweichungen.** `--numstat`: `en-us 40/20 · nl-nl 32/12 · it-it 34/14 · es-es 32/12 · da-dk 34/14 · fi-fi 38/18 · sv-se 36/16 · fr-fr 28/8 · cs-cz 30/10`. Jede `-`-Zeile ist eine alte `"value": "x"`-Zeile, jede nicht-Hint-`+`-Zeile existiert **nicht** (alle `+`-Zeilen enthalten `"hint"`); das „Removed" ist nur der Zeile-um-Bruch (`"value":"q"` → `"value":"q",` + `"hint":"1"`). Kein `width`/`alternates`-/Taste geändert oder entfernt.

3. **hint in base UND shift — belegt.** In `en-us` stehen die `-`-Zeilen sowohl für `q..p` (base) als auch `Q..P` (shift) → beide Ebenen tragen denselben Hint. qwerty (en-us) und qwertz (cs-cz) analog.

4. **Sondertasten hint-frei — AOSP-konform.** r0-Ende ohne hint: `da-dk/fi-fi/sv-se` = **1** (`å`), `it-it` = **2** (`è é`), `cs-cz` = **2** (`ě š`). Die Ziffer-Hints liegen nur auf q…p, nie auf Sondertasten. ✓

5. **Breite Reihen:** s. P2 oben (horizontal ungeprüft, latent, aktuell nicht getroffen).

6. **Fußzeile:** s. Note oben (Übergang, P2-mäßig bis F24c/d).

6b. **Regel 9:** neuer Code/Logs ohne Nutzerinhalte; `docs/AOSP_ROWKEYS_BELEGE.md` zitiert nur Datei-/Tastennamen (erlaubt). Kein Verstoß.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 2** (Fallback-Anzeige-Lüge; horizontale Hint-Kollision latent) · 2 Notes.

**Diff-Zeilen pro Layout:** en-us 40/20 · nl-nl 32/12 · it-it 34/14 · es-es 32/12 · da-dk 34/14 · fi-fi 38/18 · sv-se 36/16 · fr-fr 28/8 · cs-cz 30/10.

**Vor v0.1.16 (Bau 17) zwingend:** nichts Blockierendes — die zwei P2 sind UX-/Nightpath-Qualität; **F24c (tr/pl/ru/el)** muss als nächster gesichteter Block noch dieselbe Additivitätsprüfung durchlaufen. Offen: Testlauf nicht selbst ausgeführt (O/S 419/0 übernommen), `pendingLayoutId`-Persistenz (RAM-only, bei Prozessneustart verloren — wie F10 dokumentiert).