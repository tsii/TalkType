package de.voicekeyboard.asr

import de.voicekeyboard.asr.litert.LiteRtAsrEngine

/**
 * V13 Debug: verdrahtet den [LiteRtBridge]-Provider mit der LiteRT-Engine
 * (Variante B — Messtür bleibt offen; alle litert-Quellen leben in diesem
 * Debug-Source-Set). Geladen über LiteRtBridge.acquire() (Class.forName —
 * top-level Initialisierer installiert den Provider).
 */
fun installLiteRtBridge() {
    LiteRtBridge.provider = { LiteRtAsrEngine() }
}

private val installed: Unit = run { installLiteRtBridge() }
