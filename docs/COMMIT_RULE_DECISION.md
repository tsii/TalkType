# Entscheidung: Commit-Grenze bei LocalAgreement

**Stand:** 2026-10-27 · **Rolle:** Analyst (Designentscheidung, keine Implementierung)
**Status:** verbindlich für den Coder; **kein Code geändert**, `docs/KONZEPT.md` unverändert.

## 0. Gelesene Quellen

- `docs/KONZEPT.md` §2.6 (LocalAgreement, Commit **nur bis zur letzten Wortgrenze**,
  Pseudocode `cut = common_str.rfind(" ")`), §2.7 (Delta-Ausgabe, committed unantastbar).
- `docs/DESIGN.md` §5.4 („nie ein angebrochenes Wort committen", Partials als
  revidierbarer Tail), §6 (DeltaTyper, Besitz, 60-Zeichen-Grenze), §9 (Failure-Modes:
  „Doppelte Wörter / Satzteilverlust an Fenstergrenze", „Cursor verschoben").
- `app/src/main/java/de/voicekeyboard/commit/LocalAgreement.kt`
  (`stablePrefix()`, Zeilen 71–92), `CommitPolicy.kt`, `editor/DeltaTyper.kt`
  (`revise()`, Zeilen 60–100), `app/src/test/.../commit/LocalAgreementTest.kt`.

## 1. Befund am Ist-Code (das eigentliche Problem)

`stablePrefix()` gibt den vollen gemeinsamen Präfix frei, sobald **irgendeine**
Hypothese dort endet:

```kotlin
// LocalAgreement.kt:83-87
val endsAtWordBoundary = (0 until count).any { i ->
    t.length == len || t[len] == ' '
}
if (endsAtWordBoundary) return oldest.substring(0, len)
```

Der Term `t.length == len` ist im Normalfall **immer wahr**: `len` ist der gemeinsame
Präfix und damit ≤ jeder Hypothesenlänge; sobald die kürzere Hypothese ein Präfix der
längeren ist (der typische Streaming-Fall „alte Hypothese + gewachsener Text"),
gilt `t.length == len`. Folge: Die Wortgrenzen-Regel ist faktisch **tote Regel** —
committet wird fast immer der **ganze** gemeinsame Präfix einschließlich des letzten
Wortes. Genau das beschreibt der KDoc („Endet er mitten in einem Wort … nur bis zum
letzten Leerzeichen") **nicht**; Code und Kommentar widersprechen sich.

Die Unit-Tests kodifizieren diese Lesart mit: `wordBoundaryDoesNotCommitPartialWord`
erwartet für `("Das ist der", "Das ist derzw")` den Wert `"Das ist der"` — also die
Freigabe des angebrochenen Wortes `der`, die der Testname ausschließt.

## 2. (a) Entscheidung

**Es gilt die KONZEPT-Lesart (streng): committet wird ausschließlich der gemeinsame
Präfix bis einschließlich des letzten Leerzeichens; das letzte Wort bleibt immer
revidierbar. Die `endsAtWordBoundary`-Freigabe in `LocalAgreement.kt` wird ersatzlos
entfernt.**

Begründung (5–10 Sätze):

1. Der Text ist ein Präfix eines **weiter wachsenden** Audios. Das letzte Token liegt
   deshalb immer an der *Audio-Kante* — und an dieser Kante ist „Wort zu Ende" nicht
   beweisbar, sondern nur „vom Decoder so ausgegeben".
2. „Sieht wie ein vollständiges Wort aus" ist kein Beweis: Ein am Wortende
   abgeschnittenes Audio liefert regelmäßig ein **gültiges kurzes Wort** („der",
   „ist", „hat") für ein langes („derzeitig", „ist's", „hatte"). Damit ist der
   Unterschied zwischen „ganzes Wort" und „halbes Wort" an der Audio-Kante
   **prinzipiell nicht entscheidbar** — entscheidbar ist nur die Existenz eines
   Trenners.
3. Ein Trenner (`' '`) ist dagegen echte Evidenz: Das Leerzeichen **nach** einem Wort
   im gemeinsamen Präfix zeigt, dass das Audio dieses Wort hörbar beendet hat. Nur
   diese Evidenz begründet eine Grenze — nicht die Länge der Hypothese und nicht das
   Aussehen des letzten Tokens. Ein Trenner, der erst *hinter* dem gemeinsamen Präfix
   liegt (nur in der längeren Hypothese), wird bewusst nicht genutzt (Begründung §3).
4. Wird ein unbestätigtes Wort eingefroren, kann eine spätere Korrektur **innerhalb**
   des geschützten Präfixes liegen. `DeltaTyper.revise()` weist das ab
   (`!target.regionMatches(0, currentText, 0, committedLength) → null`), und weil
   `committedLength` nie schrumpft, scheitert danach **jede** weitere Hypothese an
   derselben Stelle: Der Ausgabestrom steht bis zum Finaldecode still (DESIGN §9
   „Satzteilverlust an der Fenstergrenze"). Das ist kein Schönheitsfehler, sondern
   ein Session-Stall.
5. Der Preis der strengen Lesart ist **kein** UX-Verlust: Das letzte Wort erscheint
   weiterhin sofort als revidierbarer Partial im Ziel-Editor (DESIGN §5.4 erlaubt das
   ausdrücklich). Geschützt wird nur, was sich nicht mehr ändern darf.
6. Am Diktatende geht nichts verloren: Der finale Decode setzt den Schutz auf null
   (KONZEPT §2.7 Regel 3) und korrigiert die gesamte eigene Spanne.
7. Die Regel wird dadurch **einfacher** (eine Abbruchbedingung statt drei) und ihre
   Invariante ist prüfbar: *Der committete Präfix endet immer auf `' '`.*

### Antwort auf die drei Teilfragen

**(1) Hypothese endet mit einem vollständigen Wort — ist das ein halbes Wort?**
Lexikalisch nein, funktional ja: Es ist ein **unbestätigtes** Wort an der Audio-Kante.
„Wortgrenze" im Sinne von KONZEPT heißt nicht „der letzte Token ist ein Wörterbuchwort",
sondern „es gibt einen belegten Trenner". Ohne Trenner wird das Wort **nicht**
committet — es bleibt als Tail sichtbar und korrigierbar.

**(2) Hypothese endet mitten im Wort („Das ist derz")?**
Eindeutig: nur bis zum letzten Leerzeichen committen (`"Das ist "`). Darüber besteht
kein Streit — beide Lesarten stimmen hier überein; der Ist-Code verletzt diese
Absicht allerdings genau dann, wenn die kürzere Hypothese ein Präfix der längeren
ist (`"Das ist der"` + `"Das ist derz"` → er committet fälschlich `"Das ist der"`).

**(3) Flackern/eingefrorene Fragmente?** Zwei Fälle sauber trennen:

- **Verlängerung ist harmlos:** `"Das ist der"` committet, danach `"Das ist derm"` →
  `regionMatches` passt, das Delta hängt nur `"m"` an. Kein Flackern, kein Problem.
- **Ersetzung ist der Killer:** `"Das ist der"` committet, danach `"Das ist die …"`
  (Genus/Kasus/Numerus klärt sich erst mit dem nächsten Wort: „der Preis" vs.
  „die Preise") → `regionMatches` schlägt fehl → Revision verworfen →
  **alle** folgenden Hypothesen scheitern → Stillstand bis zum Finaldecode.
  Genau dieser Fall ist mit der strengen Regel ausgeschlossen, weil das letzte Wort
  nie im geschützten Bereich liegt.

**Minimalregel, die KONZEPT-Treue *und* Flackerfreiheit erfüllt:** Abschneiden am
letzten Leerzeichen des gemeinsamen Präfixes, nie am Hypothesenende; monotone
Commit-Länge; keine Ausnahmen (kein Trenner → kein Commit).

## 3. (b) Verbindliche Regelformulierung für den Coder

```kotlin
// gilt für den gemeinsamen Präfix der letzten `commitAgreements` Hypothesen
val len = commonPrefixLen(hyps)                 // Graphem-sicher, wie vorhanden
if (len <= currentCommitted) return currentCommitted
val cut = hyps[0].lastIndexOf(' ', len - 1)     // letzter BELEGTER Trenner
if (cut < 0) return currentCommitted            // kein Leerzeichen → nichts committen
return maxOf(currentCommitted, cut + 1)         // schließt das Leerzeichen ein, nie rückwärts
```

**Invarianten (prüfbar, in Tests zu fixieren):**

1. `committedLength > 0 ⇒ currentText[committedLength - 1] == ' '` — der geschützte
   Präfix endet immer auf einem Leerzeichen, also nie in einem Wort.
2. `committedLength` ist monoton nicht-fallend (`CommitPolicy` klemmt bereits).
3. Kein Leerzeichen im gemeinsamen Präfix ⇒ **kein** Commit (`null`/unverändert).
4. Agreement-Zahl (2), „gleicher Audiostand zählt nicht doppelt" und die
   Budget-Regel (`budgetExceeded ⇒ kein Fortschritt`) bleiben unverändert.
5. Der Schnitt liegt auf einem ASCII-Leerzeichen ⇒ keine Graphem-/Surrogatgrenze
   kann verletzt werden.

**Zu entfernender Code:** `LocalAgreement.kt:83-87` (der `endsAtWordBoundary`-Block
inkl. `any`, `t.length == len` und `t[len] == ' '`) sowie der KDoc-Absatz
`LocalAgreement.kt:75-82`, der die Freigabe an der Wortgrenze beschreibt.

**Bewusst *nicht* übernommen (Variante B):** eine Ausnahme für Trenner-Evidenz
*außerhalb* des gemeinsamen Präfixes (`∃h: h[len] == ' ' ⇒ commit len`). Sie würde
den letzten Wort-Token einen Schritt früher einfrieren — genau die Klasse, die (3)
beschreibt: Klärt sich z. B. „der" erst mit dem Folgewort zu „die" (Genus/Kasus),
steht die Session. Zudem bliebe die Invariante „committeter Präfix endet auf `' '`"
nicht erhalten (der Trenner läge jenseits des Präfixes). Der Latenzgewinn ist
unsichtbar, weil der Partial ohnehin sofort im Editor steht — KONZEPT-Treue und eine
harte Invariante vor Mikro-Optimierung.

## 4. (c) Testfälle

| # | Hypothese 1 (älterer Audiostand) | Hypothese 2 (neuer) | Erwartetes Commit | Begründung |
|---|---|---|---|---|
| T1 | `Das ist der` | `Das ist der` | **`Das ist `** (8) | Audio-Kante, kein Trenner belegt → letztes Wort bleibt revidierbar (KONZEPT) |
| T2 | `Das ist der` | `Das ist derz` | **`Das ist `** (8) | mitten im Wort; heute fälschlich `Das ist der` (Bug §1) |
| T3 | `Das ist der` | `Das ist die` | **`Das ist `** (8) | Divergenz im letzten Wort; darf nie eingefroren werden (sonst Stall nach §2.3) |
| T4 | `Das ist der` | `Das ist der zweite` | **`Das ist `** (8) | strenge Lesart: Wort `der` erst committen, wenn **es selbst** von einem Trenner gefolgt ist, d. h. bei `Das ist der zweite` → nächster Schritt `Das ist der zweite Teil` ⇒ `Das ist der ` (12) |
| T5 | `Hallo` | `HalloWelt` | **kein Commit** (`null`) | kein Leerzeichen im gemeinsamen Präfix; Finaldecode deckt das Einzelwort ab |
| T6 | `Eins zwei` | `Eins zwei drei` | **`Eins `** (5) | Der Trenner nach `zwei` liegt **außerhalb** des gemeinsamen Präfixes und wird absichtlich nicht genutzt; `zwei` bleibt (harmlos) einen Schritt länger Tail. Mit `Eins zwei drei` + `Eins zwei drei vier` → `Eins zwei ` |
| T7 | `Schöne 🤖 Tastatur` | `Schöne 🤖 Tastaturen` | **`Schöne 🤖 `** | Schnitt am Leerzeichen vor dem wachsenden Wort; Surrogatpaar bleibt intakt |

**Bestehende Tests, die die alte Lesart festschreiben und angepasst werden müssen**
(`app/src/test/java/de/voicekeyboard/commit/LocalAgreementTest.kt`):

- `wordBoundaryDoesNotCommitPartialWord`: `"Das ist der"` + `"Das ist derzw"` →
  neu **`"Das ist "`** (Testname stimmt dann endlich mit der Erwartung überein).
- `sameAudioStateDoesNotCountTwice`: `"Hallo"` + `"HalloWelt"` → neu **`null`**.
- `unicodeSurrogatePairStaysIntact`: `"Schöne 🤖 Tastatur"` + `"… Tastaturen"` →
  neu **`"Schöne 🤖 "`** (Surrogatpaar-Assertion bleibt sinnvoll: der Schnitt darf
  nicht in `🤖` liegen).
- `twoAgreeingHypothesesReleasePrefix`: `"So, da bin ich"` + `"So, da bin ich mal"`
  → neu **`"So, da bin "`** (der Trenner nach `ich` liegt außerhalb des Präfixes).
- `resetRequiresTwoAgain`: `"Eins zwei"` + `"Eins zwei drei"` → neu **`"Eins "`**.
- Unverändert grün bleiben `singleHypothesisYieldsNothing` und
  `contradictoryPrefixYieldsNothing`.

**Konsequenz für die UX (bewusst akzeptiert):** Der committete Bereich hinkt dem
sichtbaren Text um **einen** Token hinterher. Das ist kein Latenzproblem — der
Token steht als Partial im Editor (DESIGN §5.4) —, sondern der Preis dafür, dass
im geschützten Bereich nie ein unbestätigtes Wort liegt.

## 5. (d) Braucht `docs/KONZEPT.md` eine Präzisierung?

**Keine Abweichung** — die Entscheidung setzt KONZEPT wörtlich um (`rfind(" ")`).
**Aber ja, eine Präzisierung ist sinnvoll**, weil (i) der Coder die Regel beim ersten
Anlauf anders gelesen hat, (ii) „Wortgrenze" missverständlich ist („Wort sieht
vollständig aus" ≠ belegte Grenze), (iii) der Sonderfall „gemeinsamer Präfix endet
selbst auf einem Leerzeichen" im Pseudocode nur implizit steckt, und (iv) der
Zusammenhang mit der Unantastbarkeit des Commits erklärt werden sollte.

**Textvorschlag (einzufügen in `docs/KONZEPT.md` §2.6, direkt nach dem Pseudocode —
Datei ist gesperrt, daher hier hinterlegt):**

> **Präzisierung der Wortgrenze (Rev 3).** „Nur bis zur letzten Wortgrenze" heißt:
> committet wird bis **einschließlich des letzten Leerzeichens** im gemeinsamen
> Präfix; das Wort danach bleibt immer revidierbar — auch dann, wenn es wie ein
> vollständiges Wort aussieht. Grund: Der Text ist ein Präfix wachsenden Audios, das
> letzte Wort liegt also an der Audio-Kante, und dort ist „Wort zu Ende" nicht
> beweisbar (ein abgeschnittenes Audio liefert oft ein gültiges kurzes Wort, z. B.
> „der" für „derzeitig"). Ein Leerzeichen **ist** dagegen der Beweis: Es zeigt, dass
> das Audio das Wort hörbar beendet hat. Daraus folgen drei Regeln: (1) Kein
> Leerzeichen im gemeinsamen Präfix ⇒ **kein** Commit. (2) Geschnitten wird nur am
> Leerzeichen, nie am Hypothesenende und nie mitten im Wort. (3) Der committete
> Präfix endet damit immer auf einem Leerzeichen und wird nie mehr verändert; ein
> später korrigiertes Wort kann deshalb nie im geschützten Bereich liegen (sonst
> würde jede weitere Revision an `regionMatches` scheitern und der Ausgabestrom bis
> zum Finaldecode stehen bleiben). Das letzte Wort erscheint bis dahin als
> revidierbarer Partial; am Diktatende korrigiert der finale Decode alles
> (`committed_len = 0`).

Bis zur Übernahme dieses Absatzes gilt diese Datei als Auslegungsgrundlage.

## 6. Restrisiko und Empfehlungen

- **Restrisiko (akzeptiert, von KONZEPT gedeckt):** Eine Hypothese kann ein bereits
  committetes Wort später anders dekodieren (z. B. „ist" → „ist's"). Dann blockt
  `DeltaTyper` die Revision. Häufigkeit ist klein (der Trenner war belegt), die Folge
  aber hart. **Empfehlung:** Diesen Fall nicht still verschlucken — einmalig loggen
  und den Ausgabestrom bis zum Finaldecode sichtbar pausieren (DESIGN §9, „keine
  Schreibversuche ohne Besitz/Übereinstimmung"), statt pro Hypothese erneut zu
  scheitern.
- **Empfehlung Test-Gate:** Invariante 1 („committeter Präfix endet auf `' '`",
  bzw. Länge 0) als Property-Test über eine Hypothesen-Sequenz, damit die Regel
  nicht erneut „wegoptimiert" wird.
- **Nicht im Scope:** Sprachen ohne Leerzeichen-Trennung (CJK/Thai) — die 15
  Startlayouts sind alle leerzeichengetrennt; die Regel nutzt U+0020 als Trenner.
