package de.voicekeyboard.keyboard

/**
 * T23m (PLAN_V0147 §16, user command 2026-11-05): the WORD keyboard ships for
 * de + en in 0.1.47 — THIS object is the ONE source of the availability set;
 * no hard-coded copy in UI or service. Other languages keep their LAYOUT and
 * dictation capability but drop out of the keyboard language cycle until the
 * §16 runbook makes them available (assets + extending AVAILABLE).
 */
object LanguageAvailability {

    /** Word keyboards shipped in 0.1.47 (§16 — deliberate scope, not a limit). */
    val AVAILABLE: Set<String> = setOf("de-de", "en-us")

    fun isAvailable(id: String): Boolean = id in AVAILABLE

    /**
     * Actives ∩ available — the ONLY set the keyboard and the globe cycle use.
     * No available language among the actives → fallback in AVAILABLE order
     * (never empty; de-de first).
     */
    fun filterActive(ids: List<String>): List<String> =
        ids.filter { isAvailable(it) }.ifEmpty { AVAILABLE.toList() }

    /** First available of the saved actives (current_language fallback, §16). */
    fun firstAvailable(active: List<String>): String =
        active.firstOrNull { isAvailable(it) } ?: AVAILABLE.first()
}
