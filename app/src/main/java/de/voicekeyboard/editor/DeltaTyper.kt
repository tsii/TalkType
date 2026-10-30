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
 *   `divergent=true`, Text stehen lassen (Finaldecode korrigiert).
 * - **W4** inkonsistenter Zustand (Divergenz vor ownStart) → wie W1.
 *
 * Commit ≠ Write (D3 behoben): `commitUpTo` ist NUR eine Markierung des
 * Schutzbereichs; `revise` schreibt den Tail immer, wenn W2/W3c gilt.
 *
 * Grenzen: Löschumfang in **UTF-16-Einheiten** an den Sink, Grenzwert 60
 * **Codepoints**; Surrogatpaare werden nie getrennt.
 */
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

    /** Neue Sitzung: Zustand zurücksetzen (Editor-Wechsel / Diktat-Neustart). */
    fun reset() {
        currentText = ""
        committedLength = 0
        divergent = false
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
     * Revidiert auf die neue Hypothese [target] mit minimalem Delta (E8.1).
     *
     * Rückgabe: [TextDelta] der angewandten Änderung — auch im Rollback-Fall
     * (W3c) genau EIN Delta (atomar: Löschen + Neuschreiben in einer Revision).
     * `null` nur bei: identischem Stand (Idempotenz), W1 (Besitz nicht bewiesen,
     * ohne Löschen/Anhängen) oder W3d (Rollback-Grenze verletzt, Text bleibt).
     *
     * Wirft, wenn der [EditorSink] wirft — Zustand bleibt dann unverändert.
     */
    fun revise(target: String): TextDelta? {
        divergent = false // pro Hypothese neu bewerten
        if (!ownershipVerified()) { // W1: Besitz nicht bewiesen
            divergent = true
            return null
        }

        val common = commonPrefixLength(currentText, target)

        if (common >= committedLength) {
            // W2: keine Divergenz — minimales Delta im Tail
            return reviseTail(target, common)
        }

        // W3: Divergenz im eigenen Bereich — unser früherer Fehler (Fall c).
        val toRemoveCodepoints = currentText.codePointCount(common, currentText.length)
        if (!rollbackAllowed(common, toRemoveCodepoints)) {
            // W3d: keine Live-Korrektur — Text stehen lassen
            divergent = true
            return null
        }
        // W3a/W3c: committedLength schrumpft auf den gemeinsamen Präfix,
        // dann EINE atomare Revision (Löschen UTF-16-gerecht + Neuschreiben).
        committedLength = common
        val removeUtf16 = currentText.length - common
        val toAdd = target.substring(common)
        if (removeUtf16 == 0 && toAdd.isEmpty()) {
            currentText = target
            return null
        }
        return apply(TextDelta(removeUtf16, toAdd), target)
    }

    /** W2-Pfad: minimales Delta ab [common] (bestehende Logik). */
    private fun reviseTail(target: String, common: Int): TextDelta? {
        val oldTail = currentText.substring(committedLength)
        val newTail = target.substring(committedLength)
        val tailCommon = commonPrefixLength(oldTail, newTail)
        val toRemove = oldTail.length - tailCommon
        val toAdd = newTail.substring(tailCommon)

        if (toRemove == 0 && toAdd.isEmpty()) {
            currentText = target
            return null // identischer Stand: kein Delta, kein Sink-Aufruf
        }

        if (toRemove > maxRevisionChars) {
            // > maxRevisionChars zurücknehmen committen (einfrieren) und anhängen
            if (toAdd.isEmpty()) {
                committedLength = currentText.length
                return null
            }
            val frozenLength = currentText.length
            val delta = apply(TextDelta(0, toAdd), currentText + toAdd)
            committedLength = frozenLength
            return delta
        }
        return apply(TextDelta(toRemove, toAdd), target)
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
