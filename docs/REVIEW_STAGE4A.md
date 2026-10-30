# Review Stufe 4 Teil 2a — commit/ + editor/ (LocalAgreement, CommitPolicy, DeltaTyper)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Gegenstand:** `commit/{CommitPolicy,LocalAgreement}.kt`, `editor/{EditorSink,TextDelta,DeltaTyper}.kt` + zugehörige Tests;
ergänzend Stufe 4 Teil 1 (Audio/FGS) kursorisch geprüft. Referenz: KONZEPT §2.6/§2.7, DESIGN §5.4/§6/§2.3.
**Nicht ausgeführt:** `./gradlew` (parallel). Reine (lesende) Code-/Test-Prüfung.

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| S4-01 | **P2** | `LocalAgreement.kt:83-87` | **Wortgrenzen-Regel weicht von KONZEPT §2.6 ab und kann ein angebrochenes Wort committen.** `endsAtWordBoundary = any(t.length==len || t[len]==' ')`. `t.length==len` heißt „Hypothese endet dort", **nicht** „Wortgrenze": bei zwei deckungsgleichen Hypothesen, die identisch mitten im Wort enden (deterministisches ASR auf zwei kurzen, gleich geschnittenen Fenstern ⇒ „…derz"), wird `"…derz"` komplett freigegeben — exakt der Halbwort-Fehler, den §2.6 („Das müsste jetzt **de** statt **deutlich**") verhindern soll. KONZEPT §2.6 schneidet **immer** bei `rfind(' ')`. `t[len]==' '` ist das eindeutige Grenzsignal; `t.length==len` ist es nicht. | Z.83-85 `(0 until count).any { … t.length == len || t[len] == ' ' }`; Z.87 `return oldest.substring(0, len)`. Gegenprobe: zwei identische `"Das ist derz"` ⇒ `t.length==len` true ⇒ Freigabe `"derz"`. | Siehe **Entscheidungsvorlage** unten. Minimal: `t.length==len` streichen (nur `t[len]==' '` als Grenze), oder KONZEPT-wörtlich `rfind(' ')`; Test `wordBoundaryDoesNotCommitPartialWord`/`twoAgreeingHyph…` entsprechend nachziehen. |
| S4-02 | **P2** | `DeltaTyper.kt:85-91` | **`committedLength` wird vor dem Sink-Schreiben mutiert → verletzt die Zusage „Zustand unverändert bei Sink-Fehler".** Im `toRemove > maxRevisionChars`-Zweig wird `committedLength = currentText.length` **vor** `apply(...)` gesetzt. Wirft der Sink, ist `currentText` unverändert, aber `committedLength` bereits auf die volle Länge gefroren → der nächste `revise`-Versuch derselben Revision scheitert an `regionMatches` (Präfix stimmt nicht) und wird **dauerhaft verworfen**. | Z.86 `committedLength = currentText.length` vor Z.91 `return apply(...)`; `apply` (Z.96-100) setzt `currentText` erst **nach** `sink.replaceBeforeCursor`. | `committedLength` erst nach erfolgreichem `apply` setzen (Erfolgspfad vs. Fehlerpfad trennen), sodass ein Sink-Wurf keine teilweise Zustandsmutation hinterlässt. |
| S4-03 | P2 | `LocalAgreement.kt:78,87` | **Surrogatpaare im gemeinsamen Präfix nicht geschützt.** Der Präfixvergleich läuft als reiner UTF-16-Zeichenvergleich (`while (l<m && oldest[l]==t[l]) l++`) und `substring(0,len)` — divergieren zwei Hypothesen **innerhalb** eines Surrogatpaars (zwei Astral-Zeichen mit gleichem High-Surrogat), landet `len` auf dem High-Surrogat und `substring` produziert ein einsames High-Surrogat. `DeltaTyper.commonPrefixLength` (Z.117-124) hat genau diesen Guard (`isHighSurrogate`→`i--`), `LocalAgreement` nicht reuse ihn. DESIGN §6: „Löschgrenzen an Graphemclustern ausrichten, keine Surrogatpaare zerlegen". | Z.78 `while (l < m && oldest[l] == t[l]) l++`; Z.87 `substring(0, len)` ohne Surrogat-Korrektur; vgl. `DeltaTyper.kt:120-123`. | Surrogat-Korrektur wie `commonPrefixLength` auf `len` anwenden, bevor `substring(0,len)` bzw. `lastIndexOf(' ', len-1)` läuft. |
| S4-04 | Note | `DeltaTyper.kt:101-109` | **Ownership = Länge + exakter Kontext, nicht das volle DESIGN-§6-Protokoll.** Geprüft werden `ownLength()==currentText.length` und `textBeforeCursor(len)==currentText`. Das deckt Cursor-Verschiebung und Fremd-Edit (über Kontext-Mismatch) fail-closed ab. **Nicht** repräsentiert sind `editorGeneration`, Start-Selektion, „erwarteter Suffix", `onUpdateSelection`-Callbacks aus DESIGN §6 — die müssen im (noch fehlenden) `InputConnectionSink` (2b) leben und dort als `ownLength()==-1` / `textBeforeCursor()==null` sichtbar werden. Als reine JVM-Abstraktion die richtige Schichtung, aber erst mit 2b ist §6 vollständig erfüllt. | Z.101-109 `ownershipVerified` (nur ownLength + trailing context). | In 2b: generation/selektion-verifizierenden `InputConnectionSink` liefern, der bei Editorwechsel `-1`/`null` liefert; DeltaTyper bleibt unverändert. |
| S4-05 | Note | `capture/CaptureForegroundService.kt:67` + `audio/*` | **Audio/FGS (Teil 1) regelkonform.** `startForeground(…, FOREGROUND_SERVICE_TYPE_MICROPHONE)` sofort in `onStartCommand` (vor jeder Init), `START_NOT_STICKY` + null-Intent→`stopSelf` (kein Wiederanlaufen), kein Binder (`onBind=null`, kein Audio über IPC), 16 kHz/mono/PCM16 (`AudioRecordSource` CHANNEL_IN_MONO/ENCODING_PCM_16BIT), kein Plattenzugriff, Logs nur Dauern (nie Audio/Text). Routing `WIRED > BLUETOOTH_SCO > BUILTIN`, `OTHER`/A2DP nie Mikrofon (`AudioRouter.kt:28,33-42,130-133`). Manifest trägt `RECORD_AUDIO` + `FOREGROUND_SERVICE(_MICROPHONE)` + `POST_NOTIFICATIONS` + `foregroundServiceType="microphone"` (TECH_FACTS §4.1). Offen (Caller-Zuständigkeit, nicht hier): Laufzeit-Grant von `RECORD_AUDIO` vor dem Start. | Z.67 `startForeground`; `AudioRouter.kt:40-42` Priorität; Manifest Z.20-31/52-55. | RECORD_AUDIO-Laufzeit-Anforderung im IME vor dem FGS-Start sicherstellen (nächste Stufe). |

---

## Punktgenaue Antworten

1. **LocalAgreement:** 2 Bestätigungen ⊗ unterschiedlicher Audiostand ✓ (`audioStates[lastIndex]==audioSamples` ⇒ ersetzt statt zählt, Z.64-70). **Ringpuffer `lastIndex = (head + size - 1) % size`** korrekt — zeigt auch bei noch nicht vollem Ring auf den jüngsten Eintrag (Z.63). Wortgrenzen-Logik siehe S4-01 (Abweichung + Halbwort-Risiko). Surrogat-Lücke siehe S4-03.
2. **CommitPolicy:** committed-Präfix schrumpft nie ✓ (`max(newLength, currentCommitted)`, Z.66). Budget blockiert ✓ (`budgetExceeded ⇒ return currentCommitted`, Z.63). `onHypothesis` deterministisch/idempotent: gleicher Audiostand ⇒ `accept` liefert `null` ⇒ kein Fortschritt (keine Doppelzählung). ✓
3. **DeltaTyper:** `max_revision_chars=60` — Überschreitung committet statt zerstört ✓ (`toRemove>60 ⇒ committedLength=length; apply(0, toAdd)`), aber mit Zustands-Bug S4-02. Minimales Delta über `commonPrefixLength` ✓. Surrogat-Schutz im Delta ✓ (`commonPrefixLength` Z.120-123), **nicht** aber in LocalAgreement (S4-03). Sink-Fehler: `currentText` konsistent ✓ (außer `committedLength` im >60-Zweig, S4-02). Ownership Länge+Kontext — ausreichend als Abstraktion, volles §6-Protokoll erst in 2b (S4-04).
4. **Wortgrenzen-Zielkonflikt:** siehe Entscheidungsvorlage unten.
5. **Teil 1 (Audio/FGS):** korrekt (S4-05).
6. **Testqualität:** Die Commit-/Delta-Tests sind **echte Gegenproben** (Negativ: `committedPrefixIsNeverTouched`, `ownershipMismatchIsRejected`, `contradictoryPrefixYieldsNothing`, `sameAudioStateDoesNotCountTwice`; Fehlerpfad `sinkErrorLeavesStateConsistent`). Die „4 angepassten Erwartungen" sind die Wortgrenzen-Fälle (`twoAgreeingHypothesesReleasePrefix`, `wordBoundaryDoesNotCommitPartialWord`, `CommitPolicy.twoAgreeingHypothesesAdvanceCommit` mit `"Hallo Welt"→10`, `streamingProgressively`) — sie wurden von der KONZEPT-wörtlichen `rfind(' ')`-Länge (z. B. `"Hallo "`=6) auf die `endsAtWordBoundary`-Länge (z. B. `"Hallo Welt"`=10) umgestellt, weil der Coder das **komplette** Schlusswort freigibt. Das ist konsistent **mit** der Implementierung, **nicht** mit KONZEPT §2.6 — die Tests kodieren damit die Abweichung fest (S4-01). Berechtigt als Kodierung der gewählten Lesart, aber **nicht** unabhängig vom Designentscheid.

---

## Entscheidungsvorlage: Wortgrenze (S4-01)

**Fragestellung:** Darf das abschließend **vollständige** Wort einer Zwischen-Hypothese committet werden, oder wird **immer** (KONZEPT §2.6 wörtlich) bis zum letzten Leerzeichen geschnitten?

**Kontext:** Stream liefert `"Das ist der"` (Fenster endet am Wortende), danach `"Das ist derzw"` (Wort geht weiter). Dann wieder Zwischenformen wie `"…derz"`.

| | Option A — KONZEPT wörtlich (`rfind(' ')`) | Option B — Coder (`endsAtWordBoundary`) |
|---|---|---|
| Verhalten | immer bis letztes `' '` ⇒ committet `"Das ist "` (nie das Schlusswort) | committet `"Das ist der"` (komplettes Schlusswort), wenn `t.length==len` **oder** `t[len]==' '` |
| sicher | **kein** Halbwort jemals committet | Risiko: zwei deckungsgleiche Mid-Word-Truncations ⇒ `"…derz"` wird committet (`t.length==len` ist „Ende", nicht „Wortgrenze") |
| Latenz/Flackern | letztes Wort bleibt bis zum Folge-Leerzeichen korrigierbar (evtl. neu getippt) | committet eager, weniger Flackern |
| Konzept-Treue | exakt §2.6-Pseudocode | erfüllt die **Intention** („kein halbes Wort"), aber nicht den Pseudocode |

**Empfehlung:** **Option B behalten, aber `t.length == len` als Grenz-Trigger streichen** — nur `t[len] == ' '` ist ein *eindeutiges* Wortgrenzen-Signal (auf `t[len]` nur zugreifen, wenn `t.length > len`, sonst `StringIndexOutOfBounds`). Damit wird ein komplettes Schlusswort weiterhin eager committet (sobald das Folge-Leerzeichen sichtbar ist), ohne dass eine Truncation als „Wortende" zählt. Konsequenz: `"Das ist der"` vs `"Das ist derzw"` ergibt dann `"Das ist "` (8) statt `"Das ist der"` (11) — **und** KONZEPT §2.6, die Implementierung und die betroffenen Tests müssen dafür synchron angeglichen werden (die „4 angepassten Erwartungen" aus Punkt 6).

---

## Verdikt

**Nur mit Fixes freigeben.**

Kein P0. Die Kernlogik (2 Bestätigungen über verschiedene Audiostände, committed-Präfix unantastbar, Budget-Block, minimales Delta mit Surrogat-Schutz, 60-Zeichen-Notbremse, Sink-Fehler→Zustandskonsistenz im Normalfall) ist solide und gut getestet. Blockierend sind drei P2, die vor Release zu klären sind — davon **S4-01 als Design-Entscheidung, die jetzt getroffen werden muss** (bisher still in den Tests kodiert), **S4-02** (echter Zustands-Bug im Fehlerpfad) und **S4-03** (Surrogat-Schutz im Agreement, konsistent zu DeltaTyper).

## Top-5-Aktionen

1. **S4-01** — Wortgrenzen-Regel entscheiden (Empfehlung: Option B ohne `t.length==len`), KONZEPT §2.6 + Implementierung + Tests synchron ziehen.
2. **S4-02** — `committedLength` erst nach erfolgreichem Sink-Schreiben setzen (Fehlerpfad zustandssauber).
3. **S4-03** — Surrogat-Korrektur in `LocalAgreement.stablePrefix` einbauen (Reuse von `DeltaTyper.commonPrefixLength`-Logik).
4. **S4-04** — In Teil 2b den `InputConnectionSink` mit vollständigem DESIGN-§6-Besitzprotokoll (Editorgeneration, Selektion, Suffix) liefern; Ownership erst damit vollständig.
5. Test ergänzen für die S4-01-Entscheidung (zwei identische Mid-Word-Hypothesen ⇒ **kein** Commit des Halbworts) als Regressionstest der gewählten Regel.