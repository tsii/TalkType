# Review Stufe 9 — i18n (M9a Space-Label, M9b Asset-Normalisierung, E11)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-28
**Gegenstand:** `KeyboardView`/`LayoutRepository` (SpaceLabelRule, availableIds-Scan), `tools/normalize_space_labels.py` + 15 Assets, `LayoutParserTest` (E11-Tabelle). **Kein Build/Emulator** (Orchestrator-Messwerte als Beleg: 241 Tests/0 Fehler, Skript-Idempotenz, 60 space-Tasten = 0 Abweichungen, alle 15 Wörter sichtbar).

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| I9-01 | **P2** | `KeyboardView.kt:186-199` (`accessibilityDescription`) | **Screenreader-Namen bleiben deutsch (E11‑c3, bewusst zurückgestellt in R8).** Die Accessibility-Beschreibung hartkodiert weiterhin `SPACE→"Leertaste"`, `BACKSPACE→"Löschen"`, `SHIFT→"Umschalt"` usw. — ein ru-Screenreader würde „Leertaste" statt „Пробел" ansagen. **Dokumentiert und für v0.1.1 akzeptiert** (E11 C baut genau diesen Trade-off); es blockiert nur den Claim „in 15 Sprachen barrierefrei geprüft", nicht die Dist. | `accessibilityDescription` `KeyActions.SPACE -> "Leertaste"` (unverändert deutsch). | R8: 5 Namen übersetzen, nachdem Ansagesprache + echte TalkBack-Prüfung geklärt sind. Nicht in v0.1.1. |
| I9-02 | Note | `LayoutRepository.kt` `availableIds()` | **Scan-Fallback: jeder Pfad ⇒ `emptySet()`, de-de bleibt.** Bei einer `assets.list`-Exception oder Teil-Liste verschwinden alle Nicht-de-de-Sprachen stillschweigend; `+"de-de"` garantiert den Anker. `DE-DE.JSON` (Großschreibung) fällt durch `endsWith(".json")` (case-sensitiv) raus; Unterverzeichnisse werden per `list()` nicht rekursiv erfasst → stille Unsichtbarkeit. Alle Assets sind per Konvention lowercase + top-level, also in diesem Projekt unbelegt. | `availableIds()`: `catch (e: Exception) { emptySet() }`; `filter { it.endsWith(".json") }`. | Optional: Endung case-insensitiv prüfen + ein Test „de-de immer in availableIds, auch bei leerem Scan". |
| I9-03 | Note | `LayoutRepository.kt:21` (import `ime.LanguageCycle`) | **Cross-Paket-Abhängigkeit `layouts → ime`.** `availableIds` bezieht die kanonische Reihenfolge aus `de.voicekeyboard.ime.LanguageCycle` — die frühere lokale Duplikatliste ist damit **aufgelöst** (eine Quelle, kein Auseinanderlaufen mehr). Der Preis ist eine Layering-Inversion (das frameworkfreie `layouts`-Paket importiert das `ime`-Paket). Kein Funktionsdefekt, aber architektonisch unschön. | `import …ime.LanguageCycle`; `LanguageCycle.CANONICAL`. | Optional: `CANONICAL` in ein neutrales Paket (z. B. `layouts`/`util`) verschieben; kein eigener Equality-Test nötig, da nur noch eine Liste existiert. |
| I9-04 | Note | `tools/normalize_space_labels.py:56-60` | **Roundtrip sicher, aber Ganzdatei-Rewrite.** `json.dumps(indent=2, ensure_ascii=False)+"\n"`: float `1.5` bleibt `1.5` (kein `1.500000`), int `1` bleibt `1`, Key-Reihenfolge bleibt erhalten (dict-Insertion-Order), `ensure_ascii=False` erhält Пробел/Διάστημα/Espaço literal. Es werden **nur** `label` bei `value=="space"` geändert (Filter korrekt). Restrisiko: ein künftiges Asset mit anderer Einrückung/Key-Order würde beim ersten Label-Touch **vollständig** reformatiert → ein großer Diff; die gemessene Idempotenz bestätigt, dass die 15 Assets aktuell bereits im Skriptformat vorliegen. | `key["label"] = expected` nur im `value=="space"`-Zweig; `json.dumps(data, indent=2, ensure_ascii=False)`. | Kein Fix für v0.1.1; ggf. Diff-Minimierung (nur betroffene Zeilen) als späteres Komfortziel. |
| I9-05 | ✓ | `LayoutRepository.kt:9-16` (`SpaceLabelRule`) | **Regelkonsistenz E11(B) erfüllt.** `resolve(src.label, fallback)`: `label=""`, `label=" "`, `label="\u00A0"` (alle rein whitespace) ⇒ `useAssetLabel=false` ⇒ Fallback; `label=null` nicht möglich (`LayoutKey.label` ist non-null, `src.label` ist `String`). Ein Asset-Wort mit Inhalt (Espacio/Mellemrum/Пробел) wird unverändert übernommen; der Einfüge-`value` bleibt `space` (Ausgabe weiterhin genau ein Leerzeichen). Kein blankes Label möglich. | `useAssetLabel = label.any { !it.isWhitespace() }`; Test Z.207-218 (Espacio bleibt, blank→Space, value bleibt "space"). | — |
| I9-06 | ✓ | `LayoutParserTest.kt:221-258` | **Testqualität echt.** M9b-Test hinterlegt die **E11-Tabelle im Testcode** (`"de-de" to "Leer" … "ru-ru" to "Пробел"`), liest sie **nicht** aus den Assets → echter Abschreibschutz. Er prüft „genau 1 Space-Taste je Ebene" für alle 15 IDs × 4 Ebenen und hat eine **echte Gegenprobe** (`M9b gegenprobe … ru-ru label==Пробел` scheitert bei Abweichung). | Z.224 Tabelle; Z.231-239 „alle 15 … alle Ebenen"; Z.245-257 Gegenprobe. | — |

---

## Punktgenaue Antworten (PRÜFFRAGEN)

- **Regelkonsistenz:** `resolve` behandelt `""`/`" "`/Whitespace korrekt → Fallback „Space" (defensiver Notbehelf, nie regulär in bereinigten Assets benötigt); kein Widerspruch zu E11(B) — blankes Label ist unmöglich. (I9-05)
- **Scan-Robustheit:** `de-de`-Anker garantiert; Teil-Scan/Exception degradiert auf „nur de-de" (sicher, wenn auch sprachverlustig). `DE-DE.JSON`/Unterverzeichnis werden still ignoriert, aber per Konvention ausgeschlossen. (I9-02)
- **Dopplung:** aufgelöst — `availableIds` nutzt `LanguageCycle.CANONICAL` als einzige Quelle; kein Equality-Test nötig. Rest = Layering-Inversion. (I9-03)
- **Skript-Nebenwirkungen:** nur `label` bei `value=="space"`; `1.5`→`1.5`, Key-Order/`ensure_ascii=False`/trailing `\n` stabil; Idempotenz gemessen. Ganzdatei-Rewrite ist der einzige Rest. (I9-04)
- **Testqualität:** Tabelle hartcodiert + echte Gegenprobe + alle 15×4 Ebenen. (I9-06)
- **Accessibility c3:** für v0.1.x **vertretbar**, kein P1 — dokumentierte, ehrliche Rückstellung (R8), sichtbare Labels sind korrekt; es schlägt nur die „barrierefrei in 15 Sprachen"-Behauptung fehl (I9-01, P2).

---

## Verdikt

**freigabefähig für v0.1.1** — **0 P0, 0 P1, 1 P2** (I9-01 Accessibility-c3, dokumentiert und explizit nicht blocking). M9a/M9b sind regelkonform und gut getestet; die E11-Werte stimmen 15/15.

**Nicht geprüft (kein Gerät):** tatsächliche Screenreader-Ansage (TalkBack) in den 15 Sprachen, Glyphen-/Breitenmessung der längsten Labels (Διάστημα/Пробел/Mellemrum) auf schmaler Taste inkl. `␣`-Fallback, Verhalten bei `assets.list`-Fehlern auf realer Asset-Verpackung.

## Top-3

1. **I9-01 (P2)** — R8 beauftragen (5 Accessibility-Namen übersetzen + TalkBack-Prüfung), erst dann „barrierefrei"-Claim.
2. **I9-03 (Note)** — `CANONICAL` in ein frameworkfreies Paket ziehen (Layering `layouts→ime` auflösen).
3. **I9-02 (Note)** — Endung-Insensitivität + „de-de-immer-enthalten"-Test für `availableIds`.