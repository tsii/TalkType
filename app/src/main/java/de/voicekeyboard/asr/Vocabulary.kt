package de.voicekeyboard.asr

/**
 * Parser und Detokenizer für vocab.txt (8193 Zeilen, Format "<token> <id>").
 * Filterregel aus ASR_CONTRACT.md §2.4: Token verwerfen, wenn der Token-String
 * auf '<' beginnt und auf '>' endet, plus ID 8192 (<blk>), plus IDs 0..4.
 * NICHT über ID-Bereiche filtern — IDs 234..243 sind die Ziffern 0–9 und
 * reguläres Vokabular.
 */
class Vocabulary(private val tokenById: Array<String?>) {

    companion object {
        const val WORD_BOUNDARY = "▁" // U+2581

        /**
         * Erwartet eine vocab.txt: Zeilen "<token> <id>". Tolerant gegen CRLF,
         * Leerzeilen und doppelte IDs (erste Definition gewinnt). Der Token kann
         * selbst Leerzeichen enthalten → von rechts splitten.
         */
        fun parse(text: String): Vocabulary {
            val tokens = arrayOfNulls<String>(AsrContract.VOCAB_SIZE)
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trimEnd('\r')
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val lastSpace = trimmed.lastIndexOf(' ')
                val token: String
                val idStr: String
                if (lastSpace >= 0) {
                    token = trimmed.substring(0, lastSpace)
                    idStr = trimmed.substring(lastSpace + 1)
                } else {
                    token = trimmed
                    idStr = ""
                }
                val id = idStr.toIntOrNull() ?: continue
                if (id < 0 || id >= AsrContract.VOCAB_SIZE) continue
                if (tokens[id] == null) tokens[id] = token
            }
            return Vocabulary(tokens)
        }
    }

    /** Token-String zur ID oder null. */
    fun token(id: Int): String? = if (id in 0 until AsrContract.VOCAB_SIZE) tokenById[id] else null

    /**
     * True, wenn dieses Token (diese ID) NIEMALS emittiert/angezeigt werden darf:
     * IDs 0..4, IDs mit Token-Muster "<...>", ID 8192 (Blank).
     * Ziffern-IDs 234..243 sind NICHT gefiltert (siehe §2.4, empirisch geprüft).
     */
    fun isSpecial(id: Int): Boolean {
        if (id == AsrContract.BLANK) return true
        if (id in AsrContract.SPECIAL_TOKEN_IDS) return true
        val t = token(id) ?: return true // unbekannte IDs sind per Definition Sonderfall
        return t.length >= 2 && t.startsWith("<") && t.endsWith(">")
    }

    /** IDs, die vom Decoder kommen dürfen (nicht gefiltert), bzw. null wenn unbekannt. */
    fun isEmittable(id: Int): Boolean = !isSpecial(id)

    /**
     * Detokenisierung (§2.4, vereinfacht — für alle Golden-WAVs identisch mit der
     * Referenz-Regex): konkatenieren, ▁ → Leerzeichen, trim.
     */
    fun detokenize(ids: IntArray): String {
        val sb = StringBuilder(ids.size * 4)
        for (id in ids) {
            val t = token(id) ?: continue
            sb.append(t)
        }
        return sb.toString().replace(WORD_BOUNDARY, " ").trim()
    }

    /**
     * Detokenisierung einer Liste von Token-Strings (nur für Tests/Fakes gedacht,
     * wenn man Token-Strings statt IDs vorliegen hat).
     */
    fun detokenizeTokens(tokens: List<String>): String =
        tokens.joinToString("").replace(WORD_BOUNDARY, " ").trim()
}
