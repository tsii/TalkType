package de.voicekeyboard.layouts

/**
 * Data model for a keyboard layout (JSON contract from DESIGN.md 7.2).
 * Pure JVM data classes, no Android code.
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
    val width: Double, // relative units
    val kind: KeyKind, // text | action | dead
    val value: String, // character, action id or dead key id
    val caps: String? = null,
    val alternates: List<String> = emptyList(),
    /** F23a1: optional short hint (e.g. digit "1"), max 2 characters; null = none. */
    val hint: String? = null,
    /**
     * F24e1: optional action names for the long-press menu cells, parallel to
     * [alternates] (same index; "" = normal text cell, otherwise an action like
     * "cursor-left"). Shorter than alternates = remainder is text; the length is
     * trimmed to the alternates size in the parser.
     */
    val cellActions: List<String> = emptyList(),
)

enum class KeyKind { TEXT, ACTION, DEAD, /** F27a1: edge surface (width only, no glyph, no touch). */ MARGIN }

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

/** Allowed action values (allowlist from DESIGN.md 7.2). */
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

/** Required layers per layout. */
object LayoutLayers {
    val REQUIRED = listOf("base", "shift", "symbols", "symbolsShift")
}
