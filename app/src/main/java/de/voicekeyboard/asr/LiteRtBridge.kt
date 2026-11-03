package de.voicekeyboard.asr

/**
 * V13: indirection between the variant registry and the LiteRT engine.
 *
 * Variant B (LITERT_CPU) did not pass the quality gate
 * (docs/VARIANT_B_QUALITY.md: 50.2 % word identity, 33.2 % CED, language
 * flipping on leading silence) and is NOT shipped. The source code
 * remains as a debug source set (measurement door stays open, no deletion —
 * the files are the sole preservation).
 *
 * [provider] is set ONLY by the debug source set (LiteRtBridgeSetup);
 * in release it stays null ⇒ EngineFactory falls back, as documented, to
 * ParakeetAsrEngine (ONNX_CPU). ModelVariant.LITERT_CPU remains as a
 * type (persistence backward compatibility), can in release never
 * produce a LiteRT engine.
 */
object LiteRtBridge {

    /** Delivers the LiteRT engine or null (release: always null). */
    @Volatile
    var provider: (() -> AsrEngine?)? = null

    /**
     * Delivers the provider, installing it beforehand once via the
     * variant source set (debug: LiteRtAsrEngine; release: null — there
     * the setup class exists but installs nothing).
     */
    fun acquire(): (() -> AsrEngine?)? {
        if (provider == null && !bootstrapTried) {
            bootstrapTried = true
            try {
                // Loads LiteRtBridgeSetupKt and executes its initializer
                Class.forName("de.voicekeyboard.asr.LiteRtBridgeSetup")
            } catch (_: ClassNotFoundException) {
                // The release setup class is never missing; other load errors => provider null
            }
        }
        return provider
    }

    @Volatile private var bootstrapTried = false
}
