package de.voicekeyboard.debug

import de.voicekeyboard.ime.VoiceKeyboardService

/**
 * AP1 Release-Stub: identische Signatur wie das Debug-Pendant
 * (app/src/debug/.../DebugHooks.kt), bewusst OHNE jede Wirkung —
 * Paarung wie LiteRtBridgeSetup (release/debug-Sourceset), KEINE
 * Reflection: main ruft direkt auf; im Release existiert nur dieser
 * leere Stub. Kein Property-Lesen, kein Export-Hook, kein Verhalten.
 */
object DebugHooks {
    fun apply(service: VoiceKeyboardService) {
        // Bewusst NICHTS (Release).
    }

    fun onExport(service: VoiceKeyboardService, text: String) {
        // Bewusst NICHTS (Release).
    }
}
