package de.voicekeyboard.asr

/**
 * V13 Release: [LiteRtBridge]-Provider bleibt null — dokumentierter Grund:
 * Variante B (LITERT_CPU) erfüllt das Qualitäts-Gate nicht
 * (docs/VARIANT_B_QUALITY.md: 50,2 % Wort-Identität, 33,2 % CED,
 * Sprach-Kippen Deutsch→Englisch bei 400 ms führender Stille).
 * EngineFactory fällt daher dokumentiert auf ParakeetAsrEngine (ONNX_CPU)
 * zurück; ModelVariant.LITERT_CPU bleibt als Typ erhalten.
 */
fun installLiteRtBridge() {
    // Bewusst NICHTS: Provider bleibt null (Release hat keine LiteRT-Klassen).
}
