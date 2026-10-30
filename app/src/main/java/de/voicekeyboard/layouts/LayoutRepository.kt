package de.voicekeyboard.layouts

import android.content.Context

/**
 * M9a R1: Label-Regel fuer die SPACE-Taste (reine JVM-Logik, testbar).
 * Asset-Label, wenn es Nicht-Whitespace enthaelt; sonst Fallback aus Ressourcen.
 */
object SpaceLabelRule {

    /** True, wenn das Asset-Label verwendbar ist (mind. ein Nicht-Whitespace-Zeichen). */
    fun useAssetLabel(label: String): Boolean = label.any { !it.isWhitespace() }

    /** Aufloesung: Asset-Label oder Fallback. Einfuege-Wert bleibt unangetastet. */
    fun resolve(assetLabel: String, fallback: String): String =
        if (useAssetLabel(assetLabel)) assetLabel else fallback
}

/**
 * Lädt Layout-JSONs aus assets/layouts/. Fällt bei Fehlern auf das
 * eingebaute de-DE-Layout zurück; defekte Layouts werden isoliert.
 */
class LayoutRepository(private val context: Context) {

    /**
     * L3/R1: Verfügbare Layout-IDs aus dem Asset-Verzeichnis scannen — kein Hardcode.
     * Normalisierung: `.json`-Endung weg, Kleinschreibung, Duplikate weg.
     * Reihenfolge: kanonische Sprachreihenfolge zuerst, danach alle weiteren Funde
     * alphabetisch. `de-de` ist immer enthalten (Fallback), auch wenn der Scan fehlschlägt.
     */
    fun availableIds(): List<String> {
        // M9c: EINE Quelle — die kanonische Reihenfolge lebt in LanguageCycle
        // (Paket ime/, E9.1 Regel 3). Keine lokale Duplikatliste mehr.
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
        val ids = scanned + "de-de" // de-de immer vorhanden (Fallback-Garantie)
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

    /** Lädt das Layout; bei Fehlern Rückfall auf de-DE, dann EN-US. */
    fun loadOrFallback(id: String): LayoutAsset {
        load(id)?.let { return it }
        load("de-de")?.let { return it }
        return load("en-us") ?: throw IllegalStateException("Kein Layout ladenbar (Fallback de-de/en-us fehlgeschlagen)")
    }
}
