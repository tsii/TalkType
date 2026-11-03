package de.voicekeyboard.layouts

import android.content.Context

/**
 * M9a R1: label rule for the SPACE key (pure JVM logic, testable).
 * Asset label if it contains non-whitespace; otherwise fallback from resources.
 */
object SpaceLabelRule {

    /** True if the asset label is usable (at least one non-whitespace character). */
    fun useAssetLabel(label: String): Boolean = label.any { !it.isWhitespace() }

    /** Resolution: asset label or fallback. The insert value stays untouched. */
    fun resolve(assetLabel: String, fallback: String): String =
        if (useAssetLabel(assetLabel)) assetLabel else fallback
}

/**
 * Loads layout JSONs from assets/layouts/. Falls back to the built-in de-DE
 * layout on errors; broken layouts are isolated.
 */
class LayoutRepository(private val context: Context) {

    /**
     * L3/R1: scan the available layout IDs from the asset directory — no hardcoding.
     * Normalization: strip the `.json` suffix, lowercase, remove duplicates.
     * Order: canonical language order first, then all other findings
     * alphabetically. `de-de` is always included (fallback), even if the scan fails.
     */
    fun availableIds(): List<String> {
        // M9c: ONE source — the canonical order lives in LanguageCycle
        // (package ime/, E9.1 rule 3). No local duplicate list anymore.
        val canonical = de.voicekeyboard.ime.LanguageCycle.CANONICAL
        val scanned = try {
            context.assets.list("layouts")
                ?.filter { it.endsWith(".json") }
                ?.map { it.removeSuffix(".json").lowercase() }
                ?.distinct()
                ?.toSet()
                ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        val ids = scanned + "de-de" // de-de always present (fallback guarantee)
        return canonical.filter { it in ids } + (ids - canonical.toSet()).sorted()
    }

    fun load(id: String): LayoutAsset? {
        val names = listOf("$id.json", "$id.json".lowercase())
        for (name in names) {
            val path = "layouts/$name"
            val json = try {
                context.assets.open(path).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                continue
            }
            return try {
                LayoutParser.parse(json)
            } catch (e: LayoutParser.LayoutException) {
                null
            }
        }
        return null
    }

    /** Loads the layout; on errors fallback to de-DE, then EN-US. */
    fun loadOrFallback(id: String): LayoutAsset {
        load(id)?.let { return it }
        load("de-de")?.let { return it }
        return load("en-us") ?: throw IllegalStateException("No layout loadable (fallback de-de/en-us failed)")
    }
}
