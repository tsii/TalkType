package de.voicekeyboard.correct

/**
 * T23a (V0.1.47, docs/PLAN_V0147.md §1(b), §2): rule-level typing correction.
 * Pure Kotlin — no Android APIs, no dependencies, no lexicon, and NEVER a
 * change inside a word (only structural rules; lexicon corrections are T23c).
 *
 * Rules (§1(b)):
 *  - sentence-initial capital: text start and after '.', '!', '?' (whitespace
 *    required) and after a line break; language-guarded for case-bearing layouts
 *  - no space before ',', '.', '!', '?', ';', ':' — missing space inserted after
 *    ',', ';', '!', '?', ':' when a letter follows (NOT after '.': protects
 *    domains/abbreviations like "beispiel.de")
 *  - whitespace runs collapsed (spaces/tabs -> one space; line breaks kept,
 *    spaces around them dropped)
 *  - en: standalone 'i' -> 'I'
 *  - doubled leading capitals 'DIe' -> 'Die'
 *  - directly repeated word 'die die' -> 'die' (exact, case-sensitive, length >= 2)
 *
 * Must-not-touch (§2): words with digits, URLs/e-mails ("://", '@'),
 * ALLCAPS <= 5, inner capitals ('iPhone'), word interiors. Known limitation
 * (accepted for 0.1.47): abbreviation periods ("z. B.") also trigger the
 * sentence capital; '.' directly followed by a letter is never re-spaced.
 *
 * apply() is pure and idempotent: apply(apply(x, lang), lang) == apply(x, lang).
 */
object TypingRules {

    /**
     * Case-bearing layout languages — mirrors assets/layouts (one id per
     * layouts/<id>.json asset). Sentence
     * capitalisation only runs for these ids; everything else (unknown or
     * non-case-bearing script) gets the language-neutral spacing rules only.
     */
    val CASE_BEARING_LAYOUT_IDS: Set<String> = setOf(
        "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
        "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
    )

    /** Punctuation that must not be preceded by a space. */
    private const val PUNCT_NO_SPACE_BEFORE = ",.!;:"

    /** Punctuation that needs a following space when a letter follows. */
    private const val PUNCT_SPACE_AFTER = ",;!?:"

    /** Sentence terminators that trigger the next-word capitalisation. */
    private const val TERMINATORS = ".!?"

    fun apply(text: String, languageId: String): String {
        var t = collapseWhitespaceRuns(text)
        t = removeSpaceBeforePunctuation(t)
        t = insertSpaceAfterPunctuation(t)
        t = collapseRepeatedWords(t)
        t = capitalizeSentenceStarts(t, languageId)
        t = capitalizeEnglishI(t, languageId)
        t = fixDoubledLeadingCapitals(t)
        return t
    }

    // ------------------------------------------------------------------ words

    /** Word characters: letters, digits (protection), apostrophes, hyphens. */
    private fun isWordChar(c: Char): Boolean =
        c.isLetter() || c.isDigit() || c == '\'' || c == '\u2019' || c == '-'

    /**
     * Ranges of whitespace-delimited tokens that contain "://" or "@" (URLs,
     * e-mails) — never touched by any rule (§2 must-not-touch).
     */
    private fun protectedRanges(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            var j = i
            while (j < text.length && !text[j].isWhitespace()) j++
            val token = text.substring(i, j)
            if (token.contains("://") || token.contains('@')) out.add(i until j)
            i = j
        }
        return out
    }

    /** true if [from, toExclusive) overlaps any protected range. */
    private fun inRanges(ranges: List<IntRange>, from: Int, to: Int): Boolean =
        ranges.any { from <= it.last && it.first < to }

    /**
     * §2 must-not-touch word shapes: contains digits; ALLCAPS with <= 5 letters;
     * capital at index >= 2 ('ABCde', 'McDonald'); lowercase start + inner capital
     * ('iPhone'). 'DIe' (upper start + doubled leading capital + lowercase rest)
     * is deliberately NOT protected — that is the fix pattern itself.
     */
    private fun isProtectedWord(word: String): Boolean {
        if (word.any { it.isDigit() }) return true
        val letters = word.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.length <= 5 && letters.all { it.isUpperCase() }) return true
        for (i in 2 until word.length) if (word[i].isUpperCase()) return true
        if (word.isNotEmpty() && !word[0].isUpperCase() && word.any { it.isUpperCase() }) return true
        return false
    }

    // ------------------------------------------------- 1. whitespace collapse

    /** Space/tab runs -> one space; runs with line breaks keep the line breaks. */
    private fun collapseWhitespaceRuns(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ' || text[i] == '\t') {
                var j = i
                var lineBreak = false
                while (j < text.length &&
                    (text[j] == ' ' || text[j] == '\t' || text[j] == '\n' || text[j] == '\r')
                ) {
                    if (text[j] == '\n' || text[j] == '\r') lineBreak = true
                    j++
                }
                if (lineBreak) {
                    for (k in i until j) if (text[k] == '\n' || text[k] == '\r') sb.append(text[k])
                } else {
                    sb.append(' ')
                }
                i = j
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    // ---------------------------------------- 2./3. space before/after punct

    /** Drops space/tab runs directly before [PUNCT_NO_SPACE_BEFORE]. */
    private fun removeSpaceBeforePunctuation(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ' || text[i] == '\t') {
                var j = i
                while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
                if (j < text.length && PUNCT_NO_SPACE_BEFORE.indexOf(text[j]) >= 0) {
                    i = j // drop the spaces; the punctuation itself is copied next
                } else {
                    sb.append(' ')
                    i = j
                }
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Inserts a space after [PUNCT_SPACE_AFTER] when a letter follows directly
     * (digits are protected: "1,5", "14:30" stay). Punctuation inside URLs or
     * e-mails is skipped via the protected ranges.
     */
    private fun insertSpaceAfterPunctuation(text: String): String {
        val protected = protectedRanges(text)
        val sb = StringBuilder(text.length + 8)
        for (i in text.indices) {
            sb.append(text[i])
            if (i + 1 < text.length &&
                PUNCT_SPACE_AFTER.indexOf(text[i]) >= 0 &&
                text[i + 1].isLetter() &&
                !inRanges(protected, i, i + 1)
            ) {
                sb.append(' ')
            }
        }
        return sb.toString()
    }

    // -------------------------------------------- 4. sentence-initial capital

    private fun capitalizeSentenceStarts(text: String, languageId: String): String {
        if (languageId !in CASE_BEARING_LAYOUT_IDS) return text
        val protected = protectedRanges(text)
        val sb = StringBuilder(text)
        val start = firstWordStart(text)
        if (start >= 0) capitalizeAt(text, sb, start, protected)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                val w = wordStartAt(text, j)
                if (w >= 0) capitalizeAt(text, sb, w, protected)
                i = j
            } else if (TERMINATORS.indexOf(c) >= 0) {
                var j = i + 1
                val after = j
                while (j < text.length && text[j].isWhitespace()) j++
                // '.'/'!'/'?' directly followed by a letter is left alone
                // (domains, abbreviations — and '!'/'?' are pre-spaced by pass 3)
                if (j > after) {
                    val w = wordStartAt(text, j)
                    if (w >= 0) capitalizeAt(text, sb, w, protected)
                }
                i = j
            } else {
                i++
            }
        }
        return sb.toString()
    }

    /** First sentence word of the text (-1 if none). */
    private fun firstWordStart(text: String): Int {
        var j = 0
        while (j < text.length && text[j].isWhitespace()) j++
        return wordStartAt(text, j)
    }

    /**
     * Word start at [at]: skips quote/bracket-like punctuation (non-word chars),
     * stops at word chars (digits), whitespace and sentence terminators.
     */
    private fun wordStartAt(text: String, at: Int): Int {
        var j = at
        while (j < text.length) {
            val c = text[j]
            if (c.isLetter()) return j
            if (isWordChar(c) || c.isWhitespace() || TERMINATORS.indexOf(c) >= 0) return -1
            j++
        }
        return -1
    }

    /** Uppercases [pos] if the word around it is not protected (§2). */
    private fun capitalizeAt(text: String, sb: StringBuilder, pos: Int, protected: List<IntRange>) {
        var a = pos
        while (a > 0 && isWordChar(text[a - 1])) a--
        var b = pos
        while (b < text.length && isWordChar(text[b])) b++
        if (isProtectedWord(text.substring(a, b))) return
        if (inRanges(protected, a, b)) return
        val ch = text[pos]
        if (ch.isLowerCase()) {
            val up = ch.uppercaseChar()
            if (up != ch) sb[pos] = up
        }
    }

    // ------------------------------------------------------- 5. en: 'i' -> 'I'

    /** Standalone lowercase 'i' -> 'I' (English layouts only). */
    private fun capitalizeEnglishI(text: String, languageId: String): String {
        if (!languageId.startsWith("en")) return text
        val protected = protectedRanges(text)
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            if (c == 'i' &&
                (i == 0 || !isWordChar(text[i - 1])) &&
                (i + 1 >= text.length || !isWordChar(text[i + 1])) &&
                !inRanges(protected, i, i + 1)
            ) {
                sb.append('I')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    // --------------------------------------------- 6. doubled leading capitals

    /** 'DIe' -> 'Die': first two letters upper, rest lowercase, length >= 3. */
    private fun fixDoubledLeadingCapitals(text: String): String {
        val protected = protectedRanges(text)
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (isWordChar(text[i]) && (i == 0 || !isWordChar(text[i - 1]))) {
                var j = i
                while (j < text.length && isWordChar(text[j])) j++
                val word = text.substring(i, j)
                if (word.length >= 3 &&
                    word[0].isUpperCase() && word[1].isUpperCase() &&
                    word.substring(2).all { it.isLetter() && it.isLowerCase() } &&
                    !isProtectedWord(word) && !inRanges(protected, i, j)
                ) {
                    sb.append(word[0]).append(word[1].lowercaseChar()).append(word, 2, word.length)
                } else {
                    sb.append(word)
                }
                i = j
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    // ----------------------------------------------------- 7. repeated words

    /**
     * Drops a word that exactly repeats the immediately preceding word
     * (case-sensitive, length >= 2, only whitespace — without line break —
     * between them; the predecessor may itself be dropped, so "fahren fahren
     * fahren" collapses completely). Runs BEFORE the sentence capital so that
     * "die die Katze" -> "Die Katze". Protected words (§2) are never dropped
     * ("HALLO HALLO" stays).
     */
    private fun collapseRepeatedWords(text: String): String {
        val protected = protectedRanges(text)
        val words = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            if (isWordChar(text[i]) && (i == 0 || !isWordChar(text[i - 1]))) {
                var j = i
                while (j < text.length && isWordChar(text[j])) j++
                words.add(i until j)
                i = j
            } else {
                i++
            }
        }
        val drop = BooleanArray(words.size)
        for (k in 1 until words.size) {
            val cur = text.substring(words[k].first, words[k].last + 1)
            val prev = text.substring(words[k - 1].first, words[k - 1].last + 1)
            val gap = text.substring(words[k - 1].last + 1, words[k].first)
            drop[k] = cur.length >= 2 && cur == prev &&
                gap.isNotEmpty() && gap.all { it.isWhitespace() } &&
                !gap.contains('\n') && !gap.contains('\r') &&
                !isProtectedWord(cur) &&
                !inRanges(protected, words[k].first, words[k].last + 1)
        }
        val sb = StringBuilder(text.length)
        var copyFrom = 0
        for (k in words.indices) {
            val end = words[k].last + 1
            if (!drop[k]) sb.append(text, copyFrom, end)
            copyFrom = end // dropped: skip the preceding gap together with the word
        }
        sb.append(text, copyFrom, text.length)
        return sb.toString()
    }
}
