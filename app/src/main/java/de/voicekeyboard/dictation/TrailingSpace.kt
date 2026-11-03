package de.voicekeyboard.dictation

/**
 * T10 (v0.1.39): Endet ein Diktat auf einem Satzzeichen, hängt das
 * SESSION-Finale automatisch EIN Leerzeichen in den Editor — damit das nächste
 * Diktat nicht direkt am Satzzeichen klebt („Hallo.Guten Morgen“ →
 * „Hallo. Guten Morgen“). Reine, JVM-testbare Entscheidung (keine Android-
 * Typen, Muster [DictationOutputCounter]); der Aufrufort ist
 * [DictationController.writeHypothesis] mit `final=true` — NUR das echte
 * Session-Finale (stop/UP bzw. F43-Auto-Stop-Finale), NICHT die
 * Zwischensegment-Finalisierung (F53 seg_final) und nicht der Abbruch-Pfad.
 *
 * Regel: `' '` anhängen genau dann, wenn
 *  - das massgebliche Zeichen am Textende (ohne nachgestellte
 *    SCHLUSSZEichen [CLOSERS] `” “ " » ) ]` — massgeblich ist dann das Zeichen
 *    davor, mehrstufig wie „…“) ein SATZZEICHEN aus [PUNCT] `. ! ? : ; …` ist,
 *  - UND der committete Text nicht schon auf Whitespace endet sowie am Cursor
 *    ([alreadyAtCursor], Editor-Seite, null = unbekannt) kein Leerzeichen
 *    steht (Doppelschutz gegen Doppel-Leerzeichen),
 *  - UND die Session Output hatte (null/leerer Text ⇒ nichts).
 */
object TrailingSpace {

    /** T10: Satzzeichen, nach denen das Session-Finale ein Leerzeichen anhängt. */
    val PUNCT: Set<Char> = setOf('.', '!', '?', ':', ';', '…')

    /** T10: nachgestellte Schlusszeichen — massgeblich ist das Zeichen davor. */
    val CLOSERS: Set<Char> = setOf('”', '“', '"', '»', ')', ']')

    /**
     * Anhängen entscheiden. [committedTail] = Ende des endgültig committeten
     * Texts (null = Session ohne Output), [alreadyAtCursor] = Zeichen am
     * Cursor VOR dem Anhängen (Doppelschutz Cursor-Seite; null = unbekannt —
     * der EditorSink liest nur vor dem Cursor, dann trägt der Textseiten-
     * Doppelschutz allein).
     */
    fun rule(committedTail: String?, alreadyAtCursor: Char?): Boolean {
        if (committedTail.isNullOrEmpty()) return false // leere Session ⇒ nichts
        // Doppelschutz, Textseite: endet schon auf Whitespace ⇒ kein zweites.
        if (committedTail.last().isWhitespace()) return false
        // Doppelschutz, Cursorseite: steht dort schon ein Leerzeichen ⇒ nichts.
        if (alreadyAtCursor?.isWhitespace() == true) return false
        // Massgebliches Zeichen: vom Ende zurück über nachgestellte
        // Schlusszeichen (mehrstufig: „…“) bis zum ersten normalen Zeichen.
        var i = committedTail.length - 1
        while (i >= 0 && committedTail[i] in CLOSERS) i--
        if (i < 0) return false // nur Schlusszeichen, kein Satzzeichen davor
        return committedTail[i] in PUNCT
    }
}
