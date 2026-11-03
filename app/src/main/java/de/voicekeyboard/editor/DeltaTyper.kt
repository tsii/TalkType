package de.voicekeyboard.editor

/**
 * Delta-Typer nach KONZEPT.md §2.7, Rev. E8 (docs/COMMIT_RULE_DECISION_2.md):
 * Der Transkriptions-Stream liefert wiederholt den ganzen Text; getippt wird
 * nur die Differenz zum bereits geschriebenen Text.
 *
 * E8.1-Regeln (W1–W4, Reihenfolge verbindlich):
 * - **W1** Besitz nicht bewiesen → NICHTS schreiben, `divergent=true`. Fremdtext
 *   wird nie anfassen (ownStart ist die harte Grenze).
 * - **W2** Keine Divergenz (common >= committedLength) → wie bisher minimales
 *   Delta im Tail; committedLength darf wachsen.
 * - **W3** Divergenz (common < committedLength) UND Besitz bewiesen → eigener
 *   früherer Fehler. Rollback erlaubt:
 *   **W3a** `committedLength := common` (Schrumpfen ausdrücklich erlaubt — D2
 *   aufgehoben); **W3c** `toRemove = own.length − common` ≤ 60 Codepoints,
 *   keine Satzterminator-/Umbruch-Überschreitung, keine ownStart-Überschreitung
 *   → in EINER atomaren Revision löschen + `target[common:]` tippen;
 *   **W3d** Grenze verletzt → KEIN Live-Rollback: committedLength bleibt,
 *   `divergent=true`, Text stehen lassen — A2a: EINMALIG sichtbar; der
 *   nächste besitzgesicherte Aufruf revisions die eigene Spanne GESAMT.
 * - **W4** inkonsistenter Zustand (Divergenz vor ownStart) → wie W1.
 *
 * Commit ≠ Write (D3 behoben): `commitUpTo` ist NUR eine Markierung des
 * Schutzbereichs; `revise` schreibt den Tail immer, wenn W2/W3c gilt.
 *
 * Grenzen: Löschumfang in **UTF-16-Einheiten** an den Sink, Grenzwert 60
 * **Codepoints**; Surrogatpaare werden nie getrennt.
 */

/** A2a (PLAN_V0133 §1 A2a): geschlossene Ergebnisarten von [DeltaTyper.reviseOutcome]. */
enum class ReviseKind {
    /** Identischer Stand — kein Sink-Aufruf (auch: Einfrieren ohne Schreibschritt). */
    SAME,

    /** Besitz nicht bewiesen — NICHTS geschrieben, Fremdtext unangetastet. */
    W1,

    /** Rollback-Grenze verletzt — kein Live-Rollback, einmalig sichtbar. */
    W3d,

    /** Geschrieben — [ReviseOutcome.delta] ist genau EIN Sink-Schritt. */
    DELTA,
}

/**
 * A2a (PLAN_V0133 §1 A2a): Grundrückgabe des Typer-Produktionspfads — [kind]
 * ist IMMER gesetzt (keine Null-Mehrdeutigkeit wie beim früheren `TextDelta?`);
 * [delta] ist nur bei [ReviseKind.DELTA] gesetzt (genau ein Sink-Schritt).
 * SAME/W1/W3d schreiben NICHTS.
 */
data class ReviseOutcome(val kind: ReviseKind, val delta: TextDelta?) {
    companion object {
        val SAME = ReviseOutcome(ReviseKind.SAME, null)
        val W1 = ReviseOutcome(ReviseKind.W1, null)
        val W3d = ReviseOutcome(ReviseKind.W3d, null)
    }
}
class DeltaTyper(
    private val sink: EditorSink,
    private val maxRevisionChars: Int = MAX_REVISION_CHARS,
) {

    /** Alles bisher vom Diktat geschriebene (eigene) Text. */
    var currentText: String = ""
        private set

    /** Länge des committeten Präfixes von [currentText] (E8.1 W3a: darf schrumpfen). */
    var committedLength: Int = 0
        private set

    /** W1/W3d/W4: Divergenz- oder Besitzverlust-Meldung für die Statuszeile. */
    var divergent: Boolean = false
        private set

    /**
     * A2a (PLAN_V0133 §1 A2a): Eine W3d-Sperre ist EINMALIG sichtbar (kein
     * dauerhafter Stillstand): der NÄCHSTE besitzgesicherte Aufruf löst sie
     * durch eine atomare Gesamtrevision der eigenen Spanne auf. true = der
     * letzte Aufruf meldete W3d und wurde noch nicht aufgelöst.
     */
    private var w3dPending = false

    /** Neue Sitzung: Zustand zurücksetzen (Editor-Wechsel / Diktat-Neustart). */
    fun reset() {
        currentText = ""
        committedLength = 0
        divergent = false
        w3dPending = false
    }

    /**
     * Markiert den committeten Präfix als [length] (Aufrufer: CommitPolicy nach
     * Wortgrenzen-Freigabe). D3: NUR Markierung — kein Schreib-Gate.
     * E8.1 W3a: Schrumpfen ist erlaubt (Rollback); geklemmt auf [currentText].
     */
    fun commitUpTo(length: Int) {
        committedLength = length.coerceIn(0, currentText.length)
    }

    /**
     * A2a (PLAN_V0133 §1 A2a): Grundrückgabe des PRODUKTIONSPFADS — geschlossene
     * Ergebnisse statt Null-Mehrdeutigkeit:
     *  - [ReviseKind.SAME] identischer Stand — KEIN Sink-Aufruf (Idempotenz);
     *  - [ReviseKind.W1]   Besitz nicht bewiesen — NICHTS geschrieben;
     *  - [ReviseKind.W3d]  Rollback-Grenze verletzt — KEIN Live-Rollback,
     *      EINMALIG sichtbar (kein dauerhafter Stillstand);
     *  - [ReviseKind.DELTA] geschrieben — [ReviseOutcome.delta] ist genau EIN
     *      Sink-Schritt (auch im Rollback-Fall W3c atomar).
     *
     * W3d-Auflösung: der NÄCHSTE besitzgesicherte Aufruf revisions die eigene
     * Spanne GESAMT und atomar (60-Codepoint-Grenze einmalig überschritten;
     * Fremdtextgrenze bleibt — replaceLength ≤ eigene Länge) und meldet DELTA.
     * `committedLength` schrumpft dabei auf den gemeinsamen Präfix (W3a-Analog).
     *
     * Wirft, wenn der [EditorSink] wirft — Zustand bleibt dann unverändert
     * (die Sperre ist erneut versuchbar).
     */
    fun reviseOutcome(target: String): ReviseOutcome {
        divergent = false // pro Hypothese neu bewerten
        if (!ownershipVerified()) { // W1: Besitz nicht bewiesen
            divergent = true
            return ReviseOutcome.W1
        }
        if (w3dPending) { // A2a: Sperre auflösen — atomare Gesamtrevision der eigenen Spanne
            if (target == currentText) {
                w3dPending = false
                return ReviseOutcome.SAME
            }
            val common = commonPrefixLength(currentText, target)
            val delta = apply(TextDelta(currentText.length, target), target)
            w3dPending = false
            committedLength = common
            return ReviseOutcome(ReviseKind.DELTA, delta)
        }
        val common = commonPrefixLength(currentText, target)

        if (common >= committedLength) {
            // W2: keine Divergenz — minimales Delta im Tail
            return reviseTail(target, common)
        }

        // W3: Divergenz im eigenen Bereich — unser früherer Fehler (Fall c).
        val toRemoveCodepoints = currentText.codePointCount(common, currentText.length)
        if (!rollbackAllowed(common, toRemoveCodepoints)) {
            // W3d: keine Live-Korrektur — Text stehen lassen, EINMALIG sichtbar
            divergent = true
            w3dPending = true
            return ReviseOutcome.W3d
        }
        // W3a/W3c: committedLength schrumpft auf den gemeinsamen Präfix,
        // dann EINE atomare Revision (Löschen UTF-16-gerecht + Neuschreiben).
        committedLength = common
        val removeUtf16 = currentText.length - common
        val toAdd = target.substring(common)
        if (removeUtf16 == 0 && toAdd.isEmpty()) {
            currentText = target
            return ReviseOutcome.SAME
        }
        return ReviseOutcome(ReviseKind.DELTA, apply(TextDelta(removeUtf16, toAdd), target))
    }

    /**
     * A2a/A2b (PLAN_V0133 §A2b): besitzgesichertes FINALE — prüft Besitz zuerst
     *  (W1 ohne Schreibversuch) und kann NICHT blockiert werden (§3.3: „finale
     *  W3d-Sperre ausgeschlossen", keine alte Teilhypothese bleibt stehen):
     *  - identischer Stand ⇒ SAME (kein Duplikat, kein Sink-Aufruf);
     *  - normaler Fall ⇒ MINIMALES Delta (W2-Tail bzw. W3c-Rollback — die
     *    Bestandserwartung „Finale ersetzt den Partial-Schwanz minimal" bleibt);
     *  - Rollback-Grenzen verletzt (W3d-Fall) ODER Einfrier-Fall (> 60 im Tail)
     *    ⇒ atomare GESAMTREVISION der eigenen Spanne in genau EINEM Sink-Schritt
     *    (60-Grenze einmalig überschritten; Fremdtextgrenze bleibt — der
     *    Besitzbeweis verhindert jeden Fremdtext-Zugriff).
     * Löst eine bestehende W3d-Sperre auf; committedLength schrumpft im
     * Gesamt-/Rollback-Fall auf den gemeinsamen Präfix (W3a-Analog). Wirft,
     * wenn der [EditorSink] wirft — Zustand bleibt dann unverändert.
     */
    fun reviseFinal(target: String): ReviseOutcome {
        divergent = false
        if (!ownershipVerified()) { // Besitz zuerst — ohne Beweis kein Finale
            divergent = true
            return ReviseOutcome.W1
        }
        w3dPending = false
        if (target == currentText) return ReviseOutcome.SAME
        val common = commonPrefixLength(currentText, target)
        if (common >= committedLength) {
            // W2: minimales Delta im Tail (wie reviseOutcome) — AUSNAHME:
            // Einfrier-Fall wird zur Gesamtrevision (Finale lässt nichts Altes stehen).
            val oldTail = currentText.substring(committedLength)
            val newTail = target.substring(committedLength)
            val tailCommon = commonPrefixLength(oldTail, newTail)
            val toRemove = oldTail.length - tailCommon
            val toAdd = newTail.substring(tailCommon)
            if (toRemove == 0 && toAdd.isEmpty()) {
                currentText = target
                return ReviseOutcome.SAME
            }
            if (toRemove <= maxRevisionChars) {
                return ReviseOutcome(ReviseKind.DELTA, apply(TextDelta(toRemove, toAdd), target))
            }
        } else if (rollbackAllowed(common, currentText.codePointCount(common, currentText.length))) {
            // W3c: zulässiger Rollback — minimale atomare Revision (Bestandsverhalten).
            committedLength = common
            val removeUtf16 = currentText.length - common
            val toAdd = target.substring(common)
            if (removeUtf16 == 0 && toAdd.isEmpty()) {
                currentText = target
                return ReviseOutcome.SAME
            }
            return ReviseOutcome(ReviseKind.DELTA, apply(TextDelta(removeUtf16, toAdd), target))
        }
        // W3d-/Einfrier-Fall: Finale darf NIE blockiert sein — atomare
        // GESAMTREVISION der eigenen Spanne in genau EINEM Sink-Schritt.
        val delta = apply(TextDelta(currentText.length, target), target)
        committedLength = common
        return ReviseOutcome(ReviseKind.DELTA, delta)
    }

    // A2a/A2b (PLAN_V0133): der Controller ruft ausschließlich
    // reviseOutcome/reviseFinal — die frühere Null-Kompatibilitätsbrücke
    // (`revise(): TextDelta?`) ist ENTFERNT (kein Kompatibilitäts-Null-Pfad).

    /** W2-Pfad: minimales Delta ab [common] (bestehende Logik). */
    private fun reviseTail(target: String, common: Int): ReviseOutcome {
        val oldTail = currentText.substring(committedLength)
        val newTail = target.substring(committedLength)
        val tailCommon = commonPrefixLength(oldTail, newTail)
        val toRemove = oldTail.length - tailCommon
        val toAdd = newTail.substring(tailCommon)

        if (toRemove == 0 && toAdd.isEmpty()) {
            currentText = target
            return ReviseOutcome.SAME // identischer Stand: kein Delta, kein Sink-Aufruf
        }

        if (toRemove > maxRevisionChars) {
            // > maxRevisionChars zurücknehmen committen (einfrieren) und anhängen
            if (toAdd.isEmpty()) {
                committedLength = currentText.length
                return ReviseOutcome.SAME // Einfrieren ohne Schreibschritt
            }
            val frozenLength = currentText.length
            val delta = apply(TextDelta(0, toAdd), currentText + toAdd)
            committedLength = frozenLength
            return ReviseOutcome(ReviseKind.DELTA, delta)
        }
        return ReviseOutcome(ReviseKind.DELTA, apply(TextDelta(toRemove, toAdd), target))
    }

    /**
     * W3c-Grenzen (E8.3 §3): ≤ 60 Codepoints, ≤ 2 committete Wörter, kein
     * Satzterminator (`. ! ? … :`), kein Zeilenumbruch im Löschbereich.
     * Verstöße ⇒ false ⇒ W3d.
     */
    private fun rollbackAllowed(common: Int, toRemoveCodepoints: Int): Boolean {
        if (toRemoveCodepoints > maxRevisionChars) return false // 60-Codepoint-Grenze
        // Löschbereich [common, currentText.length) prüfen
        if (common >= currentText.length) return false // W4-artiger Inkonsistenz-Fall
        val removed = currentText.substring(common)
        if (removed.contains('\n') || removed.contains('\r')) return false // Umbruch
        for (ch in removed) {
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == ':') return false
        }
        // ≤ 2 committete Wörter: Leerzeichen im Löschbereich zählen Wörter
        val spaces = removed.count { it == ' ' }
        if (spaces >= 2) return false // 3+ Wortgrenzen ⇒ mehr als 2 Wörter
        return true
    }

    private fun apply(delta: TextDelta, newCurrent: String): TextDelta {
        sink.replaceBeforeCursor(delta.replaceLength, delta.newText)
        currentText = newCurrent
        return delta
    }

    /**
     * Besitzprüfung vor jedem Schreiben (DESIGN.md §6): Länge und Kontext des
     * eigenen Bereichs müssen den Erwartungen entsprechen, sonst kein Zugriff.
     */
    private fun ownershipVerified(): Boolean {
        if (currentText.isEmpty()) return true
        val own = sink.ownLength()
        if (own >= 0 && own != currentText.length) return false
        val context = sink.textBeforeCursor(currentText.length) ?: return false
        return context.toString() == currentText
    }

    companion object {
        const val MAX_REVISION_CHARS = 60

        /** Codepoint-Anzahl von s[0, until). */
        fun codePointCount(s: String, until: Int): Int = s.codePointCount(0, until)

        /**
         * Länge des gemeinsamen Präfixes zweier Texte. Zieht ggf. ein High-Surrogate
         * am Ende ab, damit kein Surrogatpaar getrennt wird (DESIGN.md §6).
         */
        fun commonPrefixLength(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            if (i > 0 && i <= a.length && Character.isHighSurrogate(a[i - 1])) i--
            return i
        }
    }
}
