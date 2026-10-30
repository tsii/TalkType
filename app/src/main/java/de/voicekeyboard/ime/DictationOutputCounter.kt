package de.voicekeyboard.ime

/**
 * Zählt für D10-Rest §3, ob ein Diktat in dieser Sitzung Zeichen geliefert hat —
 * die Entscheidungsgrundlage für Commit-Häkchen vs. „Kein Text erkannt".
 *
 * **Warum brutto und nicht netto (Review F12, P2):** die erste Fassung zählte
 * `neue Zeichen - ersetzte Zeichen`. Eine Korrektur von fünf Zeichen durch fünf
 * neue ergibt netto 0, obwohl der Nutzer Text im Feld sieht — das Häkchen blieb
 * aus und stattdessen erschien „Kein Text erkannt". Gezählt wird deshalb die
 * Summe aller geschriebenen Zeichen dieser Sitzung.
 *
 * Bewusste Abweichung von D10 §2: ein Rollback auf nichts zählt ebenfalls als
 * Ausgabe. Sichtbares Fehlerbild ist die völlig leer gebliebene Aufnahme, nicht
 * ein korrigiertes Wort.
 *
 * Regel 9: ausschließlich Längen, niemals Textinhalte. Reine Funktion ohne
 * Android-Abhängigkeit, damit die Semantik im JVM-Test fixiert ist (Muster
 * [micToggleAction]).
 */
class DictationOutputCounter {

    private var grossChars = 0

    /** Brutto über die Sitzung — Diagnosewert, keine Entscheidung. */
    val outputChars: Int
        get() = grossChars

    /** Hat dieses Diktat irgendetwas geschrieben? */
    val hasOutput: Boolean
        get() = grossChars > 0

    /**
     * Ein Schreibschritt des DeltaTypers. [replaceLength] wird bewusst nicht
     * verrechnet (siehe Klassenkommentar).
     */
    fun onCommit(newTextLength: Int, replaceLength: Int = 0) {
        if (newTextLength > 0) grossChars += newTextLength
    }

    /** Neue Aufnahme — ohne diesen Reset würde das Urteil aus der letzten Sitzung nachwirken. */
    fun reset() {
        grossChars = 0
    }
}
