# Review Stufe 2 B.1 — FIX-DURCHLAUF (zu REVIEW_STAGE2B1.md)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Gegenstand:** Fixes zu SB1-01/03/06/07 aus `docs/REVIEW_STAGE2B1.md`.
**Nicht ausgeführt:** `./gradlew` (paralleler Emulator-Test) → Manifest-Merge-Report
nicht einsehbar; Provider-Entfernung daher als **Restrisiko** benannt, nicht behauptet.

---

## Ergebnis je behauptetem Fix

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| SB1-01 ✅ (Filter) | — | `TdtGreedy.kt:59,99` + `ParakeetAsrEngine.kt:130` | **Geschlossen.** Neuer 4-Argumente-Overload nimmt ein `isSpecial: (Int)->Boolean`-Prädikat; Haupt-Loop nutzt `if (!isSpecial(token))` statt des alten ID-Bereichs. Produktionspfad verdrahtet exakt §2.4: `TdtGreedy.decode(joint, encFrames, budget) { vocab.isSpecial(it) }`. `Vocabulary.isSpecial` (Vocabulary.kt:52-61) implementiert `<...>`-Muster (0..233, 244..273) **plus** ID 8192 und lässt Ziffern 234..243 zu — deckungsgleich mit §2.4/§7-4. | `ParakeetAsrEngine.kt:130` `decode(…, budget) { vocab.isSpecial(it) }`; `TdtGreedy.kt:99` `if (!isSpecial(token))`. | — |
| SB1-01b (neu) | **P2** | `TdtGreedy.kt:50-51` | **Stiller Fehlpfad bleibt.** Der 3-Argumente-Overload `decode(joint, frames, tokenBudget)` defaultet weiter auf die **alte, unvollständige** Regel `{ it in AsrContract.SPECIAL_TOKEN_IDS }` (nur 0..4). Ein künftiger Aufrufer ohne `isSpecial` bekommt den §2.4-Verstoß erneut — ohne Compiler-/Testwarnung. Der Bestandstest `specialTokensNotEmitted` (TdtGreedyTest, „(vii)") übt sogar genau diesen Legacy-Pfad und legitimiert ihn damit. **Bewertung:** kein aktueller Bug (einziger Produktions-Aufrufer nutzt den 4-Arg-Overload), aber echter Footgun. | `TdtGreedy.kt:50-51` `fun decode(joint, frames, tokenBudget) = decode(joint, frames, tokenBudget) { it in AsrContract.SPECIAL_TOKEN_IDS }`. | 3-Arg-Overload entfernen (Aufrufer zwingen, `isSpecial` zu liefern) oder Default auf `Vocabulary.isSpecial` legen; Test „(vii)" auf den 4-Arg-Pfad umstellen. |
| SB1-03 ⚠️ (Session-Aufbau) | **P2** | `OnnxSessions.kt:70,76` | **Leak behoben, aber Doppel-Close auf dem Validierungs-Fehlpfad.** Bei `createSession(dec)`-Fehler schließt das `finally` korrekt mel+enc (Z.62 schon gefüllt). Im **Erfolgsfall** kein Doppel-Close (`success=true` überspringt `finally`-Close, Z.73/76) — korrekt. **Aber:** wirft die Validierung (`validateMel/Encoder/Decoder`), läuft `s.close()` (Z.70) **und danach** `opened.forEach { it.close() }` (Z.76, weil `success` noch `false`) → mel/enc/dec werden zweimal geschlossen. Kein Use-after-close (kein weiterer Zugriff), kein Leak — aber redundantes/unsicheres Schließen. | Z.64-70 inneres try/catch mit `s.close()`; Z.75-76 `finally { if (!success) opened.forEach { it.close() } }` — beide treffen dieselben drei `OrtSession`-Objekte (s wrappt `opened[0..2]`). | Inneres `s.close()` entfernen und ausschließlich das `finally` schließen lassen (oder `closed`-Flag), damit jeder Pfad genau einmal schließt. |
| SB1-06 ✅ (NaN-Guard) | — | `ParakeetAsrEngine.kt:118-124` | **Geschlossen.** Guard steht jetzt zwingend **nach** dem Encoder-Run und **vor** `TdtGreedy.decode` (Z.130). Kein Decode auf NaN/Inf-Input mehr. | Z.118-124 `for (v in encOut) { if (!v.isFinite()) throw … }` vor Z.130 `TdtGreedy.decode`. | — |
| SB1-07 ⚠️ (Telemetry-Provider) | Note | `AndroidManifest.xml:21-23` | **Mechanismus korrekt, Entfernung unverifiziert.** `<provider android:name="ai.onnxruntime.TelemetryInitializer" tools:node="remove" />` mit passendem Provider-Namen (gemäß TECH_FACTS §3). Ob der Merger ihn tatsächlich entfernt, ist hier **nicht prüfbar** (kein `./gradlew` → kein `outputs/logs/manifest-merger-*.txt`). Restrisiko: falls das ORT-AAR den Provider unter anderem Namen/anderen Attributen deklarierte, greift `remove` still nicht. **Als Restrisiko benannt, nicht als verifiziert behauptet.** | Z.21-23 `<provider … tools:node="remove" />`. | Nach dem Build den Manifest-Merge-Report prüfen (Provider darf im gemergten Manifest nicht mehr auftauchen). |
| SB1-01c (neu) | Note | `TdtGreedyTest.kt` (2 neue Tests) | **Tests beweisen Prädikat-Weitergabe, nicht End-to-End.** Die zwei neuen Tests (`specialTokenLanguageTokenNotEmitted` 78, `specialTokenSplTokenNotEmitted` 244) übergeben eine **hartkodierte** Lambda `{ id in SPECIAL_TOKEN_IDS || id==78 || id==244 }`, nicht `Vocabulary.isSpecial`/eine echte Vokabel. Sie belegen damit, dass der 4-Arg-Overload das Prädikat für 78/244 respektiert — **nicht**, dass `vocab.isSpecial` korrekt verdrahtet ist oder das echte Vokabular 78/244 filtert. Letzteres deckt `VocabularyTest` ab (isSpecial(78)=true, isSpecial(244)=true, Ziffern false). Lücke: kein Test verdrahtet `vocab.isSpecial` in `decode` (die kritische Zeile `ParakeetAsrEngine.kt:130` ist nur per Sichtprüfung belegt). | Neue Tests nutzen Inline-Lambda statt echter Vokabel; Verdrahtung `ParakeetAsrEngine.kt:130` ohne Unit-Test. | Einen Test ergänzen, der `TdtGreedy.decode(joint, frames, budget, ::vocab.isSpecialLike)` mit einem `Vocabulary.parse`-Konstrukt aufruft (78/244 gefiltert, 240 behalten). |
| SB1-02/04/05 | (deferred) | — | `ModelError`-Typ, Dispatcher/`withContext`, Per-Frame-Allokationen bewusst in die nächste Stufe verschoben. **Weiterhin offen** (P2 aus Vorreview), hier nicht erneut bewertet, aber nicht erledigt. | — | In der Folgestufe aufnehmen. |

---

## Punktgenaue Antworten auf die Prüfaufträge

1. **SB1-01 wirklich geschlossen?** **Ja.** Produktionspfad verdrahtet exakt §2.4 (`vocab.isSpecial` = `<...>`-Muster + 8192, Ziffern 234..243 erlaubt). Das Restrisiko ist ausschließlich der **verbleibende 3-Arg-Overload** mit der Alt-Regel (0..4) — P2, da aktuell kein Aufrufer ihn nutzt, aber als Footgun zu beseitigen.
2. **SB1-03:** enc/dec-Fehler → mel+enc werden im `finally` geschlossen (✔). Erfolgsfall → kein Doppel-Close (✔, `success=true`). **Aber** Validierungsfehler → Doppel-Close über `s.close()` + `finally` (P2). Kein Leak, kein Use-after-close.
3. **SB1-06:** Guard steht zwingend vor dem Decode (✔).
4. **SB1-07:** Mechanismus korrekt (Provider-Name passt zu §3), aber Entfernung **im gemergten Manifest nicht verifizierbar** ohne Build → Restrisiko.
5. **Neue Regressionen:** die Doppel-Close-Lücke (SB1-03) ist eine; die Tests sind nur Prädikat-Weitergabe-Beweise (SB1-01c). Sonst keine funktionalen Regressionen — der Decode-Loop selbst ist unverändert, die Lambda-Verwendung ist äquivalent.
6. **Merge-Fähigkeit unten (Verdikt).**

---

## Verdikt

**Merge freigeben.**

Das einzige P1 aus dem Vorreview (SB1-01, verbindlicher §2.4-Filter) ist **echt geschlossen** — der Produktionspfad verdrahtet `Vocabulary.isSpecial` exakt nach §2.4/§7-4, und der Guard-Fix (SB1-06) sowie die Leak-Beseitigung (SB1-03 Kern) sind korrekt. **Keine offenen P0/P1.** Die verbleibenden P2 (3-Arg-Footgun, Doppel-Close im Validierungsfehlpfad) und die verschobenen P2 (SB1-02/04/05) sind nicht merge-blockierend, gehören aber in die nächste Stufe bzw. ins Cleanup.

## Restrisiken

1. **P2 — Doppel-Close** bei Validierungsfehler (`OnnxSessions.kt:70+76`): unsicher, falls `OrtSession.close()` nicht idempotent ist (folgenlos, da der Pfad ohnehin mit Fehler endet).
2. **P2 — Footgun 3-Arg-Overload** (`TdtGreedy.kt:50-51`): alte unvollständige Regel bleibt als stiller Default erreichbar und wird von Test „(vii)" geübt.
3. **Note — SB1-07 unverifiziert:** Provider-Entfernung erst nach Build über den Manifest-Merge-Report belegen.
4. **Note — Testlücke:** kein Test verdrahtet `vocab.isSpecial` in `decode` (Zeile `ParakeetAsrEngine.kt:130` nur sichtgeprüft).
5. **Offen (deferred):** SB1-02 (`ModelError`), SB1-04 (Dispatcher), SB1-05 (Frame-Allokationen) — gemäß Plan in der nächsten Stufe.