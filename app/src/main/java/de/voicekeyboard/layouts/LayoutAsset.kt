package de.voicekeyboard.layouts

/**
 * Datenmodell für ein Tastaturlayout (JSON-Vertrag aus DESIGN.md 7.2).
 * Reine JVM-Datenklassen, kein Android-Code.
 */
data class LayoutLanguage(
    val tag: String,
    val label: String,
    val caseLocale: String,
    val direction: String, // "ltr" | "rtl"
)

data class LayoutKey(
    val id: String,
    val label: String,
    val width: Double, // relative Einheiten
    val kind: KeyKind, // text | action | dead
    val value: String, // Zeichen, Action-ID oder Deadkey-ID
    val caps: String? = null,
    val alternates: List<String> = emptyList(),
)

enum class KeyKind { TEXT, ACTION, DEAD }

data class LayoutLayer(
    val name: String, // base | shift | symbols | symbolsShift
    val rows: List<List<LayoutKey>>,
)

data class LayoutAsset(
    val schemaVersion: Int,
    val id: String,
    val language: LayoutLanguage,
    val layers: Map<String, LayoutLayer>,
    val deadKeys: Map<String, DeadKey> = emptyMap(),
)

data class DeadKey(
    val id: String,
    val spacing: String,
    val combining: String? = null,
    val compose: Map<String, String> = emptyMap(),
)

/** Erlaubte Action-Werte (Allowlist aus DESIGN.md 7.2). */
object KeyActions {
    const val SHIFT = "shift"
    const val CAPS = "caps"
    const val LAYER_SYMBOLS = "layer-symbols"
    const val LAYER_BASE = "layer-base"
    const val BACKSPACE = "backspace"
    const val SPACE = "space"
    const val ENTER = "enter"
    const val LANGUAGE = "language"
    const val EMOJI = "emoji"
    const val MIC = "mic"
    const val CURSOR_LEFT = "cursor-left"
    const val CURSOR_RIGHT = "cursor-right"

    val ALL = setOf(
        SHIFT, CAPS, LAYER_SYMBOLS, LAYER_BASE, BACKSPACE, SPACE, ENTER,
        LANGUAGE, EMOJI, MIC, CURSOR_LEFT, CURSOR_RIGHT,
    )
}

/** Erforderliche Ebenen je Layout. */
object LayoutLayers {
    val REQUIRED = listOf("base", "shift", "symbols", "symbolsShift")
}
