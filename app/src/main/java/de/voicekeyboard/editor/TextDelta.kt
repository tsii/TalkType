package de.voicekeyboard.editor

/**
 * Wertetyp für „ersetze die zuletzt eingefügten [replaceLength] Zeichen durch
 * [newText]" (KONZEPT.md §2.7: Backspaces + neuer Text als eine Operation).
 *
 * @param replaceLength Anzahl Zeichen vor dem Cursor, die zurückgenommen werden
 *   (0 = reines Anhängen).
 * @param newText neu einzufügender Text (kann leer sein — reine Korrektur).
 */
data class TextDelta(val replaceLength: Int, val newText: String) {

    init {
        require(replaceLength >= 0) { "replaceLength muss >= 0 sein, war $replaceLength" }
    }

    /** true, wenn das Delta nichts bewirkt (weder löschen noch einfügen). */
    val isEmpty: Boolean get() = replaceLength == 0 && newText.isEmpty()
}
