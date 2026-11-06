# REVIEW v0.1.47 (T23 Tipp-Vorschlags-Zeile, vc48) — cortecs/deepseek-v4-pro-0813
Persistenz durch Orchestrator (Reviewer ohne Schreibzugriff). Stand: 70 Dateien unkommiert; Release-Suite vollständig grün (alle XML `failures="0" errors="0"`, 842 Tests), Debug-Lauf im Hintergrund.

## Ergebnis
**Verdict: P0 = 1, P1 = 2 — BLOCK.** P2 = 4. Bau-Empfehlung: **NO** ( erst VK-1..VK-3 fixen + Re-Review).

## Was gut ist (Belege)
- **Tracker-Delta (§4)**: `TypedRegionTracker.kt` — Graphem-Cluster-Rücktasten via `Graphemes.lastGraphemeBoundary` (Surrogatpaare, ZWJ-Familie, Variante-Waehler FE00–FE0F sind EINE Klasse — `Graphemes.kt` `isExtend`), CAP 500 mit Anker-Vorschub, `verify()` exakter Suffix-Beweis, null-Tail = Mismatch, keine Logs, Reset-Gründe als Codes. Tests: `TypedRegionTrackerTest` (14 @Test, inkl. ZWJ-Familie und CAP 500).
- **RegionReplacer (§5)**: Prüfreihenfolge identisch zur Doku; Abbruchpfade ohne Editor-Mutation (`RegionReplacerTest.suffixMismatchAbortsBeforeAnyEditorCall`), UTF-16-Längen (`surrogateLengthsAreUtf16Units`), Undo genau eine Ebene.
- **Regel 9**: In `correct/` und der Wiring-Klasse keine Log-Aufrufe (grep `Log` exit 1); `CorrCounters` nur Zahlen; `DebugInfoFormatter.kt:292-296,408-418` +9 Zählerfelder, `DebugInfoFormatterTest.kt:98-101` Feld-Matrix, `CorrectionFieldsTest` "corr lines carry only codes and numbers"; `CorrectionWiringTest.logs never contain the typed or corrected text`.
- **Permission-Matrix**: `ImeRootView.kt:64-78` + Matrix-Test `CorrectionRowTest.kt:69-118` (NULL/NUMBER/PHONE/DATETIME, alle 3 Passwort-Varianten, URI, E-Mail, NO_SUGGESTIONS → GONE).
- **Diktat-Nichtunterbrechung**: `replace(…, dictationIdle)` + `enabled = has && dictationIdle()`; Test `wand APPLY blocked while dictation runs`.
- **Rücksetz-Feeds komplett**: FieldStart (Service:789), EditorChange (656), SelectionMove/NonEmpty (851-856), Word-Backspace (1971), DictationCommit (1290); Generation-Guard gegen Nachzügler.
- **LanguageAvailability (§16)**: eine Quelle der Wahrheit; Setup-Zeilen ausgegraut ohne Persistenzänderung (`SetupActivity.kt:184-206`), Zyklus filtert, gespeicherte Auswahl unangetastet; Globus-Langdruck nur für `language` (`KeyboardView.kt:1023-1029`); AllLanguagesLabel-Tabelle + 1:1-Test.
- **Heights-Budget**: statusRow einmalige Subtraktion, Tests `CorrectionRowTest.visible row costs exactly 40 dp once` (eng offer) + `ImeRootViewLayoutTest` (debug 48 dp unverändert).
- **Assets**: sha256 beider .bin byte-genau wie in `tools/wordlists/SOURCES.md` verifiziert (`sha256sum`); NOTICE deckt beide Listen + Modifikationen + Rebuild-Kommandos ab; en 704 KB / de 1,08 MB im Budget (§15.2).
- **Idempotenz**: `TypingRulesTest` Idempotenz + 15-Layout-Smoke; en-'i' doppelt gated (`TypingRules.startsWith("en")` × `LanguageModePolicy.appliesEnglishIRule`).

## Findings

- **VK-1 · P0 — ALL_ACTIVE crasht den IME-Prozess bei jedem Wörterbuch-fremden Wort (Seed/Lade-Sprache verwechselt).**
  `VoiceKeyboardService.kt:2096-2102`: geseedet wird NUR `NeighbourModel.seed(rulesLang, …)`; `regionCorrection(…, correctorLang = current, …)` ruft `WordCorrector.correct(word, layoutId=current)` → `WordCorrector.kt:128 NeighbourModel.load(layoutId=current)`. In **ALL_ACTIVE mit current=de-de und en-us aktiv** (der Out-of-box-Fall §14.6(3)) erzwingt die en-'i'-Matrix `rulesLang="en-us"` (`VoiceKeyboardService.kt:2091-2095`, `LanguageModePolicy.kt:96-101`) → Cache enthält nur "en-us"; `load("de-de")` fällt auf `readLayoutJson` zurück (JVM-only walk-up, `NeighbourModel.kt:57-62, 104-114`) → `IllegalStateException` auf dem corrWorker-Thread; `runCompute` fängt nichts (`VoiceKeyboardService.kt:2884-2888`), kein UncaughtExceptionHandler im App-Code (grep = 0) → Prozess-Tod des IME. JVM-Tests Maskieren den Fehler (walk-up findet die JSON-Dateien); daher 842/0 grün.
  **Verifizier-Befehl:** `grep -n "NeighbourModel" app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt app/src/main/java/de/voicekeyboard/correct/WordCorrector.kt` — einziger Seed nutzt `rulesLang` (:2098), einziger Load nutzt `layoutId`/current (WordCorrector:128).
  **Min-Fix:** in `corrComputeRegion` zusätzlich das Layout der aktuellen Sprache seeden (`layouts/$current.json` → `NeighbourModel.seed(current, …)`), plus `runCatching` um `compute(region)` in `runCompute` als Netz.

- **VK-2 · P1 — Rückgängig (UNDO) ist in der UI unerreichbar: Stab zeigt Undo-Glyphe, ist aber deaktiviert.**
  APPLY-Pfad: `VoiceKeyboardService.kt:2839-2843` → `clearRow()` (:2863-2867) → `onRowUpdate(null,null,false,false)` → `ImeRootView.setCorrection` setzt `wandButton.isEnabled = hasSuggestion && enabled` = **false** (`ImeRootView.kt:496`); das anschließende `setWandUndoMode(true)` (`ImeRootView.kt:503-508`) dreht nur Rotation/ContentDescription, re-aktiviert den Button nicht. Zwischen APPLY und nächstem Tastendruck (dem einzigen Undo-Fenster §5) ist der Klick also tot — `corr_undone` bleibt in Produktion 0, CHANGELOG (Zeile 19) verspricht Undo. Tests laufen vorbei: `CorrectionWiringTest.kt:242,250` ruft `wiring.onWandPress` direkt; `CorrectionRowTest.kt:220-227` klickt, ohne zuvor `setCorrection(…,false,…)` aufzurufen.
  **Verifizier-Befehl:** `grep -n "isEnabled" app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt` — einzige Setzstelle :496 hängt an `hasSuggestion && enabled`; Undo-Modus ohne Suggestion ⇒ false.
  **Min-Fix:** in `setWandUndoMode`: `wandButton.isEnabled = hasUndoLevel || (lastHasSuggestion && lastEnabled)` (letztes SetCorrection-Paar merken) — plus Robolectric-Sequenz setCorrection(true)→(false)+undo→performClick.

- **VK-3 · P1 — Stale-Suggestion-Race: APPLY innerhalb des 300-ms-Debounce ersetzt den NEUEREN Puffer durch die KORREKTUR DES ÄLTEREN und schluckt die zuletzt getippten Zeichen.**
  `suggestion` wird nur in `publish()` gesetzt (`VoiceKeyboardService.kt:2895-2901`) und bei neuen Feeds NICHT geleert: `feedText`→`scheduleCompute` (:2871-2883) löscht die Zeile nur bei leerem Puffer; die Zeile bleibt mit alter Korrektur enabled stehen. `onWandPress(APPLY)` (:2832-2837) kombiniert `tracker.buffer` (= NEUER Region "morgem w") mit `suggestion` (= Korrektur der ALTEN Region, "morgen ") — der Suffix-Beweis in `replace()` vergleicht nur gegen den neuen Puffer und passt, Ergebnis: `deleteSurroundingText(neu)` + `commitText(altKorrigiert)` → das frisch getippte "w" verschwindet kommentarlos; Undo (VK-2) kann es nicht retten.
  **Verifizier-Befehl:** `grep -n "suggestion" app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt` — Zuweisung nur in publish; weder `feedText`/`feedBackspace`/`scheduleCompute` noch APPLY vergleicht Puffer vs. Region der letzten Veröffentlichung.
  **Min-Fix:** `private var suggestionForRegion: String?` in publish mitschreiben; APPLY nur wenn `tracker.buffer == suggestionForRegion`, sonst ignorieren/neu planen; Test: feed → (Debounce offen) → APPLY → Batch-Menge muss 0 sein.

- **VK-4 · P2 — Tippfehler direkt vor Interpunktion bekommen NIE eine lexikalische Korrektur (Test-Tokenizer ≠ Produktions-Tokenizer).**
  `regionCorrection` tokenisiert nur an Whitespace (`VoiceKeyboardService.kt:2666-2678`), übergibt also "kalr." an `WordCorrector.correct`, das alles außer Buchstaben/`-` ablehnt (`WordCorrector.kt:85`). Der Golden-Harness trennt die Buchstaben selbst ab (`CorrectorGoldenTest.kt:201-203 Regex("[\\p{L}]+")`) und hält das Gate grün, obwohl "Der Kaffee ist zu kalr." real unbearbeitet bleibt. Minimallöfung: in `regionCorrection` führende/abschließende Interpunktion abtrennen, Kern korrigieren, Interpunktion anhängen; Golden-Zeile mit satzends Typo ergänzen.
  **Verifizier-Befehl:** `grep -n "isLetter" app/src/main/java/de/voicekeyboard/correct/WordCorrector.kt` (:85) vs. Whitespace-Split in `VoiceKeyboardService.kt` :2666-2678.

- **VK-5 · P2 — PLAN §3 "last in-progress word held back" ist nirgends implementiert (und nirgends als Abweichung dokumentiert).**
  Plan `docs/PLAN_V0147.md:38`; die Kette compute→`regionCorrection`→`publish`→APPLY korrigiert den unvollendeten letzten Token durchgängig (Zeilen wie oben VK-4); es gibt weder Hold-back in der Anzeige noch eine Berechnung zum Wand-Press-Zeitpunkt. Folge: Zeile kann mitten im Wort textvorschlagen ("mor"→"mir "), APPLY setzt ihn — harmlos erst nach VK-2-Fix (Undo). Entweder Hold-back implementieren oder als begründete Verfeinerung in die Konzept-Treue-Tabelle (Regel 2) schreiben.
  **Verifizier-Befehl:** `grep -rni "in-progress\|holdback\|hold back" app/src/main/java/de/voicekeyboard/correct app/src/main/java/de/voicekeyboard/ime/VoiceKeyboardService.kt` — ohne Treffer (Fundstellen gehören der Diktat-Commitschicht).

- **VK-6 · P2 — Adressfelder: Prüfungsauftrag nennt "Adresse", `isCorrectionFieldEligible` lässt `TYPE_TEXT_VARIATION_POSTAL_ADDRESS` (und PERSON_NAME) zu.**
  `ImeRootView.kt:64-78` (nur 3 Passwort- + URI- + 2 E-Mail-Varianten), Plan §6 nennt ebenfalls nur password/URI/email — Entweder die Anforderung (Nutzervertrag 2026-11-05) präzisieren oder Variante `TYPE_TEXT_VARIATION_POSTAL_ADDRESS` in die Ausschlussliste + Matrix-Test (`CorrectionRowTest.kt:80-98`) aufnehmen.
  **Verifizier-Befehl:** `grep -n "VARIATION" app/src/main/java/de/voicekeyboard/ime/ImeRootView.kt` — POSTAL_ADDRESS fehlt.

- **VK-7 · P2 — Doku-Drift bei den Word-List-Assets (Artefakte selbst stimmen).**
  `tools/wordlists/SOURCES.md:33` dokumentiert de-de.bin "1,082,649 B plain", real sind es **1,082,777 B** (`stat`), sha256 c19613ff… passt dagegen exakt — Zahl ist veraltet (T23c-Stoplist-Neubau). `NOTICE.txt` Modifikationspunkt "stoplist subtraction (initially empty…)" ist unrichtig: `stop_de.txt`/`stop_en.txt` sind kuratiert und nicht leer (1267/660 B, inkl. `strasse`, `bud`). Nur Textkorrekturen in SOURCES.md/NOTICE.txt.
  **Verifizier-Befehl:** `stat -c %s app/src/main/assets/wordlists/de-de.bin` (1082777) vs. `grep "1,082,649" tools/wordlists/SOURCES.md`.

## Abgehakt (ohne Befund)
Loeschcluster-Approximation (eine UTF-16-Einheit bei Leerpuffer) ist dokumentiert und selbstheilend (Suffix-Beweis + Selection-Abgleich); Caps/Spacing-Regeln idempotent inkl. `DIe`, `HALLO HALLO`, `14:30`, Domainschutzwürdiges; buffer cap 500 mit Anker-Vorschub getestet; Globus-Kurz-Tipp-Zyklus unverändert; Space-Label/Präfix-Tag "DE ·" wie §14.6; Zähler-Matrix gegen Muster; debug corr_* nur Zahlen/Codes; Höhe 40/48 dp genau einmal (VK-INSETS-Tests lesen bestanden); Qwen-T22 außerhalb des Umfangs.

## Naechster Schritt
VK-1 (Seed der aktuellen Layout-Sprache + runCatching um runCompute) und VK-2/VK-3 (Wand-Enabled-Folge + Regions-Bindung der Suggestion) fixen inkl. je einem neuen Test, VK-4 aufwandsoffen nachziehen; VK-5/6 mit Nutzer-/Planentscheid schließen; VK-7 Dokuabgleich. Danach Re-Review (Gate P0=0 & P1=0). Debug-Vollauf nach Fix erneut.

**P0=1 P1=2 P2=4 — build-ready: NO**
