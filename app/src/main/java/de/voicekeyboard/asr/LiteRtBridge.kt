package de.voicekeyboard.asr

/**
 * V13: Indirektion zwischen Varianten-Registry und der LiteRT-Engine.
 *
 * Variante B (LITERT_CPU) hat das Qualitäts-Gate nicht bestanden
 * (docs/VARIANT_B_QUALITY.md: 50,2 % Wort-Identität, 33,2 % CED, Sprache-
 * Kippen bei führender Stille) und wird NICHT ausgeliefert. Der Quellcode
 * bleibt als Debug-Source-Set erhalten (Messtür offen, kein Löschen — die
 * Dateien sind die einzige Aufbewahrung).
 *
 * [provider] wird NUR vom Debug-Source-Set gesetzt (LiteRtBridgeSetup);
 * in Release bleibt er null ⇒ EngineFactory fällt dokumentiert auf
 * ParakeetAsrEngine (ONNX_CPU) zurück. ModelVariant.LITERT_CPU bleibt als
 * Typ erhalten (Persistenz-Abwärtskompatibilität), kann in Release nie
 * eine LiteRT-Engine erzeugen.
 */
object LiteRtBridge {

    /** Liefert die LiteRT-Engine oder null (Release: immer null). */
    @Volatile
    var provider: (() -> AsrEngine?)? = null

    /**
     * Liefert den Provider, installiert ihn vorher einmalig über den
     * Varianten-Source-Set (debug: LiteRtAsrEngine; release: null — dort
     * existiert die Setup-Klasse, installiert aber nichts).
     */
    fun acquire(): (() -> AsrEngine?)? {
        if (provider == null && !bootstrapTried) {
            bootstrapTried = true
            try {
                // Laedt LiteRtBridgeSetupKt und fuehrt dessen Initialisierer aus
                Class.forName("de.voicekeyboard.asr.LiteRtBridgeSetup")
            } catch (_: ClassNotFoundException) {
                // Release-Setup-Klasse fehlt nie; andere Ladefehler => Provider null
            }
        }
        return provider
    }

    @Volatile private var bootstrapTried = false
}
