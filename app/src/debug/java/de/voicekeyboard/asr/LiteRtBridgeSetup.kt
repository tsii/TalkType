package de.voicekeyboard.asr

import de.voicekeyboard.asr.litert.LiteRtAsrEngine

/**
 * V13 debug: wires the [LiteRtBridge] provider to the LiteRT engine
 * (variant B — measurement door stays open; all litert sources live in this
 * debug source set). Loaded via LiteRtBridge.acquire() (Class.forName —
 * top-level initializer installs the provider).
 */
fun installLiteRtBridge() {
    LiteRtBridge.provider = { LiteRtAsrEngine() }
}

private val installed: Unit = run { installLiteRtBridge() }
