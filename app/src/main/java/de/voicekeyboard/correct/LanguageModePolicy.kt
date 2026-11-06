package de.voicekeyboard.correct

import java.util.Locale

/**
 * T23k (V0.1.47, docs/PLAN_V0147.md §14.3/§14.6(3)): correction language mode
 * — PURE decisions: which lists are affected per mode, pref parsing, the
 * §14.3 list-less char guard and the en-'i' rule gating. Candidate RANKING
 * (cost, current-language tie-break, frequency rank) stays in WordCorrector;
 * affectedLists() returns the current language FIRST so the corrector can
 * tie-break by list order.
 *
 * Modes (§14.1):
 *  - CURRENT (default): only the current layout language's list.
 *  - ALL_ACTIVE: all ACTIVE languages ∩ existing lists; with a single active
 *    language the behavior is IDENTICAL to CURRENT.
 *  A current language without a list -> NO list -> rules only (§14.3).
 *
 * 0.1.47 lists: de-de, en-us only (§14.6(3) deliberate default scope); other
 * 13 languages = rule layer only. New languages become list-capable purely
 * via assets/wordlists/<lang>.bin (WordListRegistry discovers, no code
 * change — T23g adapts [availableLists]).
 */
object LanguageModePolicy {

    const val PREF_KEY = "corr_lang_mode"
    const val VALUE_CURRENT = "current"
    const val VALUE_ALL = "all"

    enum class Mode { CURRENT, ALL_ACTIVE }

    /** Pref value -> mode; null/empty/unknown -> CURRENT (default, §14.1). */
    fun modeFromPref(value: String?): Mode =
        if (value == VALUE_ALL) Mode.ALL_ACTIVE else Mode.CURRENT

    /**
     * List ids the correction may use. CURRENT: only [currentLanguageId] if
     * a list exists for it. ALL_ACTIVE: active languages ∩ [availableLists],
     * deduplicated, current language FIRST (tie-break anchor for
     * WordCorrector's per-list ranking).
     */
    fun affectedLists(
        mode: Mode,
        currentLanguageId: String,
        activeLanguageIds: Collection<String>,
        availableLists: Set<String>,
    ): List<String> = when (mode) {
        Mode.CURRENT ->
            if (currentLanguageId in availableLists) listOf(currentLanguageId) else emptyList()
        Mode.ALL_ACTIVE -> {
            val ids = LinkedHashSet<String>()
            if (currentLanguageId in availableLists) ids.add(currentLanguageId)
            for (id in activeLanguageIds) {
                if (id in availableLists) ids.add(id)
            }
            ids.toList()
        }
    }

    /**
     * §14.3 acceptance: the word is in ANY affected list, case-insensitive per
     * caseLocale of the respective language (in-list -> no correction).
     */
    fun accepts(
        word: String,
        mode: Mode,
        currentLanguageId: String,
        activeLanguageIds: Collection<String>,
        lists: Map<String, WordList>,
    ): Boolean = affectedLists(mode, currentLanguageId, activeLanguageIds, lists.keys).any { id ->
        lists[id]?.contains(word, caseLocaleOf(id)) == true
    }

    /**
     * §14.3 list-less char guard: [listlessExclusiveChars] are characters that
     * occur ONLY on layouts of active languages WITHOUT a word list (T23g
     * derives the set from the layout JSON char sets: union of list-less
     * layout chars minus the chars of list-bearing layouts). Words containing
     * such a char are skipped entirely; everything else is processed
     * (wand-only + undo bound the damage).
     */
    fun skipsForListlessChars(word: String, listlessExclusiveChars: Set<Char>): Boolean =
        word.any { it in listlessExclusiveChars }

    /**
     * §14.3 en-'i' rule matrix: the standalone i -> I typing rule runs when
     * English is inside the correction scope — CURRENT: current layout is
     * en-us; ALL_ACTIVE: en-us is among the ACTIVE languages. (T23g passes
     * the resulting decision to TypingRules' language gating.)
     */
    fun appliesEnglishIRule(
        mode: Mode,
        currentLanguageId: String,
        activeLanguageIds: Collection<String>,
    ): Boolean = when (mode) {
        Mode.CURRENT -> currentLanguageId == "en-us"
        Mode.ALL_ACTIVE -> currentLanguageId == "en-us" || "en-us" in activeLanguageIds
    }

    private fun caseLocaleOf(langId: String): Locale = Locale.forLanguageTag(langId.replace('_', '-'))
}
