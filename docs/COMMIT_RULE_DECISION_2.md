# Entscheidung E8: Divergenzbehandlung, Commit-Zurückhaltung, sichtbare Reihenfolge

**Stand:** 2026-10-27 · **Rolle:** Regelhoheit (keine Code-/Test-Änderung)
**Revision 2 (2026-10-27):** B1 (Einheiten, §4a), B3 (kurze Sessions/Finaldecode,
§4b) und B4 (`ownStart`-Verschiebung, §2a) sind nach dem **grünen Gerätebeweis**
(`connectedDebugAndroidTest` 6/6, `s1_realEngine_realWav` PASSED, Unit 204/0)
wörtlich nachgezogen — inkl. Tests T7–T9. Die Regeln E8.1–E8.3 selbst sind unverändert.
**Ersetzt teilweise:** `docs/COMMIT_RULE_DECISION.md` (dort §2/§4 bleiben gültig:
Wortgrenze = `rfind(' ')` im gemeinsamen Präfix, kein Commit ohne Trenner; **neu**
sind Rollback, Commit-Hysterese und die Trennung „Commit ≠ Write").
**Grundlage:** `docs/KONZEPT.md` §2.6/§2.7 (Live-Korrektur, committed „unantastbar"),
`docs/ASR_CONTRACT.md` §4.1 (Golden-Texte/Token-IDs), `docs/DESIGN.md` §5.4/§6,
`commit/{LocalAgreement,CommitPolicy}.kt`, `editor/{DeltaTyper,EditorSink,OwnershipTracker}.kt`,
`dictation/DictationController.kt`.

---

## 1. Gemessener Schaden und Ursachenkette (belegt)

Messung (<device>, `connectedDebugAndroidTest`, `s1_realEngine_realWav`):

```
Soll: So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert.
Ist : So, jetzt bin ich gespannt, ob die Viktifunktion auf dem T.
```

Decoder ist nachweislich korrekt (identische Token-IDs, 27 Tokens, `incomplete=false`,
Golden-Tests grün). Die Kette im Commit-/Revisionsteil:

1. Eine frühe Hypothese enthält `… ob die Vikti` (Fehlerkennung).
2. Zwei Hypothesen stimmen darin überein → `LocalAgreement` gibt den Präfix frei,
   `CommitPolicy` meldet die Länge, `DeltaTyper.commitUpTo()` friert `… Vikti` ein.
3. Alle späteren (korrekten) Hypothesen divergieren an Position „V|D".
4. `DeltaTyper.revise()` prüft **vor** der Besitzprüfung:
   `if (!target.regionMatches(0, currentText, 0, committedLength)) return null`
   (`DeltaTyper.kt:63-71`) → **jede** weitere Hypothese wird verworfen.
5. `writeHypothesis()` (`DictationController.kt:189-199`) schreibt nur, wenn `revise()`
   ein Delta liefert → **es wird nie wieder Text geschrieben**. Sichtbar bleibt der
   Stand vom Zeitpunkt des Fehlcommits („… auf dem T." ist der letzte Tail, der vor
   dem Commit geschrieben wurde).

Drei strukturelle Defekte, nicht ein Einzelfall:

| Defekt | Stelle | Wirkung |
|---|---|---|
| **D1** Divergenz ⇒ Totalverweigerung | `DeltaTyper.revise()` Z.63-71 | Ein einziges früh eingefrorenes Wort blockiert **den Rest des Diktats** |
| **D2** `committedLength` ist monoton | `DeltaTyper.commitUpTo()` Z.45 (`coerceIn(committedLength, …)`) | Ein Fehlcommit ist per Konstruktion **nicht korrigierbar** |
| **D3** Commit gated das Schreiben | `DictationController.writeHypothesis()` | „Commit" und „Text schreiben" sind vermischt; ein Commit-Fehler wird zum Schreibfehler |

Der Nutzer sieht damit genau das Gegenteil von KONZEPT §2.7 (Live-Korrektur an der
Cursor-Position): Der falsche Text bleibt stehen, der richtige kommt nie an.

---

## 2. E8.1 — Divergenzbehandlung (ersetzt „bei Abweichung alles verwerfen")

### Begriffe (verbindlich)

- **Eigener Bereich** = der Text, den *diese Session* geschrieben hat:
  `own = typer.currentText`, `ownStart = cursorPos − own.length`.
  Alles **vor** `ownStart` ist fremd (Nutzertext/andere App) und wird **nie** berührt.
- **Besitz bewiesen** = `OwnershipTracker.isOwned(own, textBeforeCursor)` **und**
  `sink.ownLength()` stimmt **und** `!ownershipLost` (unverändert zu DESIGN §6).
- **Divergenz** = `commonPrefixLen(own, target) < typer.committedLength`.

### Regel (Wenn/Dann, in dieser Reihenfolge — Reihenfolge ist verbindlich)

```
W0  VOR jeder Entscheidung: `ownStart` NEU rekonstruieren (nicht aus einer
      gespeicherten Konstante): `ownStart = cursorPos − own.length` aus der
      aktuellen Selektion/`textBeforeCursor`; `own` muss das SUFFIX vor dem Cursor
      sein (`OwnershipTracker.isOwned`). Schlägt das fehl → W1.

W1  Wenn Besitz NICHT bewiesen
      → NICHTS schreiben (kein Löschen, kein Anhängen), Session als `divergent`
        markieren, Statuszeile „Editor geändert – Diktat angehalten", Ende.
        (Fremdtext/Overformung: niemals anfassen.)

W2  Wenn keine Divergenz (common >= committedLength)
      → wie bisher: minimales Delta im Tail schreiben; committedLength darf wachsen.

W3  Wenn Divergenz (common < committedLength) UND Besitz bewiesen
      → DAS IST UNSER EIGENER FRÜHERER FEHLER (Fall c). Rollback ist ERLAUBT:
      a) `committedLength := common`   (Schrumpfen ausdrücklich erlaubt, D2 aufheben)
      b) `toRemove = own.length − common` (nur eigener Bereich, nie < ownStart)
         — `toRemove` ist eine **UTF-16-Länge** (String-Indizes), siehe §4a.
      c) wenn `codePointCount(common, own.length) <= 60` → lösche `toRemove`
         Zeichen, tippe `target[common:]` in EINER Revision (ein Delta), Cursor
         bleibt am Ende.
      d) wenn `codePointCount(common, own.length) > 60` → **kein** Live-Rollback:
         `divergent := true`, Text stehen lassen, Statuszeile „Korrektur zu groß –
         wird beim Abschluss korrigiert". Der Finaldecode (committed_len = 0)
         korrigiert die gesamte eigene Spanne.

W4  Wenn Divergenz UND Besitz bewiesen UND die Divergenz läge vor `ownStart`
      → kann nicht auftreten (common ist per Definition ≤ own.length relativ zum
        eigenen Bereich); wenn doch (inkonsistenter Zustand) → wie W1: nichts schreiben.
```

**„Bewusst stehen lassen" ist nur in W1 und W3d zulässig** — nie in W3c. Begründung
mit dem gemessenen Schaden: In W3c kostet das Korrigieren **eine** Revision
(≤ 60 Zeichen, ein Backspace-Block, ein Neuschrieb) und liefert den Golden-Text;
Stehenlassen kostet **den Rest des Diktats** (gemessen: 27 Tokens Soll → 12 Tokens
Ist, 41 Zeichen Verlust) und ist für den Nutzer nicht als Fehler erkennbar.

**Invariante (neu, verbindlich):** *Fremdtext wird nie gelöscht; eigener Text darf
jederzeit innerhalb der 60-Zeichen-Grenze korrigiert werden.* Damit ist
Review-Regel 1 („kein fremder Text gelöscht") präzisiert, nicht abgeschwächt:
`ownStart` ist die harte Grenze, `ownershipVerified()` ist das Gate.

**Zusatz-Invariante (behebt D3):** Commit-Entscheidungen dürfen das Schreiben des
Tails **nicht** blockieren. `writeHypothesis()` schreibt den Tail **immer**, wenn
W2/W3c zutrifft; `commitUpTo` ist ausschließlich eine Markierung für den
Schutzbereich, kein Schreib-Gate.

### 2a. `ownStart`-Verschiebung / Fremdtext vor unserem Bereich (B4)

`ownStart` ist **keine gespeicherte Position**, sondern wird vor jedem Schreiben aus
Curserposition und eigener Textlänge rekonstruiert (`ownStart = cursorPos −
own.length`, W0). Damit gilt verbindlich:

1. **Fremdtext VOR unserem Bereich** (Nutzer tippt links davon) ist erlaubt. `isOwned()`
   prüft nur, ob `own` das **Suffix** vor dem Cursor ist — die eingefügte Fremdzeichen-
   Zahl verschiebt `ownStart` automatisch nach rechts, `own` bleibt unverändert gültig.
   Ein Rollback löscht dann relativ zum Cursor (`own.length − common` Zeichen davor)
   und kann per Konstruktion **keinen** Fremdtext treffen.
2. **Änderung INNERHALB unseres Bereichs** oder Cursorbewegung/Selektion: `onUpdateSelection`
   ohne erwarteten eigenen Callback ⇒ `lost = true` ⇒ **W1** (nichts schreiben).
3. **Zusätzliche Vorprüfung vor W3c:** `sink.ownLength() == own.length` (Längen-
   vergleich, **keine** absolute Position) **und** `common`/`own`/`committedLength`
   sind Indizes in `own`, nie in `actual`. Wird `ownLength` inkonsistent gemeldet oder
   ist der eigene Text nicht mehr Suffix ⇒ W1.
4. **Verboten:** eine beim Sitzungsstart gemerkte absolute `ownStart`-Zahl zum
   Löschen zu benutzen, oder mit `deleteSurroundingText` über `ownStart` hinaus zu
   gehen — beides würde bei vorgeschaltetem Fremdtext fremde Zeichen löschen.

---

## 3. E8.2 — Commit-Zurückhaltung (Zahlen)

Ein Präfix wird **nur** dann `committed`, wenn **alle** Bedingungen gelten:

| # | Bedingung | Wert | Zweck |
|---|---|---|---|
| C1 | Agreements für **Commit** (nicht für Anzeige) | **N = 3** aufeinanderfolgende Hypothesen | tötet „2× gleich falsch" — genau der gemessene Fall (`Vikti` hatte 2 Übereinstimmungen) |
| C2 | Mindestabstand zur stabilen Kante | **M = 2 Wörter** bleiben ungeschützt (die letzten zwei Wörter des stabilen Präfixes werden **nicht** committet) | ein Wort an der Wachstumskante ist nie „fest" |
| C3 | Mindestgröße | erst ab **≥ 3 Wörtern** im stabilen Präfix (sonst nichts committen) | bei 1–2 Wörtern gibt es nichts sicher zu schützen |
| C4 | Wortgrenze | weiterhin: nur bis `rfind(' ')` im gemeinsamen Präfix (COMMIT_RULE_DECISION §3) | nie mitten im Wort |
| C5 | Budget | `budgetExceeded` ⇒ kein Commit-Fortschritt (bestehende Regel 4) | abgeschnittene Hypothese ist kein Beweis |
| C6 | Divergenz-Fall | nach W3a beginnt die Zählung neu (3 neue Übereinstimmungen ab `common`) | kein sofortiger Re-Commit desselben Fehlers |

Konfigurationswerte: `commit_agreements = 3`, `commit_holdback_words = 2`,
`commit_min_words = 3` (Defaults; `min_audio_s = 2.0` bleibt unverändert).

**Konsequenz für die wahrgenommene Latenz:** keine. Der **Text** erscheint
unverändert ab `min_audio_s` (2,0 s) als Partial und wird laufend korrigiert; C1/C2
verzögern nur, ab wann ein Präfix als „fest" gilt. Rechnung bei
`asr_interval_s = 1,0`: ein Wort ist nach 3 Hypothesen ≈ **+2 s** bestätigt und
liegt dann ≥ 2 Wörter hinter der Kante ⇒ „fest" typisch **3–4 s** nach dem Sprechen
(vorher: ~2 s). Sichtbare Buchstaben-Latenz: unverändert. Preis: der geschützte
Bereich ist kleiner — genau das ist gewollt, denn der Schutz ist die Ursache des
gemessenen Ausfalls.

---

## 4. E8.3 — Was der Nutzer sieht (sichtbare Reihenfolge + Grenzen)

1. **Partial (Tail):** wird bei jeder Hypothese neu geschrieben/korrigiert, ohne
   Bestätigung. Löschumfang pro Revision ≤ **60** Codepoints (bestehende Grenze).
2. **Committed:** gilt sichtbar als fest und flackert nicht — **außer** im Fall W3c
   (eigener Fehler, Besitz bewiesen).
3. **Rollback-Grenzen (W3c), alle gleichzeitig:** ≤ **60** Codepoints **und** ≤ **2**
   committete Wörter **und** nie über einen Satzterminator (`. ! ? … :` ) hinweg
   **und** nie über einen Zeilenumbruch **und** nie vor `ownStart`.
   Ist eine Grenze verletzt ⇒ W3d (stehen lassen, Finaldecode korrigiert).
4. **Kein Cursor-Sprung:** Nach W3c steht der Cursor am Ende des neu geschriebenen
   eigenen Textes — dieselbe Position wie nach jedem normalen Delta. Es gibt **keine**
   Cursorbewegung in Fremdtext, keine Selektion, kein `setText`, kein
   `setComposingText` (DESIGN §6 unverändert).
5. **Sichtbarer Umfang des Rollbacks:** höchstens die letzten zwei Wörter werden
   sichtbar neu getippt (Löschen + erneutes Tippen in einem Batch, `beginBatchEdit`/
   `endBatchEdit`), typisch also „Vikti" → „Dikti" — ein Flackern von ≤ 5 Zeichen,
   kein Springen des Cursors, kein Textverlust davor.
6. **Nach dem Rollback:** `committedLength = common`; die Hysterese (C1–C3) startet
   neu; bis dahin bleibt alles Weitere Partial (sichtbar, korrigierbar).
7. **Anzeige-Regel:** Der Nutzer sieht **nie** einen Zustand, in dem geschriebener
   Text wieder verschwindet, ohne dass neuer Text an derselben Stelle erscheint
   (ein Delta, atomar im Batch).

### 4a. Einheiten-Regel (B1 — verbindlich, nicht verhandelbar)

Zwei verschiedene Einheiten sind im Spiel und dürfen **nicht** vermischt werden:

| Was | Einheit | Beleg im Code |
|---|---|---|
| **Grenzwertprüfung** des Rollbacks (≤ 60) | **Codepoints** (`String.codePointCount`) | `DeltaTyper.kt:87` `toRemoveCodepoints = currentText.codePointCount(common, currentText.length)` |
| **Löschlänge am Editor** | **UTF-16-Codeeinheiten** (`String.length`, `TextDelta.replaceLength`) | `EditorSink.replaceBeforeCursor(replaceLength, …)`; `deleteSurroundingText` zählt UTF-16 (DESIGN §6) |

**Regel:** Die Grenze („maximal 60 Zeichen zurücknehmen") ist eine
**Codepoint**-Grenze — das ist die Zahl, die der Nutzer als Zeichen sieht. Die
Löschoperation selbst nutzt die **UTF-16-Länge** `own.length − common`. Beide Werte
sind korrekt; wer sie vertauscht, produziert entweder falsche Verweigerungen
(UTF-16-Prüfung bei Emoji) oder Textreste (Delete in Codepoints gerechnet).

**Warnung (Indizes):** `committedLength`, `common`, `own` und `ownStart` liegen
**immer** als String-Indizes (UTF-16) vor — sie werden gegen `currentText.length`,
`regionMatches` und `TextDelta.replaceLength` benutzt. **Nur** die Grenzwertprüfung
rechnet in Codepoints. Eine Umrechnung der Indizes in Codepoint-Positionen ist
verboten (sie würde `regionMatches`/Substring-Grenzen verschieben).

Beispiele:

| Fall | Codepoints | UTF-16-Einheiten | Gilt |
|---|---|---|---|
| (a) `ä` (U+00E4, vorkomponiert) | 1 | 1 | Grenze und Delete identisch |
| (b) `😀` (U+1F600) | 1 | **2** | 1 Zeichen zählt gegen die Grenze, das Delete entfernt 2 Einheiten |
| (c) 60 Codepoints gemischt: 15 × `😀` + 45 × BMP-Zeichen | 60 | **75** | Grenze **eingehalten** (60 ≤ 60), Delete löscht **75** UTF-16-Einheiten — korrekt so |
| (d) Gegenprobe zu (c) mit UTF-16-Prüfung | — | 75 > 60 | **falsch**: der Rollback würde verweigert (W3d), obwohl der Nutzer nur 60 Zeichen sieht |

### 4b. Kurze Sessions und „Commit verweigert" (B3)

Mit C1 (N = 3) und C2 (M = 2 Wörter) ist `committedLength = 0` am Ende eines kurzen
Diktats der **Normalfall**, kein Fehlerzustand. Verbindlich gilt:

- **Der Finaldecode schreibt den kompletten Text und braucht keinen Commit.** Beim
  Loslassen wird der Schutz auf 0 gesetzt (`committed_len = 0`, KONZEPT §2.7 Regel 3)
  und die **gesamte eigene Spanne** neu geschrieben — „Commit ≠ Write" (D3) gilt
  auch hier: ein fehlender Commit verhindert nie das Schreiben.
- **Es gibt keinen Zustand „Text vergessen".** Jeder geschriebene Text ist entweder
  (a) committet, (b) Partial im eigenen, revidierbaren Bereich, oder (c) durch den
  Finaldecode ersetzt. Ein `committedLength = 0` ist nur die Aussage „nichts ist
  geschützt", nicht „nichts wird geschrieben".

| Fall | Erwartetes Ergebnis |
|---|---|
| **(i)** 1 Wort („Danke."), nie committed (C3: < 3 Wörter) | Partial steht sichtbar im Feld; Finaldecode schreibt „Danke." vollständig (Delta = gemeinsamer Präfix von `own` und Final ⇒ minimal, kein Duplikat) |
| **(ii)** 2 Wörter, `committedLength = 0` | identisch zu (i): Finaldecode ersetzt den eigenen Bereich komplett, kein Datenverlust, kein Doppeltext, keine Rücknahme von Fremdtext |
| **(iii)** Abbruch (UP) **mitten im Partial** | Der zuletzt geschriebene Partial **bleibt stehen** (nichts löschen beim Loslassen); der Finaldecode ersetzt ihn durch das finale Ergebnis (volle eigene Spanne, `committed_len = 0`). Ist der Besitz **nicht** bewiesen: gar kein Schreiben, Text bleibt wie er ist, Statuszeile „Editor geändert – Diktat angehalten" (kein Verlust, ggf. unvollständig) |

---

## 5. Testfälle (verbindlich, als Unit-Tests ohne Emulator)

| # | Eingabe (Hypothesen-Sequenz, je 1 s) | Erwartung |
|---|---|---|
| T1 | `… ob die Vikti` ×2, dann `… ob die Dikti`, `… ob die Dikti-Funktion`, … bis Golden | **Endtext = Golden** (§4.1). Kein `null`-Stau: jede Hypothese erzeugt ein Delta; Rollback genau einmal, `toRemove ≤ 60` |
| T2 | wie T1, aber zwischen den Hypothesen tippt der Nutzer (Ownership verloren) | kein Schreiben mehr (W1); bereits geschriebener Text unverändert; Statuszeile „Editor geändert" |
| T3 | Divergenz mit `own.length − common = 75` | **kein** Rollback (W3d), Text bleibt, `divergent=true`, Statuszeile „Korrektur zu groß"; Finaldecode korrigiert |
| T4 | 2 Übereinstimmungen auf falschem Wort, 3. Hypothese korrekt | `committedLength` bleibt 0 bzw. hinter dem Fehlwort (C1): Fehlwort wird **nie** fest |
| T5 | stabile Kante mit 3 Wörtern | nur das 1. Wort ist committed (C2/C3), die letzten 2 sind Partial |
| T6 | Rollback über einen Satzpunkt (`… Teil.`) | kein Rollback über `.` hinweg (W3d-Grenze), Text bleibt, Hinweis |
| T7 | **Einheiten (B1):** `own` = 15 × `😀` + 45 BMP-Zeichen, Divergenz am Anfang | Grenzprüfung in Codepoints (60 ≤ 60) ⇒ Rollback **erlaubt**; `TextDelta.replaceLength == 75` (UTF-16); Text danach exakt = Ziel |
| T8 | **Kurze Session (B3):** nur 1 Hypothese „Danke.", dann Final | `committedLength == 0` vor dem Final; Finaldecode schreibt „Danke." exakt einmal (kein Duplikat, kein Rest) |
| T9 | **Fremdtext davor (B4):** Nutzer tippt „Hallo " links vor unseren Bereich, dann Divergenz | Besitz bleibt gültig (Suffix-Prüfung), `ownStart` verschiebt sich; Rollback löscht nur eigene Zeichen, „Hallo " bleibt unverändert |

Regression: T1 ist **exakt der gemeldete Gerätefall** — er muss im Unit-Test
(DeltaTyper + CommitPolicy, ohne Emulator) reproduzierbar sein, damit dieser Ausfall
nie wieder nur auf dem Gerät auffällt.

---

## 6. Auswirkungen auf bestehende Dokumente/Tests

- `docs/COMMIT_RULE_DECISION.md`: §2 (Wortgrenze) und §3 (Pseudocode) bleiben gültig;
  die dortige Aussage „committedLength ist monoton" ist **aufgehoben** (W3a) und der
  Abschnitt „Konsequenzen → Stall-Risiko" wird durch E8.1 ersetzt.
- `LocalAgreementTest`: Agreement-Zahl für Commit 3 (Anzeige-Tests bleiben bei 2);
  Erwartungen aus COMMIT_RULE_DECISION §4 bleiben (Wortgrenze), neue Tests T4/T5.
- `DeltaTyperTest`: neue Tests T1/T3/T6 (Rollback, Budget, Satzpunkt-Grenze);
  `commitUpTo` muss Schrumpfen erlauben (D2) — der Test „committed bleibt monoton"
  entfällt bzw. wird zu „nur innerhalb W3a schrumpfbar".
- `docs/KONZEPT.md` §2.6 („committeter Text wird nie mehr zurückgenommen") ist damit
  **bewusst präzisiert**: zurückgenommen wird ausschließlich *eigener*, besitz-
  bestätigter Text im 60-Zeichen-/2-Wort-Rahmen; Fremdtext bleibt unantastbar.
  Ein Präzisierungsabsatz ist in KONZEPT.md nachzutragen (Datei gesperrt — Text hier:
  „Präzisierung Rev 4: Der committete Präfix ist gegen *fremde* Änderungen und gegen
  Revisionen ohne Besitznachweis unantastbar. Er ist **nicht** unantastbar gegen
  unseren eigenen, nachweislich falschen Text: liegt die Abweichung im eigenen
  Bereich, wird auf den gemeinsamen Präfix zurückgegangen (≤ 60 Codepoints, ≤ 2
  Wörter, nie über Satzzeichen) und neu geschrieben. Das ist Live-Korrektur an der
  Cursor-Position, nicht Datenverlust.")
