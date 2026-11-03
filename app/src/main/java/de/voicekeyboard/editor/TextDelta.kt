package de.voicekeyboard.editor

/**
 * Value type for "replace the last inserted [replaceLength] characters with
 * [newText]" (KONZEPT.md §2.7: backspaces + new text as one operation).
 *
 * @param replaceLength number of characters before the cursor to be taken back
 *   (0 = pure append).
 * @param newText new text to insert (may be empty — pure correction).
 */
data class TextDelta(val replaceLength: Int, val newText: String) {

    init {
        require(replaceLength >= 0) { "replaceLength must be >= 0, was $replaceLength" }
    }

    /** true if the delta has no effect (neither delete nor insert). */
    val isEmpty: Boolean get() = replaceLength == 0 && newText.isEmpty()
}
