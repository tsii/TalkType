package de.voicekeyboard.debug

import de.voicekeyboard.ime.VoiceKeyboardService

/**
 * AP1 release stub: identical signature to the debug counterpart
 * (app/src/debug/.../DebugHooks.kt), deliberately WITHOUT any effect —
 * pairing like LiteRtBridgeSetup (release/debug source set), NO
 * reflection: main calls directly; in the release only this
 * empty stub exists. No property reading, no export hook, no behavior.
 */
object DebugHooks {
    fun apply(service: VoiceKeyboardService) {
        // Deliberately NOTHING (release).
    }

    fun onExport(service: VoiceKeyboardService, text: String) {
        // Deliberately NOTHING (release).
    }
}
