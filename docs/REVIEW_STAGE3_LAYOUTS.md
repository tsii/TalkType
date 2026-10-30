# Review Stufe 3 — Layouts (8 Asset-Fixes) + 3 Code-Fixes

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Gegenstand:** 8 Layout-Assets (de-de, en-us, it-it, nl-nl, pt-pt, pl-pl, ru-ru, el-gr) nach
`docs/LAYOUT_AUDIT.md` + `docs/LAYOUT_REQUIREMENTS.md` (Rev 3); dazu Manifest (Telemetry-Provider),
`OnnxSessions` (Doppel-Close), `TdtGreedy` (+ Test). **Nicht ausgeführt:** `./gradlew` (paralleler
Build) — manifest-Merge-Wahrheit ersatzweise aus `app/build/intermediates/…/AndroidManifest.xml`
gelesen (erlaubt).

**Methode:** lesendes Python-Audit über die 8 Assets (Schema/Zeilen/Paare, §2.1-Gruppen, §2.2
A/B/SZ, NFC, Duplikate, `label==value`, Alternates) + Dateilesen der 3 Code-Fixes.

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| A-1 | Note | `de-de/pl-pl/ru-ru` `symbols`+`symbolsShift` | **`_` doppelt über beide Symbolebenen** (und damit Gruppe-1-Zeichen auf `symbolsShift`). `symbols` hat `underscore`→`_` (Pflicht, Gruppe 1), `symbolsShift` hat `hyphen`→`_` (Shift von `-`). Verstößt gegen SCHEMA_REF #7 („`symbolsShift` trägt keine Gruppe-1-Zeichen") und §2.1.1 „genau ein Vorkommen". **Vorbestehend**, nicht durch diese Fixes eingeführt, nicht im Audit-Fixumfang. | `de-de` `symbols`: `{"id":"underscore","value":"_"}`; `symbolsShift`: `{"id":"hyphen","value":"_"}` (analog pl-pl, ru-ru; en-us dagegen sauber). | `hyphen`-Shift in `symbolsShift` auf ein anderes Zeichen (z. B. `–`/`¯`) legen oder die `_`-Doppelbelegung in §2.1.1/SCHEMA_REF explizit als „Hyphen-Shift-Ausnahme" dokumentieren. |
| A-2 | Note | `pt-pt` `layers.base[0..4]` | **`base`/`shift` haben 5 Zeilen** (12/12/8/9/13) statt der üblichen 4; `symbols`/`symbolsShift` bleiben 4-zeilig (11/11/11/13). Paarregel ist erfüllt (`shift↔base` 5==5, `symbolsShift↔symbols` 4==4, Breitensummen je Zeilenindex gleich), Parser `validateSemantics` prüft jetzt korrekt **paarweise** → kein Parse-Fehler. Aber die Tastaturhöhe springt beim Ebenenwechsel base↔symbols (5→4 Zeilen); Ergonomie, kein Regelverstoß. | `LayoutParser.kt:150-171` (paarweise `shift↔base`, `symbolsShift↔symbols`); `pt-pt.json` base 5 Zeilen, `#text base/shift = 38`. | Bewusst lassen oder `pt-pt` auf 4 Zeilen konsolidieren (nur wenn §2.2-A weiterhin als eigene Tasten erfüllbar). |
| A-3 | **P2** | `LayoutCompletenessTest.kt:51,52,53` | **Testmatrix ist gegen §2.2 (Rev 3) veraltet** → 3 der 8 Fixes sind **nicht** regressiongeschützt: `it-it` fehlt `ü` (=§2.2-B), `nl-nl` fehlt `ä`, `pt-pt` führt `à` in `requiredReachable` (B) statt in der A-Liste. Bei Rückbau (ü/ä wieder weglassen, à wieder nur Alternate) bleibt der Test **grün** — die Fixes werden also nicht „gefangen". | Z.51 `it-it … requiredReachable=listOf("á","í","ó","ú","î")` (ohne `ü`); Z.53 `nl-nl … =listOf("ë","ï","é","è","ê","ö","ü","á","à")` (ohne `ä`); Z.52 `pt-pt A=listOf("ç","ã","õ","á","é","í","ó","ú","â","ê","ô")` (ohne `à`), `requiredReachable=listOf("à","ü")`. | Matrix auf §2.2 Rev 3 synchronisieren: `it-it`+`ü`, `nl-nl`+`ä`, `pt-pt` `à` in die A-Liste (und nur `ü` in B). |
| A-4 | **P2** | — (fehlende Tests) | **Die 6 §2.2-Satzzeichen-Fixes, der NFC-Fix und Regel 5a–5e sind durch *keinen* Test geschützt.** `PunctuationCoverageTest` (§6.3), `NfcLabelTest` (§6.4) und `LayoutArtifactTest` (§6.8) existieren **nicht** (`app/src/test/java/de/voicekeyboard/layouts/` enthält nur `CaseMapperTest`, `LayoutCompletenessTest`, `LayoutParserTest`). `LayoutCompletenessTest.checkMandatorySymbols` prüft nur §2.1 (Gruppe 1/2+Ziffern), keine §2.2-SZ-Matrix, kein NFC, keine Regel 5a–5e. Ein Rückbau von `–` (de-de/ru-ru), `–/—` (en-us), `”` (pl-pl), `·` (el-gr) oder der U+0387/U+037E-Alternates bliebe unentdeckt. | `ls`/grep: keine `PunctuationCoverageTest`/`NfcLabelTest`/`LayoutArtifactTest`; `LayoutCompletenessTest.kt:181-203` nur `checkMandatorySymbols` (§2.1). Genau die Lücke, die laut `LAYOUT_AUDIT.md §f` die 6 SZ-Befunde durchgelassen hat. | `PunctuationCoverageTest` (mit §2.2-SZ-Matrix), `NfcLabelTest` (Labels **und** Alternates), `LayoutArtifactTest` (5a–5e + `Z`) gemäß §6.3/6.4/6.8 nachliefern. |
| B-1 | ✓ | `AndroidManifest.xml:34-37` + merged | **Telemetry-Provider jetzt korrekt als Kind von `<application>`** und tatsächlich entfernt: `grep -c TelemetryInitializer` und `grep -c "android.permission.INTERNET"` über `app/build/intermediates/merged_manifest/{debug,release}/process*MainManifest/AndroidManifest.xml` sowie `merged_manifests/*` → **alle 0**. SB1-07 jetzt verifiziert (nicht mehr Restrisiko). | Merged-Manifest (4 Dateien): `TelemetryInitializer` = 0, `INTERNET` = 0. | — |
| B-2 | ✓ | `OnnxSessions.kt:56-73` | **Doppel-Close restlos weg.** Das innere `try { validate… } catch { s.close(); throw }` ist entfernt. Jetzt: `createSession×3` + `validate×3` + `success=true; return s`; `finally { if (!success) opened.forEach { it.close() } }`. Erfolg → keine Schließung (ein Besitzer); `createSession(enc/dec)`-Fehler → partielle Sessions genau 1×; Validierungsfehler → alle 3 genau 1×. Kein Leak, kein Use-after-close. | Z.60-72 (kein inneres catch mehr, nur ein `finally`). | — |
| B-3 | ✓ | `TdtGreedy.kt:50` + `ParakeetAsrEngine.kt:130` + `TdtGreedyTest.kt` | **3-Arg-Overload entfernt.** Nur noch `decode(joint, frames, tokenBudget, isSpecial)`; die `frames<0`-Klemmung reicht jetzt `isSpecial` durch (Z.51 `decode(joint, 0, tokenBudget, isSpecial)`). **Alle 17 Aufrufer** (16 Tests + `ParakeetAsrEngine` Z.130 `{ vocab.isSpecial(it) }`) nutzen die 4-Arg-Form — kein stiller 0..4-Fehlpfad mehr. Test nutzt jetzt die **echte** Regel: `miniVocab = Vocabulary.parse("<|de|> 78 <|spltoken0|> 244 …")` + `IS_SPECIAL = { miniVocab.isSpecial(it) }` (statt Ad-hoc-Prädikat). | grep `TdtGreedy.decode` → nur 4-Arg; `TdtGreedyTest.kt:44-50` (miniVocab + IS_SPECIAL). | — |
| B-4 | Note | `AndroidManifest.xml:20-31,52-55` | Manifest enthält zusätzlich (außerhalb des 3-Fix-Umfangs): `RECORD_AUDIO`/`FOREGROUND_SERVICE(_MICROPHONE)`/`POST_NOTIFICATIONS` + `CaptureForegroundService` (`foregroundServiceType="microphone"`). Inhaltlich korrekt gemäß TECH_FACTS §4.1; gehört thematisch zu Stufe 4, nicht zu dieser Lieferung — hier nur zur Vollständigkeit notiert. | Z.20-31 Permissions, Z.52-55 Service. | — |

---

## Punktgenaue Antworten auf die Prüfaufträge

1. **Regel 5a–5f / §2.1 / §2.1.3 / §2.2 / Regeln 3,7,8:** Alle **Neu-Tasten/Alternates regelkonform**. `label==value` gilt für jede Texttaste (0 Abweichungen), kein Nicht-NFC-Label/-Value/-Alternate (el-gr U+0387/U+037E sind entfernt), keine Alternates-Verstöße (≤8, 1 Codepoint, keine Duplikate, nicht das eigene Zeichen). §2.1-Gruppe 1 + Ziffern je genau 1× auf `symbols`, Gruppe 2 vollständig vorhanden (MISS leer). §2.2 A/B/SZ komplett (die „fehlenden" `»«/”/“` sind nur `{…}`-Oder-Varianten, nicht gefordert). **Verdrängte Zeichen** (`µ`, `‹ ›`, `¿`, `¡`, `¥`) sind sämtlich §2.1.2-optional bzw. (`‹ ›`) vorher ein Regel-5d-Verstoß — **kein Pflichtzeichen geopfert**.
2. **Paarregel:** erfüllt — `shift↔base` und `symbolsShift↔symbols` je paarweise gleiche Zeilenzahl + gleiche Breitensumme je Index; `base`-Text-IDs == `shift`-Text-IDs (alle 8 Assets). pt-pt bestanden trotz 5-zeiliger Buchstabenebenen (Note A-2).
3. **NFC:** alle Labels/Values/Alternates der 8 Assets NFC (el-gr `·` = U+00B7, U+0387/U+037E raus). ✔
4. **Duplikate (Regel 8):** keine neuen Duplikate; einzig `Σ/Σ` in el-gr-`shift` (dokumentierte Ausnahme D1). Kein `–`-Doppel in de-de, kein `”`-Doppel in pl-pl. (Note A-1: vorbestehendes `_`-Kreuz-level.)
5. **Code-Fixes:** B-1 Provider richtig platziert + gemergtes Manifest **verifiziert** (0× Provider, 0× INTERNET). B-2 Doppel-Close weg, jede Session genau 1×. B-3 Overload-Wegfall vollständig (kein Aufrufer ohne `isSpecial`).
6. **Testqualität:** B-Test (TdtGreedyTest) jetzt echt (echte `Vocabulary.isSpecial` statt Ad-hoc-Prädikat). **A-Tests jedoch lückenhaft** (A-3/A-4): `LayoutCompletenessTest` fängt die 8 Asset-Fixes **nicht** — bei Rückbau bleibt er grün.

---

## Verdikt

- **Teil A (8 Assets): Merge freigeben** — die Assets selbst sind regelkonform (0 P0, 0 P1). Die P2 (Testlücken A-3/A-4) betreffen den Regression-Schutz, nicht die Korrektheit der gelieferten Assets.
- **Teil B (3 Code-Fixes): Merge freigeben** — B-1/B-2/B-3 vollständig und (B-1) sogar empirisch verifiziert; 0 P0, 0 P1.

**Gesamt: Merge freigeben.** Keine offenen P0/P1.

## Top-5-Aktionen

1. **A-3 (P2)** — `LayoutCompletenessTest`-Matrix auf §2.2 Rev 3 synchronisieren (`it-it`+`ü`, `nl-nl`+`ä`, `pt-pt` `à` in die A-Liste) — sonst bleiben 3 Fixes ungeschützt.
2. **A-4 (P2)** — `PunctuationCoverageTest` (mit §2.2-SZ-Matrix), `NfcLabelTest` (Labels+Alternates) und `LayoutArtifactTest` (Regel 5a–5e + `Z`) nach §6.3/6.4/6.8 nachliefern — deckt die 6 SZ-Fixes, den NFC-Fix und 5a–5e ab.
3. **A-1 (Note)** — `_`-Doppelbelegung (`underscore`+`hyphen`-Shift) in de-de/pl-pl/ru-ru klären (Zeichen tauschen oder als Hyphen-Shift-Ausnahme dokumentieren).
4. **A-2 (Note)** — pt-pt 5-zeilige Buchstabenebenen bewusst bestätigen oder konsolidieren (Höhensprung beim Ebenenwechsel).
5. Beim nächsten Lauf die 2 instrumented Golden-Tests gegen `ASR_CONTRACT §4.1`-Token-IDs (nicht nur Text) abgleichen lassen — für den Decode-Pfad gleichwertig zu den Unit-Tests.