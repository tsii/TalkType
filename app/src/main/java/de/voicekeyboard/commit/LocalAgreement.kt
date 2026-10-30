package de.voicekeyboard.commit

/**
 * LocalAgreement nach KONZEPT.md §2.6: Erst wenn `commitAgreements` (Default 2)
 * aufeinanderfolgende Transkriptionen denselben Präfix liefern, gilt er als
 * stabil und wird bis zur **Wortgrenze** freigegeben.
 *
 * - Ein einzelner Lauf kann eine Halluzination sein (KONZEPT §3.4) → erst
 *   2 Bestätigungen (unterschiedliche Audiostände) geben frei.
 * - Nur bis zur Wortgrenze committen: das letzte Wort bleibt immer korrigierbar
 *   (kein halb gesprochenes Wort einfrieren; Details zur Grenzregel siehe
 *   [stablePrefix]).
 * - Deterministisch (Voraussetzung: ASR mit temperature=0, KONZEPT §2.5).
 * - Kein Zwischen-String im Hot-Path: Der Vergleich läuft zeichenweise über die
 *   gespeicherten Hypothesen; alloziert wird nur das Endergebnis.
 */
class LocalAgreement(private val requiredAgreements: Int = DEFAULT_AGREEMENTS) {

    init {
        require(requiredAgreements in 1..MAX_AGREEMENTS) {
            "requiredAgreements muss in 1..$MAX_AGREEMENTS liegen (KONZEPT §2.6 / E8.2), war $requiredAgreements"
        }
    }

    private val texts = arrayOfNulls<String>(requiredAgreements)
    private val audioStates = LongArray(requiredAgreements)
    private var count = 0
    private var head = 0 // Index des ältesten Eintrags

    /** Neue Aufnahme-Sitzung: Historie verwerfen. */
    fun reset() {
        java.util.Arrays.fill(texts, null)
        count = 0
        head = 0
    }

    /**
     * Reicht eine neue Transkriptionshypothese ein ([text], decodiert ab
     * [audioSamples] Audio). **Dieselbe Audioposition zählt nicht als zweite
     * Bestätigung** (DESIGN.md §5.4) — sie überschreibt den letzten Eintrag.
     *
     * @return neu stabiler Präfix bis zur Wortgrenze, oder `null`, wenn die
     *   Historie noch nicht gefüllt bzw. nichts stabil ist.
     */
    /**
     * Wie [accept], liefert aber den ROHEN gemeinsamen Präfix (ohne Wortgrenzen-
     * Beschnitt) — E8.2 wendet C2/C3/C4 erst in der Policy an.
     */
    fun acceptRaw(text: String, audioSamples: Long): String? {
        val cut = stablePrefix(text, audioSamples, wordCut = false)
        return cut
    }

    fun accept(text: String, audioSamples: Long): String? =
        stablePrefix(text, audioSamples, wordCut = true)

    private fun stablePrefix(text: String, audioSamples: Long, wordCut: Boolean): String? {
        // letzter Eintrag = Schreibslot - 1 (gilt auch, solange der Ring noch nicht voll ist)
        val lastIndex = (head + texts.size - 1) % texts.size
        if (count > 0 && audioStates[lastIndex] == audioSamples) {
            // Gleicher Audiostand: Snapshot ersetzen, keine neue Bestätigung.
            texts[lastIndex] = text
            audioStates[lastIndex] = audioSamples
            return null
        }
        texts[head] = text
        audioStates[head] = audioSamples
        head = (head + 1) % texts.size
        if (count < texts.size) count++
        return if (count < requiredAgreements) null else stablePrefix(wordCut)
    }

    /**
     * Gemeinsamer Präfix der letzten [requiredAgreements] Hypothesen, beschnitten
     * auf die Wortgrenze (KONZEPT §2.6, verbindliche Lesart aus
     * docs/COMMIT_RULE_DECISION.md — Review S4-01):
     *
     * Committet wird **ausschließlich** bis einschließlich des letzten
     * Leerzeichens **innerhalb** des gemeinsamen Präfixes; das Wort danach
     * bleibt immer revidierbar — auch wenn es wie ein vollständiges Wort
     * aussieht (an der Audio-Kante ist „Wort zu Ende“ nicht beweisbar, ein
     * Leerzeichen schon). Dadurch kann ein später korrigiertes Wort nie im
     * geschützten Bereich liegen (sonst Session-Stall in DeltaTyper).
     *
     * - Invariante: der Rückgabewert endet **immer auf `' '`** oder ist null.
     * - Kein Leerzeichen im gemeinsamen Präfix ⇒ kein Commit (null).
     * - Surrogatpaare werden nie getrennt (S4-03): endet der gemeinsame Präfix
     *   auf einem High-Surrogate, wird die Grenze um eins zurückgenommen, bevor
     *   geschnitten wird — ein einsames High-Surrogat entsteht nie.
     */
    private fun stablePrefix(wordCut: Boolean): String? {
        val oldest = texts[(head + texts.size - count) % texts.size]!!
        var len = oldest.length
        for (i in 1 until count) {
            val t = texts[(head + texts.size - count + i) % texts.size]!!
            val m = minOf(len, t.length)
            var l = 0
            while (l < m && oldest[l] == t[l]) l++
            len = l
            if (len == 0) return null // widersprüchlicher Präfix ⇒ kein Commit
        }

        // S4-03: gemeinsamer Präfix darf nicht auf einem High-Surrogat enden
        // (analog DeltaTyper.commonPrefixLength) — VOR substring/lastIndexOf.
        if (len > 0 && len < oldest.length && Character.isHighSurrogate(oldest[len - 1])) {
            len--
            if (len == 0) return null
        }

        if (!wordCut) return oldest.substring(0, len) // E8.2: roher Präfix

        // S4-01 / COMMIT_RULE_DECISION: letzter BELEGTER Trenner innerhalb des
        // gemeinsamen Präfixes; das Leerzeichen wird mitgecommittet, dahinter
        // beginnt das immer revidierbare letzte Wort.
        val cut = oldest.lastIndexOf(' ', len - 1)
        return if (cut < 0) null else oldest.substring(0, cut + 1)
    }

    companion object {
        /** Anzeige-Schwelle (Partials): 2 Bestätigungen — unverändert (E8.2 trennt
         *  Commit- von Anzeige-Schwelle; die Commit-Schwelle N=3 lebt in
         *  [CommitPolicy.COMMIT_AGREEMENTS]). */
        const val DEFAULT_AGREEMENTS = 2

        /** E8.2: Commit-Variante darf bis zu 3 Agreements verlangen (C1, N=3). */
        const val MAX_AGREEMENTS = 3
    }
}
