package de.voicekeyboard.correct

/**
 * T23k (V0.1.47, docs/PLAN_V0147.md §14.4): the ALL_ACTIVE space-bar label
 * "All languages" in the DEVICE display language — 1:1 with
 * tools/labels/all_languages.tsv (translator-role source of truth, 2026-11-05;
 * AllLanguagesLabelTest enforces the 1:1 match at build time), <= 14 chars for
 * the 12sp space bar, language-subtag resolution with English fallback.
 *
 * Pure Kotlin/JVM; the label map ships as constants (the TSV is a tools/
 * file and does not travel in the APK).
 */
object AllLanguagesLabel {

    const val FALLBACK = "All languages"

    /** language subtag -> label, 1:1 with tools/labels/all_languages.tsv. */
    val entries: Map<String, String> = mapOf(
        "cs" to "Všechny jazyky",
        "da" to "Alle sprog",
        "de" to "Alle Sprachen",
        "el" to "Όλα",
        "en" to "All languages",
        "es" to "Todos",
        "fi" to "Kaikki kielet",
        "fr" to "Toutes langues",
        "it" to "Tutte lingue",
        "nl" to "Alle talen",
        "pl" to "Wszystkie jęz.",
        "pt" to "Todos idiomas",
        "ru" to "Все языки",
        "sv" to "Alla språk",
        "tr" to "Tüm diller",
    )

    /**
     * Label for a system locale tag: language subtag only ('pt-BR' -> pt,
     * 'DE-de'/'de_DE' -> de); languages outside the table (e.g. ja) fall
     * back to the English label (§14.4).
     */
    fun resolve(systemLocaleTag: String): String {
        if (systemLocaleTag.isEmpty()) return FALLBACK
        val normalized = systemLocaleTag.replace('_', '-').lowercase()
        val subtag = normalized.substringBefore('-')
        return entries[subtag] ?: FALLBACK
    }
}
