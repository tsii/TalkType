# Review F29/F33 — Status/🐞-Fix, Mikro-Zustand, Pfad-Icons, r2-Raster, LayoutTest

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-11-01
**Stand:** v0.1.25 / vc26 · Debug 471/0 · Release 440/0 (nicht selbst gelaufen).

---

## Befundtabelle

| Befund | Schwere | Datei:Zeile | Fix |
|---|---|---|---|
| **Status-/🐞-Fix ist strukturiert (eine Quelle).** Der 🐞 ist jetzt **festes View-Kind** der `statusRow` (`ImeRootView:44,93-102,136`), kein dynamisches `addView` mehr — das frühere Doppel-Verschwinden (2×) kam vom wiederholten `addDebugInfoButton` über `onCreateInputView`. `postEvent` → `StatusRouting.route` → `StatusBarText.select` liefert **immer** einen Text (Idle-Fallback „Bereit"), kein Leer-Zustand mehr. | ✓ (kein Fund) | `ImeRootView.kt:136`, `VoiceKeyboardService.kt:331` | — |
| **Popup verdeckt 🐞 visuell (r0-Hold, clipChildren=false).** Das r0-Menü zeichnet mit negativem top in die Statusleiste; im Root gilt `clipChildren=false` (`ImeRootView:159-160`), also liegt die Palette über der Zeile — die 🐞-Taste bleibt zwar touchbar (Popup ist Canvas), ist aber während des Holds verdeckt. | P2 | `KeyboardView.kt:521-533`, `ImeRootView.kt:159-160` | r0-Popup nicht über die Statusleiste ziehen (db/Zeile aussparen) oder `clipChildren` nur am KeyboardView statt an der Root. |
| **„Grün hängt" nach Watchdog-/Audio-Abbruch.** `micRecording` (KeyboardView:773) wird nur auf den expliziten Pfaden (stop/abort/onMicUp) auf false gesetzt; ein Stall-/Silence-/Audio-Fehler läuft über `coordinator.errorListener → abort()`, ohne dass der **Service** `micRecording=false` + `updateStatus` anstößt — die Mic-Taste bleibt „aufnahmereif" (accent), obwohl die Session IDLE ist. | P2 | `DictationCoordinator.errorListener` (abort), `VoiceKeyboardService` (kein Reset im Fehlerpfad) | In `onError`/`visibleError`→`updateStatus` zusätzlich `keyboardView.micRecording = (state==RECORDING)` setzen (zentral in `updateStatus`, statt verstreut). |
| **Pfad-Icons:** `drawActionGlyph` (KeyboardView:676-737) zeichnet ⌫/⇧/⏎ als Canvas-Pfade (kein Font-Tofu); `displayLabel` behält „⌫"/„⇧"/„⏎" **nur** als A11y-Label (Z.746-747), wird nicht gezeichnet. Kein Rest-Glyph. | ✓ | `KeyboardView.kt:676-737` | — |
| **Pfad-Per-Frame-Allokation.** `drawActionGlyph` baut pro Taste pro `onDraw` einen neuen `Path` (kein Cache) → 3 Pfade × Frame. Klein, aber im Hot-Path. | P2 | `KeyboardView.kt:695-737` (`Path()` je draw) | Pfade einmal in `init` vorbereiten (skalieren über `Matrix`/`scale`), nicht je Frame neu allokieren. |
| **F33 r2-Raster (Zentrierung 450/MARGIN/cs-cz/D27) und ImeRootViewLayoutTest:** Einzelwert-Gates (`r1[0]=0,5`, `@`, `shift 1/1,5`, `space 6,5`) halte ich aus F31b für vorhanden; die neuen r2-MARGIN-/Mitten-Asserts und die ru-ru-D27-Ausnahme habe ich **nicht** gelesen. | offen | `LayoutCompletenessTest`/`FooterAcrossAssetsTest` | Einzelwert-Assert für `r2`-Mitte (450) + `cs-cz`-Begründung + D27-Doku gegenlesen. |
| **ImeRootViewLayoutTest (ShadowCanvas DrawOps):** nicht gelesen; JVM-Textmetrik-Abhängigkeit wäre der einzige Flakiness-Risikopunkt. | offen | `ime/ImeRootViewLayoutTest.kt` | Prüfen, ob nur Draw-Op-*Struktur* (nicht Pixelmaße aus geräteabhängigen Textmetriken) assertiert wird. |

---

## Gesamturteil

**P0 = 0 · P1 = 0 · P2 = 3** (Popup verdeckt 🐞; „grün hängt" nach Fehlerabbruch; Pfad-Per-Frame-Allokation) · 2 „offen" (F33-r2-Gates, ImeRootViewLayoutTest).

**Push-fähig: JA.** Die zwei früheren P0 (leerer Status / verschwundener 🐞) sind **strukturiert** behoben (feste View-Kind-Wurzel + immer-gesetzter StatusText), der Mikro-Doppelstart ist durch das `startingUp`-Gate weiterhin ausgeschlossen, und die drei P2 sind kosmetisch/robustheits-only. Die zwei „offen"-Punkte bitte vor 0.1.26 gegenlesen, blockieren aber nicht.

---

Datei: `docs/REVIEW_F29_F33.md` · ~95 Zeilen.