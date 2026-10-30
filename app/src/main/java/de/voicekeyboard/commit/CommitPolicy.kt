package de.voicekeyboard.commit

/**
 * E8.2-Commit-Politik (COMMIT_RULE_DECISION_2.md §3): N=3 Agreements für Commit,
 * M=2 Wörter Holdback, C3 ≥3 Wörter, C4 Wortgrenze (bleibt), C5 Budget,
 * C6 Neuzählung nach Rollback (via shrink-fähigen currentCommitted + reset).
 *
 * Der **Anzeige-**Betrieb (LocalAgreement mit N=2) bleibt unangetastet — hier
 * wird nur die Commit-Schwelle auf 3 gehoben.
 */
class CommitPolicy(
    private val requiredAgreements: Int = COMMIT_AGREEMENTS,
    private val holdbackWords: Int = COMMIT_HOLDBACK_WORDS,
    private val minWords: Int = COMMIT_MIN_WORDS,
) {

    private val agreement = LocalAgreement(requiredAgreements)

    /** Neue Aufnahme-Sitzung: Agreement-Historie verwerfen (auch nach Rollback). */
    fun reset() = agreement.reset()

    /**
     * Reicht eine Hypothese ein und liefert die neu erlaubte Commit-Länge
     * (E8.2 C1–C6). Reihenfolge: C1 (N=3 Agreements) wirkt im [LocalAgreement];
     * danach C2 (letzte M=2 Wörter des ROHEN stabilen Präfixes halten),
     * C3 (≥ 3 committbare Wörter), C4 Wortgrenze (C4-Schnitt auf den Rest),
     * C5 Budget (früher Abbruch oben).
     */
    fun onHypothesis(
        hypothesisText: String,
        audioSamples: Long,
        currentCommitted: Int,
        budgetExceeded: Boolean,
    ): Int {
        if (budgetExceeded) return currentCommitted // C5: abgeschnitten ⇒ kein Beweis
        val raw = agreement.acceptRaw(hypothesisText, audioSamples) ?: return currentCommitted
        // C2: die letzten holdbackWords Wörter des rohen stabilen Präfixes halten.
        val kept = trimHoldbackWords(raw) ?: return currentCommitted // C3: zu wenige Wörter
        // C4: nur bis einschließlich des letzten Leerzeichens im verbleibenden Präfix
        val cut = kept.lastIndexOf(' ')
        if (cut < 0) return currentCommitted
        val newLength = cut + 1
        return if (newLength > currentCommitted) newLength else currentCommitted
    }

    /**
     * C2/C3: entfernt die letzten [holdbackWords] Wörter aus [raw] (getrennt durch
     * Leerzeichen). Rückgabe null, wenn danach < [minWords] Wörter übrig bleiben
     * (C3) oder nichts Haltbares existiert.
     */
    private fun trimHoldbackWords(raw: String): String? {
        // C3: mindestens minWords vollständige Wörter im stabilen Präfix
        // (minWords vollständige Wörter ⇒ minWords−1 Wortgrenzen; das letzte
        // Wort kann ohne Trenner enden).
        val spaces = raw.count { it == ' ' }
        if (spaces < minWords - 1) return null
        var end = raw.length
        var remaining = holdbackWords
        while (remaining > 0) {
            val ls = raw.lastIndexOf(' ', end - 1)
            if (ls < 0) return null
            end = ls // Index des Leerzeichens vor dem gehaltenen Wort
            remaining--
        }
        if (end < 0) return null
        return raw.substring(0, end + 1) // Trenner bleibt beim committeten Teil
    }

    companion object {
        /** E8.2 C1: Commit erst ab 3 aufeinanderfolgenden Agreements. */
        const val COMMIT_AGREEMENTS = 3

        /** E8.2 C2: die letzten 2 Wörter bleiben ungeschützt. */
        const val COMMIT_HOLDBACK_WORDS = 2

        /** E8.2 C3: mindestens 3 Wörter im stabilen Präfix. */
        const val COMMIT_MIN_WORDS = 3
    }
}
