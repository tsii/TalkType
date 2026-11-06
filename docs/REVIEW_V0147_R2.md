# RE-REVIEW v0.1.47 Fix-Runde (REVIEW_V0147 VK-1..VK-7) — cortecs/deepseek-v4-pro-0813
Maschinell bestätigt: Debug-Volllauf 885/0/0 fresh; Release-XMLs weiterhin `failures="0" errors="0"`.

## Befund um Befund

**VK-1 (P0 Seed/Crash) — BEHOBEN.**
`VoiceKeyboardService.kt:2099-2109`: Seed-Schleife `for (seedLang in linkedSetOf(current, rulesLang))` deckt BEIDE Sprachen ab, die der Korrektor dieser Berechnung laden kann (`load` erfolgt ausschließlich über `layoutId=correctorLang=current`, WordCorrector.kt:128; `rulesLang` zusätzlich für Seed-Vollständigkeit). Crash-Netz zusätzlich: `runCompute` wickelt `compute(region)` in `runCatching … getOrElse { Log.w("VK-CORR","compute_abort"); postToMain { clearRow }; return }` (:2925-2933) — walk-up-Reserve kann den Worker nicht mehr töten. Observability neu: `NeighbourModel.walkUpLoads` / `isSeeded` / `resetSeedsForTest` (NeighbourModel.kt:59-77).
Test: `corrComputeRegion seeds current and rulesLang - zero walk-up loads` (CorrectionWiringTest.kt:334-360, Robolectric-Echt-Service, ALL_ACTIVE de-de: beide `isSeeded`, `walkUpLoads == 0`) + `compute crash clears the row and never propagates` (:365-386, inkl. Regel-9-Log-Check). Verifizier-Befehl erneut ausgeführt — Seed-Abdeckung NACH `corrComputeRegion`, nicht nur Seed-Aufruf.

**VK-2 (P1 Undo unerreichbar) — BEHOBEN, Klick-Kette produktionstauglich.**
`ImeRootView.kt:164-165,503-504,520`: letztes `(hasSuggestion, enabled)` gemerkt; `setWandUndoMode` setzt `wandButton.isEnabled = hasUndoLevel || (lastHasSuggestion && lastEnabled)`. Produktionssequenz geprüft: APPLY→clearRow(setCorrection false/false)→onUndoMode(true)→ `isEnabled=true`→performClick dispatcht UNDO (wandUndoMode true); nächster Tastendruck→invalidateUndo→onUndoMode(false)→wieder deaktiviert — exakt das §5-Fenster.
Test: `undo glyph keeps the wand clickable after the row cleared` (CorrectionRowTest.kt:298-317) spielt genau diese Sequenz inkl. `performClick` und Gegentest „ohne Undo/Ebene disabled".
Restkante → P2-a (UNDO-Zweig prüft Diktat-IDLE nicht, siehe unten).

**VK-3 (P1 Stale-Suggestion-Race) — BEHOBEN, Race wirklich zu.**
`VoiceKeyboardService.kt:2869-2874`: APPLY vergleicht `tracker.buffer` gegen `suggestionForRegion` (bei publish gebunden, :2946-2947; in clearRow geleert :2901-2902); bei Abweichung: **kein** Editor-Aufruf, kein `applied++`, stattdessen `scheduleCompute(immediate=false)` — PRESSE wird ignoriert und der Compute neu geplant.
Test: `APPLY on a stale suggestion is ignored and re-plans the compute` (CorrectionWiringTest.kt:393-411): leeres Editor-Log, `applied==0`, Re-Plan.pending, und der abgefeuerte Re-Plan berechnet die FRISCHE Region („morgem  w"). Genau der in R1 geforderte Nachweis.

**VK-4 (P2 Interpunktionsinseln) — BEHOBEN, Insel-Ansatz idempotent.**
`VoiceKeyboardService.kt:2686-2704`: Core = erstes/letztes `isLetter || '-'` aus dem Whitespace-Token, Core korrigieren, Anhängsel unverändert wieder anhängen; reine Interpunktion (`start < 0`) bleibt unangetastet.
Edge-Fall-Durchrechnung: „kalr."→„kalt." ✓; Anführungs-/Klammer-Vorsilben ✓; Bindestrichwörter bleiben als Ganzes im Core (`start==0 && end==len` → `correct()` mit `-`-Pfad) ✓; `-abc`/`abc-` → `correctHyphenated` läuft auf `correct("")→null` → unverändert, keine Korruption ✓; „b2b", „z.B.", „1,5" (Core mit Ziffer/Punkt → `correct()` lehnt ab) ✓; „geht's" (Apostroph) ✓. Idempotent: der Core des ersten Laufs ist Listenwort bzw. Hyphen-Komposit „accepted" → zweiter Lauf `corrected == region → null`.
Test: `regionGoldenFixesTypoBeforeSentencePunctuation` (CorrectorGoldenTest.kt:259-273, datengetrieben über de.tsv class=region; de.tsv:114 „Der Kaffee ist zu kalr."→„…zu kalt.") + Zeilenzahl-Gates in TypingRulesTest.kt:182,191 (84 Zeilen, 1 region). Dünn (1 Goldense), aber der Mechanismus ist durch die existierenden Wort-Tests abgedeckt.

**VK-5 (P2 Hold-back-Abweichung) — BEHOBEN (Doku).**
`docs/PLAN_V0147.md:39-44`: DECISION 2026-11-05 (Oracle) — Hold-back bewusst NICHT in 0.1.47, Begründung (Hauptanwendungsfall „…ist zu kalr"+Wand), Restrisiko-Begrenzung über VK-2/VK-3/Suffix-Beweis, 0.1.48-Kandidat nur gemeinsam mit Wand-Press-Neuberechnung. Deckelt die Entscheidung sauber ab.

**VK-6 (P2 Adresse-Frage) — BEHOBEN (Doku inkl. Nutzer-Open-Point).**
`docs/PLAN_V0147.md:65-69`: Ausschlussliste FINAL (Passwort/URI/E-Mail/numerisch/Phone/DATETIME/NULL); POSTAL_ADDRESS und PERSON_NAME bewusst DRIN; das „Adresse"-Anhängsel des Reviews ist ausdrücklich als **USER OPEN POINT for 0.1.48** markiert. Code unverändert und konsistent (`isCorrectionFieldEligible`, ImeRootView.kt:64-78).

**VK-7 (P2 Doku-Drift Assets) — BEHOBEN.**
`SOURCES.md:33` jetzt „1,082,777 B plain" (Datei real 1,082,777, `stat` erneut bestätigt); NOTICE.txt:26-29 „stoplist subtraction (curated stop lists … 1267/660 bytes incl. 'strasse','bud')" — das falsche „initially empty" ist raus; Bearbeitungsvermerk vollständig (Filter, Dedup, Cut-off, Stoplist, VKWL1-Packung), Quelle/Autor/Lizenz/sha beider Listen vorhanden. Lizenzfest bis auf einen Restwunsch → P2-c.

## NEUE Regression (runCatching-Netz) — beobachtbar, aber nur per Log
`Log.w("VK-CORR","compute_abort")` ist ein fester Code-String — **kein** getippter/korrigierter Text, also regel-9-konform; Test `compute crash clears the row and never propagates` prüft auf Text-Leak und Row-Clear. Ein Zählerfeld (z. B. `corr_compute_abort`) existiert NICHT — Plan §8 verlangt es nicht; Beobachtung nur über logcat, nicht über den 🐞-Export. → P2-b.

## Verbleibende P2 (Bericht nur, keine Blocker)
- **P2-a** — `onWandPress(UNDO)` (VoiceKeyboardService.kt:2881-2886) und `RegionReplacer.undo` prüfen `dictationIdle()` nicht; durch den VK-2-Fix ist der Stab im Undo-Fenster wieder klickbar, sehingga Push-to-talk-start + Undo-Tipp vor dem nächsten Tastendruck ein Feldschreiben während laufender Session erlaubt (kein Abbruch, Suffix-Beweis begrenzt den Schaden; §5-IDLE-Vorbedingung sollte auch für Undo gelten). Min-Fix: `if (replacer.undo(ed) && dictationIdle())` → besser: UNDO-Zweig mit `if (!dictationIdle()) return` gate + Test.
- **P2-b** — compute-Abort ohne Zählerfeld im 🐞-Export (nur Log-Code). Optional: `CorrCounters.computeAbort` + Formatter-Feld.
- **P2-c** — NOTICE.txt nennt die Lizenz des Ausgangswerks und alle Bearbeitungen, enthält aber keinen ausdrücklichen Satz, dass die abgeleiteten .bin-Assets selbst unter CC-BY-SA 4.0 weitergegeben werden (SA-Lizenzvermerk). Ein Satz genügt.

## Abschluss
Alle sieben Findings der Vorrunde sind behoben (vier im Kode mit produktionstauglichen Tests, zwei per Oracle-Entscheidung im bindenden Plan, einer in der Doku); die neuen Test-Strings (Seed-Abdeckung, Klick-Kette, Stale-APPLY, Region-Golden) belegen genau die R1-Forderungen; die Fix-Runde introduces keine P0/P1-Regression.

**P0=0 P1=0 P2=3** — **build-ready: YES** (Release-Lauf kann; P2-a/b/c als 0.1.48-Kandidaten notieren, P2-a vor jeder Freigabe des Undo-Typs ohnehin im Blick behalten.)
