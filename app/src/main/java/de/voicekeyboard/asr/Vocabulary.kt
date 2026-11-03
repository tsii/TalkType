package de.voicekeyboard.asr

/**
 * Parser and detokenizer for vocab.txt (8193 lines, format "<token> <id>").
 * Filter rule from ASR_CONTRACT.md §2.4: discard a token if the token string
 * starts with '<' and ends with '>', plus ID 8192 (<blk>), plus IDs 0..4.
 * Do NOT filter by ID ranges — IDs 234..243 are the digits 0–9 and
 * regular vocabulary.
 */
class Vocabulary(private val tokenById: Array<String?>) {

    companion object {
        const val WORD_BOUNDARY = "▁" // U+2581

        /**
         * Expects a vocab.txt: lines "<token> <id>". Tolerant of CRLF,
         * empty lines and duplicate IDs (first definition wins). The token may
         * itself contain spaces → split from the right.
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

    /** Token string for an ID or null. */
    fun token(id: Int): String? = if (id in 0 until AsrContract.VOCAB_SIZE) tokenById[id] else null

    /**
     * True if this token (this ID) must NEVER be emitted/shown:
     * IDs 0..4, IDs with token pattern "<...>", ID 8192 (blank).
     * Digit IDs 234..243 are NOT filtered (see §2.4, checked empirically).
     */
    fun isSpecial(id: Int): Boolean {
        if (id == AsrContract.BLANK) return true
        if (id in AsrContract.SPECIAL_TOKEN_IDS) return true
        val t = token(id) ?: return true // unknown IDs are by definition a special case
        return t.length >= 2 && t.startsWith("<") && t.endsWith(">")
    }

    /** IDs that may come from the decoder (not filtered), or null if unknown. */
    fun isEmittable(id: Int): Boolean = !isSpecial(id)

    /**
     * Detokenization (§2.4, simplified — identical to the
     * reference regex for all golden WAVs): concatenate, ▁ → space, trim.
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
     * Detokenization of a list of token strings (intended only for tests/fakes,
     * when token strings are at hand instead of IDs).
     */
    fun detokenizeTokens(tokens: List<String>): String =
        tokens.joinToString("").replace(WORD_BOUNDARY, " ").trim()
}
